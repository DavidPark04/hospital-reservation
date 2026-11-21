import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {root,loadLocalEnv,DEFAULT_MONGODB_URI} from './local-env.mjs';
import {backendJar,createBackendJarCopy,inspectBackend,trackBackend,stopOwnedChildren,resolveJavaExecutable} from './backend-runtime.mjs';

const env=loadLocalEnv();
const children=[];
let stopping=false;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function launch(command,args,cwd=root,ipc=false) {
  const child=spawn(command,args,{cwd,env,stdio:ipc?['inherit','inherit','inherit','ipc']:'inherit',windowsHide:true}); children.push(child);
  child.on('error',()=>{console.error(`Failed to launch ${path.basename(command)}.`); shutdown(1);});
  child.on('exit',code=>{if(!stopping){console.error(`${path.basename(command)} exited (${code}).`);shutdown(1);}});
  return child;
}
async function alive(url) {try{return (await fetch(url,{signal:AbortSignal.timeout(2000)})).ok;}catch{return false;}}
async function waitFor(url,seconds) {
  for(let i=0;i<seconds;i++){if(stopping)throw new Error('Launcher is stopping.');if(await alive(url))return;await pause(1000);}
  throw new Error(`Server did not become ready: ${url}`);
}
async function shutdown(code=0) {
  if(stopping)return;stopping=true;
  // Only direct children created by this launcher are eligible for termination.
  const deadline=setTimeout(()=>process.exit(code||1),3000);
  try {const stopped=await stopOwnedChildren(children);process.exit(stopped?code:1);}
  finally {clearTimeout(deadline);}
}
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>shutdown());

try {
  const javaExecutable=resolveJavaExecutable(env);
  env.JAVA_HOME=path.dirname(path.dirname(javaExecutable));
  if(!fs.existsSync(backendJar))throw new Error('Build the backend first with scripts/build.ps1.');
  if(!fs.existsSync(path.join(root,'frontend/node_modules/vite/bin/vite.js')))throw new Error('Run npm install in frontend first.');
  let backendReady=await alive('http://127.0.0.1:18081/api/health');
  const existing=inspectBackend(env);
  if(existing.status==='mismatch'||existing.status==='unverified'||backendReady&&existing.status!=='matching')
    throw new Error('An existing backend could not be matched to this configuration. Stop the existing backend (port 18081) from its original terminal, then run start.cmd again. No existing server was changed.');
  if(!backendReady&&existing.status==='matching') {
    await waitFor('http://127.0.0.1:18081/api/health',60);backendReady=true;
  }
  if(!backendReady) {
    if(env.MONGODB_URI===DEFAULT_MONGODB_URI) {
      const {MongoClient}=await import('mongodb');
      const probe=new MongoClient(env.MONGODB_URI,{serverSelectionTimeoutMS:1500});let mongoReady=false;
      try{await probe.connect();await probe.db().command({ping:1});mongoReady=true;}catch{}finally{await probe.close();}
      if(!mongoReady) {
        launch(process.execPath,['scripts/mongo.mjs'],root,true);
        for(let i=0;i<180;i++) {
          const client=new MongoClient(env.MONGODB_URI,{serverSelectionTimeoutMS:1000});
          try{await client.connect();await client.db().command({ping:1});mongoReady=true;}catch{}finally{await client.close();}
          if(mongoReady)break;await pause(1000);
        }
        if(!mongoReady)throw new Error('MongoDB could not start. Check the messages above.');
      }
    }
    const runtimeJar=createBackendJarCopy(env);
    let backend;
    try {backend=launch(javaExecutable,['-Dfile.encoding=UTF-8','-jar',runtimeJar.jar]);}
    catch(error) {runtimeJar.cleanup();throw error;}
    trackBackend(backend,env,{runtimeJar,onError:()=>{console.error('Could not record backend process state.');shutdown(1);}});
    await waitFor('http://127.0.0.1:18081/api/health',60);
  }
  if(!await alive('http://127.0.0.1:5174'))launch(process.execPath,['node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5174'],path.join(root,'frontend'));
  await waitFor('http://127.0.0.1:5174',30);
  console.log('\nHospital reservation is ready: http://127.0.0.1:5174');
  console.log('Swagger: http://127.0.0.1:18081/swagger-ui.html');
  console.log('Press Ctrl+C to stop servers started by this launcher.');
  setInterval(()=>{},60_000);
}catch(error){console.error(error.message);await shutdown(1);}
