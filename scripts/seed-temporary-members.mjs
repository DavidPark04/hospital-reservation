import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { MongoClient } from 'mongodb';

export const BATCH = 'temporary-personal-test0001-test2000-v1';
export const COUNT = 2000;
const BCRYPT = /^\$2[aby]\$1[0-6]\$[./A-Za-z0-9]{53}$/;

export function temporaryMembers(passwordHashes, createdAt = new Date()) {
  if (passwordHashes !== undefined && (!Array.isArray(passwordHashes) || passwordHashes.length !== COUNT
      || passwordHashes.some(hash => typeof hash !== 'string' || !BCRYPT.test(hash)))) {
    throw new Error('Exactly 2000 BCrypt hashes with cost 10..16 are required.');
  }
  return Array.from({ length: COUNT }, (_, index) => {
    const number = String(index + 1).padStart(4, '0');
    const name = `test${number}`, email = `ex${number}@gmail.com`;
    return {
      _id: `temporary-member-${name}`, name, nameNormalized: name, loginId: name,
      ...(passwordHashes ? { passwordHash: passwordHashes[index] } : {}),
      phone: `010${number}${number}`, email, emailNormalized: email, emailSource: 'TEST',
      role: 'USER', authProvider: 'LOCAL', profileComplete: true, tokenVersion: 0,
      testUser: true, seedBatchId: BATCH, memberIdentityClaimed: true,
      emailVerified: false, phoneVerified: false, createdAt,
    };
  });
}

const identityFields = ['_id', 'name', 'loginId', 'phone', 'email', 'emailNormalized', 'role', 'authProvider',
  'testUser', 'seedBatchId', 'memberIdentityClaimed', 'emailVerified', 'phoneVerified', 'profileComplete'];

export async function preflightTemporaryMembers(db, session) {
  const planned = temporaryMembers();
  const expected = new Map(planned.map(member => [member._id, member]));
  const rows = await db.collection('users').find({ $or: [
    { _id: { $in: planned.map(member => member._id) } },
    { loginId: { $in: planned.map(member => member.loginId) } },
    { emailNormalized: { $in: planned.map(member => member.emailNormalized) } },
    { email: { $regex: '^ex(?:0[0-9]{3}|1[0-9]{3}|2000)@gmail\\.com$', $options: 'i' } },
    { phone: { $in: planned.flatMap(member => [member.phone, `010-${member.phone.slice(3, 7)}-${member.phone.slice(7)}`]) } },
    { seedBatchId: BATCH },
  ] }, { session }).toArray();
  const valid = new Set();
  for (const row of rows) {
    const target = expected.get(row._id);
    if (!target || identityFields.some(field => row[field] !== target[field])
        || typeof row.passwordHash !== 'string' || !BCRYPT.test(row.passwordHash)) {
      throw new Error('Seed conflicts with an existing account or changed batch record. No accounts were overwritten.');
    }
    valid.add(row._id);
  }
  return { planned, missing: planned.filter(member => !valid.has(member._id)), existing: valid.size };
}

export async function seedTemporaryMembers(client, db, passwordHashes) {
  const planned = temporaryMembers(passwordHashes);
  if (!passwordHashes) throw new Error('Password hashes are required for apply.');
  const indexes = await db.collection('users').listIndexes().toArray();
  for (const name of ['user_phone_v1', 'user_email_v1']) {
    const index = indexes.find(item => item.name === name);
    if (!index?.unique || index.partialFilterExpression?.memberIdentityClaimed !== true) {
      throw new Error('Existing member identity indexes must be present and unchanged.');
    }
  }
  if (!indexes.some(index => index.unique && index.key.loginId === 1 && Object.keys(index.key).length === 1)) {
    throw new Error('Unique login ID protection is required.');
  }
  const session = client.startSession();
  try {
    return await session.withTransaction(async () => {
      const before = await preflightTemporaryMembers(db, session);
      const missing = new Set(before.missing.map(member => member._id));
      if (missing.size) await db.collection('users').insertMany(planned.filter(member => missing.has(member._id)), { session, ordered: true });
      const after = await preflightTemporaryMembers(db, session);
      if (after.existing !== COUNT || after.missing.length) throw new Error('Seed verification failed; transaction rolled back.');
      return { batch: BATCH, total: after.existing, inserted: missing.size, existing: before.existing };
    }, { readConcern: { level: 'snapshot' }, writeConcern: { w: 'majority' }, maxCommitTimeMS: 30000 });
  } finally { await session.endSession(); }
}

async function main() {
  const { values } = parseArgs({ options: {
    apply: { type: 'boolean', default: false }, 'expected-database': { type: 'string' },
    'password-hashes-file': { type: 'string' }, 'test-user-support-deployed': { type: 'boolean', default: false },
    output: { type: 'string' },
  } });
  if (!process.env.MONGODB_URI || !values['expected-database']) throw new Error('MONGODB_URI and --expected-database are required.');
  if (values.apply && !values['test-user-support-deployed']) throw new Error('Deploy and verify testUser support before applying the seed.');
  const hashes = values.apply ? JSON.parse(fs.readFileSync(values['password-hashes-file'], 'utf8')).passwordHashes : undefined;
  if (values.apply) temporaryMembers(hashes);
  const client = new MongoClient(process.env.MONGODB_URI, { serverSelectionTimeoutMS: 15000, connectTimeoutMS: 15000 });
  try {
    await client.connect();
    const db = client.db();
    if (db.databaseName !== values['expected-database']) throw new Error('Database name does not match the explicit target.');
    let result;
    if (values.apply) result = await seedTemporaryMembers(client, db, hashes);
    else {
      const check = await preflightTemporaryMembers(db);
      result = { batch: BATCH, existing: check.existing, toInsert: check.missing.length };
    }
    const evidence = { ...result, database: db.databaseName, mode: values.apply ? 'apply' : 'preflight', checkedAt: new Date().toISOString() };
    if (values.output) fs.writeFileSync(values.output, JSON.stringify(evidence, null, 2), { mode: 0o600 });
    console.log(JSON.stringify(evidence));
  } finally { await client.close(); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => {
    // Never print MongoDB URIs, duplicate records, passwords or driver diagnostics.
    console.error(error?.name?.startsWith('Mongo') ? 'Database operation failed; no existing accounts were overwritten.' : error.message);
    process.exitCode = 1;
  });
}
