import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { MongoClient } from 'mongodb';

// Local smoke only: sample appointments and exact, disposable test accounts.
// Supply SMOKE_ADMIN_PASSWORD explicitly; never load or print credential files.
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:18081';
const uri = process.env.SMOKE_MONGODB_URI || 'mongodb://127.0.0.1:27118/?replicaSet=hospital-rs';
const databaseName = process.env.SMOKE_DATABASE || 'hospital_reservation';
const adminLoginId = (process.env.SMOKE_ADMIN_LOGIN_ID || 'admin').trim().toLowerCase();
const adminPassword = process.env.SMOKE_ADMIN_PASSWORD;
const users = [];
const originalSlots = [];
let admin;
let client;
let db;
let passed = 0;

class SmokeFailure extends Error {}

function check(condition, message) {
  if (!condition) throw new SmokeFailure(message);
}

function pass(name) {
  passed++;
  console.log(`PASS ${name}`);
}

function fail(stage, error) {
  process.exitCode = 1;
  const detail = error instanceof SmokeFailure ? error.message : 'Unexpected error; sensitive details suppressed';
  console.error(`FAIL ${stage}: ${detail}`);
}

async function request(path, { method = 'GET', token, body, key, expected = 200 } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  let response;
  try {
    response = await fetch(`${base}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
  } catch {
    throw new SmokeFailure(`${method} request could not complete`);
  }
  let value;
  try { value = await response.json(); } catch { value = null; }
  check(response.status === expected, `${method} request expected HTTP ${expected}, received ${response.status}`);
  return value;
}

function publicProfile(profile, user, role = 'USER') {
  check(profile?.id === user.id && profile?.loginId === user.loginId && profile?.role === role,
    'Public account identity or role differs from the expected account');
  const fields = ['id', 'name', 'loginId', 'role', 'authProvider', 'profileComplete', 'emailVerified'];
  if (profile.phone !== undefined) fields.push('phone');
  check(profile.authProvider === 'LOCAL' && profile.profileComplete === true && profile.emailVerified === false
    && Object.keys(profile).sort().join(',') === fields.sort().join(',')
    && (profile.phone === undefined || typeof profile.phone === 'string' && /^0[0-9]{8,10}$/.test(profile.phone))
    && (user.phone === undefined || profile.phone === user.phone),
    'Local public account must expose its login method and only public profile fields');
}

async function signup(label) {
  const user = {
    name: `Members smoke ${label}`,
    loginId: `members_${randomUUID().replaceAll('-', '').slice(0, 22)}`,
    password: `Members-${randomUUID()}!`,
    phone: '01012345678',
  };
  // Register the unique fixture first so cleanup can recover an interrupted response.
  users.push(user);
  const result = await request('/api/auth/register', {
    method: 'POST', body: { name: user.name, loginId: user.loginId, password: user.password, phone: '010-1234-5678' },
  });
  user.id = result?.user?.id;
  user.token = result?.token;
  check(typeof user.id === 'string' && user.id.length > 0 && typeof user.token === 'string' && user.token.length > 0,
    'Signup did not return an internal ID and token');
  publicProfile(result.user, user);
  publicProfile(await request('/api/auth/me', { token: user.token }), user);
  return user;
}

function koreanDate(daysAhead) {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
  const date = new Date(`${today}T00:00:00+09:00`);
  date.setTime(date.getTime() + daysAhead * 86_400_000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(date);
}

function memberRows(result) {
  check(Array.isArray(result?.items), 'Member listing did not return an items array');
  const fields = ['createdAt', 'id', 'loginId', 'name', 'role', 'authProvider', 'profileComplete'];
  for (const member of result.items) {
    const expectedFields = [...fields];
    if (member?.email !== undefined) expectedFields.push('email');
    if (member?.phone !== undefined) expectedFields.push('phone');
    check(member && Object.keys(member).sort().join(',') === [...expectedFields].sort().join(','),
      'Member listing must expose only the public profile, login method and optional contact fields');
    check(typeof member.id === 'string' && typeof member.name === 'string' && typeof member.loginId === 'string'
      && ['USER', 'ADMIN'].includes(member.role) && ['LOCAL', 'GOOGLE', 'KAKAO'].includes(member.authProvider)
      && member.profileComplete === true
      && Number.isFinite(Date.parse(member.createdAt))
      && (member.email === undefined || member.authProvider !== 'LOCAL' && member.profileComplete && typeof member.email === 'string' && member.email.length > 0)
      && (member.phone === undefined || member.profileComplete && typeof member.phone === 'string' && /^0[0-9]{8,10}$/.test(member.phone)),
    'Member listing contains an invalid public field');
  }
  return result.items;
}

async function cleanup() {
  if (users.length === 0) return;
  check(db, 'Cleanup requires the configured database connection');
  let cleanupFailed = false;
  for (const user of users) {
    try {
      if (!user.id) {
        const recovered = await db.collection('users').findOne(
          { loginId: user.loginId, name: user.name, role: 'USER' }, { projection: { _id: 1 } },
        );
        if (!recovered) continue;
        user.id = recovered._id;
      }
      check(typeof user.id === 'string' && user.id !== admin?.id, 'Cleanup refused an unsafe account ID');
      const existing = await db.collection('users').findOne(
        { _id: user.id }, { projection: { loginId: 1, role: 1 } },
      );
      if (existing) {
        check(existing.loginId === user.loginId && existing.role === 'USER',
          'Cleanup refused a mismatched or privileged account');
        const login = await request('/api/auth/login', {
          method: 'POST', body: { loginId: user.loginId, password: user.password },
        });
        user.token = login?.token;
        check(typeof user.token === 'string' && user.token.length > 0, 'Cleanup login did not return a token');
        const list = await request('/api/reservations', { token: user.token });
        check(Array.isArray(list?.items), 'Cleanup reservation list is invalid');
        for (const reservation of list.items) {
          if (reservation.status === 'RESERVED') {
            await request(`/api/reservations/${encodeURIComponent(reservation.id)}`, {
              method: 'DELETE', token: user.token, key: randomUUID(),
            });
          }
        }
      }
      // A deleted account cannot authenticate; its bookings must already be canceled.
      check(await db.collection('reservations').countDocuments({ userId: user.id, status: 'RESERVED' }) === 0,
        'Cleanup refused to remove records with an active reservation');
      const reservationIds = (await db.collection('reservations')
        .find({ userId: user.id }, { projection: { _id: 1 } }).toArray()).map(value => value._id);
      // Include admin-authored events only for these exact disposable reservations.
      await db.collection('reservationEvents').deleteMany({ reservationId: { $in: reservationIds } });
      await db.collection('requests').deleteMany({ userId: user.id });
      await db.collection('reservations').deleteMany({ userId: user.id, _id: { $in: reservationIds } });
      await db.collection('users').deleteOne({ _id: user.id, loginId: user.loginId, role: 'USER' });
      check(await db.collection('users').countDocuments({ _id: user.id }) === 0
        && await db.collection('requests').countDocuments({ userId: user.id }) === 0
        && await db.collection('reservations').countDocuments({ userId: user.id }) === 0
        && await db.collection('reservationEvents').countDocuments({ reservationId: { $in: reservationIds } }) === 0,
      'Exact test records remain after cleanup');
    } catch (error) {
      cleanupFailed = true;
      fail('test-user cleanup', error);
    }
  }
  for (const slot of originalSlots) {
    const actual = await db.collection('timeSlots').findOne({ _id: slot.id }, { projection: { reservedCount: 1 } });
    check(actual?.reservedCount === slot.reservedCount, 'Cleanup did not restore a test slot to its initial reservation count');
  }
  check(!cleanupFailed, 'Some exact test-user cleanup operations failed');
  pass('exact test artifacts removed; sample slot counts restored');
}

try {
  check(typeof adminPassword === 'string' && adminPassword.length > 0,
    'SMOKE_ADMIN_PASSWORD must be supplied explicitly');
  const login = await request('/api/auth/login', {
    method: 'POST', body: { loginId: adminLoginId, password: adminPassword },
  });
  admin = { id: login?.user?.id, loginId: adminLoginId, token: login?.token };
  check(typeof admin.id === 'string' && admin.id.length > 0 && typeof admin.token === 'string' && admin.token.length > 0,
    'Admin login did not return an internal ID and token');
  publicProfile(login.user, admin, 'ADMIN');
  publicProfile(await request('/api/auth/me', { token: admin.token }), admin, 'ADMIN');
  pass('existing admin signs in with supplied credentials');

  client = new MongoClient(uri, { serverSelectionTimeoutMS: 10_000 });
  await client.connect();
  db = client.db(databaseName);
  const first = await signup('A');
  const second = await signup('B');
  check(first.id !== second.id && first.id !== admin.id && second.id !== admin.id,
    'Test accounts must have distinct internal IDs');
  pass('two unique USER accounts expose their own public profiles');

  const firstMemberPath = `/api/admin/members/${encodeURIComponent(first.id)}`;
  await request('/api/admin/members', { expected: 401 });
  pass('anonymous member listing rejected with 401');
  await request(firstMemberPath, { method: 'DELETE', expected: 401 });
  pass('anonymous member deletion rejected with 401');
  await request('/api/admin/members', { token: first.token, expected: 403 });
  pass('USER member listing rejected with 403');
  await request(firstMemberPath, { method: 'DELETE', token: second.token, expected: 403 });
  pass('USER member deletion rejected with 403');
  await request(`/api/admin/members/${encodeURIComponent(admin.id)}`, {
    method: 'DELETE', token: admin.token, expected: 403,
  });
  publicProfile(await request('/api/auth/me', { token: admin.token }), admin, 'ADMIN');
  pass('admin self-deletion rejected with 403');
  await request(`/api/admin/members/${randomUUID()}`, { method: 'DELETE', token: admin.token, expected: 404 });
  pass('missing member deletion returns 404');

  const members = memberRows(await request('/api/admin/members', { token: admin.token }));
  check([first, second, admin].every(user => members.some(member => member.id === user.id && member.loginId === user.loginId)),
    'Member listing omitted an expected account');
  check(members.some(member => member.id === admin.id && member.role === 'ADMIN')
    && [first, second].every(user => members.some(member => member.id === user.id && member.role === 'USER')),
  'Member listing roles differ from the expected roles');
  pass('admin lists members with public fields only');

  const doctors = await request('/api/hospitals/sample-ent/doctors?department=13');
  check(Array.isArray(doctors?.items) && doctors.items.length > 0 && doctors.items.every(doctor => doctor.isVirtual === true),
    'Need virtual doctors from the sample hospital');
  const doctorId = doctors.items[0].id;
  let selectedDate;
  let slots = [];
  for (let day = 1; day <= 7; day++) {
    selectedDate = koreanDate(day);
    const result = await request(`/api/slots?doctorId=${encodeURIComponent(doctorId)}&date=${selectedDate}`);
    slots = result.items.filter(slot => !slot.closed && slot.remaining > 0 && new Date(slot.startAt) > new Date());
    if (slots.length >= 2) break;
  }
  check(slots.length >= 2, 'Need two future open sample slots');
  originalSlots.push(...slots.slice(0, 2));
  const slotPath = `/api/slots?doctorId=${encodeURIComponent(doctorId)}&date=${selectedDate}`;
  pass('two future sample slots available');

  const firstBooking = await request('/api/reservations', {
    method: 'POST', token: first.token, body: { slotId: slots[0].id }, key: randomUUID(),
  });
  const secondBooking = await request('/api/reservations', {
    method: 'POST', token: second.token, body: { slotId: slots[1].id }, key: randomUUID(),
  });
  check(firstBooking?.status === 'RESERVED' && firstBooking.slotId === slots[0].id
    && secondBooking?.status === 'RESERVED' && secondBooking.slotId === slots[1].id,
  'Test bookings were not reserved in the selected slots');
  const bookedSlots = await request(slotPath);
  check(originalSlots.every(slot => bookedSlots.items.find(item => item.id === slot.id)?.reservedCount === slot.reservedCount + 1),
    'Booking did not increment each selected slot exactly once');
  check(await db.collection('requests').countDocuments({ userId: first.id }) > 0,
    'Need A idempotency records before verifying their deletion');
  pass('A and B reserve separate sample appointments');

  const deletion = await request(firstMemberPath, { method: 'DELETE', token: admin.token });
  check(deletion?.deletedUserId === first.id && deletion.canceledReservations === 1 && typeof deletion.message === 'string',
    'Member deletion response must identify A and one canceled reservation');
  pass('admin deletes USER A and reports one canceled reservation');
  await request('/api/auth/me', { token: first.token, expected: 401 });
  await request('/api/auth/login', {
    method: 'POST', body: { loginId: first.loginId, password: first.password }, expected: 401,
  });
  pass('deleted account token and credentials no longer authenticate');

  const retained = await db.collection('reservations').findOne({ _id: firstBooking.id, userId: first.id });
  check(retained?.status === 'CANCELED'
    && await db.collection('reservations').countDocuments({ userId: first.id, status: 'RESERVED' }) === 0
    && await db.collection('users').countDocuments({ _id: first.id }) === 0
    && await db.collection('requests').countDocuments({ userId: first.id }) === 0,
  'Deleted account data must be removed while its canceled reservation is retained');
  pass('deleted account and requests removed; canceled reservation retained');
  const events = await db.collection('reservationEvents').find({ reservationId: firstBooking.id }).toArray();
  check(events.some(event => event.actorId === first.id && event.action === 'CREATE')
    && events.some(event => event.actorId === admin.id && event.after?.status === 'CANCELED'),
  'Reservation creation and admin cancellation audit must be retained');
  pass('creation and admin cancellation audit retained');

  const secondList = await request('/api/reservations', { token: second.token });
  check(secondList.items.length === 1 && isDeepStrictEqual(secondList.items[0], secondBooking),
    'Deleting A changed B reservation');
  pass('B reservation remains unchanged');
  const afterDelete = await request(slotPath);
  check(afterDelete.items.find(slot => slot.id === slots[0].id)?.reservedCount === slots[0].reservedCount
    && afterDelete.items.find(slot => slot.id === slots[1].id)?.reservedCount === slots[1].reservedCount + 1,
  'Deletion must release A capacity while preserving B capacity');
  pass('A slot capacity restored once; B slot capacity preserved');
  await request(firstMemberPath, { method: 'DELETE', token: admin.token, expected: 404 });
  const afterRepeat = await request(slotPath);
  check(originalSlots.every(slot => afterRepeat.items.find(item => item.id === slot.id)?.reservedCount
    === afterDelete.items.find(item => item.id === slot.id)?.reservedCount)
    && await db.collection('reservationEvents').countDocuments({ reservationId: firstBooking.id }) === events.length,
  'Repeated deletion must not alter capacity or duplicate audit events');
  pass('repeated deletion returns 404 without releasing capacity again');
  const remainingMembers = memberRows(await request('/api/admin/members', { token: admin.token }));
  check(!remainingMembers.some(member => member.id === first.id)
    && [second, admin].every(user => remainingMembers.some(member => member.id === user.id)),
  'Member listing after deletion must retain B and admin, and exclude A');
  pass('member listing reflects only the intended account deletion');
} catch (error) {
  fail('member smoke', error);
} finally {
  try {
    await cleanup();
  } catch (error) {
    fail('cleanup', error);
  }
  try {
    if (admin?.token) {
      await request('/api/auth/logout', { method: 'POST', token: admin.token });
      await request('/api/auth/me', { token: admin.token, expected: 401 });
      pass('admin verification token revoked');
    }
  } catch (error) {
    fail('admin token cleanup', error);
  }
  try {
    if (client) await client.close();
  } catch (error) {
    fail('database connection cleanup', error);
  }
  console.log(`Member HTTP smoke: ${passed} checks passed; result=${process.exitCode ? 'FAIL' : 'PASS'}`);
}
