import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { MongoClient } from 'mongodb';
import { BATCH, COUNT, temporaryMembers, preflightTemporaryMembers, seedTemporaryMembers } from '../seed-temporary-members.mjs';

// A synthetic format-valid hash is sufficient here; Java auth integration tests verify BCrypt login.
const hashes = Array(COUNT).fill('$2a$10$' + 'A'.repeat(53));
test('fixed seed produces distinct requested contacts with ordinary uniqueness and unverified flags', () => {
  const rows = temporaryMembers(hashes);
  assert.equal(rows.length, 2000);
  assert.equal(rows[0].loginId, 'test0001');
  assert.equal(rows[0].email, 'ex0001@gmail.com');
  assert.equal(rows.at(-1).name, 'test2000');
  assert.equal(rows.at(-1).email, 'ex2000@gmail.com');
  assert.equal(rows[0].phone, '01000010001');
  assert.equal(rows.at(-1).phone, '01020002000');
  assert.equal(new Set(rows.map(row => row._id)).size, 2000);
  assert.equal(new Set(rows.map(row => row.phone)).size, 2000);
  assert(rows.every((row, index) => row.phone === `010${String(index + 1).padStart(4, '0').repeat(2)}`
    && row.authProvider === 'LOCAL' && row.role === 'USER'
    && row.testUser && row.memberIdentityClaimed && !row.phoneVerified && !row.emailVerified));
  assert.throws(() => temporaryMembers(['plaintext']), /2000 BCrypt/);
});

async function isolated(run) {
  const uri = process.env.MONGODB_TEST_URI;
  assert.match(uri, /^mongodb:\/\/127\.0\.0\.1:/, 'loopback MongoDB required');
  const client = new MongoClient(uri);
  await client.connect();
  const db = client.db('temporary_member_seed_test_' + randomUUID().replaceAll('-', ''));
  try {
    await db.collection('users').createIndex({ loginId: 1 }, { unique: true, name: 'user_login_id_v1' });
    for (const [key, name] of [['phone', 'user_phone_v1'], ['emailNormalized', 'user_email_v1']]) {
      await db.collection('users').createIndex({ [key]: 1 }, { unique: true, name,
        partialFilterExpression: { memberIdentityClaimed: true, [key]: { $type: 'string', $gt: '' } } });
    }
    await run(client, db);
  } finally { await db.dropDatabase(); await client.close(); }
}

test('Mongo seed is atomic, idempotent, preserves unrelated accounts and keeps normal uniqueness',
  { skip: !process.env.MONGODB_TEST_URI }, async () => isolated(async (client, db) => {
    const ordinary = { _id: 'existing-member', loginId: 'existing', role: 'USER', phone: '01088887777', memberIdentityClaimed: true };
    await db.collection('users').insertOne(ordinary);
    const before = await preflightTemporaryMembers(db);
    assert.equal(before.missing.length, 2000);
    assert.equal(await db.collection('users').countDocuments(), 1, 'preflight must not write');
    const first = await seedTemporaryMembers(client, db, hashes);
    assert.equal(first.inserted, 2000);
    const second = await seedTemporaryMembers(client, db, hashes);
    assert.equal(second.inserted, 0);
    assert.equal(second.existing, 2000);
    assert.equal(await db.collection('users').countDocuments({ seedBatchId: BATCH }), 2000);
    assert.equal(await db.collection('users').countDocuments(), 2001);
    assert.deepEqual(await db.collection('users').findOne({ _id: ordinary._id }), ordinary);
    await assert.rejects(db.collection('users').insertOne({ _id: 'duplicate', loginId: 'duplicate', phone: ordinary.phone, memberIdentityClaimed: true }), error => error.code === 11000);
    await assert.rejects(db.collection('users').insertOne({ _id: 'duplicate-test', loginId: 'duplicate-test', testUser: true, phone: '01000010001', memberIdentityClaimed: true }), error => error.code === 11000);
    await db.collection('users').updateOne({ _id: 'temporary-member-test0001' }, { $set: { name: 'edited' } });
    await assert.rejects(seedTemporaryMembers(client, db, hashes), /conflicts/);
    assert.equal((await db.collection('users').findOne({ _id: 'temporary-member-test0001' })).name, 'edited');
  }));

test('Mongo collision stops the whole batch before any temporary user is inserted',
  { skip: !process.env.MONGODB_TEST_URI }, async () => isolated(async (client, db) => {
    await db.collection('users').insertOne({ _id: 'actual-user', loginId: 'test2000', role: 'USER' });
    await assert.rejects(seedTemporaryMembers(client, db, hashes), /conflicts/);
    assert.equal(await db.collection('users').countDocuments(), 1);
    assert.equal(await db.collection('users').countDocuments({ testUser: true }), 0);
  }));
