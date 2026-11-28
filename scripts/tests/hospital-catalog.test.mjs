import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {DEPARTMENTS,REGION_CODES,hospitalId,normalizeRow,updateFor,assertReasonableRemoval,assertReasonableRegionalRemoval,validateSnapshot,applySnapshot} from '../sync-hospital-catalog.mjs';

const time='2026-11-21T00:00:00.000Z';
const raw={ykiho:'fixture-hospital',yadmNm:'가상 테스트 병원',addr:'경기도 수원시',telno:'031-000-0000',XPos:'127.01',YPos:'37.21',customField:'보존'};
const row=(changes={})=>({apiFields:{...raw},departmentCodes:['05','01','01'],fetchedAt:time,...changes});
const scopeTimes=(stamp=time)=>Object.fromEntries(['basic',...Object.keys(DEPARTMENTS)].map(code=>[code,{firstFetchedAt:stamp,lastFetchedAt:stamp}]));

test('uses Java nameUUIDFromBytes mapping, not namespace UUID3',()=>{
  assert.equal(hospitalId('fixture-hospital'),'33200005-9898-3d89-9c6e-f98e55191e84');
});
test('retains every API field, valid coordinates and sorted department memberships',()=>{
  const doc=normalizeRow(row());
  assert.deepEqual(doc.apiFields,raw);
  assert.deepEqual(doc.departmentCodes,['01','05']);
  assert.deepEqual(doc.departments,[{code:'01',name:'내과'},{code:'05',name:'정형외과'}]);
  assert.deepEqual(doc.location,{latitude:37.21,longitude:127.01});
});
test('timestamps and original property order cannot create false content changes',()=>{
  const first=normalizeRow(row());
  const second=normalizeRow(row({apiFields:Object.fromEntries(Object.entries(raw).reverse()),fetchedAt:'2026-11-22T00:00:00Z'}));
  assert.equal(first.catalogHash,second.catalogHash);
  assert.equal(updateFor(first,second,new Date()),null);
});
test('classification-only changes avoid rewriting raw data',()=>{
  const first=normalizeRow(row());
  const second=normalizeRow(row({departmentCodes:['01']}));
  const update=updateFor(first,second,new Date()).updateOne.update;
  assert.deepEqual(update.$set.departmentCodes,['01']);
  assert.equal(Object.hasOwn(update.$set,'apiFields'),false);
  assert.equal(Object.hasOwn(update.$set,'basicFetchedAt'),false);
});
test('changed coordinates and retired hospital reappearance are handled',()=>{
  const first=normalizeRow(row());
  const second=normalizeRow(row({apiFields:{...raw,XPos:'',YPos:'999'}}));
  const update=updateFor(first,second,new Date()).updateOne.update;
  assert.equal(update.$set.location,null);
  assert.equal(Object.hasOwn(update.$unset,'latitude'),true);
  const restored=updateFor({...first,active:false},first,new Date()).updateOne.update;
  assert.equal(restored.$set.active,true);
  assert.equal(Object.hasOwn(restored.$unset,'retiredAt'),true);
});
test('unexpected large missing sets stop retirement before writes',()=>{
  assert.doesNotThrow(()=>assertReasonableRemoval(80000,20));
  assert.throws(()=>assertReasonableRemoval(80000,10000),/unexpected_large_catalog_reduction/);
});

test('regional retirement guard catches missing small regions and permits hospitals moving regions',()=>{
  const current=Array.from({length:1000},(_,index)=>({_id:String(index),apiFields:{sidoCd:index<20?'410000':'110000'},active:true}));
  const documents=new Map(current.map(value=>[value._id,value]));
  for(let index=0;index<20;index++)documents.delete(String(index));
  assert.doesNotThrow(()=>assertReasonableRemoval(current.length,20));
  assert.throws(()=>assertReasonableRegionalRemoval(current,documents),/unexpected_large_regional_reduction/);
  for(let index=0;index<20;index++)documents.set(String(index),{...current[index],apiFields:{sidoCd:'310000'}});
  assert.doesNotThrow(()=>assertReasonableRegionalRemoval(current,documents));
  for(let index=0;index<5;index++)documents.delete(String(index));
  assert.doesNotThrow(()=>assertReasonableRegionalRemoval(current,documents));
  documents.delete('5');
  assert.throws(()=>assertReasonableRegionalRemoval(current,documents),/unexpected_large_regional_reduction/);
  const small=[{_id:'only',apiFields:{sidoCd:'410000'},active:true}];
  assert.throws(()=>assertReasonableRegionalRemoval(small,new Map()),/unexpected_large_regional_reduction/);
  assert.doesNotThrow(()=>assertReasonableRegionalRemoval([{...small[0],active:false}],new Map()));
});

async function fixture(directory,values=[row()]) {
  const payload=values.map(value=>JSON.stringify(value)+'\n').join('');
  const scopeCounts={basic:values.length,...Object.fromEntries(Object.keys(DEPARTMENTS).map(code=>[code,values.filter(value=>value.departmentCodes.includes(code)).length]))};
  const manifest={schemaVersion:1,complete:true,source:'hospInfoServicev2/getHospBasisList',scope:'nationwide-with-department-memberships',
    rows:values.length,departments:DEPARTMENTS,scopeCounts,scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time,completedAt:time,
    sha256:crypto.createHash('sha256').update(payload).digest('hex')};
  await fs.writeFile(path.join(directory,'catalog.jsonl'),payload);
  await fs.writeFile(path.join(directory,'catalog-manifest.json'),JSON.stringify(manifest));
  return manifest;
}
test('snapshot integrity checks raw bytes, duplicate IDs and department completeness',async()=>{
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'hospital-catalog-test-'));
  try {
    await fixture(directory);
    assert.equal((await validateSnapshot(directory,new Date(time))).documents.size,1);
    await fs.appendFile(path.join(directory,'catalog.jsonl'),' ');
    await assert.rejects(validateSnapshot(directory,new Date(time)),/snapshot_checksum_mismatch/);
    await fixture(directory,[row(),row()]);
    await assert.rejects(validateSnapshot(directory,new Date(time)),/duplicate_catalog_identity/);
    const manifest=await fixture(directory);
    delete manifest.scopeCounts['80'];
    await fs.writeFile(path.join(directory,'catalog-manifest.json'),JSON.stringify(manifest));
    await assert.rejects(validateSnapshot(directory,new Date(time)),/incomplete_department_scopes/);
    const oldDepartment=await fixture(directory);
    oldDepartment.firstFetchedAt='2026-11-18T00:00:00.000Z';
    oldDepartment.scopeTimes['01']={firstFetchedAt:oldDepartment.firstFetchedAt,lastFetchedAt:oldDepartment.firstFetchedAt};
    await fs.writeFile(path.join(directory,'catalog-manifest.json'),JSON.stringify(oldDepartment));
    await assert.rejects(validateSnapshot(directory,new Date(time)),/stale_or_invalid_snapshot_time/);
    const mismatched=await fixture(directory);
    mismatched.scopeTimes.basic.lastFetchedAt='2026-11-21T01:00:00.000Z';
    await fs.writeFile(path.join(directory,'catalog-manifest.json'),JSON.stringify(mismatched));
    await assert.rejects(validateSnapshot(directory,new Date(time)),/invalid_scope_times/);
  }finally{
    const target=path.resolve(directory),temporaryRoot=path.resolve(os.tmpdir());
    assert.equal(path.dirname(target),temporaryRoot);
    assert.ok(path.basename(target).startsWith('hospital-catalog-test-'));
    await fs.rm(target,{recursive:true,force:true});
  }
});

