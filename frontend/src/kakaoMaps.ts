import type {AddressResult} from './hospitalLocationUtils';

type LatLng = object;
export type KakaoMap = {relayout:()=>void;setCenter:(position:LatLng)=>void};
export type KakaoMarker = {setMap:(map:KakaoMap|null)=>void};
export type KakaoMaps = {
  load:(callback:()=>void)=>void;
  LatLng:new(latitude:number,longitude:number)=>LatLng;
  Map:new(container:HTMLElement,options:{center:LatLng;level:number})=>KakaoMap;
  Marker:new(options:{map:KakaoMap;position:LatLng;title:string})=>KakaoMarker;
  event:{addListener:(map:KakaoMap,type:string,callback:()=>void)=>void;removeListener:(map:KakaoMap,type:string,callback:()=>void)=>void};
  services:{
    Status:{OK:string};AnalyzeType:{EXACT:string};
    Geocoder:new()=>{addressSearch:(address:string,callback:(results:AddressResult[],status:string,pagination:{totalCount:number})=>void,options:{analyze_type:string;size:number})=>void};
  };
};

declare global {interface Window {kakao?:{maps?:KakaoMaps}}}
let loading:Promise<KakaoMaps>|null=null;

/** One SDK request shared by mounts, with a fresh attempt after a load failure. */
export function loadKakaoMaps(key:string):Promise<KakaoMaps> {
  if(!key)return Promise.reject(new Error('지도 설정을 준비하고 있어요.'));
  const available=window.kakao?.maps;
  if(available?.Map&&available.services?.Geocoder)return Promise.resolve(available);
  if(loading)return loading;
  const pending=new Promise<KakaoMaps>((resolve,reject)=>{
    let settled=false;
    let script:HTMLScriptElement|null=null;
    let attemptedMaps:KakaoMaps|undefined;
    const timeout=window.setTimeout(failed,15000);
    function detach(){window.clearTimeout(timeout);if(script){script.removeEventListener('load',loaded);script.removeEventListener('error',failed);}}
    function failed(){
      if(settled)return;settled=true;detach();script?.remove();
      // A failed bootstrap must not leave retries attached to an incomplete SDK.
      if(script&&attemptedMaps&&window.kakao?.maps===attemptedMaps
          &&(!attemptedMaps.Map||!attemptedMaps.services?.Geocoder))delete window.kakao.maps;
      reject(new Error('지도를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'));
    }
    function loaded(){
      if(settled)return;
      const maps=window.kakao?.maps;
      if(!maps?.load){failed();return;}
      attemptedMaps=maps;
      try{maps.load(()=>{
        if(settled)return;
        if(!maps.Map||!maps.services?.Geocoder){failed();return;}
        settled=true;detach();resolve(maps);
      });}catch{failed();}
    }
    if(available?.load){loaded();return;}
    script=document.createElement('script');
    script.id='hospital-kakao-maps-sdk';script.async=true;
    script.src=`https://dapi.kakao.com/v2/maps/sdk.js?${new URLSearchParams({appkey:key,autoload:'false',libraries:'services'})}`;
    script.addEventListener('load',loaded);script.addEventListener('error',failed);
    document.head.appendChild(script);
  });
  loading=pending;
  void pending.catch(()=>{if(loading===pending)loading=null;});
  return pending;
}
