import {test} from 'node:test';
import assert from 'node:assert/strict';
import {CHAT_MESSAGE_LIMIT,chatConversationLimit,chatLocation,chatLocationSelection,chatRequest,safeRecommendationSources,straightLineDistance,chatRecommendations,chatFollowUpOptions,isEmergencyResult} from './chatPresentation.ts';

const hospital={id:'hospital-1',name:'병원',address:'서울',source:'CACHE'};
const result={reply:'안내',hospitals:[hospital],needsMoreInfo:false,emergency:false,mode:'AI',carePreference:'NEARBY',stage:'RESULTS',followUpOptions:['다른 지역으로 찾아주세요']};

test('source links reject executable, relative, credentialed and control-character URLs',()=>{
  const source=url=>({url,title:'병원 안내',checkedAt:'2026-11-21T00:00:00Z'});
  const sources=safeRecommendationSources([
    source('javascript:alert(1)'),source('data:text/html,<script>alert(1)</script>'),
    source('//example.org/clinic'),source('/clinic'),source('https://user:password@example.org/clinic'),
    source('https://exam\nple.org/clinic'),source('file:///clinic.html'),
    source('https://example.org/clinic'),source('https://example.org/clinic'),source('http://example.org/department')
  ]);
  assert.deepEqual(sources.map(item=>item.url),['https://example.org/clinic','http://example.org/department']);
  assert.equal(sources[0].checkedAt,'2026-11-21T00:00:00Z');
  assert.equal(safeRecommendationSources([{url:'https://example.org/',title:'',checkedAt:''}])[0].title,'example.org');
  assert.deepEqual(safeRecommendationSources(),[]);
});

test('source categories remain distinguishable without trusting arbitrary verification labels',()=>{
  const kinds=['OFFICIAL','NEWS','EXPERIENCE','WEB','UNVERIFIED',undefined];
  const sources=safeRecommendationSources(kinds.slice(0,5).map((kind,index)=>({url:`https://example.org/${index}`,title:'참고자료',checkedAt:'',kind,label:'검증 완료·치료 보장'})));
  assert.deepEqual(sources.map(source=>source.kind),['OFFICIAL','NEWS','EXPERIENCE','WEB','WEB']);
  assert.deepEqual(sources.map(source=>source.label),['병원·공공자료','기사·보도','후기·SNS','웹 참고','웹 참고']);
  assert.equal(safeRecommendationSources([{url:'https://example.org/',title:'참고자료',checkedAt:''}])[0].kind,'WEB');
  assert.equal(safeRecommendationSources([{url:'https://www.instagram.com/example/',title:'경험담',checkedAt:'',kind:'EXPERIENCE'}])[0].label,'후기·SNS');
  assert.deepEqual(safeRecommendationSources([{url:'javascript:alert(1)',title:'위험 링크',checkedAt:'',kind:'OFFICIAL'}]),[]);
});

test('request snapshot retains the original question and selected location for a retry',()=>{
  const messages=[{role:'user',content:'전문 진료 후보를 보고 싶어요'}];
  const location={latitude:37.5,longitude:127.1};
  const request=chatRequest(messages,location);
  messages[0].content='수정된 질문';messages.push({role:'assistant',content:'다른 답변'});location.latitude=35;
  assert.deepEqual(request,{messages:[{role:'user',content:'전문 진료 후보를 보고 싶어요'}],location:{latitude:37.5,longitude:127.1}});
  assert.deepEqual(chatRequest(request.messages,null),{messages:request.messages});
  assert.equal(CHAT_MESSAGE_LIMIT,1500);
});

