import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';

const LEGACY_BASIC='hospitalBasicCatalog',BASIC='hospitals',DETAILS='hospitalDetails';
const NAMES=[LEGACY_BASIC,BASIC,DETAILS];
const MOVES=Object.freeze([Object.freeze({from:BASIC,to:DETAILS,role:'details'}),Object.freeze({from:LEGACY_BASIC,to:BASIC,role:'basic'})]);

export class RenameError extends Error {
  constructor(code) { super(code);this.name='RenameError';this.code=code; }
}
function canonical(value) {
  if(Array.isArray(value))return value.map(canonical);
  if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])]));
  return value;
}
function indexSignature(indexes) {
  const definitions=indexes.map(({ns,key,...options})=>({...canonical(options),key:Object.entries(key)}))
    .sort((left,right)=>left.name.localeCompare(right.name));
  return crypto.createHash('sha256').update(JSON.stringify(definitions)).digest('hex');
}
async function collectionSnapshot(db,info,role) {
  if(info.type!=='collection'||info.info?.uuid?.sub_type!==4)throw new RenameError('unsupported_collection_metadata');
  const collection=db.collection(info.name);
  const shape=role==='basic'
    ? {_id:{$type:'string'},provider:'HIRA',externalId:{$type:'string'},name:{$type:'string'},
      apiFields:{$type:'object'},catalogHash:{$type:'string',$regex:'^[0-9a-f]{64}$'},
      basicSourceHash:{$type:'string',$regex:'^[0-9a-f]{64}$'},departmentCodes:{$type:'array'},active:{$type:'bool'}}
    : {_id:{$type:'string'},provider:{$in:['HIRA','SAMPLE']},externalId:{$type:'string'},name:{$type:'string'},
      apiFields:{$exists:false},catalogHash:{$exists:false},basicSourceHash:{$exists:false}};
  // Only a matching ID crosses the wire; no hospital or member values are logged.
  if(await collection.findOne({$nor:[shape]},{projection:{_id:1},maxTimeMS:60000}))throw new RenameError('unexpected_'+role+'_collection_shape');
  const count=await collection.countDocuments({},{hint:'_id_',maxTimeMS:60000});
  const indexes=await collection.listIndexes().toArray();
  return {uuid:Buffer.from(info.info.uuid.buffer).toString('hex'),count,indexCount:indexes.length,indexSignature:indexSignature(indexes)};
}
export async function inspectRenameLayout(db) {
  // Atlas shared clusters accept fewer listCollections name filters than mongod.
  const collections=(await db.listCollections({}).toArray()).filter(info=>NAMES.includes(info.name));
  const byName=new Map(collections.map(info=>[info.name,info]));
  let state,basicName,detailName;
  if(byName.has(LEGACY_BASIC)&&byName.has(BASIC)&&!byName.has(DETAILS)) {
    state='legacy';basicName=LEGACY_BASIC;detailName=BASIC;
  }else if(byName.has(LEGACY_BASIC)&&!byName.has(BASIC)&&byName.has(DETAILS)) {
    state='intermediate';basicName=LEGACY_BASIC;detailName=DETAILS;
  }else if(!byName.has(LEGACY_BASIC)&&byName.has(BASIC)&&byName.has(DETAILS)) {
    state='current';basicName=BASIC;detailName=DETAILS;
  }else throw new RenameError('ambiguous_or_missing_hospital_collections');
  return {state,names:{basic:basicName,details:detailName},collections:{
    basic:await collectionSnapshot(db,byName.get(basicName),'basic'),
    details:await collectionSnapshot(db,byName.get(detailName),'details')
  }};
}
export async function createRenamePlan(db) {
  const layout=await inspectRenameLayout(db);
  return {schemaVersion:1,database:db.databaseName,createdAt:new Date().toISOString(),
    sourceState:layout.state,moves:MOVES,collections:layout.collections};
}
export async function verifyRenamePlan(db,plan) {
  if(plan?.schemaVersion!==1||plan.database!==db.databaseName||JSON.stringify(plan.moves)!==JSON.stringify(MOVES))throw new RenameError('migration_plan_database_or_schema_mismatch');
  const layout=await inspectRenameLayout(db);
  for(const role of ['basic','details']) {
    const expected=plan.collections?.[role],actual=layout.collections[role];
    if(!expected||expected.uuid!==actual.uuid||expected.count!==actual.count||expected.indexCount!==actual.indexCount||expected.indexSignature!==actual.indexSignature)throw new RenameError('migration_plan_collection_changed');
  }
  return layout;
}
export async function applyRenamePlan(db,plan,{writersStopped=false,rollback=false,progress=()=>{}}={}) {
  if(!writersStopped)throw new RenameError('writers_must_be_stopped');
  let layout=await verifyRenamePlan(db,plan);
  const initialState=layout.state;
  async function rename(from,to,expectedState,errorCode) {
    try { await db.collection(from).rename(to,{dropTarget:false}); }
    catch { throw new RenameError(errorCode); }
    layout=await verifyRenamePlan(db,plan);
    if(layout.state!==expectedState)throw new RenameError('unexpected_post_rename_layout');
    progress({event:'collection_renamed',from,to,state:layout.state,collections:layout.collections});
  }
  if(rollback) {
    if(layout.state==='current')await rename(BASIC,LEGACY_BASIC,'intermediate','rollback_basic_failed_resume_with_same_plan');
    if(layout.state==='intermediate')await rename(DETAILS,BASIC,'legacy','rollback_details_failed_resume_with_same_plan');
  }else {
    if(layout.state==='legacy')await rename(BASIC,DETAILS,'intermediate','rename_details_failed_resume_with_same_plan');
    if(layout.state==='intermediate')await rename(LEGACY_BASIC,BASIC,'current','rename_basic_failed_resume_with_same_plan');
  }
  return {database:db.databaseName,applied:initialState!==layout.state,rollback,state:layout.state,
    names:layout.names,collections:layout.collections};
}
function argument(args,name) {
  const index=args.indexOf(name);
  if(index<0)return null;
  if(!args[index+1]||args[index+1].startsWith('--'))throw new RenameError('missing_'+name.slice(2)+'_argument');
  return args[index+1];
}
async function main() {
  const args=process.argv.slice(2),allowed=new Set(['--plan','--database','--apply','--writers-stopped','--rollback']);
  for(let index=0;index<args.length;index++) {
    if(!allowed.has(args[index]))throw new RenameError('unknown_argument');
    if(args[index]==='--plan'||args[index]==='--database')index++;
  }
  const filename=argument(args,'--plan'),expectedDatabase=argument(args,'--database');
  const apply=args.includes('--apply'),rollback=args.includes('--rollback'),writersStopped=args.includes('--writers-stopped');
  if(!filename)throw new RenameError('missing_plan_argument');
  if(apply&&(!expectedDatabase||!writersStopped))throw new RenameError('apply_requires_database_and_writers_stopped');
  if(rollback&&!apply)throw new RenameError('rollback_requires_apply');
  const uri=process.env.MONGODB_URI;
  if(!uri||!/^mongodb(?:\+srv)?:\/\//.test(uri))throw new RenameError('missing_mongodb_uri');
  const planFile=path.resolve(filename);
  if(apply&&!fs.existsSync(planFile))throw new RenameError('apply_requires_existing_plan');
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(uri,{maxPoolSize:1,serverSelectionTimeoutMS:15000,appName:'hospital-collection-rename'});
  try {
    await client.connect();
    const db=client.db();
    if(expectedDatabase&&db.databaseName!==expectedDatabase)throw new RenameError('unexpected_database');
    let plan;
    if(fs.existsSync(planFile))plan=JSON.parse(fs.readFileSync(planFile,'utf8'));
    else {
      plan=await createRenamePlan(db);
      fs.mkdirSync(path.dirname(planFile),{recursive:true});
      fs.writeFileSync(planFile,JSON.stringify(plan,null,2),{flag:'wx'});
    }
    if(apply)console.log(JSON.stringify(await applyRenamePlan(db,plan,{writersStopped,rollback,progress:record=>console.log(JSON.stringify(record))})));
    else {
      const layout=await verifyRenamePlan(db,plan);
      console.log(JSON.stringify({database:db.databaseName,applied:false,state:layout.state,before:layout.names,
        after:{basic:BASIC,details:DETAILS},collections:layout.collections,planFile}));
    }
  }finally {await client.close();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error=>{
    console.log(JSON.stringify({event:'collection_rename_failed',reason:error instanceof RenameError?error.code:'database_or_plan_operation_failed',errorType:error.name,
      recovery:'Keep all collections and the saved plan. With writers stopped, rerun the same apply command to resume, or add --rollback to restore the legacy names. Never use dropTarget.'}));
    process.exitCode=1;
  });
}
