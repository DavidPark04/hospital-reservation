import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {claimBatch,validateBatchSelection,parseArguments,writeClaimResult} from '../claim-hospital-catalog-batch.mjs';
import {catalogWeekForTime,catalogWeekStartTimestamp} from '../sync-hospital-catalog.mjs';

const week='2026-11-23',monday=new Date('2026-11-22T15:00:00.000Z'),sunday=new Date('2026-11-28T15:00:00.000Z');
const selection=(changes={})=>({weekStartKst:week,day:'monday',mode:'collect',...changes});
const configuredUri=process.env.MONGODB_TEST_URI;
function requireLocalTestUri() {
  const url=new URL(configuredUri);
  if(url.protocol!=='mongodb:'||!['127.0.0.1','localhost','[::1]'].includes(url.hostname)||url.username||url.password)throw new Error('Catalogue claim tests require a local MongoDB URI');
  return configuredUri;
}

test('batch selection uses KST week/day boundaries and allows weekday manual groups only within that week',()=>{
  assert.equal(catalogWeekForTime(new Date('2026-11-22T14:59:59.999Z')),'2026-11-16');
  assert.equal(catalogWeekForTime(monday),week);
  assert.equal(catalogWeekStartTimestamp(week),monday.getTime());
  assert.equal(validateBatchSelection(selection(),monday).batchId,'hospital-basic-batch:2026-11-23:monday:collect');
  assert.equal(validateBatchSelection(selection({day:'saturday'}),monday).day,'saturday');
  assert.equal(validateBatchSelection(selection({mode:'retry'}),sunday).mode,'retry');
  assert.throws(()=>validateBatchSelection(selection(),sunday),/catalog_batch_wrong_day/);
  assert.throws(()=>validateBatchSelection(selection({mode:'retry'}),new Date(sunday.getTime()-1)),/catalog_batch_wrong_day/);
  assert.throws(()=>validateBatchSelection(selection({mode:'retry'}),new Date('2026-11-29T15:00:00.000Z')),/catalog_batch_wrong_week/);
  for(const value of ['2026-11-24','2026-02-30','2026-11-23T00:00:00Z','2026-1-05','',null])assert.throws(()=>validateBatchSelection(selection({weekStartKst:value}),monday),/invalid_catalog_week/);
  for(const value of ['sunday','__proto__','',null])assert.throws(()=>validateBatchSelection(selection({day:value}),monday),/invalid_catalog_batch/);
  for(const value of ['apply','RETRY','',null])assert.throws(()=>validateBatchSelection(selection({mode:value}),monday),/invalid_catalog_batch/);
  assert.equal(validateBatchSelection(selection({githubRunId:'1234567890'}),monday).githubRunId,'1234567890');
  for(const value of ['request-secret','123\n456','-1',123,null])assert.throws(()=>validateBatchSelection(selection({githubRunId:value}),monday),/invalid_github_run_id/);
});

test('claim CLI requires exactly its four explicit flags and writes the claimed result atomically',()=>{
  const args=['--week-start',week,'--day','monday','--mode','collect','--output','claim-result.json'];
  assert.deepEqual(parseArguments(args),{weekStartKst:week,day:'monday',mode:'collect',output:path.resolve('claim-result.json')});
  for(const invalid of [[],args.slice(0,-1),[...args,'--extra','x'],[...args,'--day','tuesday'],args.slice(0,-2),['--week-start','--day']])assert.throws(()=>parseArguments(invalid),/invalid_catalog_batch_arguments/);
  const folder=fs.mkdtempSync(path.join(os.tmpdir(),'catalog-claim-test-'));
  try {
    const filename=path.join(folder,'result.json');
    const claimed={claimed:true,batchId:'fixture'};
    writeClaimResult(filename,claimed);
    assert.deepEqual(JSON.parse(fs.readFileSync(filename,'utf8')),claimed);
    writeClaimResult(filename,{...claimed,claimed:false});
    assert.equal(JSON.parse(fs.readFileSync(filename,'utf8')).claimed,false);
    assert.deepEqual(fs.readdirSync(folder),['result.json']);
  }finally{
    const resolved=path.resolve(folder);
    assert.equal(path.dirname(resolved),path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('catalog-claim-test-'));
    fs.rmSync(resolved,{recursive:true,force:true});
  }
});

test('a failed claim never authorizes API work and validation runs before database access',async()=>{
  let calls=0;
  const db={collection(){calls++;return {insertOne(){throw new Error('database_unavailable');}};}};
  await assert.rejects(claimBatch(db,selection({mode:'retry'}),{now:monday}),/catalog_batch_wrong_day/);
  assert.equal(calls,0);
  await assert.rejects(claimBatch(db,selection(),{now:monday}),/database_unavailable/);
  assert.equal(calls,1);
});

test('local Mongo claims authorize a batch once across concurrent runs, retries and process crashes', {skip:!configuredUri},async()=>{
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(requireLocalTestUri(),{serverSelectionTimeoutMS:3000});
  await client.connect();
  const db=client.db('catalog_batch_test_'+crypto.randomUUID().replaceAll('-',''));
  try {
    const claims=await Promise.all(Array.from({length:12},(_,index)=>claimBatch(db,selection({githubRunId:String(index+1)}),{now:monday})));
    assert.equal(claims.filter(result=>result.claimed).length,1);
    const calls=[];
    for(const result of claims)if(result.claimed)calls.push('API-request');
    assert.equal(calls.length,1);
    const marker=await db.collection('hospitalImportRuns').findOne({_id:claims[0].batchId});
    assert.equal(marker.provider,'HIRA');assert.equal(marker.kind,'BASIC_BATCH');assert.equal(marker.status,'CLAIMED');
    assert.equal(marker.claimedAt.toISOString(),monday.toISOString());
    assert.equal(marker.originalDay,'monday');assert.equal(marker.mode,'collect');
    // No completion/release is needed: even a crash consumes that whole batch.
    assert.equal((await claimBatch(db,selection({githubRunId:'99'}),{now:monday})).claimed,false);
    assert.equal((await claimBatch(db,selection({mode:'retry'}),{now:sunday})).claimed,true);
    assert.equal((await claimBatch(db,selection({mode:'retry',githubRunId:'100'}),{now:sunday})).claimed,false);
    assert.equal((await claimBatch(db,selection({day:'tuesday'}),{now:monday})).claimed,true);
    const nextWeek=selection({weekStartKst:'2026-11-30'});
    assert.equal((await claimBatch(db,nextWeek,{now:new Date('2026-11-29T15:00:00.000Z')})).claimed,true);
    assert.equal(await db.collection('hospitalImportRuns').countDocuments({}),4);
    assert.deepEqual(await db.listCollections({}, {nameOnly:true}).toArray(),[{name:'hospitalImportRuns',type:'collection'}]);
    assert.deepEqual(await db.collection('hospitalImportRuns').findOne({_id:claims[0].batchId}),marker);
  }finally{await db.dropDatabase();await client.close();}
});
