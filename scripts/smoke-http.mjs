import { randomInt, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { MongoClient } from 'mongodb';

// Local smoke only: no public API/AI calls and no real hospital reservations.
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:18081';
const uri = process.env.SMOKE_MONGODB_URI || 'mongodb://127.0.0.1:27118/?replicaSet=hospital-rs';
const databaseName = process.env.SMOKE_DATABASE || 'hospital_reservation';
const users = [];
let passed = 0;

function pass(name) {
  passed++;
  console.log(`PASS ${name}`);
}

async function request(path, { method = 'GET', token, body, key, expected = 200 } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30_000),
  });
  let value;
  try { value = await response.json(); } catch { value = null; }
  // Deliberately exclude request bodies, authorization headers, and response tokens from errors.
  assert.equal(response.status, expected, `${method} ${path}: expected HTTP ${expected}, received ${response.status}`);
  return value;
}

function assertPublicUser(profile, expected) {
  assert.equal(profile?.id, expected.id);
  assert.equal(profile?.loginId, expected.loginId);
  assert.equal(profile?.role, 'USER');
  assert.equal(profile?.authProvider, 'LOCAL');
  assert.equal(profile?.profileComplete, true);
  assert.equal(profile?.phone, expected.phone);
  assert.equal(profile?.email, expected.email);
  assert.ok(!Object.keys(profile).some(key => /password|hash|salt|secret|credential|token/i.test(key)),
    'Public user profile must not contain credential fields');
  assert.ok(!JSON.stringify(profile).includes(expected.password),
    'Public user profile must not contain the password');
}

async function signup(label, extraBody = {}) {
  const loginId = `smoke_${randomUUID().replaceAll('-', '').slice(0, 24)}`;
  const password = `Smoke-${randomUUID()}!`;
  const phone = `010${String(randomInt(100_000_000)).padStart(8, '0')}`;
  const email = `${loginId}@example.test`;
  const value = await request('/api/auth/register', {
    method: 'POST', body: { ...extraBody, name: `Smoke ${label}`, loginId, password, phone, email },
  });
  assert.ok(typeof value?.user?.id === 'string');
  assert.ok(typeof value?.token === 'string');
  const user = { id: value.user.id, loginId, password, phone, email, token: value.token };
  users.push(user);
  assertPublicUser(value.user, user);
  return user;
}

