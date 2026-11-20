import {test} from 'node:test';
import assert from 'node:assert/strict';
import {hospitalCoordinates,exactAddressCoordinates,kakaoLocationLinks} from './hospitalLocationUtils.ts';

const location={latitude:37.3952969470752,longitude:127.110449292622};
const address={x:String(location.longitude),y:String(location.latitude),address_type:'ROAD_ADDR',road_address:{main_building_no:'166'}};

test('accepts Korean coordinates and rejects missing, reversed, zero or implausible locations',()=>{
  assert.deepEqual(hospitalCoordinates(location.latitude,location.longitude),location);
  for(const pair of [[undefined,undefined],[null,null],['37.5','127.0'],[0,0],[NaN,127],[127,37],[51.5,-.12],[37,Infinity]])assert.equal(hospitalCoordinates(...pair),null);
});
test('address fallback accepts only one exact building result',()=>{
  assert.deepEqual(exactAddressCoordinates([address],1),location);
  assert.equal(exactAddressCoordinates([address],2),null);
  assert.equal(exactAddressCoordinates([address,address],2),null);
  assert.equal(exactAddressCoordinates([{...address,address_type:'REGION'}],1),null);
  assert.equal(exactAddressCoordinates([{...address,road_address:null}],1),null);
  assert.equal(exactAddressCoordinates([{...address,x:'',y:''}],1),null);
});
test('map links retain latitude/longitude order and encode names and searches',()=>{
  const name='병원, A/B? #<test>';
  const links=kakaoLocationLinks(name,'경기도 수원시 123',location);
  assert.equal(links.directions,`https://map.kakao.com/link/to/${encodeURIComponent(name)},${location.latitude},${location.longitude}`);
  assert.equal(new URL(links.directions).search,'');
  assert.equal(new URL(links.directions).hash,'');
  const unavailable=kakaoLocationLinks(name,'경기도 수원시 123',null);
  assert.equal(unavailable.map,null);assert.equal(unavailable.directions,null);
  assert.equal(unavailable.search,`https://map.kakao.com/link/search/${encodeURIComponent(name+' 경기도 수원시 123')}`);
});

let version=0;
async function sdkFixture(run){
  const savedWindow=globalThis.window;const savedDocument=globalThis.document;
  const scripts=[];const timers=[];
  globalThis.window={setTimeout:callback=>{timers.push(callback);return timers.length;},clearTimeout:id=>{timers[id-1]=null;}};
  globalThis.document={createElement:()=>Object.assign(new EventTarget(),{remove(){this.removed=true;}}),head:{appendChild:script=>scripts.push(script)}};
  try{const {loadKakaoMaps}=await import(`./kakaoMaps.ts?test=${++version}`);await run({loadKakaoMaps,scripts,timers});}
  finally{if(savedWindow===undefined)delete globalThis.window;else globalThis.window=savedWindow;if(savedDocument===undefined)delete globalThis.document;else globalThis.document=savedDocument;}
}
function completeSDK(script){
  window.kakao={maps:{load(callback){this.Map=class {};this.services={Geocoder:class {}};callback();}}};
  script.dispatchEvent(new Event('load'));
}
test('concurrent map mounts share one SDK load and a ready SDK is reused',()=>sdkFixture(async({loadKakaoMaps,scripts})=>{
  const first=loadKakaoMaps('test-javascript-key');const second=loadKakaoMaps('test-javascript-key');
  assert.equal(first,second);assert.equal(scripts.length,1);
  const url=new URL(scripts[0].src);assert.equal(url.host,'dapi.kakao.com');assert.equal(url.searchParams.get('autoload'),'false');assert.equal(url.searchParams.get('libraries'),'services');
  completeSDK(scripts[0]);const maps=await first;assert.equal(await loadKakaoMaps('test-javascript-key'),maps);assert.equal(scripts.length,1);
}));
test('network failure removes failed SDK and a later retry can succeed',()=>sdkFixture(async({loadKakaoMaps,scripts})=>{
  const first=loadKakaoMaps('test-javascript-key');scripts[0].dispatchEvent(new Event('error'));await assert.rejects(first);
  assert.equal(scripts[0].removed,true);const retry=loadKakaoMaps('test-javascript-key');assert.equal(scripts.length,2);completeSDK(scripts[1]);await retry;
}));
test('an incomplete SDK timeout does not trap the next retry',()=>sdkFixture(async({loadKakaoMaps,scripts,timers})=>{
  const first=loadKakaoMaps('test-javascript-key');window.kakao={maps:{load(){}}};scripts[0].dispatchEvent(new Event('load'));
  timers[0]();await assert.rejects(first);assert.equal(window.kakao.maps,undefined);
  const retry=loadKakaoMaps('test-javascript-key');assert.equal(scripts.length,2);completeSDK(scripts[1]);await retry;
}));