async function withRegionalSnapshot(check) {
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'hospital-regional-test-'));
  try { await check(directory); }
  finally {
    const target=path.resolve(directory);
    assert.equal(path.dirname(target),path.resolve(os.tmpdir()));
    assert.ok(path.basename(target).startsWith('hospital-regional-test-'));
    await fs.rm(target,{recursive:true,force:true});
  }
}
async function regionalFixture(directory) {
  const values=REGION_CODES.map((region,index)=>row({apiFields:{...raw,ykiho:'fixture-'+region,sidoCd:region},departmentCodes:index%2?[]:['01']}));
  const manifest=await fixture(directory,values);
  manifest.schemaVersion=2;
  manifest.regionScopes=Object.fromEntries(REGION_CODES.map(region=>{
    const rows=values.filter(value=>value.apiFields.sidoCd===region);
    return [region,{scopeCounts:{basic:rows.length,...Object.fromEntries(Object.keys(DEPARTMENTS).map(code=>[code,rows.filter(value=>value.departmentCodes.includes(code)).length]))},scopeTimes:scopeTimes()}];
  }));
  await writeManifest(directory,manifest);
  return {manifest,values};
}
async function writeManifest(directory,manifest) {
  await fs.writeFile(path.join(directory,'catalog-manifest.json'),JSON.stringify(manifest));
}
async function rewriteRegionalRows(directory,manifest,values) {
  const payload=values.map(value=>JSON.stringify(value)+'\n').join('');
  manifest.sha256=crypto.createHash('sha256').update(payload).digest('hex');
  await fs.writeFile(path.join(directory,'catalog.jsonl'),payload);
  await writeManifest(directory,manifest);
}

function scopedSnapshot(documents,regions,stamp=time) {
  const values=[...documents];
  const counts=rows=>({basic:rows.length,...Object.fromEntries(Object.keys(DEPARTMENTS).map(code=>[code,rows.filter(value=>value.departmentCodes.includes(code)).length]))});
  const manifest={schemaVersion:3,complete:true,source:'hospInfoServicev2/getHospBasisList',scope:'regions-with-department-memberships',
    regions,rows:values.length,departments:DEPARTMENTS,scopeCounts:counts(values),scopeTimes:scopeTimes(stamp),
    firstFetchedAt:stamp,lastFetchedAt:stamp,completedAt:stamp,sha256:'fixture-'+stamp,
    regionScopes:Object.fromEntries(regions.map(region=>[region,{scopeCounts:counts(values.filter(value=>value.apiFields.sidoCd===region)),scopeTimes:scopeTimes(stamp)}]))};
  return {manifest,documents:new Map(values.map(value=>[value._id,value]))};
}
function regionDocuments(region,count=8,stamp=time) {
  return Array.from({length:count},(_,index)=>normalizeRow(row({apiFields:{...raw,ykiho:`${region}-${index}`,sidoCd:region},fetchedAt:stamp})));
}
function changedDocument(document,stamp,changes={}) {
  return normalizeRow({apiFields:{...document.apiFields,...changes},departmentCodes:document.departmentCodes,fetchedAt:stamp});
}
async function writeScopedSnapshot(directory,snapshot) {
  const values=[...snapshot.documents.values()].map(doc=>({apiFields:doc.apiFields,departmentCodes:doc.departmentCodes,fetchedAt:doc.basicFetchedAt.toISOString()}));
  await rewriteRegionalRows(directory,snapshot.manifest,values);
}
async function withCatalogDb(check) {
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {await check(db);}finally{await db.dropDatabase();await client.close();}
}

const recoveryMonday='2026-11-22T15:05:00.000Z',recoverySunday='2026-11-28T15:10:00.000Z';
function recoverySnapshot(region='110000',originalDay='monday') {
  const documents=regionDocuments(region,8,recoveryMonday);
  documents[1]=changedDocument(documents[1],recoverySunday,{addr:'recovered address'});
  const snapshot=scopedSnapshot(documents,[region],recoveryMonday);
  Object.assign(snapshot.manifest,{schemaVersion:4,lastFetchedAt:recoverySunday,completedAt:recoverySunday,
    recovery:{mode:'sunday-failed-requests',weekStartKst:'2026-11-23',originalDay,attempt:1,recoveredAt:recoverySunday}});
  snapshot.manifest.scopeTimes.basic.lastFetchedAt=recoverySunday;
  snapshot.manifest.regionScopes[region].scopeTimes.basic.lastFetchedAt=recoverySunday;
  return snapshot;
}

