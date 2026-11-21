import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {DEPARTMENTS,hospitalId,normalizeRow,updateFor,assertReasonableRemoval,validateSnapshot,applySnapshot} from '../sync-hospital-catalog.mjs';

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