function koreanDate(daysAhead) {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
  const date = new Date(`${today}T00:00:00+09:00`);
  date.setTime(date.getTime() + daysAhead * 86_400_000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(date);
}

async function cleanup() {
  if (users.length === 0) return;
  // Cancel through the application before deleting exact test-user records.
  for (const user of users) {
    const login = await request('/api/auth/login', {
      method: 'POST', body: { loginId: user.loginId, password: user.password },
    });
    user.token = login.token;
    const list = await request('/api/reservations', { token: user.token });
    for (const reservation of list.items) {
      if (reservation.status === 'RESERVED') {
        await request(`/api/reservations/${encodeURIComponent(reservation.id)}`, {
          method: 'DELETE', token: user.token, key: randomUUID(),
        });
      }
    }
  }
  const client = new MongoClient(uri);
  await client.connect();
  try {
    const db = client.db(databaseName);
    const userIds = users.map(user => user.id);
    const active = await db.collection('reservations').countDocuments({ userId: { $in: userIds }, status: 'RESERVED' });
    assert.equal(active, 0, 'Cleanup requires zero active reservations for the exact smoke-user IDs');
    const reservationIds = (await db.collection('reservations').find({ userId: { $in: userIds } }, { projection: { _id: 1 } }).toArray()).map(value => value._id);
    await db.collection('reservationEvents').deleteMany({ actorId: { $in: userIds }, reservationId: { $in: reservationIds } });
    await db.collection('requests').deleteMany({ userId: { $in: userIds } });
    await db.collection('reservations').deleteMany({ userId: { $in: userIds } });
    await db.collection('users').deleteMany({ _id: { $in: userIds }, loginId: { $in: users.map(user => user.loginId) } });
    assert.equal(await db.collection('users').countDocuments({ _id: { $in: userIds } }), 0);
    assert.equal(await db.collection('reservations').countDocuments({ userId: { $in: userIds } }), 0);
    pass('exact test-user cleanup; no active reservations remain');
  } finally {
    await client.close();
  }
}

try {
  const first = await signup('A');
  pass('signup creates USER account');
  const invalidLoginId = `smoke-${randomUUID().slice(0, 8)}@id.test`;
  await request('/api/auth/register', {
    method: 'POST', body: { name: 'Invalid Smoke ID', loginId: invalidLoginId, password: first.password, phone: first.phone, email: first.email }, expected: 400,
  });
  await request('/api/auth/login', {
    method: 'POST', body: { loginId: invalidLoginId, password: first.password }, expected: 400,
  });
  pass('email-formatted login ID rejected for signup and login');
  await request('/api/auth/login', {
    method: 'POST', body: { email: first.loginId, password: first.password }, expected: 400,
  });
  pass('email-only login request no longer authenticates');
  await request('/api/auth/register', {
    method: 'POST', body: { name: 'Duplicate Smoke ID', loginId: `  ${first.loginId.toUpperCase()}  `, password: first.password, phone: first.phone, email: first.email }, expected: 409,
  });
  pass('duplicate normalized login ID rejected with 409');
  for (const phone of [undefined, 'not-a-phone']) {
    await request('/api/auth/register', {method:'POST', body:{name:'Phone validation', loginId:`phone_${randomUUID().replaceAll('-', '').slice(0,20)}`,password:first.password,email:first.email,...(phone===undefined?{}:{phone})},expected:400});
  }
  pass('signup requires a valid phone number');
  const repeatedMember = await request('/api/auth/register', {
    method: 'POST', body: { name: 'Different member name', loginId: `dup_${randomUUID().replaceAll('-', '').slice(0,20)}`, password: first.password, phone: `${first.phone.slice(0,3)}-${first.phone.slice(3,7)}-${first.phone.slice(7)}`, email: `other-${randomUUID()}@example.test` }, expected: 409,
  });
  assert.equal(repeatedMember.code, undefined);
  assert.equal(repeatedMember.existingAuthProvider, undefined);
  assert.equal(repeatedMember.message, '이미 가입된 정보가 있어요. 입력 정보를 확인해 주세요.');
  assert.ok(!/이름|연락처/.test(repeatedMember.message));
  assert.ok(!('token' in repeatedMember) && !('user' in repeatedMember));
  pass('duplicate phone rejected regardless of name and email');
  const repeatedEmail = await request('/api/auth/register', {
    method: 'POST', body: { name: 'Another name', loginId: `mail_${randomUUID().replaceAll('-', '').slice(0,20)}`, password: first.password, phone: '021234567', email: `  ${first.email.toUpperCase()}  ` }, expected: 409,
  });
  assert.equal(repeatedEmail.code, undefined);
  assert.equal(repeatedEmail.existingAuthProvider, undefined);
  assert.equal(repeatedEmail.message, repeatedMember.message);
  pass('duplicate normalized email rejected with a different phone');
  const matchingMember = await request('/api/auth/register', {
    method: 'POST', body: { name: '  SMOKE  A  ', loginId: `match_${randomUUID().replaceAll('-', '').slice(0,20)}`, password: first.password, phone: first.phone, email: `match-${randomUUID()}@example.test` }, expected: 409,
  });
  assert.equal(matchingMember.code, 'MEMBER_ALREADY_EXISTS');
  assert.equal(matchingMember.existingAuthProvider, 'LOCAL');
  assert.ok(matchingMember.message.includes('아이디로 로그인'));
  pass('matching normalized name and phone reveals the existing login method');
  await request('/api/auth/login', {
    method: 'POST', body: { loginId: first.loginId, password: 'DefinitelyWrongPassword!' }, expected: 401,
  });
  pass('wrong password rejected with 401');
  const login = await request('/api/auth/login', {
    method: 'POST', body: { loginId: first.loginId, password: first.password },
  });
  assertPublicUser(login.user, first);
  first.token = login.token;
  const me = await request('/api/auth/me', { token: first.token });
  assertPublicUser(me, first);
  pass('login and authenticated me');
  const normalizedLogin = await request('/api/auth/login', {
    method: 'POST', body: { loginId: `  ${first.loginId.toUpperCase()}  `, password: first.password },
  });
  assertPublicUser(normalizedLogin.user, first);
  first.token = normalizedLogin.token;
  assertPublicUser(await request('/api/auth/me', { token: first.token }), first);
  pass('login trims whitespace and normalizes uppercase ID');
  await request('/api/reservations', { expected: 401 });
  pass('anonymous reservation access rejected');

  const second = await signup('B', { role: 'ADMIN' });
  pass('signup cannot promote a claimed ADMIN role');
  const hospital = await request('/api/hospitals/sample-ent');
  assert.equal(hospital.id, 'sample-ent');
  assert.equal(hospital.source, 'SAMPLE');
  pass('sample hospital detail is explicitly SAMPLE');
  const doctorResult = await request('/api/hospitals/sample-ent/doctors?department=13');
  assert.equal(doctorResult.items.length, 3);
  assert.ok(doctorResult.items.every(doctor => doctor.isVirtual === true));
  const repeatedDoctors = await request('/api/hospitals/sample-ent/doctors?department=13');
  assert.deepEqual(doctorResult.items.map(doctor => doctor.id), repeatedDoctors.items.map(doctor => doctor.id));
  pass('three persistent virtual doctors');

  const doctorId = doctorResult.items[0].id;
  let selectedDate;
  let slots;
  for (let day = 1; day <= 7; day++) {
    selectedDate = koreanDate(day);
    const response = await request(`/api/slots?doctorId=${encodeURIComponent(doctorId)}&date=${selectedDate}`);
    slots = response.items.filter(slot => !slot.closed && slot.remaining > 0 && new Date(slot.startAt) > new Date());
    if (slots.length >= 2) break;
  }
  assert.ok(slots.length >= 2, 'Need two future open sample slots');
  pass('future virtual schedule and remaining capacity');
  await request(`/api/admin/slots?doctorId=${encodeURIComponent(doctorId)}&date=${selectedDate}`, {
    token: first.token, expected: 403,
  });
  pass('USER denied ADMIN schedule API with 403');

  const createKey = randomUUID();
  const created = await request('/api/reservations', {
    method: 'POST', token: first.token, body: { slotId: slots[0].id }, key: createKey,
  });
  assert.equal(created.status, 'RESERVED');
  assert.equal(created.slotId, slots[0].id);
  pass('reservation create');
  const replay = await request('/api/reservations', {
    method: 'POST', token: first.token, body: { slotId: slots[0].id }, key: createKey,
  });
  assert.deepEqual(replay, created);
  pass('same idempotency key replays original response');
  await request('/api/reservations', {
    method: 'POST', token: first.token, body: { slotId: slots[1].id }, key: createKey, expected: 409,
  });
  pass('same key with different body rejected with 409');

  const reservationPath = `/api/reservations/${encodeURIComponent(created.id)}`;
  await request(reservationPath, {
    method: 'PATCH', token: second.token, body: { slotId: slots[1].id }, key: randomUUID(), expected: 404,
  });
  await request(reservationPath, {
    method: 'DELETE', token: second.token, key: randomUUID(), expected: 404,
  });
  const otherList = await request('/api/reservations', { token: second.token });
  assert.equal(otherList.items.length, 0);
  pass('other user cannot list/change/cancel owner reservation');
  const list = await request('/api/reservations', { token: first.token });
  assert.ok(list.items.some(reservation => reservation.id === created.id));
  pass('owner reservation list');

  const changed = await request(reservationPath, {
    method: 'PATCH', token: first.token, body: { slotId: slots[1].id }, key: randomUUID(),
  });
  assert.equal(changed.id, created.id);
  assert.equal(changed.slotId, slots[1].id);
  pass('reservation schedule change');
  const canceled = await request(reservationPath, {
    method: 'DELETE', token: first.token, key: randomUUID(),
  });
  assert.equal(canceled.status, 'CANCELED');
  const canceledAgain = await request(reservationPath, {
    method: 'DELETE', token: first.token, key: randomUUID(),
  });
  assert.equal(canceledAgain.status, 'CANCELED');
  const after = await request(`/api/slots?doctorId=${encodeURIComponent(doctorId)}&date=${selectedDate}`);
  for (const original of slots.slice(0, 2)) {
    assert.equal(after.items.find(slot => slot.id === original.id)?.reservedCount, original.reservedCount);
  }
  pass('cancel repeat preserves capacity; both slots restored');

  await request('/api/auth/logout', { method: 'POST', token: first.token });
  await request('/api/auth/me', { token: first.token, expected: 401 });
  pass('logout revokes previously issued JWT');
} catch (error) {
  process.exitCode = 1;
  console.error(`FAIL ${error instanceof Error ? error.message : 'Unexpected smoke failure'}`);
} finally {
  try {
    await cleanup();
  } catch (error) {
    process.exitCode = 1;
    console.error(`FAIL cleanup: ${error instanceof Error ? error.message : 'Unexpected cleanup failure'}`);
  }
  console.log(`HTTP smoke: ${passed} checks passed; result=${process.exitCode ? 'FAIL' : 'PASS'}`);
}
