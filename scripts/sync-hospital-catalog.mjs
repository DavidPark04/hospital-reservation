import fs from 'node:fs';
import readline from 'node:readline';
import crypto from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const DEPARTMENTS = Object.freeze({'01':'내과','02':'신경과','03':'정신건강의학과','04':'외과',
  '05':'정형외과','06':'신경외과','10':'산부인과','11':'소아청소년과','12':'안과','13':'이비인후과',
  '14':'피부과','15':'비뇨의학과','21':'재활의학과','23':'가정의학과','49':'치과','80':'한방내과'});
export const REGION_CODES = Object.freeze(['110000','210000','220000','230000','240000','250000','260000',
  '310000','320000','330000','340000','350000','360000','370000','380000','390000','410000']);
export const CATALOG_WEEKDAY_REGIONS = Object.freeze({
  monday:Object.freeze(['110000']),tuesday:Object.freeze(['310000']),
  wednesday:Object.freeze(['210000','360000','380000']),thursday:Object.freeze(['220000','230000','370000']),
  friday:Object.freeze(['250000','340000','350000']),saturday:Object.freeze(['240000','260000','320000','330000','390000','410000']),
});
const DAY_MS=24*3600_000,KST_OFFSET_MS=9*3600_000;
const SCOPES = Object.freeze(['basic',...Object.keys(DEPARTMENTS)]);
const SOURCE = 'https://www.data.go.kr/data/15001698/openapi.do';
const COLLECTION = 'hospitals';