test('conversation limits reject the next oversized request without discarding clinical history',()=>{
  const messages=Array.from({length:19},(_,index)=>({role:index%2?'assistant':'user',content:index===0?'처음에 증상이 악화됐다고 말했어요.':'상담 내용'}));
  const before=structuredClone(messages);
  assert.equal(chatConversationLimit(messages,'다음 질문'),null);
  assert.ok(chatConversationLimit([...messages,{role:'assistant',content:'안내'}],'다음 질문'));
  assert.deepEqual(messages,before);
  const longHistory=Array.from({length:5},()=>({role:'user',content:'가'.repeat(1500)}));
  assert.equal(chatConversationLimit(longHistory,'나'.repeat(1500)),null);
  assert.ok(chatConversationLimit(longHistory,'나'.repeat(1501)));
  assert.ok(chatConversationLimit([...longHistory,{role:'assistant',content:'다'.repeat(1500)}]));
  assert.equal(chatConversationLimit(longHistory,'내용을 줄인 질문'),null);
  assert.equal(longHistory.length,5);
});

test('only valid domestic coordinates enter a chat request, and typed-region requests have no location',()=>{
  assert.deepEqual(chatLocation(37.5,127.1),{latitude:37.5,longitude:127.1});
  for(const pair of [[undefined,undefined],[null,null],[NaN,127],['37.5',127],[127,37],[0,0],[51.5,-.1],[37,Infinity]]){
    assert.equal(chatLocation(...pair),null);
    assert.equal(Object.hasOwn(chatRequest([],{latitude:pair[0],longitude:pair[1]}),'location'),false);
  }
  assert.equal(Object.hasOwn(chatRequest([{role:'user',content:'수원에서 찾아주세요'}],null),'location'),false);
});

test('current location submits only nearby location answers; specialist searches still ask for a named area or nationwide choice',()=>{
  assert.equal(chatLocationSelection({...result,stage:'LOCATION',carePreference:'NEARBY'}).reply,'현재 위치를 기준으로 찾아주세요.');
  const specialist=chatLocationSelection({...result,stage:'LOCATION',carePreference:'SPECIALIST'});
  assert.equal(specialist.reply,null);
  assert.ok(specialist.message.includes('지역명 또는 전국'));
  assert.ok(specialist.message.includes('거리 표시 기준'));
  for(const stage of ['CONTEXT','PREFERENCE','RESULTS','EMERGENCY'])assert.equal(chatLocationSelection({...result,stage}).reply,null);
  assert.equal(chatLocationSelection(null).reply,null);
});

test('new requests, incomplete contexts and emergency results never expose recommendation cards',()=>{
  assert.deepEqual(chatRecommendations(result),[hospital]);
  assert.deepEqual(chatRecommendations(null),[]);
  for(const changed of [{emergency:true},{stage:'EMERGENCY'},{needsMoreInfo:true},{stage:'CONTEXT'},{stage:'PREFERENCE'},{stage:'LOCATION'}]){
    assert.deepEqual(chatRecommendations({...result,...changed}),[]);
  }
  for(const changed of [{emergency:true},{stage:'EMERGENCY'}]){
    assert.equal(isEmergencyResult({...result,...changed}),true);
    assert.deepEqual(chatFollowUpOptions({...result,...changed}),[]);
  }
  assert.deepEqual(chatFollowUpOptions(null),[]);
});

test('preference choices allow nearby and specialist answers while retaining free-text guidance',()=>{
  const options=chatFollowUpOptions({...result,stage:'PREFERENCE',followUpOptions:[]});
  assert.ok(options.some(option=>option.includes('가까운')));
  assert.ok(options.some(option=>option.includes('전문 진료')));
  assert.deepEqual(chatFollowUpOptions({...result,followUpOptions:['  가까운 병원  ','가까운 병원','', 'x'.repeat(1501)]}),['가까운 병원']);
});

test('distance labels explicitly describe straight-line distance and omit unknown or invalid values',()=>{
  for(const value of [undefined,null,NaN,Infinity,-1,'2'])assert.equal(straightLineDistance(value),null);
  assert.equal(straightLineDistance(0),'직선거리 100 m 이내');
  assert.equal(straightLineDistance(.31),'직선거리 약 300 m');
  assert.equal(straightLineDistance(3.24),'직선거리 약 3.2 km');
});