test('Sunday recovery validates same-week reused pages while ordinary snapshots retain the 48-hour limit',async()=>{
  await withRegionalSnapshot(async directory=>{
    const snapshot=recoverySnapshot();
    await writeScopedSnapshot(directory,snapshot);
    assert.equal((await validateSnapshot(directory,new Date(recoverySunday))).documents.size,8);
    snapshot.manifest.schemaVersion=3;
    await writeScopedSnapshot(directory,snapshot);
    await assert.rejects(validateSnapshot(directory,new Date(recoverySunday)),/invalid_catalog_recovery/);
    delete snapshot.manifest.recovery;
    await writeScopedSnapshot(directory,snapshot);
    await assert.rejects(validateSnapshot(directory,new Date(recoverySunday)),/stale_or_invalid_snapshot_time/);
    const subset=recoverySnapshot('360000','wednesday');
    await writeScopedSnapshot(directory,subset);
    assert.equal((await validateSnapshot(directory,new Date(recoverySunday))).documents.size,8);
    const empty=scopedSnapshot([],['240000'],recoveryMonday);
    Object.assign(empty.manifest,{schemaVersion:4,completedAt:recoverySunday,recovery:{...recoverySnapshot('240000','saturday').manifest.recovery}});
    await writeScopedSnapshot(directory,empty);
    assert.equal((await validateSnapshot(directory,new Date(recoverySunday))).documents.size,0);
  });
});

test('Sunday recovery rejects invalid metadata, extra attempts, wrong weeks and weekday/region mismatches',async()=>{
  await withRegionalSnapshot(async directory=>{
    for(const [change,error] of [
      [manifest=>{delete manifest.recovery;},/invalid_catalog_recovery/],
      [manifest=>{manifest.recovery.attempt=2;},/invalid_catalog_recovery/],
      [manifest=>{manifest.recovery.attempt='1';},/invalid_catalog_recovery/],
      [manifest=>{manifest.recovery.mode='unrestricted-retry';},/invalid_catalog_recovery/],
      [manifest=>{manifest.recovery.originalDay='sunday';},/invalid_catalog_recovery/],
      [manifest=>{manifest.recovery.extra=true;},/invalid_catalog_recovery/],
      [manifest=>{manifest.recovery.originalDay='tuesday';},/invalid_catalog_recovery_regions/],
      [manifest=>{manifest.recovery.weekStartKst='2026-11-24';},/invalid_catalog_week/],
      [manifest=>{manifest.recovery.weekStartKst='2026-02-30';},/invalid_catalog_week/],
      [manifest=>{manifest.recovery.weekStartKst='2026-11-16';},/invalid_catalog_recovery_time/],
      [manifest=>{manifest.recovery.weekStartKst='2026-11-30';},/invalid_catalog_recovery_time/],
    ]) {
      const snapshot=recoverySnapshot();change(snapshot.manifest);
      await writeScopedSnapshot(directory,snapshot);
      await assert.rejects(validateSnapshot(directory,new Date(recoverySunday)),error);
    }
  });
});

test('Sunday recovery enforces Sunday completion, same-week observations, eight-day and 48-hour expiry',async()=>{
  await withRegionalSnapshot(async directory=>{
    for(const change of [
      manifest=>{manifest.recovery.recoveredAt='2026-11-28T15:11:00.000Z';},
      manifest=>{manifest.completedAt=manifest.recovery.recoveredAt='2026-11-29T15:00:00.000Z';},
      manifest=>{manifest.lastFetchedAt=manifest.completedAt=manifest.recovery.recoveredAt='2026-11-28T14:59:59.000Z';},
      manifest=>{manifest.firstFetchedAt='2026-11-22T14:59:59.000Z';},
      manifest=>{manifest.lastFetchedAt=manifest.completedAt=manifest.recovery.recoveredAt='2026-11-30T00:00:00.000Z';},
      manifest=>{manifest.recovery.recoveredAt='2026-11-29T00:10:00';},
    ]) {
      const snapshot=recoverySnapshot();change(snapshot.manifest);
      await writeScopedSnapshot(directory,snapshot);
      await assert.rejects(validateSnapshot(directory,new Date('2026-11-30T01:00:00.000Z')),/invalid_catalog_recovery_time/);
    }
    const snapshot=recoverySnapshot();
    await writeScopedSnapshot(directory,snapshot);
    await assert.rejects(validateSnapshot(directory,new Date('2026-11-30T15:10:01.000Z')),/invalid_catalog_recovery_time/);
    await assert.rejects(validateSnapshot(directory,new Date('2026-11-28T15:00:00.000Z')),/stale_or_invalid_snapshot_time/);
    // Independently exceed eight days while keeping the completed snapshot under 48 hours old.
    snapshot.manifest.completedAt=snapshot.manifest.recovery.recoveredAt='2026-11-29T14:59:00.000Z';
    await writeScopedSnapshot(directory,snapshot);
    await assert.rejects(validateSnapshot(directory,new Date('2026-11-30T15:06:00.000Z')),/invalid_catalog_recovery_time/);
  });
});

test('Mongo Sunday recovery preserves unrelated hospitals and cannot bypass newer region generations', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  await withCatalogDb(async db=>{
    const old='2026-11-16T00:00:00.000Z',seoul=regionDocuments('110000',8,old),busan=regionDocuments('210000',8,old);
    await applySnapshot(db,scopedSnapshot([...seoul,...busan],REGION_CODES,old),{apply:true,progress:()=>{}});
    const catalog=db.collection('hospitals'),runs=db.collection('hospitalImportRuns');
    const unrelated=await catalog.find({'apiFields.sidoCd':'210000'}).sort({_id:1}).toArray();
    const recovery=recoverySnapshot();
    assert.equal((await applySnapshot(db,recovery,{apply:true,now:new Date(recoverySunday),progress:()=>{}})).verified,8);
    assert.deepEqual(await catalog.find({'apiFields.sidoCd':'210000'}).sort({_id:1}).toArray(),unrelated);
    const current='2026-11-29T16:00:00.000Z';
    const newer=scopedSnapshot(regionDocuments('110000',8,current),['110000'],current);
    await applySnapshot(db,newer,{apply:true,now:new Date(current),progress:()=>{}});
    const marker=await runs.findOne({_id:'hospital-basic-region-current:110000'});
    await assert.rejects(applySnapshot(db,recovery,{apply:true,now:new Date(current),progress:()=>{}}),/snapshot_older_than_published_catalog/);
    assert.deepEqual(await runs.findOne({_id:'hospital-basic-region-current:110000'}),marker);
    const invalid=recoverySnapshot();invalid.manifest.recovery.attempt=2;
    const noDb={listCollections(){throw new Error('database_must_not_be_used');}};
    await assert.rejects(applySnapshot(noDb,invalid,{apply:true,now:new Date(recoverySunday),progress:()=>{}}),/invalid_catalog_recovery/);
  });
});