export class SyncError extends Error {
  constructor(code) { super(code); this.name='SyncError'; this.code=code; }
}
export function catalogWeekStartTimestamp(weekStartKst) {
  if(typeof weekStartKst!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(weekStartKst))throw new SyncError('invalid_catalog_week');
  const timestamp=Date.parse(`${weekStartKst}T00:00:00+09:00`),local=new Date(timestamp+KST_OFFSET_MS);
  if(!Number.isFinite(timestamp)||local.toISOString().slice(0,10)!==weekStartKst||local.getUTCDay()!==1)throw new SyncError('invalid_catalog_week');
  return timestamp;
}
export function catalogWeekForTime(now=new Date()) {
  if(!(now instanceof Date)||!Number.isFinite(now.getTime()))throw new SyncError('invalid_catalog_time');
  const local=new Date(now.getTime()+KST_OFFSET_MS);
  const monday=Date.UTC(local.getUTCFullYear(),local.getUTCMonth(),local.getUTCDate())-((local.getUTCDay()+6)%7)*DAY_MS;
  return new Date(monday).toISOString().slice(0,10);
}
export function hospitalId(externalId) {
  const bytes=crypto.createHash('md5').update('HIRA:'+externalId,'utf8').digest();
  bytes[6]=(bytes[6]&15)|48; bytes[8]=(bytes[8]&63)|128;
  const value=bytes.toString('hex');
  return `${value.slice(0,8)}-${value.slice(8,12)}-${value.slice(12,16)}-${value.slice(16,20)}-${value.slice(20)}`;
}
export function canonical(raw) {
  return JSON.stringify(Object.fromEntries(Object.keys(raw).sort().map(key=>[key,raw[key]])));
}
function sha(value) { return crypto.createHash('sha256').update(value).digest('hex'); }
function coordinate(value,bound) {
  if(typeof value!=='string'||!value.trim())return null;
  const number=Number(value);
  return Number.isFinite(number)&&Math.abs(number)<=bound?number:null;
}
export function normalizeRow(row) {
  const raw=row.apiFields;
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||typeof raw.ykiho!=='string'||!raw.ykiho||raw.ykiho.trim()!==raw.ykiho||!raw.yadmNm?.trim())throw new SyncError('invalid_hospital_identity');
  for(const [key,value] of Object.entries(raw))if(typeof value!=='string'||/[.$]/.test(key))throw new SyncError('invalid_raw_field');
  if(!Array.isArray(row.departmentCodes)||row.departmentCodes.some(code=>!Object.hasOwn(DEPARTMENTS,code)))throw new SyncError('invalid_department_membership');
  const departmentCodes=[...new Set(row.departmentCodes)].sort();
  const fetchedAt=new Date(row.fetchedAt);
  if(!Number.isFinite(fetchedAt.getTime()))throw new SyncError('invalid_fetched_time');
  const latitude=coordinate(raw.YPos,90),longitude=coordinate(raw.XPos,180);
  const location=latitude!==null&&longitude!==null?{latitude,longitude}:null;
  const basicSourceHash=sha(canonical(raw));
  return {_id:hospitalId(raw.ykiho),provider:'HIRA',externalId:raw.ykiho,name:raw.yadmNm,
    address:raw.addr||'',tel:raw.telno||'',source:'LIVE',sourceUrl:SOURCE,active:true,
    location,...(location||{}),apiFields:raw,departmentCodes,
    departments:departmentCodes.map(code=>({code,name:DEPARTMENTS[code]})),
    basicSourceHash,catalogHash:sha(JSON.stringify({basicSourceHash,departmentCodes})),
    basicFetchedAt:fetchedAt,fetchedAt};
}
export function updateFor(previous,document,now) {
  if(previous?.catalogHash===document.catalogHash&&previous.active!==false)return null;
  const {_id,...values}=document;
  const update={$set:{...values,lastChangedAt:now},$setOnInsert:{firstImportedAt:now},$unset:{retiredAt:''}};
  if(previous?.basicSourceHash===document.basicSourceHash) {
    // A classification-only change does not rewrite unchanged basic fields or their observed time.
    for(const key of ['provider','externalId','name','address','tel','source','sourceUrl','location','latitude','longitude','apiFields','basicSourceHash','basicFetchedAt','fetchedAt'])delete update.$set[key];
  }
  if(!document.location&&previous?.basicSourceHash!==document.basicSourceHash)Object.assign(update.$unset,{latitude:'',longitude:''});
  return {updateOne:{filter:{_id},update,upsert:true}};
}
export function assertReasonableRemoval(existingActive,missing) {
  if(missing>Math.max(100,Math.floor(existingActive*.05)))throw new SyncError('unexpected_large_catalog_reduction');
}
export function assertReasonableRegionalRemoval(current,documents,regions=REGION_CODES) {
  const counts=Object.fromEntries(regions.map(region=>[region,{active:0,missing:0}]));
  for(const previous of current) {
    const region=previous.apiFields?.sidoCd;
    if(previous.active===false||!Object.hasOwn(counts,region))continue;
    counts[region].active++;
    if(!documents.has(previous._id))counts[region].missing++;
  }
  for(const {active,missing} of Object.values(counts)) {
    if(missing>0&&(missing===active||missing>Math.max(5,Math.floor(active*.05))))throw new SyncError('unexpected_large_regional_reduction');
  }
}
async function* readRows(filename) {
  const input=readline.createInterface({input:fs.createReadStream(filename,{encoding:'utf8'}),crlfDelay:Infinity});
  for await(const line of input)if(line.trim())yield JSON.parse(line);
}
async function fileHash(filename) {
  const hash=crypto.createHash('sha256');
  for await(const data of fs.createReadStream(filename))hash.update(data);
  return hash.digest('hex');
}
function hasExactKeys(value,keys) {
  return value!==null&&typeof value==='object'&&!Array.isArray(value)&&
    Object.keys(value).length===keys.length&&keys.every(key=>Object.hasOwn(value,key));
}
function snapshotRegions(manifest) {
  if(![3,4].includes(manifest.schemaVersion))return REGION_CODES;
  const regions=manifest.regions;
  if(!Array.isArray(regions)||!regions.length||regions.some(region=>!REGION_CODES.includes(region))||
    JSON.stringify(regions)!==JSON.stringify(REGION_CODES.filter(region=>regions.includes(region))))throw new SyncError('invalid_selected_regions');
  return regions;
}
function validateRecovery(manifest,now,regions) {
  const recovery=manifest.recovery;
  if(!hasExactKeys(recovery,['mode','weekStartKst','originalDay','attempt','recoveredAt'])||
    recovery.mode!=='sunday-failed-requests'||recovery.attempt!==1||
    !Object.hasOwn(CATALOG_WEEKDAY_REGIONS,recovery.originalDay))throw new SyncError('invalid_catalog_recovery');
  const allowed=CATALOG_WEEKDAY_REGIONS[recovery.originalDay];
  if(regions.some(region=>!allowed.includes(region)))throw new SyncError('invalid_catalog_recovery_regions');
  const weekStart=catalogWeekStartTimestamp(recovery.weekStartKst),sunday=weekStart+6*DAY_MS,end=weekStart+7*DAY_MS;
  const timestamps=[manifest.firstFetchedAt,manifest.lastFetchedAt,manifest.completedAt,recovery.recoveredAt];
  if(timestamps.some(value=>typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)))throw new SyncError('invalid_catalog_recovery_time');
  const [first,last,completed,recovered]=timestamps.map(value=>Date.parse(value));
  if(![first,last,completed,recovered,now.getTime()].every(Number.isFinite)||first<weekStart||last>=end||
    first>last||last>completed||completed<sunday||completed>=end||recovered!==completed||
    now-first>8*DAY_MS||now-completed>2*DAY_MS||completed-now>300_000)throw new SyncError('invalid_catalog_recovery_time');
}
function validateRegionalScopes(manifest,regions) {
  if(!hasExactKeys(manifest.regionScopes,regions))throw new SyncError('incomplete_regional_scopes');
  const totals=Object.fromEntries(SCOPES.map(code=>[code,0]));
  const bounds=Object.fromEntries(SCOPES.map(code=>[code,{first:Infinity,last:-Infinity}]));
  for(const region of regions) {
    const entry=manifest.regionScopes[region];
    if(!entry||!hasExactKeys(entry.scopeCounts,SCOPES)||!hasExactKeys(entry.scopeTimes,SCOPES))throw new SyncError('incomplete_regional_scopes');
    for(const code of SCOPES) {
      const count=entry.scopeCounts[code];
      if(!Number.isSafeInteger(count)||count<0||count>entry.scopeCounts.basic)throw new SyncError('invalid_regional_scope_count');
      totals[code]+=count;
      const times=entry.scopeTimes[code],start=new Date(times?.firstFetchedAt),end=new Date(times?.lastFetchedAt);
      if(!Number.isFinite(start.getTime())||!Number.isFinite(end.getTime())||start>end)throw new SyncError('invalid_regional_scope_times');
      bounds[code].first=Math.min(bounds[code].first,start.getTime());
      bounds[code].last=Math.max(bounds[code].last,end.getTime());
    }
  }
  for(const code of SCOPES) {
    if(totals[code]!==manifest.scopeCounts[code])throw new SyncError('regional_scope_count_mismatch');
    if(bounds[code].first!==new Date(manifest.scopeTimes[code].firstFetchedAt).getTime()||
       bounds[code].last!==new Date(manifest.scopeTimes[code].lastFetchedAt).getTime())throw new SyncError('regional_scope_time_bounds_mismatch');
  }
}
export async function validateSnapshot(directory,now=new Date()) {
  const manifest=JSON.parse(fs.readFileSync(path.join(directory,'catalog-manifest.json'),'utf8'));
  const filename=path.join(directory,'catalog.jsonl');
  const regions=snapshotRegions(manifest),scoped=[3,4].includes(manifest.schemaVersion);
  const scope=scoped?'regions-with-department-memberships':'nationwide-with-department-memberships';
  if(![1,2,3,4].includes(manifest.schemaVersion)||manifest.complete!==true||manifest.source!=='hospInfoServicev2/getHospBasisList'||manifest.scope!==scope||!Number.isSafeInteger(manifest.rows)||manifest.rows<(scoped&&regions.length<REGION_CODES.length?0:1))throw new SyncError('incomplete_catalog_snapshot');
  if(manifest.schemaVersion!==4&&Object.hasOwn(manifest,'recovery'))throw new SyncError('invalid_catalog_recovery');
  if(!scoped&&Object.hasOwn(manifest,'regions'))throw new SyncError('incomplete_catalog_snapshot');
  if(manifest.schemaVersion===1&&Object.hasOwn(manifest,'regionScopes'))throw new SyncError('incomplete_catalog_snapshot');
  if(canonical(manifest.departments||{})!==canonical(DEPARTMENTS)||manifest.scopeCounts?.basic!==manifest.rows||
    !hasExactKeys(manifest.scopeCounts,SCOPES)||!hasExactKeys(manifest.scopeTimes,SCOPES))throw new SyncError('incomplete_department_scopes');
  for(const code of Object.keys(DEPARTMENTS))if(!Number.isInteger(manifest.scopeCounts[code])||manifest.scopeCounts[code]<0)throw new SyncError('incomplete_department_scopes');
  const first=new Date(manifest.firstFetchedAt),last=new Date(manifest.lastFetchedAt),completed=new Date(manifest.completedAt);
  if(![first,last,completed].every(value=>Number.isFinite(value.getTime()))||
    (manifest.schemaVersion!==4&&now-first>48*3600_000)||first>last||last>completed||completed-now>300_000)throw new SyncError('stale_or_invalid_snapshot_time');
  if(manifest.schemaVersion===4)validateRecovery(manifest,now,regions);
  const scopeDates=[];
  for(const code of SCOPES) {
    const times=manifest.scopeTimes?.[code],start=new Date(times?.firstFetchedAt),end=new Date(times?.lastFetchedAt);
    if(!Number.isFinite(start.getTime())||!Number.isFinite(end.getTime())||start>end||start<first||end>last)throw new SyncError('invalid_scope_times');
    scopeDates.push(start.getTime(),end.getTime());
  }
  if(Math.min(...scopeDates)!==first.getTime()||Math.max(...scopeDates)!==last.getTime())throw new SyncError('scope_time_bounds_mismatch');
  const regional=manifest.schemaVersion===2||scoped;
  if(regional)validateRegionalScopes(manifest,regions);
  const checksum=await fileHash(filename);
  if(checksum!==manifest.sha256)throw new SyncError('snapshot_checksum_mismatch');
  const documents=new Map();
  const departmentCounts=Object.fromEntries(Object.keys(DEPARTMENTS).map(code=>[code,0]));
  const regionCounts=Object.fromEntries(regions.map(region=>[region,Object.fromEntries(SCOPES.map(code=>[code,0]))]));
  for await(const row of readRows(filename)) {
    const doc=normalizeRow(row);
    if(documents.has(doc._id))throw new SyncError('duplicate_catalog_identity');
    if(doc.basicFetchedAt<new Date(manifest.scopeTimes.basic.firstFetchedAt)||doc.basicFetchedAt>new Date(manifest.scopeTimes.basic.lastFetchedAt))throw new SyncError('row_time_outside_collection');
    if(regional) {
      const region=doc.apiFields.sidoCd;
      if(!Object.hasOwn(regionCounts,region))throw new SyncError('invalid_hospital_region');
      const times=manifest.regionScopes[region].scopeTimes.basic;
      if(doc.basicFetchedAt<new Date(times.firstFetchedAt)||doc.basicFetchedAt>new Date(times.lastFetchedAt))throw new SyncError('row_time_outside_regional_collection');
      regionCounts[region].basic++;
      for(const code of doc.departmentCodes)regionCounts[region][code]++;
    }
    for(const code of doc.departmentCodes)departmentCounts[code]++;
    documents.set(doc._id,doc);
  }
  if(documents.size!==manifest.rows)throw new SyncError('catalog_count_mismatch');
  for(const code of Object.keys(DEPARTMENTS))if(departmentCounts[code]!==manifest.scopeCounts[code])throw new SyncError('department_count_mismatch');
  if(regional)for(const region of regions)for(const code of SCOPES) {
    if(regionCounts[region][code]!==manifest.regionScopes[region].scopeCounts[code])throw new SyncError('regional_catalog_count_mismatch');
  }
  return {manifest,documents,filename,checksum};
}
function emit(event,fields={}) { console.log(JSON.stringify({event,...fields})); }

