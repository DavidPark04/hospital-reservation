import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {root} from './local-env.mjs';

export const backendJar=path.join(root,'backend/build/libs/hospital-reservation.jar');
const stateFile=path.join(root,'.runtime/backend-process.json');
const configKeys=['MONGODB_URI','SPRING_DATA_MONGODB_URI','SPRING_DATA_MONGODB_DATABASE','JWT_SECRET','OPENAI_API_KEY','OPENAI_MODEL','HIRA_HOSPITAL_KEY','HIRA_DETAIL_KEY','DEMO_ENABLED','SERVER_ADDRESS','SERVER_PORT','SPRING_PROFILES_ACTIVE','JAVA_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','GOOGLE_CLIENT_ID','GOOGLE_CLIENT_SECRET','KAKAO_CLIENT_ID','KAKAO_CLIENT_SECRET','APP_FRONTEND_URL','APP_BACKEND_URL'];

function executableFromHome(home) {
  if(typeof home!=='string'||!home.trim())return null;
  const candidate=path.join(home.trim().replace(/^"(.*)"$/,'$1'),'bin',process.platform==='win32'?'java.exe':'java');
  try {
    const executable=fs.realpathSync(candidate);
    if(!fs.statSync(executable).isFile())return null;
    fs.accessSync(executable,fs.constants.X_OK);
    return executable;
  } catch {return null;}
}
export function resolveJavaExecutable(env) {
  const configured=executableFromHome(env.JAVA_HOME);
  if(configured)return configured;
  // On Windows, PATH may point to Oracle's forwarding launcher. Resolve java.home
  // so the child PID belongs to the JVM itself, not a launcher that leaves it running.
  const result=spawnSync('java',['-XshowSettings:properties','-version'],{
    env,encoding:'utf8',stdio:['ignore','pipe','pipe'],timeout:10000,maxBuffer:1024*1024,windowsHide:true
  });
  if(!result.error&&result.status===0) {
    const home=String(result.stderr??'').match(/^\s*java\.home\s*=\s*(.+)$/m)?.[1];
    const executable=executableFromHome(home);
    if(executable)return executable;
  }
  // Do not expose diagnostic output: JAVA_TOOL_OPTIONS may contain sensitive values.
  throw new Error('Could not locate the actual Java executable. Set JAVA_HOME to a valid JDK 21 installation and try again.');
}

export function configurationFingerprint(env) {
  let artifact=null;
  try {const stat=fs.statSync(backendJar);artifact=[stat.size,stat.mtimeMs];} catch {}
  return crypto.createHash('sha256').update(JSON.stringify([artifact,configKeys.map(key=>[key,env[key]??''])])).digest('hex');
}
export function readBackendState(file=stateFile) {
  try {
    const value=JSON.parse(fs.readFileSync(file,'utf8'));
    return value.version===1 && Number.isInteger(value.pid) && value.pid>0 && /^[a-f0-9]{64}$/.test(value.fingerprint) ? value : null;
  } catch {return null;}
}
function processAlive(pid) {
  try {process.kill(pid,0);return true;} catch(error) {return error.code==='ESRCH' ? false : undefined;}
}
export function inspectBackend(env,{file=stateFile,isAlive=processAlive}={}) {
  const state=readBackendState(file);
  if(!state)return {status:'missing'};
  const alive=isAlive(state.pid);
  if(alive===false)return {status:'stopped',pid:state.pid};
  if(alive!==true)return {status:'unverified',pid:state.pid};
  return {status:state.fingerprint===configurationFingerprint(env)?'matching':'mismatch',pid:state.pid};
}
export function trackBackend(child,env,{file=stateFile,onError=()=>{}}={}) {
  child.once('spawn',()=>{
    try {
      fs.mkdirSync(path.dirname(file),{recursive:true});
      fs.writeFileSync(file,JSON.stringify({version:1,pid:child.pid,fingerprint:configurationFingerprint(env),startedAt:new Date().toISOString()}),{mode:0o600});
    } catch {onError();}
  });
  child.once('exit',()=>{
    // An older process must never remove a newer process's metadata.
    try {if(readBackendState(file)?.pid===child.pid)fs.unlinkSync(file);} catch {}
  });
}
function exited(child) {return child.exitCode!==null && child.exitCode!==undefined || child.signalCode!==null && child.signalCode!==undefined;}
async function waitForChildren(children,ms) {
  let timer;
  const listeners=[];
  try {
    await Promise.race([
      Promise.all(children.map(child=>exited(child)?Promise.resolve():new Promise(resolve=>{listeners.push([child,resolve]);child.once('exit',resolve); }))),
      new Promise(resolve=>{timer=setTimeout(resolve,ms);})
    ]);
  } finally {clearTimeout(timer);for(const [child,listener] of listeners)child.off('exit',listener);}
}
export async function stopOwnedChildren(children,{graceMs=1500,forceMs=500,onWarning=console.error}={}) {
  const owned=[...children].reverse();
  for(const child of owned) {
    if(exited(child))continue;
    try {
      if(child.connected)child.send({type:'shutdown'},()=>{});
      else child.kill('SIGTERM');
    } catch { /* Retry below without allowing a permission error to hang shutdown. */ }
  }
  await waitForChildren(owned,graceMs);
  for(const child of owned) {
    if(exited(child))continue;
    try {child.kill('SIGKILL');} catch {}
  }
  await waitForChildren(owned,forceMs);
  const remaining=owned.filter(child=>!exited(child) && child.pid);
  for(const child of remaining)onWarning(`Could not stop owned child PID ${child.pid}. Stop it from the terminal that started it before restarting.`);
  return remaining.length===0;
}