test('selected-region snapshots validate only declared regions and allow an explicitly empty subset',async()=>{
  await withRegionalSnapshot(async directory=>{
    const snapshot=scopedSnapshot(regionDocuments('110000'),['110000']);
    await writeScopedSnapshot(directory,snapshot);
    assert.equal((await validateSnapshot(directory,new Date(time))).documents.size,8);
    for(const regions of [[],['110000','110000'],['990000'],['210000','110000'],'110000']) {
      const bad=scopedSnapshot([],['240000']);
      bad.manifest.regions=regions;
      await writeScopedSnapshot(directory,bad);
      await assert.rejects(validateSnapshot(directory,new Date(time)),/invalid_selected_regions/);
    }
    const empty=scopedSnapshot([],['240000']);
    await writeScopedSnapshot(directory,empty);
    assert.equal((await validateSnapshot(directory,new Date(time))).documents.size,0);
    const allEmpty=scopedSnapshot([],REGION_CODES);
    await writeScopedSnapshot(directory,allEmpty);
    await assert.rejects(validateSnapshot(directory,new Date(time)),/incomplete_catalog_snapshot/);
    const downgrade=scopedSnapshot(regionDocuments('110000'),['110000']);
    downgrade.manifest.schemaVersion=2;
    downgrade.manifest.scope='nationwide-with-department-memberships';
    await writeScopedSnapshot(directory,downgrade);
    await assert.rejects(validateSnapshot(directory,new Date(time)),/incomplete_catalog_snapshot/);
  });
});

test('selected-region manifest cannot smuggle unrelated rows or omit regions, departments, counts or times',async()=>{
  await withRegionalSnapshot(async directory=>{
    for(const [change,error] of [
      [snapshot=>{snapshot.manifest.regionScopes['210000']=snapshot.manifest.regionScopes['110000'];},/incomplete_regional_scopes/],
      [snapshot=>{delete snapshot.manifest.regionScopes['110000'];},/incomplete_regional_scopes/],
      [snapshot=>{delete snapshot.manifest.regionScopes['110000'].scopeTimes['80'];},/incomplete_regional_scopes/],
      [snapshot=>{snapshot.manifest.regionScopes['110000'].scopeCounts.basic++;},/regional_scope_count_mismatch/],
      [snapshot=>{snapshot.manifest.regionScopes['110000'].scopeTimes.basic.firstFetchedAt='2026-11-20T23:00:00.000Z';},/regional_scope_time_bounds_mismatch/],
      [snapshot=>{snapshot.manifest.scopeCounts.extra=0;},/incomplete_department_scopes/],
      [snapshot=>{snapshot.manifest.scopeTimes.extra=scopeTimes().basic;},/incomplete_department_scopes/],
    ]) {
      const snapshot=scopedSnapshot(regionDocuments('110000'),['110000']);
      change(snapshot);
      await writeScopedSnapshot(directory,snapshot);
      await assert.rejects(validateSnapshot(directory,new Date(time)),error);
    }
    const outsider=scopedSnapshot(regionDocuments('110000'),['110000']);
    const doc=[...outsider.documents.values()][0];
    doc.apiFields.sidoCd='210000';
    await writeScopedSnapshot(directory,outsider);
    await assert.rejects(validateSnapshot(directory,new Date(time)),/invalid_hospital_region/);
  });
});

test('Mongo scoped sync updates and retires only selected hospitals while preserving other regions and full baselines', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  await withCatalogDb(async db=>{
    const seoul=regionDocuments('110000'),busan=regionDocuments('210000'),later='2026-11-21T01:00:00.000Z';
    const full=scopedSnapshot([...seoul,...busan],REGION_CODES);
    await applySnapshot(db,full,{apply:true,progress:()=>{}});
    const catalog=db.collection('hospitals'),runs=db.collection('hospitalImportRuns');
    const outside=await catalog.find({'apiFields.sidoCd':'210000'}).sort({_id:1}).toArray();
    const globalCurrent=await runs.findOne({_id:'hospital-basic-current'}),globalHighWater=await runs.findOne({_id:'hospital-basic-high-water'});
    const busanMarker=await runs.findOne({_id:'hospital-basic-region-current:210000'});
    for(const name of ['users','reservations','hospitalDetails'])await db.collection(name).insertOne({_id:'preserved',sentinel:name});
    const incoming=seoul.slice(0,-1).map((doc,index)=>changedDocument(doc,later,index===0?{addr:'changed address'}:{}));
    incoming.push(normalizeRow(row({apiFields:{...raw,ykiho:'new-seoul',sidoCd:'110000'},fetchedAt:later})));
    const snapshot=scopedSnapshot(incoming,['110000'],later);
    const result=await applySnapshot(db,snapshot,{apply:true,progress:()=>{}});
    assert.deepEqual({inserted:result.inserted,updated:result.updated,retired:result.retired,verified:result.verified},{inserted:1,updated:1,retired:1,verified:8});
    assert.deepEqual(result.regions,['110000']);
    assert.deepEqual(await catalog.find({'apiFields.sidoCd':'210000'}).sort({_id:1}).toArray(),outside);
    assert.equal((await catalog.findOne({_id:seoul.at(-1)._id})).active,false);
    assert.deepEqual(await runs.findOne({_id:'hospital-basic-current'}),globalCurrent);
    assert.deepEqual(await runs.findOne({_id:'hospital-basic-high-water'}),globalHighWater);
    assert.deepEqual(await runs.findOne({_id:'hospital-basic-region-current:210000'}),busanMarker);
    assert.deepEqual((await runs.findOne({_id:'hospital-basic-last-run'})).regions,['110000']);
    for(const name of ['users','reservations','hospitalDetails'])assert.deepEqual(await db.collection(name).findOne({_id:'preserved'}),{_id:'preserved',sentinel:name});
    const beforeRepeat=await catalog.find({}).sort({_id:1}).toArray();
    assert.equal((await applySnapshot(db,snapshot,{apply:true,progress:()=>{}})).unchanged,8);
    assert.deepEqual(await catalog.find({}).sort({_id:1}).toArray(),beforeRepeat);
    const beforeEmpty=await runs.findOne({_id:'hospital-basic-region-high-water:210000'});
    await assert.rejects(applySnapshot(db,scopedSnapshot([],['210000'],later),{apply:true,progress:()=>{}}),/unexpected_large_regional_reduction/);
    assert.deepEqual(await runs.findOne({_id:'hospital-basic-region-high-water:210000'}),beforeEmpty);
    assert.equal((await applySnapshot(db,scopedSnapshot([],['240000'],later),{apply:true,progress:()=>{}})).verified,0);
    assert.deepEqual(await catalog.find({}).sort({_id:1}).toArray(),beforeRepeat);
  });
});