function regionalGeneration(manifest,region) {
  const times=manifest.regionScopes?.[region]?.scopeTimes;
  if(!times)return {firstFetchedAt:manifest.firstFetchedAt,lastFetchedAt:manifest.lastFetchedAt,scopeTimes:manifest.scopeTimes};
  const starts=Object.values(times).map(value=>new Date(value.firstFetchedAt).getTime());
  const ends=Object.values(times).map(value=>new Date(value.lastFetchedAt).getTime());
  return {firstFetchedAt:new Date(Math.min(...starts)),lastFetchedAt:new Date(Math.max(...ends)),scopeTimes:times};
}
function assertGenerationOrder(incoming,previous,reason='snapshot_older_than_started_generation') {
  if(!previous)return;
  if(previous.lastFetchedAt&&new Date(incoming.lastFetchedAt)<new Date(previous.lastFetchedAt))throw new SyncError(reason);
  for(const [code,times] of Object.entries(previous.scopeTimes||{})) {
    const next=incoming.scopeTimes?.[code];
    if(!next||new Date(next.firstFetchedAt)<new Date(times.firstFetchedAt)||new Date(next.lastFetchedAt)<new Date(times.lastFetchedAt))throw new SyncError(reason);
  }
}
const regionMarkerId=(kind,region)=>`hospital-basic-region-${kind}:${region}`;

