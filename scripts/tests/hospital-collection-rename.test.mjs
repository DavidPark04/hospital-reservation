import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {createRenamePlan,inspectRenameLayout,verifyRenamePlan,applyRenamePlan} from '../rename-hospital-collections.mjs';
import {normalizeRow} from '../sync-hospital-catalog.mjs';

const mongo={skip:!process.env.MONGODB_TEST_URI};
async function withDatabase(operation) {
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(process.env.MONGODB_TEST_URI);
  await client.connect();
  const db=client.db('catalog_rename_test_'+crypto.randomUUID().replaceAll('-',''));
  try {await operation(db);}finally {await db.dropDatabase();await client.close();}
}
async function fixture(db) {
  const basic=normalizeRow({apiFields:{ykiho:'fixture-hospital',yadmNm:'병원 값은 계획에 기록하지 않음',addr:'fixture-address'},departmentCodes:['01'],fetchedAt:'2026-11-21T00:00:00Z'});
  const details={_id:basic._id,provider:'HIRA',externalId:basic.externalId,name:basic.name,departments:[{code:'01'}],detailFetchedAt:new Date('2026-11-21T00:00:00Z')};
  await db.collection('hospitalBasicCatalog').insertOne(basic);
  await db.collection('hospitalBasicCatalog').createIndex({provider:1,externalId:1},{name:'catalog_provider_external_v1',unique:true});
  await db.collection('hospitalBasicCatalog').createIndex({active:1,name:1,_id:1},{name:'catalog_active_name_v1'});
  await db.collection('hospitals').insertOne(details);
  await db.collection('hospitals').createIndex({provider:1,externalId:1},{name:'hospital_provider_external_v1',unique:true});
  const doctor={_id:'fixture-doctor',hospitalId:basic._id};
  await db.collection('doctors').insertOne(doctor);
  return {basic,details,doctor};
}

test('dry-run plan contains metadata only and application requires stopped writers',mongo,()=>withDatabase(async db=>{
  const before=await fixture(db);
  const plan=await createRenamePlan(db);
  assert.equal(plan.database,db.databaseName);
  assert.equal(plan.sourceState,'legacy');
  assert.equal(plan.collections.basic.count,1);
  assert.match(plan.collections.basic.uuid,/^[a-f0-9]{32}$/);
  assert.equal(plan.collections.basic.indexCount,3);
  assert.equal(JSON.stringify(plan).includes(before.basic.name),false);
  assert.equal((await verifyRenamePlan(db,plan)).state,'legacy');
  await assert.rejects(applyRenamePlan(db,plan),/writers_must_be_stopped/);
  await assert.rejects(applyRenamePlan(db,{...plan,database:'other_database'},{writersStopped:true}),/migration_plan_database_or_schema_mismatch/);
  assert.deepEqual(await db.collection('hospitalBasicCatalog').findOne({}),before.basic);
  assert.deepEqual(await db.collection('hospitals').findOne({}),before.details);
  assert.equal(await db.listCollections({name:'hospitalDetails'}).hasNext(),false);
}));

test('renaming preserves complete documents, IDs, collection UUIDs, indexes and references; repeat is a no-op',mongo,()=>withDatabase(async db=>{
  const before=await fixture(db),plan=await createRenamePlan(db);
  const events=[];
  const result=await applyRenamePlan(db,plan,{writersStopped:true,progress:value=>events.push(value)});
  assert.equal(result.state,'current');
  assert.equal(result.applied,true);
  assert.deepEqual(result.collections,plan.collections);
  assert.deepEqual(events.map(event=>[event.from,event.to]),[['hospitals','hospitalDetails'],['hospitalBasicCatalog','hospitals']]);
  assert.deepEqual(await db.collection('hospitals').findOne({}),before.basic);
  assert.deepEqual(await db.collection('hospitalDetails').findOne({}),before.details);
  assert.deepEqual(await db.collection('doctors').findOne({}),before.doctor);
  assert.equal(await db.listCollections({name:'hospitalBasicCatalog'}).hasNext(),false);
  assert.equal((await applyRenamePlan(db,plan,{writersStopped:true})).applied,false);
}));

