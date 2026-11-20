import {test} from 'node:test';
import assert from 'node:assert/strict';
import {formatPhone,formatPhoneEdit,deletePhoneSeparator} from './phoneFormatting.ts';
import {passwordChangeError} from './passwordValidation.ts';
import {api,beginSessionRenewal,getToken,setToken,normalizePhone,validPhone} from './api.ts';

test('mobile typing automatically adds separators without changing digits',()=>{
  const digits='01012345678';
  const expected=['','0','01','010','010-1','010-12','010-123','010-1234','010-1234-5','010-1234-56','010-1234-567','010-1234-5678'];
  for(let length=0;length<=digits.length;length++){
    const input=digits.slice(0,length);const edited=formatPhoneEdit(input,input.length);
    assert.equal(edited.value,expected[length]);assert.equal(edited.caret,edited.value.length);
    assert.equal(normalizePhone(edited.value),input);
  }
});

test('landline formats preserve the accepted domestic contact identity',()=>{
  for(const [digits,display] of [['021234567','02-123-4567'],['0212345678','02-1234-5678'],['0311234567','031-123-4567'],['03112345678','031-1234-5678'],['07012345678','070-1234-5678']]){
    assert.equal(formatPhone(digits),display);assert.equal(normalizePhone(display),digits);assert.equal(validPhone(display),true);
  }
});

test('paste and middle edits keep the caret beside the edited digits',()=>{
  assert.deepEqual(formatPhoneEdit('010 1234 5678',13),{value:'010-1234-5678',caret:13});
  assert.deepEqual(formatPhoneEdit('010-1299-5678',8),{value:'010-1299-5678',caret:8});
  assert.deepEqual(formatPhoneEdit('010-12 34-5678',7),{value:'010-1234-5678',caret:6});
  assert.deepEqual(formatPhoneEdit('0a10-1234-5678',2),{value:'010-1234-5678',caret:1});
  assert.equal(normalizePhone(formatPhone('010-1234-56789')),'010123456789');
  assert.equal(validPhone(formatPhone('010-1234-56789')),false);
});

test('backspace and delete cross automatic separators without trapping the caret',()=>{
  const value='010-1234-5678';
  assert.deepEqual(deletePhoneSeparator(value,4,4,'Backspace'),{value:'011-2345-678',caret:2});
  assert.deepEqual(deletePhoneSeparator(value,8,8,'Delete'),{value:'010-1234-678',caret:8});
  assert.deepEqual(deletePhoneSeparator(value,9,9,'Backspace'),{value:'010-1235-678',caret:7});
  assert.equal(deletePhoneSeparator(value,4,8,'Backspace'),null);
  assert.equal(deletePhoneSeparator(value,6,6,'Delete'),null);
});

test('password confirmation, existing password and UTF-8 byte limits are validated without trimming',()=>{
  assert.ok(passwordChangeError('','NewPassword123!','NewPassword123!'));
  assert.ok(passwordChangeError('Current123!','short','short'));
  assert.ok(passwordChangeError('Current123!','Current123!','Current123!'));
  assert.ok(passwordChangeError('Current123!','NewPassword123!','other'));
  assert.equal(passwordChangeError('Current123!','  NewPass  ','  NewPass  '),'');
  assert.equal(passwordChangeError('Current123!','가'.repeat(24),'가'.repeat(24)),'');
  assert.ok(passwordChangeError('Current123!','가'.repeat(25),'가'.repeat(25)));
  assert.equal(passwordChangeError('Current123!','a'.repeat(72),'a'.repeat(72)),'');
  assert.ok(passwordChangeError('Current123!','a'.repeat(73),'a'.repeat(73)));
});

async function sessionFixture(run){
  const originals={sessionStorage:globalThis.sessionStorage,window:globalThis.window,fetch:globalThis.fetch};
  const values=new Map();const events=[];const requests=[];
  globalThis.sessionStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};
  globalThis.window={dispatchEvent:event=>events.push(event.type)};
  globalThis.fetch=(url,options)=>new Promise(resolve=>requests.push({url,options,resolve}));
  const deny=request=>request.resolve(new Response(JSON.stringify({message:'로그인이 필요합니다.'}),{status:401,headers:{'Content-Type':'application/json'}}));
  try{await run({events,requests,deny});}
  finally{for(const [key,value] of Object.entries(originals)){if(value===undefined)delete globalThis[key];else globalThis[key]=value;}}
}

test('a stale unauthorized response cannot clear a renewed session',()=>sessionFixture(async({requests,events,deny})=>{
  setToken('old-session');const pending=api('/reservations');setToken('new-session');deny(requests[0]);
  await assert.rejects(pending,error=>error.status===401);assert.equal(getToken(),'new-session');assert.deepEqual(events,[]);
}));

test('renewal protects the old token until the password response is installed',()=>sessionFixture(async({requests,events,deny})=>{
  setToken('old-session');const finish=beginSessionRenewal('old-session');
  try{
    const pending=api('/reservations');deny(requests[0]);await assert.rejects(pending);
    assert.equal(getToken(),'old-session');assert.deepEqual(events,[]);
    setToken('new-session');finish();
    const current=api('/reservations');deny(requests[1]);await assert.rejects(current);
    assert.equal(getToken(),null);assert.deepEqual(events,['auth-expired']);
  }finally{finish();}
}));

test('ending a failed renewal restores normal expired-session handling',()=>sessionFixture(async({requests,events,deny})=>{
  setToken('old-session');const finish=beginSessionRenewal('old-session');finish();finish();
  const pending=api('/reservations');deny(requests[0]);await assert.rejects(pending);
  assert.equal(getToken(),null);assert.deepEqual(events,['auth-expired']);
}));
