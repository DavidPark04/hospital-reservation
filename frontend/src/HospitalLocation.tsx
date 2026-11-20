import {useEffect,useRef,useState} from 'react';
import {ExternalLink,LoaderCircle,MapPin,Navigation,RefreshCw,Search} from 'lucide-react';
import type {Hospital} from './api';
import {exactAddressCoordinates,hospitalCoordinates,kakaoLocationLinks} from './hospitalLocationUtils';
import type {HospitalCoordinates} from './hospitalLocationUtils';
import {loadKakaoMaps} from './kakaoMaps';
import type {KakaoMap,KakaoMaps,KakaoMarker} from './kakaoMaps';

const javascriptKey=(import.meta.env.VITE_KAKAO_MAP_JS_KEY||'').trim();

export default function HospitalLocation({hospital}:{hospital:Hospital}){
  // A detail refresh can replace coordinates even for the same ID. Never retain the old destination.
  const key=JSON.stringify([hospital.id,hospital.source,hospital.name,hospital.address,hospital.latitude,hospital.longitude]);
  return <HospitalLocationView key={key} hospital={hospital}/>;
}

function HospitalLocationView({hospital}:{hospital:Hospital}){
  const sample=hospital.source==='SAMPLE';
  const [coordinates,setCoordinates]=useState<HospitalCoordinates|null>(()=>sample?null:hospitalCoordinates(hospital.latitude,hospital.longitude));
  const [addressBased,setAddressBased]=useState(false);
  const [status,setStatus]=useState<'loading'|'ready'|'unavailable'|'error'>(sample||!javascriptKey?'unavailable':'loading');
  const [message,setMessage]=useState(!javascriptKey?'지도를 준비 중이에요. 카카오맵에서 위치를 확인해 주세요.':'');
  const [attempt,setAttempt]=useState(0);
  const container=useRef<HTMLDivElement>(null);
  const links=kakaoLocationLinks(hospital.name,hospital.address,coordinates);

  useEffect(()=>{
    if(sample||!javascriptKey||!container.current)return;
    let active=true;
    let maps:KakaoMaps|undefined;
    let map:KakaoMap|undefined;
    let marker:KakaoMarker|undefined;
    let observer:ResizeObserver|undefined;
    let tileTimer:number|undefined;
    let addressTimer:number|undefined;
    let cancelAddress:()=>void=()=>{};
    let tilesLoaded:()=>void=()=>{};
    let resized:()=>void=()=>{};
    const element=container.current;
    setStatus('loading');setMessage('');
    const known=hospitalCoordinates(hospital.latitude,hospital.longitude);
    setCoordinates(known);setAddressBased(false);

    async function show(){
      try{
        maps=await loadKakaoMaps(javascriptKey);
        if(!active)return;
        let position=known;
        if(!position){
          if(!hospital.address?.trim())throw new Error('병원 주소가 제공되지 않아 위치를 확인할 수 없어요.');
          const sdk=maps;
          position=await new Promise<HospitalCoordinates>((resolve,reject)=>{
            let finished=false;
            function finish(result:HospitalCoordinates|null){
              if(finished)return;finished=true;window.clearTimeout(addressTimer);
              if(result)resolve(result);else reject(new Error('정확한 병원 위치를 확인하지 못했어요. 카카오맵에서 병원명과 주소를 확인해 주세요.'));
            }
            cancelAddress=()=>finish(null);
            addressTimer=window.setTimeout(()=>finish(null),10000);
            new sdk.services.Geocoder().addressSearch(hospital.address,(results,status,pagination)=>{
              finish(status===sdk.services.Status.OK?exactAddressCoordinates(results,pagination?.totalCount??0):null);
            },{analyze_type:sdk.services.AnalyzeType.EXACT,size:2});
          });
          if(!active)return;
          setAddressBased(true);setCoordinates(position);
        }
        if(!active)return;
        const center=new maps.LatLng(position.latitude,position.longitude);
        map=new maps.Map(element,{center,level:3});
        marker=new maps.Marker({map,position:center,title:hospital.name});
        tilesLoaded=()=>{if(active){window.clearTimeout(tileTimer);setStatus('ready');setMessage('');}};
        maps.event.addListener(map,'tilesloaded',tilesLoaded);
        tileTimer=window.setTimeout(()=>{if(active){setStatus('error');setMessage('지도 화면을 불러오지 못했어요. 카카오맵에서 위치를 확인할 수 있어요.');}},15000);
        resized=()=>{if(active&&map){map.relayout();map.setCenter(center);}};
        if(typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(resized);observer.observe(element);}
        window.addEventListener('resize',resized);
      }catch(error){
        if(active){setStatus('error');setMessage(error instanceof Error?error.message:'지도를 불러오지 못했어요. 다시 시도해 주세요.');}
      }
    }
    void show();
    return()=>{
      active=false;cancelAddress();window.clearTimeout(addressTimer);window.clearTimeout(tileTimer);
      observer?.disconnect();window.removeEventListener('resize',resized);
      if(map&&maps)maps.event.removeListener(map,'tilesloaded',tilesLoaded);
      marker?.setMap(null);element.replaceChildren();
    };
  },[hospital.name,hospital.address,hospital.latitude,hospital.longitude,sample,attempt]);

  return <section className="panel hospital-location" aria-label="병원 위치와 길찾기">
    <div className="section-title-row"><h2><MapPin size={21}/>병원 위치</h2><span className="map-provider-label">Kakao Map</span></div>
    {sample?<div className="map-unavailable"><MapPin size={28}/><p>시연용 가상 병원이에요.<br/>실제 위치와 길찾기는 제공하지 않아요.</p></div>:<>
      <div className="hospital-map-frame" aria-busy={status==='loading'}>
        <div className="hospital-map-canvas" ref={container} role="region" aria-label={`${hospital.name} 위치 지도`}/>
        {status!=='ready'&&<div className="hospital-map-status" role="status">
          {status==='loading'?<><LoaderCircle size={25} className="spin"/><p>병원 위치를 확인하고 있어요.</p></>:<><MapPin size={27}/><p>{message}</p>{status==='error'&&<button className="button outline small" type="button" onClick={()=>setAttempt(value=>value+1)}><RefreshCw size={15}/>다시 불러오기</button>}</>}
        </div>}
      </div>
      <div className="hospital-location-details"><div><strong>{hospital.name}</strong><p>{hospital.address||'주소 정보 미제공'}</p></div><div className="hospital-map-actions">
        {coordinates?<><a className="button primary small" href={links.directions!} target="_blank" rel="noopener noreferrer"><Navigation size={16}/>길찾기</a><a className="button outline small" href={links.map!} target="_blank" rel="noopener noreferrer">지도 크게 보기<ExternalLink size={14}/></a></>:<a className="button outline small" href={links.search} target="_blank" rel="noopener noreferrer"><Search size={16}/>카카오맵에서 검색</a>}
      </div></div>
      <p className="hospital-map-help">{addressBased?'병원 주소를 기준으로 표시한 위치예요. 건물 입구와 층수는 방문 전에 확인해 주세요.':coordinates?'길찾기를 누르면 병원이 목적지로 설정된 카카오맵이 열려요.':'카카오맵에서 병원명과 주소를 함께 검색해 위치를 확인할 수 있어요.'} 현재 위치는 수집하지 않아요.</p>
    </>}
  </section>;
}
