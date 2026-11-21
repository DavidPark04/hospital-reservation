import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {root,loadLocalEnv} from './local-env.mjs';
import {backendJar,createBackendJarCopy,inspectBackend,trackBackend,stopOwnedChildren,resolveJavaExecutable} from './backend-runtime.mjs';

const env=loadLocalEnv();
let javaExecutable;
try {javaExecutable=resolveJavaExecutable(env);env.JAVA_HOME=path.dirname(path.dirname(javaExecutable));}
catch(error) {console.error(error.message);process.exit(1);}
const existing=inspectBackend(env);
let healthy=false;
try {healthy=(await fetch('http://127.0.0.1:18081/api/health',{signal:AbortSignal.timeout(2000)})).ok;} catch {}
if(!healthy&&existing.status==='matching') {
  for(let i=0;i<60;i++) {
    if(inspectBackend(env).status!=='matching')break;
    try {healthy=(await fetch('http://127.0.0.1:18081/api/health',{signal:AbortSignal.timeout(2000)})).ok;} catch {}
    if(healthy)break;
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
}
if(healthy&&existing.status==='matching') {
  console.log('Backend is already running with the same configuration.');process.exit(0);
}
if(healthy||['matching','mismatch','unverified'].includes(existing.status)) {
  console.error('An existing backend must be stopped from its original terminal before starting this configuration. No existing server was changed.');process.exit(1);
}
if(!fs.existsSync(backendJar)) {console.error('Build the backend first with scripts/build.ps1.');process.exit(1);}
let stopping=false;
let child,runtimeJar;
try {
  runtimeJar=createBackendJarCopy(env);
  child=spawn(javaExecutable,['-Dfile.encoding=UTF-8','-jar',runtimeJar.jar],{cwd:root,env,stdio:'inherit',windowsHide:true});
} catch(error) {runtimeJar?.cleanup();console.error(error.message);process.exit(1);}
async function shutdown(code=0) {
  if(stopping)return;stopping=true;
  const deadline=setTimeout(()=>process.exit(code||1),3000);
  try {const stopped=await stopOwnedChildren([child]);process.exit(stopped?code:1);}
  finally {clearTimeout(deadline);}
}
trackBackend(child,env,{runtimeJar,onError:()=>{console.error('Could not record backend process state.');shutdown(1);}});
child.on('error',()=>{console.error('Could not start the backend process.');shutdown(1);});
child.on('exit',code=>{if(!stopping)process.exit(code??1);});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>shutdown());
