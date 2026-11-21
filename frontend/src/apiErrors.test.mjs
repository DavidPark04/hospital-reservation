import {test} from 'node:test';
import assert from 'node:assert/strict';
import {api,ApiError,getToken,setToken} from './api.ts';

async function fixture(run){
  const originals={sessionStorage:globalThis.sessionStorage,window:globalThis.window,fetch:globalThis.fetch};
  const values=new Map(),events=[];
  globalThis.sessionStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};
  globalThis.window={dispatchEvent:event=>events.push(event.type)};
  const respond=(status,body)=>{globalThis.fetch=async()=>new Response(body,{status});};
  try{await run({respond,events});}
  finally{for(const [key,value] of Object.entries(originals)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
}

test('non-JSON gateway and rate errors retain their status and explain distinct recovery paths',()=>fixture(async({respond})=>{
  for(const [status,message] of [[504,'응답 시간이'],[503,'일시적으로 이용'],[502,'일시적으로 이용'],[429,'요청이 많아'],[500,'일시적인 문제']]){
    respond(status,'<html>internal platform error details</html>');
    await assert.rejects(api('/chat'),error=>error instanceof ApiError&&error.status===status&&error.message.includes(message)&&!error.message.includes('internal'));
  }
}));

test('non-JSON unauthorized responses still expire the matching session',()=>fixture(async({respond,events})=>{
  setToken('test-session');respond(401,'Unauthorized');
  await assert.rejects(api('/mypage'),error=>error instanceof ApiError&&error.status===401&&error.message.includes('로그인'));
  assert.equal(getToken(),null);assert.deepEqual(events,['auth-expired']);
}));

test('JSON application error details remain available and successful HTML is not treated as valid data',()=>fixture(async({respond})=>{
  respond(409,JSON.stringify({message:'이미 가입된 계정이 있어요.',code:'DUPLICATE_ACCOUNT',existingAuthProvider:'KAKAO'}));
  await assert.rejects(api('/auth/signup'),error=>error.status===409&&error.message==='이미 가입된 계정이 있어요.'&&error.code==='DUPLICATE_ACCOUNT'&&error.existingAuthProvider==='KAKAO');
  respond(200,'<html>not API JSON</html>');
  await assert.rejects(api('/chat'),error=>error instanceof ApiError&&error.status===200&&error.message.includes('응답을 확인'));
}));