test('Mongo per-region generations permit unrelated older work but reject same-region and full rollback', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  await withCatalogDb(async db=>{
    const a=regionDocuments('110000'),b=regionDocuments('210000');
    await applySnapshot(db,scopedSnapshot([...a,...b],REGION_CODES),{apply:true,progress:()=>{}});
    const t2='2026-11-21T02:00:00.000Z',t3='2026-11-21T03:00:00.000Z',t4='2026-11-21T04:00:00.000Z';
    const newerA=scopedSnapshot(a.map(doc=>changedDocument(doc,t3)),['110000'],t3);
    await applySnapshot(db,newerA,{apply:true,progress:()=>{}});
    const olderB=scopedSnapshot(b.map(doc=>changedDocument(doc,t2)),['210000'],t2);
    assert.equal((await applySnapshot(db,olderB,{apply:true,progress:()=>{}})).verified,8);
    await assert.rejects(applySnapshot(db,scopedSnapshot(a.map(doc=>changedDocument(doc,t2)),['110000'],t2),{apply:true,progress:()=>{}}),/snapshot_older_than_published_catalog/);
    const staleFull=scopedSnapshot([...a,...b].map(doc=>changedDocument(doc,t2)),REGION_CODES,t2);
    await assert.rejects(applySnapshot(db,staleFull,{apply:true,progress:()=>{}}),/snapshot_older_than_published_catalog/);
    const freshFull=scopedSnapshot([...a,...b].map(doc=>changedDocument(doc,t4)),REGION_CODES,t4);
    assert.equal((await applySnapshot(db,freshFull,{apply:true,progress:()=>{}})).verified,16);
    assert.equal((await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-current'})).lastFetchedAt.toISOString(),t4);
  });
});

test('Mongo stale cross-region moves cannot overwrite newer observations even when unchanged rows keep old fetch times', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  await withCatalogDb(async db=>{
    const a=regionDocuments('110000'),b=regionDocuments('210000'),moving=a[0];
    const t2='2026-11-21T02:00:00.000Z',t3='2026-11-21T03:00:00.000Z',t4='2026-11-21T04:00:00.000Z',t5='2026-11-21T05:00:00.000Z',t6='2026-11-21T06:00:00.000Z';
    await applySnapshot(db,scopedSnapshot([...a,...b],REGION_CODES),{apply:true,progress:()=>{}});
    await applySnapshot(db,scopedSnapshot(a.map(doc=>changedDocument(doc,t2)),['110000'],t2),{apply:true,progress:()=>{}});
    const moved=changedDocument(moving,t4,{sidoCd:'210000'});
    await applySnapshot(db,scopedSnapshot([...b.map(doc=>changedDocument(doc,t4)),moved],['210000'],t4),{apply:true,progress:()=>{}});
    const oldA=scopedSnapshot(a.map(doc=>changedDocument(doc,t3)),['110000'],t3);
    const marker=await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-region-high-water:110000'});
    await assert.rejects(applySnapshot(db,oldA,{apply:true,progress:()=>{}}),/snapshot_older_than_published_catalog/);
    assert.deepEqual(await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-region-high-water:110000'}),marker);
    await applySnapshot(db,scopedSnapshot([...b.map(doc=>changedDocument(doc,t5)),changedDocument(moved,t5)],['210000'],t5),{apply:true,progress:()=>{}});
    assert.equal((await db.collection('hospitals').findOne({_id:moving._id})).basicFetchedAt.toISOString(),t4);
    await assert.rejects(applySnapshot(db,scopedSnapshot(a.map(doc=>changedDocument(doc,t4)),['110000'],t4),{apply:true,progress:()=>{}}),/snapshot_older_than_published_catalog/);
    assert.equal((await db.collection('hospitals').findOne({_id:moving._id})).apiFields.sidoCd,'210000');
    const currentA=scopedSnapshot(a.map(doc=>changedDocument(doc,t6)),['110000'],t6);
    assert.equal((await applySnapshot(db,currentA,{apply:true,progress:()=>{}})).updated,1);
    assert.equal((await db.collection('hospitals').findOne({_id:moving._id})).apiFields.sidoCd,'110000');
  });
});

test('Mongo interrupted scoped apply keeps regional high-water and does not block unrelated-region retries', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  await withCatalogDb(async db=>{
    const a=regionDocuments('110000'),b=regionDocuments('210000'),t2='2026-11-21T02:00:00.000Z',t3='2026-11-21T03:00:00.000Z';
    await applySnapshot(db,scopedSnapshot([...a,...b],REGION_CODES),{apply:true,progress:()=>{}});
    const runs=db.collection('hospitalImportRuns'),catalog=db.collection('hospitals');
    const globalBefore=await runs.findOne({_id:'hospital-basic-high-water'});
    const newerA=scopedSnapshot(a.map(doc=>changedDocument(doc,t3,{addr:'newer address'})),['110000'],t3);
    const interrupted={listCollections:(...args)=>db.listCollections(...args),stats:()=>db.stats(),collection:name=>name!=='hospitals'?db.collection(name):new Proxy(catalog,{get(target,key){
      if(key==='bulkWrite')return async(...args)=>{await target.bulkWrite(...args);throw new Error('simulated_scoped_interruption');};
      return typeof target[key]==='function'?target[key].bind(target):target[key];
    }})};
    await assert.rejects(applySnapshot(interrupted,newerA,{apply:true,progress:()=>{}}),/simulated_scoped_interruption/);
    assert.equal((await runs.findOne({_id:'hospital-basic-region-high-water:110000'})).lastFetchedAt.toISOString(),t3);
    assert.equal((await runs.findOne({_id:'hospital-basic-region-current:110000'})).lastFetchedAt.toISOString(),time);
    assert.deepEqual(await runs.findOne({_id:'hospital-basic-high-water'}),globalBefore);
    await assert.rejects(applySnapshot(db,scopedSnapshot(a.map(doc=>changedDocument(doc,t2)),['110000'],t2),{apply:true,progress:()=>{}}),/snapshot_older_than_started_generation/);
    const olderB=scopedSnapshot(b.map(doc=>changedDocument(doc,t2)),['210000'],t2);
    assert.equal((await applySnapshot(db,olderB,{apply:true,progress:()=>{}})).verified,8);
    assert.equal((await applySnapshot(db,newerA,{apply:true,progress:()=>{}})).unchanged,8);
    assert.equal((await runs.findOne({_id:'hospital-basic-region-current:110000'})).lastFetchedAt.toISOString(),t3);
  });
});