export async function applySnapshot(db,snapshot,{apply=false,now=new Date(),progress=emit,heartbeatMs=30_000}={}) {
  const {manifest,documents}=snapshot;
  const regions=snapshotRegions(manifest),scoped=[3,4].includes(manifest.schemaVersion);
  if(manifest.schemaVersion===4)validateRecovery(manifest,now,regions);
  else if(Object.hasOwn(manifest,'recovery'))throw new SyncError('invalid_catalog_recovery');
  const selected=new Set(regions),full=regions.length===REGION_CODES.length;
  if(scoped) {
    if(manifest.scope!=='regions-with-department-memberships'||!Number.isSafeInteger(manifest.rows)||manifest.rows<0||
      (full&&manifest.rows===0)||manifest.rows!==documents.size)throw new SyncError('incomplete_catalog_snapshot');
    validateRegionalScopes(manifest,regions);
    for(const doc of documents.values())if(!selected.has(doc.apiFields?.sidoCd))throw new SyncError('invalid_hospital_region');
  }
  if(await db.listCollections({name:'hospitalBasicCatalog'},{nameOnly:true}).hasNext())throw new SyncError('hospital_collection_migration_required');
  const catalog=db.collection(COLLECTION),runs=db.collection('hospitalImportRuns');
  const runId=crypto.randomUUID(),owner=crypto.randomUUID(),lockId='hospital-basic-sync-lock';
  let locked=false,started=false,heartbeatTimer=null,heartbeat=Promise.resolve(),lockFailure=null;
  async function renewLock() {
    if(lockFailure)throw lockFailure;
    const result=await runs.updateOne({_id:lockId,owner,expiresAt:{$gt:new Date()}},{$set:{expiresAt:new Date(Date.now()+15*60_000)}});
    if(result.matchedCount!==1)throw new SyncError('catalog_sync_lock_lost');
  }
  try {
    if(apply) {
      try {
        await runs.findOneAndUpdate({_id:lockId,expiresAt:{$lt:new Date()}},{$set:{owner,expiresAt:new Date(Date.now()+15*60_000)}},{upsert:true});
        locked=true;
      }catch(error){if(error.code===11000)throw new SyncError('catalog_sync_already_running');throw error;}
      heartbeatTimer=setInterval(()=>{
        heartbeat=heartbeat.then(()=>renewLock()).catch(error=>{lockFailure=error;});
      },heartbeatMs);
    }
    const latest=await runs.findOne({_id:'hospital-basic-current'});
    const highWater=await runs.findOne({_id:'hospital-basic-high-water'});
    const regionMarkers=new Map();
    for await(const marker of runs.find({_id:{$in:REGION_CODES.flatMap(region=>['current','high-water'].map(kind=>regionMarkerId(kind,region)))}}))regionMarkers.set(marker._id,marker);
    function checkRegion(incoming,region) {
      assertGenerationOrder(incoming,latest&&regionalGeneration(latest,region),'snapshot_older_than_published_catalog');
      assertGenerationOrder(incoming,highWater&&regionalGeneration(highWater,region));
      assertGenerationOrder(incoming,regionMarkers.get(regionMarkerId('current',region)),'snapshot_older_than_published_catalog');
      assertGenerationOrder(incoming,regionMarkers.get(regionMarkerId('high-water',region)));
    }
    for(const region of regions)checkRegion(regionalGeneration(manifest,region),region);
    const current=new Map();
    for await(const row of catalog.find({},{projection:{_id:1,provider:1,active:1,basicSourceHash:1,catalogHash:1,'apiFields.sidoCd':1}})) {
      if(row.provider!=='HIRA'||typeof row.basicSourceHash!=='string'||typeof row.catalogHash!=='string')throw new SyncError('hospital_collection_migration_required');
      current.set(row._id,row);
    }
    // A hospital can move between regions. Reject a stale incoming move before
    // it can overwrite a newer observation made in its current region.
    for(const doc of documents.values()) {
      const previousRegion=current.get(doc._id)?.apiFields?.sidoCd;
      if(previousRegion&&previousRegion!==doc.apiFields?.sidoCd&&REGION_CODES.includes(previousRegion)) {
        checkRegion(regionalGeneration(manifest,doc.apiFields.sidoCd),previousRegion);
      }
    }
    const selectedCurrent=[...current.values()].filter(row=>!scoped||selected.has(row.apiFields?.sidoCd));
    if(manifest.schemaVersion===2||scoped)assertReasonableRegionalRemoval(selectedCurrent,documents,regions);
    const missing=selectedCurrent.filter(row=>row.active!==false&&!documents.has(row._id)).map(row=>row._id);
    assertReasonableRemoval(selectedCurrent.filter(row=>row.active!==false).length,missing.length);
    if(apply) {
      await renewLock();
      // This marker survives failed/partial applies, so an older snapshot cannot
      // roll back records already written by a newer generation.
      for(const region of regions) {
        const generation=regionalGeneration(manifest,region);
        await runs.updateOne({_id:regionMarkerId('high-water',region)},{$set:{provider:'HIRA',kind:'BASIC',region,runId,startedAt:now,
          firstFetchedAt:new Date(generation.firstFetchedAt),lastFetchedAt:new Date(generation.lastFetchedAt),
          scopeTimes:generation.scopeTimes,sourceHash:manifest.sha256}},{upsert:true});
      }
      if(full)await runs.updateOne({_id:'hospital-basic-high-water'},{$set:{provider:'HIRA',kind:'BASIC',runId,startedAt:now,
        firstFetchedAt:new Date(manifest.firstFetchedAt),lastFetchedAt:new Date(manifest.lastFetchedAt),regions,
        scopeTimes:manifest.scopeTimes,regionScopes:manifest.regionScopes||{},sourceHash:manifest.sha256}},{upsert:true});
    }
    const totals={rows:documents.size,inserted:0,updated:0,reactivated:0,unchanged:0,retired:missing.length};
    const {BSON}=await import('mongodb');
    let approximateDocumentBytes=0;
    for(const doc of documents.values()) {
      const previous=current.get(doc._id);
      if(!previous)totals.inserted++;
      else if(previous.active===false)totals.reactivated++;
      else if(previous.catalogHash!==doc.catalogHash)totals.updated++;
      else totals.unchanged++;
      approximateDocumentBytes+=BSON.calculateObjectSize({...doc,lastChangedAt:now,firstImportedAt:now});
    }
    const stats=await db.stats();
    const storage={approximateDocumentBytes,currentDatabaseDataBytes:stats.dataSize,currentDatabaseIndexBytes:stats.indexSize};
    progress('sync_plan',{...totals,...storage,regions});
    if(!apply)return {...totals,...storage,regions,applied:false};
    if(snapshot.filename&&await fileHash(snapshot.filename)!==snapshot.checksum)throw new SyncError('snapshot_changed_after_validation');
    await renewLock();
    await catalog.createIndex({provider:1,externalId:1},{unique:true,name:'catalog_provider_external_v1'});
    await renewLock();
    await catalog.createIndex({active:1,name:1,_id:1},{name:'catalog_active_name_v1'});
    await renewLock();
    await catalog.createIndex({'apiFields.postNo':1,active:1},{name:'catalog_postcode_active_v1'});
    await renewLock();
    await catalog.createIndex({departmentCodes:1,active:1,name:1,_id:1},{name:'catalog_department_name_v1'});
    await renewLock();
    await runs.insertOne({_id:runId,provider:'HIRA',kind:'BASIC',status:'IMPORTING',startedAt:now,
      expectedRows:manifest.rows,sourceHash:manifest.sha256,scopeCounts:manifest.scopeCounts,regions});
    started=true;
    let batch=[],processed=0;
    async function flush() {
      if(!batch.length)return;
      await renewLock();
      await catalog.bulkWrite(batch,{ordered:false});
      batch=[];
    }
    for(const doc of documents.values()) {
      const operation=updateFor(current.get(doc._id),doc,now);
      if(operation)batch.push(operation);
      processed++;
      if(batch.length===500)await flush();
      if(processed%5000===0)progress('sync_progress',{processed,total:documents.size});
    }
    await flush();
    // Deactivate only after every valid new/current hospital has been saved.
    for(let start=0;start<missing.length;start+=500) {
      await renewLock();
      await catalog.updateMany({_id:{$in:missing.slice(start,start+500)},provider:'HIRA',active:{$ne:false}},{$set:{active:false,retiredAt:now,lastChangedAt:now}});
    }
    let verified=0;
    for await(const row of catalog.find({provider:'HIRA',active:{$ne:false},...(scoped?{'apiFields.sidoCd':{$in:regions}}:{})})) {
      const recomputed=normalizeRow({apiFields:row.apiFields,departmentCodes:row.departmentCodes,fetchedAt:row.basicFetchedAt});
      if(recomputed._id!==row._id||recomputed.basicSourceHash!==row.basicSourceHash||recomputed.catalogHash!==row.catalogHash||documents.get(row._id)?.catalogHash!==recomputed.catalogHash||recomputed.name!==row.name||recomputed.address!==row.address||recomputed.tel!==row.tel||JSON.stringify(recomputed.location)!==JSON.stringify(row.location))throw new SyncError('database_catalog_verification_failed');
      verified++;
      if(verified%5000===0)await renewLock();
    }
    if(verified!==documents.size)throw new SyncError('database_catalog_count_mismatch');
    const completedAt=new Date();
    const result={...totals,...storage,regions,applied:true,verified,runId,completedAt:completedAt.toISOString()};
    await renewLock();
    await runs.updateOne({_id:runId},{$set:{status:'COMPLETE',completedAt,...totals,verified}});
    await renewLock();
    for(const region of regions) {
      const generation=regionalGeneration(manifest,region);
      await runs.updateOne({_id:regionMarkerId('current',region)},{$set:{provider:'HIRA',kind:'BASIC',region,status:'COMPLETE',runId,completedAt,
        firstFetchedAt:new Date(generation.firstFetchedAt),lastFetchedAt:new Date(generation.lastFetchedAt),
        scopeTimes:generation.scopeTimes,sourceHash:manifest.sha256,
        expectedRows:manifest.regionScopes?.[region]?.scopeCounts?.basic??null}},{upsert:true});
    }
    const summary={provider:'HIRA',kind:'BASIC',status:'COMPLETE',runId,regions,
      completedAt,firstFetchedAt:new Date(manifest.firstFetchedAt),lastFetchedAt:new Date(manifest.lastFetchedAt),
      expectedRows:manifest.rows,sourceHash:manifest.sha256,scopeCounts:manifest.scopeCounts,scopeTimes:manifest.scopeTimes,
      regionScopes:manifest.regionScopes||{},...totals};
    if(full)await runs.updateOne({_id:'hospital-basic-current'},{$set:summary},{upsert:true});
    await renewLock();
    await runs.updateOne({_id:'hospital-basic-last-run'},{$set:summary},{upsert:true});
    progress('sync_complete',result);
    return result;
  }catch(error){
    if(started)await runs.updateOne({_id:runId},{$set:{status:'FAILED',failedAt:new Date(),errorCode:error instanceof SyncError?error.code:'database_operation_failed'}}).catch(()=>{});
    throw error;
  }finally{
    if(heartbeatTimer)clearInterval(heartbeatTimer);
    await heartbeat;
    if(locked)await runs.deleteOne({_id:lockId,owner}).catch(()=>{});
  }
}

async function main() {
  const args=process.argv.slice(2),directoryIndex=args.indexOf('--directory');
  if(directoryIndex<0||!args[directoryIndex+1])throw new SyncError('missing_snapshot_directory');
  const directory=path.resolve(args[directoryIndex+1]);
  const snapshot=await validateSnapshot(directory);
  const uri=process.env.MONGODB_URI;
  if(!uri||!/^mongodb(?:\+srv)?:\/\//.test(uri))throw new SyncError('missing_mongodb_uri');
  const {MongoClient}=await import('mongodb');
  const client=new MongoClient(uri,{maxPoolSize:3,serverSelectionTimeoutMS:15000,connectTimeoutMS:15000,appName:'hospital-basic-catalog-sync'});
  try {
    await client.connect();
    const result=await applySnapshot(client.db(),snapshot,{apply:args.includes('--apply')});
    fs.writeFileSync(path.join(directory,'sync-result.json'),JSON.stringify(result,null,2));
  }finally{await client.close();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error=>{
    emit('sync_failed',{reason:error instanceof SyncError?error.code:'unexpected_failure',errorType:error.name});
    process.exitCode=1;
  });
}
