import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {CATALOG_WEEKDAY_REGIONS,catalogWeekStartTimestamp,catalogWeekForTime,SyncError} from './sync-hospital-catalog.mjs';

export function validateBatchSelection({weekStartKst,day,mode,githubRunId},now=new Date()) {
  catalogWeekStartTimestamp(weekStartKst);
  if(!Object.hasOwn(CATALOG_WEEKDAY_REGIONS,day)||!['collect','retry'].includes(mode))throw new SyncError('invalid_catalog_batch');
  if(catalogWeekForTime(now)!==weekStartKst)throw new SyncError('catalog_batch_wrong_week');
  const weekday=new Date(now.getTime()+9*3600_000).getUTCDay();
  if((mode==='retry'&&weekday!==0)||(mode==='collect'&&weekday===0))throw new SyncError('catalog_batch_wrong_day');
  if(githubRunId!==undefined&&(typeof githubRunId!=='string'||!/^\d{1,30}$/.test(githubRunId)))throw new SyncError('invalid_github_run_id');
  return {batchId:`hospital-basic-batch:${weekStartKst}:${day}:${mode}`,weekStartKst,day,mode,
    ...(githubRunId===undefined?{}:{githubRunId})};
}

export async function claimBatch(db,selection,{now=new Date()}={}) {
  const batch=validateBatchSelection(selection,now);
  try {
    await db.collection('hospitalImportRuns').insertOne({_id:batch.batchId,provider:'HIRA',kind:'BASIC_BATCH',status:'CLAIMED',
      weekStartKst:batch.weekStartKst,originalDay:batch.day,mode:batch.mode,claimedAt:now,
      ...(batch.githubRunId===undefined?{}:{githubRunId:batch.githubRunId})});
    return {claimed:true,...batch};
  }catch(error) {
    // The _id claim is consumed permanently, even if the caller crashes or a
    // later API request fails. A re-run must not make those requests again.
    if(error.code===11000)return {claimed:false,...batch};
    throw error;
  }
}

export function parseArguments(args) {
  const flags=new Set(['--week-start','--day','--mode','--output']),values={};
  for(let index=0;index<args.length;index+=2) {
    const key=args[index],value=args[index+1];
    if(!flags.has(key)||Object.hasOwn(values,key)||typeof value!=='string'||!value||value.startsWith('--'))throw new SyncError('invalid_catalog_batch_arguments');
    values[key]=value;
  }
  if(Object.keys(values).length!==flags.size)throw new SyncError('invalid_catalog_batch_arguments');
  return {weekStartKst:values['--week-start'],day:values['--day'],mode:values['--mode'],output:path.resolve(values['--output'])};
}

export function writeClaimResult(filename,result) {
  fs.mkdirSync(path.dirname(filename),{recursive:true});
  const temporary=`${filename}.${crypto.randomUUID()}.tmp`;
  const descriptor=fs.openSync(temporary,'wx');
  try {
    fs.writeFileSync(descriptor,JSON.stringify(result,null,2));
    fs.fsyncSync(descriptor);
  }finally{fs.closeSync(descriptor);}
  fs.renameSync(temporary,filename);
}

async function main() {
  const {output,...selection}=parseArguments(process.argv.slice(2));
  if(process.env.GITHUB_RUN_ID)selection.githubRunId=process.env.GITHUB_RUN_ID;
  validateBatchSelection(selection);
  const uri=process.env.MONGODB_URI;
  if(!uri||!/^mongodb(?:\+srv)?:\/\//.test(uri))throw new SyncError('missing_mongodb_uri');
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(uri,{maxPoolSize:2,serverSelectionTimeoutMS:15000,connectTimeoutMS:15000,appName:'hospital-catalog-batch-claim'});
  try {
    await client.connect();
    const result=await claimBatch(client.db(),selection);
    writeClaimResult(output,result);
    console.log(JSON.stringify({event:'catalog_batch_claim',...result}));
  }finally{await client.close();}
}

if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error=>{
    console.error(JSON.stringify({event:'catalog_batch_claim_failed',reason:error instanceof SyncError?error.code:'database_operation_failed',errorType:error.name}));
    process.exitCode=1;
  });
}