test('all 17 regional scopes validate without changing hospital fields, identities or update behavior',async()=>{
  await withRegionalSnapshot(async directory=>{
    const {manifest}=await regionalFixture(directory);
    const regional=await validateSnapshot(directory,new Date(time));
    assert.equal(regional.documents.size,17);
    manifest.schemaVersion=1;
    await writeManifest(directory,manifest);
    await assert.rejects(validateSnapshot(directory,new Date(time)),/incomplete_catalog_snapshot/);
    delete manifest.regionScopes;
    await writeManifest(directory,manifest);
    const legacy=await validateSnapshot(directory,new Date(time));
    assert.deepEqual(regional.documents,legacy.documents);
    for(const [id,doc] of regional.documents)assert.equal(updateFor(legacy.documents.get(id),doc,new Date()),null);
  });
});

test('a regional snapshot must include every region and department even when totals were adjusted',async()=>{
  await withRegionalSnapshot(async directory=>{
    for(const change of [
      manifest=>{delete manifest.regionScopes;},
      manifest=>{const removed=manifest.regionScopes['110000'];delete manifest.regionScopes['110000'];manifest.rows--;for(const [code,count] of Object.entries(removed.scopeCounts))manifest.scopeCounts[code]-=count;},
      manifest=>{manifest.regionScopes['990000']=manifest.regionScopes['110000'];},
      manifest=>{delete manifest.regionScopes['110000'].scopeCounts['80'];},
      manifest=>{delete manifest.regionScopes['110000'].scopeTimes['80'];},
    ]) {
      const {manifest}=await regionalFixture(directory);
      change(manifest);
      await writeManifest(directory,manifest);
      await assert.rejects(validateSnapshot(directory,new Date(time)),/incomplete_regional_scopes/);
    }
  });
});

test('a verified zero-count region is valid when every regional scope is present',async()=>{
  await withRegionalSnapshot(async directory=>{
    const {manifest,values}=await regionalFixture(directory);
    const region='240000';
    const removed=manifest.regionScopes[region].scopeCounts;
    for(const code of Object.keys(removed)) {
      manifest.scopeCounts[code]-=removed[code];
      removed[code]=0;
    }
    manifest.rows--;
    await rewriteRegionalRows(directory,manifest,values.filter(value=>value.apiFields.sidoCd!==region));
    assert.equal((await validateSnapshot(directory,new Date(time))).documents.size,16);
  });
});

test('regional counts are checked against both national totals and actual rows',async()=>{
  await withRegionalSnapshot(async directory=>{
    const cases=[
      [manifest=>{manifest.regionScopes['110000'].scopeCounts.basic=0;},/invalid_regional_scope_count/],
      [manifest=>{manifest.regionScopes['110000'].scopeCounts['01']=2;},/invalid_regional_scope_count/],
      [manifest=>{manifest.regionScopes['110000'].scopeCounts.basic=2;},/regional_scope_count_mismatch/],
      [manifest=>{manifest.regionScopes['110000'].scopeCounts['01']=0;manifest.regionScopes['210000'].scopeCounts['01']=1;},/regional_catalog_count_mismatch/],
    ];
    for(const [change,error] of cases) {
      const {manifest}=await regionalFixture(directory);
      change(manifest);
      await writeManifest(directory,manifest);
      await assert.rejects(validateSnapshot(directory,new Date(time)),error);
    }
    for(const change of [
      values=>{values[0].apiFields.sidoCd='990000';},
      values=>{delete values[0].apiFields.sidoCd;},
    ]) {
      const {manifest,values}=await regionalFixture(directory);
      change(values);
      await rewriteRegionalRows(directory,manifest,values);
      await assert.rejects(validateSnapshot(directory,new Date(time)),/invalid_hospital_region/);
    }
    const {manifest,values}=await regionalFixture(directory);
    values[1].apiFields.sidoCd=values[0].apiFields.sidoCd;
    await rewriteRegionalRows(directory,manifest,values);
    await assert.rejects(validateSnapshot(directory,new Date(time)),/regional_catalog_count_mismatch/);
  });
});

test('regional time bounds and per-row regional collection times must agree',async()=>{
  await withRegionalSnapshot(async directory=>{
    for(const [change,error] of [
      [manifest=>{manifest.regionScopes['110000'].scopeTimes.basic.firstFetchedAt='invalid';},/invalid_regional_scope_times/],
      [manifest=>{manifest.regionScopes['110000'].scopeTimes.basic.firstFetchedAt='2026-11-20T23:59:59.000Z';},/regional_scope_time_bounds_mismatch/],
    ]) {
      const {manifest}=await regionalFixture(directory);
      change(manifest);
      await writeManifest(directory,manifest);
      await assert.rejects(validateSnapshot(directory,new Date(time)),error);
    }
    const {manifest}=await regionalFixture(directory);
    const later='2026-11-21T00:00:01.000Z';
    manifest.regionScopes['110000'].scopeTimes.basic={firstFetchedAt:later,lastFetchedAt:later};
    manifest.scopeTimes.basic.lastFetchedAt=later;
    manifest.lastFetchedAt=later;
    manifest.completedAt=later;
    await writeManifest(directory,manifest);
    await assert.rejects(validateSnapshot(directory,new Date(later)),/row_time_outside_regional_collection/);
  });
});

