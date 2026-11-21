import type {ChatLocation, ChatRequest, ChatResult, Message, RecommendationSource, RecommendationSourceKind} from './api';

export const CHAT_MESSAGE_LIMIT = 1500;
export const CHAT_HISTORY_MESSAGE_LIMIT = 20;
export const CHAT_HISTORY_CHARACTER_LIMIT = 9000;

export function chatConversationLimit(messages:Message[],nextContent=''):string|null {
  if(messages.length+1>CHAT_HISTORY_MESSAGE_LIMIT)return '이번 상담의 대화 횟수 한도에 도달했어요. 새 상담을 시작해 주세요.';
  const used=messages.reduce((total,message)=>total+message.content.trim().length,0);
  const nextLength=nextContent.trim().length;
  if(used+nextLength>CHAT_HISTORY_CHARACTER_LIMIT||used>=CHAT_HISTORY_CHARACTER_LIMIT)return '이번 상담의 글자 수 한도에 도달했어요. 입력을 줄이거나 새 상담을 시작해 주세요.';
  return null;
}

export function chatLocation(latitude:unknown,longitude:unknown):ChatLocation|null {
  if(typeof latitude!=='number'||typeof longitude!=='number'||!Number.isFinite(latitude)||!Number.isFinite(longitude))return null;
  if(latitude<32||latitude>40||longitude<123||longitude>133)return null;
  return {latitude,longitude};
}

export function chatRequest(messages:Message[],location:ChatLocation|null):ChatRequest {
  const request:ChatRequest={messages:messages.map(message=>({...message}))};
  const validLocation=location&&chatLocation(location.latitude,location.longitude);
  if(validLocation)request.location=validLocation;
  return request;
}

export function chatLocationSelection(result:ChatResult|null):{message:string;reply:string|null} {
  if(result?.stage==='LOCATION'&&result.carePreference==='SPECIALIST')return {
    message:'현재 위치는 거리 표시 기준으로만 사용해요. 전문 진료기관을 찾을 지역명 또는 전국 범위를 채팅으로 알려주세요.',
    reply:null
  };
  return {
    message:'현재 위치를 이번 상담에만 사용해요. 위치는 저장하지 않아요.',
    reply:result?.stage==='LOCATION'&&result.carePreference==='NEARBY'?'현재 위치를 기준으로 찾아주세요.':null
  };
}

export function safeRecommendationSources(sources?:RecommendationSource[]):RecommendationSource[] {
  if(!Array.isArray(sources))return [];
  const seen=new Set<string>();
  return sources.flatMap(source=>{
    if(!source||typeof source.url!=='string'||/[\u0000-\u001f\u007f]/.test(source.url))return [];
    try {
      const url=new URL(source.url);
      if(!['https:','http:'].includes(url.protocol)||url.username||url.password||seen.has(url.href))return [];
      seen.add(url.href);
      const kind:RecommendationSourceKind=source.kind==='OFFICIAL'||source.kind==='NEWS'||source.kind==='EXPERIENCE'?source.kind:'WEB';
      const label={OFFICIAL:'병원·공공자료',NEWS:'기사·보도',EXPERIENCE:'후기·SNS',WEB:'웹 참고'}[kind];
      return [{url:url.href,title:typeof source.title==='string'&&source.title.trim()?source.title.trim():url.hostname,checkedAt:typeof source.checkedAt==='string'?source.checkedAt:'',kind,label}];
    } catch {return [];}
  }).slice(0,5);
}

export function straightLineDistance(distanceKm?:number):string|null {
  if(typeof distanceKm!=='number'||!Number.isFinite(distanceKm)||distanceKm<0)return null;
  if(distanceKm<.1)return '직선거리 100 m 이내';
  return `직선거리 약 ${distanceKm<1?`${Math.round(distanceKm*10)*100} m`:`${distanceKm.toFixed(1)} km`}`;
}

export function isEmergencyResult(result:ChatResult|null):boolean {
  return !!result&&(result.emergency||result.stage==='EMERGENCY');
}

export function chatRecommendations(result:ChatResult|null) {
  if(!result||isEmergencyResult(result)||result.needsMoreInfo||['CONTEXT','PREFERENCE','LOCATION'].includes(result.stage||''))return [];
  return result.hospitals||[];
}

export function chatFollowUpOptions(result:ChatResult|null):string[] {
  if(!result||isEmergencyResult(result))return [];
  const options=result.followUpOptions?.length?result.followUpOptions:result.stage==='PREFERENCE'?['가까운 의원·병원으로 찾아주세요','전문 진료·대학병원 후보를 보고 싶어요','어느 쪽이 좋을지 아직 모르겠어요']:[];
  return [...new Set(options.filter(option=>typeof option==='string'&&option.trim()&&option.length<=CHAT_MESSAGE_LIMIT).map(option=>option.trim()))].slice(0,5);
}