test('second rename failure leaves both collections intact and the saved plan resumes safely',mongo,()=>withDatabase(async db=>{
  const before=await fixture(db),plan=await createRenamePlan(db);
  const failed={databaseName:db.databaseName,listCollections:(...args)=>db.listCollections(...args),collection:name=>{
    const target=db.collection(name);
    if(name!=='hospitalBasicCatalog')return target;
    return new Proxy(target,{get(object,key){
      if(key==='rename')return async()=>{throw new Error('simulated_second_rename_failure');};
      return typeof object[key]==='function'?object[key].bind(object):object[key];
    }});
  }};
  await assert.rejects(applyRenamePlan(failed,plan,{writersStopped:true}),/rename_basic_failed_resume_with_same_plan/);
  assert.equal((await verifyRenamePlan(db,plan)).state,'intermediate');
  assert.deepEqual(await db.collection('hospitalBasicCatalog').findOne({}),before.basic);
  assert.deepEqual(await db.collection('hospitalDetails').findOne({}),before.details);
  assert.equal(await db.listCollections({name:'hospitals'}).hasNext(),false);
  assert.equal((await applyRenamePlan(db,plan,{writersStopped:true})).state,'current');
}));

test('explicit rollback restores legacy namespaces and is repeatable without dropping data',mongo,()=>withDatabase(async db=>{
  const before=await fixture(db),plan=await createRenamePlan(db);
  await applyRenamePlan(db,plan,{writersStopped:true});
  const result=await applyRenamePlan(db,plan,{writersStopped:true,rollback:true});
  assert.equal(result.state,'legacy');
  assert.deepEqual(result.collections,plan.collections);
  assert.deepEqual(await db.collection('hospitalBasicCatalog').findOne({}),before.basic);
  assert.deepEqual(await db.collection('hospitals').findOne({}),before.details);
  assert.equal(await db.listCollections({name:'hospitalDetails'}).hasNext(),false);
  assert.equal((await applyRenamePlan(db,plan,{writersStopped:true,rollback:true})).applied,false);
}));

test('existing destination, mixed content and changed plan metadata fail without overwriting',mongo,()=>withDatabase(async db=>{
  const before=await fixture(db),plan=await createRenamePlan(db);
  await db.collection('hospitalDetails').insertOne({...before.details,_id:'existing-target'});
  await assert.rejects(applyRenamePlan(db,plan,{writersStopped:true}),/ambiguous_or_missing_hospital_collections/);
  assert.equal(await db.collection('hospitalDetails').countDocuments({}),1);
  await db.collection('hospitalDetails').drop();
  await db.collection('hospitalBasicCatalog').insertOne({...before.details,_id:'mixed-detail',externalId:'mixed-external'});
  await assert.rejects(applyRenamePlan(db,plan,{writersStopped:true}),/unexpected_basic_collection_shape/);
  await db.collection('hospitalBasicCatalog').deleteOne({_id:'mixed-detail'});
  await db.collection('hospitals').createIndex({name:1},{name:'changed-after-plan'});
  await assert.rejects(applyRenamePlan(db,plan,{writersStopped:true}),/migration_plan_collection_changed/);
  assert.equal((await inspectRenameLayout(db)).state,'legacy');
  assert.deepEqual(await db.collection('hospitalBasicCatalog').findOne({}),before.basic);
  assert.deepEqual(await db.collection('hospitals').findOne({}),before.details);
}));

test('recreated collection UUID is rejected even when count and indexes match the plan',mongo,()=>withDatabase(async db=>{
  const before=await fixture(db),plan=await createRenamePlan(db);
  await db.collection('hospitals').drop();
  await db.collection('hospitals').insertOne(before.details);
  await db.collection('hospitals').createIndex({provider:1,externalId:1},{name:'hospital_provider_external_v1',unique:true});
  await assert.rejects(applyRenamePlan(db,plan,{writersStopped:true}),/migration_plan_collection_changed/);
  assert.equal(await db.listCollections({name:'hospitalDetails'}).hasNext(),false);
  assert.deepEqual(await db.collection('hospitals').findOne({}),before.details);
}));