test('Mongo integration: unchanged sync writes no hospital records; missing/returning entries retain identity', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const a=normalizeRow(row()),b=normalizeRow(row({apiFields:{...raw,ykiho:'another-hospital',yadmNm:'다른 가상 병원'}}));
    const manifest={rows:2,sha256:'test-source',scopeCounts:{basic:2},scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time};
    const snapshot={manifest,documents:new Map([[a._id,a],[b._id,b]])};
    const initial=await applySnapshot(db,snapshot,{apply:true,progress:()=>{}});
    assert.equal(initial.inserted,2);
    await db.collection('hospitalDetails').insertOne({_id:'preserved',departments:[{code:'01'}]});
    await db.collection('users').insertOne({_id:'preserved'});
    const before=await db.collection('hospitals').find({}).sort({_id:1}).toArray();
    const repeated=await applySnapshot(db,snapshot,{apply:true,progress:()=>{}});
    assert.equal(repeated.unchanged,2);
    assert.equal(repeated.updated,0);
    assert.deepEqual(await db.collection('hospitals').find({}).sort({_id:1}).toArray(),before);
    const one={manifest:{...manifest,rows:1},documents:new Map([[a._id,a]])};
    assert.equal((await applySnapshot(db,one,{apply:true,progress:()=>{}})).retired,1);
    assert.equal((await db.collection('hospitals').findOne({_id:b._id})).active,false);
    assert.equal((await applySnapshot(db,snapshot,{apply:true,progress:()=>{}})).reactivated,1);
    assert.deepEqual(await db.collection('hospitalDetails').findOne({_id:'preserved'}),{_id:'preserved',departments:[{code:'01'}]});
    assert.equal(await db.collection('users').countDocuments({}),1);
    assert.equal((await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-current'})).expectedRows,2);
    const older={...snapshot,manifest:{...manifest,lastFetchedAt:'2026-11-20T00:00:00Z'}};
    await assert.rejects(applySnapshot(db,older,{apply:true,progress:()=>{}}),/snapshot_older_than_published_catalog/);
    assert.equal(await db.collection('hospitalImportRuns').countDocuments({_id:'hospital-basic-sync-lock'}),0);
    await db.collection('hospitalImportRuns').insertOne({_id:'hospital-basic-sync-lock',owner:'other-run',expiresAt:new Date(Date.now()+60000)});
    await assert.rejects(applySnapshot(db,snapshot,{apply:true,progress:()=>{}}),/catalog_sync_already_running/);
    await db.collection('hospitalImportRuns').deleteOne({_id:'hospital-basic-sync-lock',owner:'other-run'});
    await db.collection('hospitals').updateOne({_id:a._id},{$set:{'apiFields.addr':'corrupted content'}});
    await assert.rejects(applySnapshot(db,snapshot,{apply:true,progress:()=>{}}),/database_catalog_verification_failed/);
    assert.equal((await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-current'})).expectedRows,2);
  }finally{await db.dropDatabase();await client.close();}
});

test('regional apply rejects a missing small region before changing hospitals or generation markers', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const seoul=normalizeRow(row({apiFields:{...raw,ykiho:'seoul-fixture',sidoCd:'110000'}}));
    const sejong=normalizeRow(row({apiFields:{...raw,ykiho:'sejong-fixture',sidoCd:'410000'}}));
    const manifest={schemaVersion:1,rows:2,sha256:'regional-test-source',scopeCounts:{basic:2},scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time};
    const snapshot={manifest,documents:new Map([[seoul._id,seoul],[sejong._id,sejong]])};
    await applySnapshot(db,snapshot,{apply:true,progress:()=>{}});
    const before=await db.collection('hospitals').find({}).sort({_id:1}).toArray();
    const highWater=await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-high-water'});
    const incomplete={manifest:{...manifest,schemaVersion:2,rows:1},documents:new Map([[seoul._id,seoul]])};
    await assert.rejects(applySnapshot(db,incomplete,{apply:true,progress:()=>{}}),/unexpected_large_regional_reduction/);
    assert.deepEqual(await db.collection('hospitals').find({}).sort({_id:1}).toArray(),before);
    assert.deepEqual(await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-high-water'}),highWater);
    assert.equal(await db.collection('hospitalImportRuns').countDocuments({status:'FAILED'}),0);
    assert.equal(await db.collection('hospitalImportRuns').countDocuments({_id:'hospital-basic-sync-lock'}),0);
    const repeated=await applySnapshot(db,{...snapshot,manifest:{...manifest,schemaVersion:2}},{apply:true,progress:()=>{}});
    assert.equal(repeated.unchanged,2);
    assert.equal(repeated.updated,0);
    assert.deepEqual(await db.collection('hospitals').find({}).sort({_id:1}).toArray(),before);
  }finally{await db.dropDatabase();await client.close();}
});

test('legacy and interrupted collection renames block sync; completed rename retains IDs and details', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const basic=normalizeRow(row());
    const detail={_id:basic._id,provider:'HIRA',externalId:basic.externalId,departments:[{code:'01'}],detailFetchedAt:new Date(time)};
    const snapshot={manifest:{rows:1,sha256:'source',scopeCounts:{basic:1},scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time},documents:new Map([[basic._id,basic]])};
    await db.collection('hospitalBasicCatalog').insertOne(basic);
    await db.collection('hospitals').insertOne(detail);
    for(const apply of [false,true])await assert.rejects(applySnapshot(db,snapshot,{apply,progress:()=>{}}),/hospital_collection_migration_required/);
    assert.deepEqual(await db.collection('hospitals').findOne({_id:basic._id}),detail);
    assert.deepEqual(await db.collection('hospitalBasicCatalog').findOne({_id:basic._id}),basic);
    assert.equal(await db.collection('hospitalImportRuns').countDocuments({}),0);
    await db.collection('hospitals').rename('hospitalDetails');
    await assert.rejects(applySnapshot(db,snapshot,{apply:true,progress:()=>{}}),/hospital_collection_migration_required/);
    assert.equal(await db.listCollections({name:'hospitals'},{nameOnly:true}).hasNext(),false);
    await db.collection('hospitalBasicCatalog').rename('hospitals');
    const result=await applySnapshot(db,snapshot,{apply:true,progress:()=>{}});
    assert.equal(result.unchanged,1);
    assert.equal(result.inserted,0);
    assert.equal(result.updated,0);
    assert.deepEqual(await db.collection('hospitals').findOne({_id:basic._id}),basic);
    assert.deepEqual(await db.collection('hospitalDetails').findOne({_id:basic._id}),detail);
    assert.equal(await db.listCollections({name:'hospitalBasicCatalog'},{nameOnly:true}).hasNext(),false);
  }finally{await db.dropDatabase();await client.close();}
});

