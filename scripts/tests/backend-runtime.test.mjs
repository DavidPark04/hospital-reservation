import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test} from 'node:test';
import {configurationFingerprint,createBackendJarCopy,readBackendState,trackBackend} from '../backend-runtime.mjs';
import {loadLocalEnv,readLocalConfig} from '../local-env.mjs';

function fixture(t) {
  const temporaryRoot=fs.realpathSync(os.tmpdir());
  const directory=fs.mkdtempSync(path.join(temporaryRoot,'hospital-runtime-test-'));
  t.after(()=>{
    const resolved=fs.realpathSync(directory);
    assert.equal(path.dirname(resolved),temporaryRoot);
    assert.ok(path.basename(resolved).startsWith('hospital-runtime-test-'));
    fs.rmSync(resolved,{recursive:true,force:true});
  });
  const source=path.join(directory,'built.jar');
  fs.writeFileSync(source,'first complete build');
  return {directory,source,copies:path.join(directory,'runtime-jars'),state:path.join(directory,'process.json')};
}

test('local AI key source is explicit and preserves ordinary environment overrides', t=>{
  const {directory}=fixture(t);
  const notes=path.join(directory,'api.txt');
  const config=path.join(directory,'.env');
  fs.writeFileSync(notes,'OpenAI\nsk-project-fixture-key\n');
  fs.writeFileSync(config,`HOSPITAL_API_FILE=${notes}\nHOSPITAL_OPENAI_KEY_SOURCE=api-file\nJWT_SECRET=test-fixture\n`);
  const parent={OPENAI_API_KEY:'sk-system-fixture-key'};
  assert.equal(loadLocalEnv(readLocalConfig(config,parent)).OPENAI_API_KEY,'sk-project-fixture-key');
  const ordinary=readLocalConfig(config,{...parent,HOSPITAL_OPENAI_KEY_SOURCE:'environment'});
  assert.equal(loadLocalEnv(ordinary).OPENAI_API_KEY,parent.OPENAI_API_KEY);
  assert.equal(loadLocalEnv({...ordinary,OPENAI_API_KEY:''}).OPENAI_API_KEY,'sk-project-fixture-key');
  fs.writeFileSync(notes,'HIRA key only\n');
  assert.throws(()=>loadLocalEnv(readLocalConfig(config,parent)),/must contain the project OpenAI API key/);
});

test('a rebuilt source cannot replace the artifact read by an existing JVM',t=>{
  const files=fixture(t);
  const runtime=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  const originalFingerprint=runtime.fingerprint;
  fs.writeFileSync(files.source,'second build with different nested classes');
  assert.equal(fs.readFileSync(runtime.jar,'utf8'),'first complete build');
  assert.equal(runtime.fingerprint,originalFingerprint);
  const updated=fs.statSync(files.source);
  assert.notEqual(runtime.fingerprint,configurationFingerprint({},[updated.size,updated.mtimeMs]));
  runtime.cleanup();
  assert.equal(fs.readFileSync(files.source,'utf8'),'second build with different nested classes');
});

test('cleanup removes only its own artifact and leaves other runs and unexpected files intact',t=>{
  const files=fixture(t);
  const first=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  const second=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  assert.notEqual(first.jar,second.jar);
  const unexpected=path.join(path.dirname(first.jar),'keep.txt');
  fs.writeFileSync(unexpected,'not owned by artifact cleanup');
  first.cleanup();
  first.cleanup();
  assert.equal(fs.existsSync(first.jar),false);
  assert.equal(fs.existsSync(second.jar),true);
  assert.equal(fs.existsSync(files.source),true);
  assert.equal(fs.readFileSync(unexpected,'utf8'),'not owned by artifact cleanup');
  second.cleanup();
  assert.equal(fs.existsSync(path.dirname(second.jar)),false);
});

test('child exit cleans its copy without removing a newer process state or artifact',t=>{
  const files=fixture(t);
  const runtime=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  const child=Object.assign(new EventEmitter(),{pid:1001});
  trackBackend(child,{}, {file:files.state,runtimeJar:runtime});
  fs.writeFileSync(files.source,'later build');
  child.emit('spawn');
  assert.equal(readBackendState(files.state).fingerprint,runtime.fingerprint);
  const newer=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  fs.writeFileSync(files.state,JSON.stringify({version:1,pid:1002,fingerprint:newer.fingerprint}));
  child.emit('exit',0);
  assert.equal(fs.existsSync(runtime.jar),false);
  assert.equal(fs.existsSync(newer.jar),true);
  assert.equal(readBackendState(files.state).pid,1002);
});

test('spawn failure cleans the unused copy, but a running child error does not delete its JAR',t=>{
  const files=fixture(t);
  const failed=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  const notStarted=new EventEmitter();
  trackBackend(notStarted,{}, {file:files.state,runtimeJar:failed});
  notStarted.emit('error',new Error('spawn failed'));
  assert.equal(fs.existsSync(failed.jar),false);
  const active=createBackendJarCopy({}, {source:files.source,directory:files.copies});
  const running=Object.assign(new EventEmitter(),{pid:1003});
  trackBackend(running,{}, {file:files.state,runtimeJar:active});
  running.emit('error',new Error('signal not delivered'));
  assert.equal(fs.existsSync(active.jar),true);
  running.emit('exit',0);
  assert.equal(fs.existsSync(active.jar),false);
});