test('detailed hospital records in the basic target stop sync before hospital or generation writes', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const basic=normalizeRow(row());
    const detail={_id:'legacy-detail',provider:'HIRA',externalId:'legacy-hospital',departments:[{code:'01'}]};
    await db.collection('hospitals').insertMany([basic,detail]);
    const before=await db.collection('hospitals').find({}).sort({_id:1}).toArray();
    const snapshot={manifest:{rows:1,sha256:'source',scopeCounts:{basic:1},scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time},documents:new Map([[basic._id,basic]])};
    for(const apply of [false,true])await assert.rejects(applySnapshot(db,snapshot,{apply,progress:()=>{}}),/hospital_collection_migration_required/);
    assert.deepEqual(await db.collection('hospitals').find({}).sort({_id:1}).toArray(),before);
    assert.equal(await db.collection('hospitalImportRuns').countDocuments({}),0);
  }finally{await db.dropDatabase();await client.close();}
});

test('failed newer apply keeps a high-water mark, including department collection times', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const original=normalizeRow(row()),later='2026-11-21T01:00:00.000Z';
    const manifest={rows:1,sha256:'old',scopeCounts:{basic:1},scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time};
    const old={manifest,documents:new Map([[original._id,original]])};
    await applySnapshot(db,old,{apply:true,progress:()=>{}});
    const changed=normalizeRow(row({apiFields:{...raw,addr:'newer address'},fetchedAt:later}));
    const newer={manifest:{...manifest,sha256:'new',scopeTimes:scopeTimes(later),firstFetchedAt:later,lastFetchedAt:later},documents:new Map([[changed._id,changed]])};
    const catalog=db.collection('hospitals');
    const interrupted={listCollections:(...args)=>db.listCollections(...args),stats:()=>db.stats(),collection:name=>name!=='hospitals'?db.collection(name):new Proxy(catalog,{get(target,key){
      if(key==='bulkWrite')return async(...args)=>{await target.bulkWrite(...args);throw new Error('simulated_write_interruption');};
      return typeof target[key]==='function'?target[key].bind(target):target[key];
    }})};
    await assert.rejects(applySnapshot(interrupted,newer,{apply:true,progress:()=>{}}),/simulated_write_interruption/);
    assert.equal((await catalog.findOne({_id:original._id})).address,'newer address');
    assert.equal((await db.collection('hospitalImportRuns').findOne({_id:'hospital-basic-current'})).lastFetchedAt.toISOString(),time);
    await assert.rejects(applySnapshot(db,old,{apply:true,progress:()=>{}}),/snapshot_older_than_started_generation/);
    const oldDepartment={...newer,manifest:{...newer.manifest,scopeTimes:{...newer.manifest.scopeTimes,'01':scopeTimes()['01']}}};
    await assert.rejects(applySnapshot(db,oldDepartment,{apply:true,progress:()=>{}}),/snapshot_older_than_started_generation/);
    await applySnapshot(db,newer,{apply:true,progress:()=>{}});
  }finally{await db.dropDatabase();await client.close();}
});

test('heartbeat covers slow pre-write work and lock loss prevents publication', {skip:!process.env.MONGODB_TEST_URI},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_sync_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const doc=normalizeRow(row());
    const snapshot={manifest:{rows:1,sha256:'source',scopeCounts:{basic:1},scopeTimes:scopeTimes(),firstFetchedAt:time,lastFetchedAt:time},documents:new Map([[doc._id,doc]])};
    const runs=db.collection('hospitalImportRuns'),catalog=db.collection('hospitals');
    const slow={listCollections:(...args)=>db.listCollections(...args),collection:name=>db.collection(name),stats:async()=>{
      await runs.updateOne({_id:'hospital-basic-sync-lock'},{$set:{expiresAt:new Date(Date.now()+100)}});
      await new Promise(resolve=>setTimeout(resolve,180));
      assert.ok((await runs.findOne({_id:'hospital-basic-sync-lock'})).expiresAt>new Date(Date.now()+60000));
      return db.stats();
    }};
    await applySnapshot(slow,snapshot,{apply:true,progress:()=>{},heartbeatMs:10});
    const latest=await runs.findOne({_id:'hospital-basic-current'});
    const stolen={listCollections:(...args)=>db.listCollections(...args),stats:()=>db.stats(),collection:name=>name!=='hospitals'?db.collection(name):new Proxy(catalog,{get(target,key){
      if(key==='find')return (filter,options)=>options?target.find(filter,options):{async *[Symbol.asyncIterator](){
        for await(const entry of target.find(filter))yield entry;
        await runs.updateOne({_id:'hospital-basic-sync-lock'},{$set:{owner:'replacement-owner'}});
      }};
      return typeof target[key]==='function'?target[key].bind(target):target[key];
    }})};
    await assert.rejects(applySnapshot(stolen,snapshot,{apply:true,progress:()=>{},heartbeatMs:10}),/catalog_sync_lock_lost/);
    assert.equal((await runs.findOne({_id:'hospital-basic-current'})).runId,latest.runId);
    assert.equal((await runs.findOne({_id:'hospital-basic-sync-lock'})).owner,'replacement-owner');
  }finally{await db.dropDatabase();await client.close();}
});
