export type Department = {code:string;name:string};
export type Evidence = {kind:string;label?:string;value:string;sourceUrl?:string;basePeriod?:string;fetchedAt?:string};
export type RecommendationSourceKind = 'OFFICIAL'|'NEWS'|'EXPERIENCE'|'WEB';
export type RecommendationSource = {url:string;title:string;checkedAt:string;kind?:RecommendationSourceKind;label?:string};
export type Hospital = {id:string;name:string;address:string;tel?:string;departmentCode?:string;departmentName?:string;departments?:Department[];evidence?:Evidence[];source:'LIVE'|'CACHE'|'SAMPLE';fetchedAt?:string;latitude?:number;longitude?:number;recommendationReason?:string;notice?:string;hospitalType?:string;distanceKm?:number;recommendationSources?:RecommendationSource[];specialty?:string};
export type HospitalResult = {items:Hospital[];source:'LIVE'|'CACHE'|'SAMPLE';message?:string;fetchedAt?:string};
export type AuthProvider = 'LOCAL'|'GOOGLE'|'KAKAO';
export type User = {id:string;name:string;loginId:string;role:'USER'|'ADMIN'|'HOSPITAL';authProvider?:AuthProvider;profileComplete?:boolean;emailVerified?:boolean;email?:string;phone?:string;hospitalId?:string;businessNumber?:string};
export type SocialProvider = {id:'google'|'kakao';enabled:boolean;authorizationUrl:string};
export type Member = User & {createdAt:string|null};
export type DeleteMemberResult = {message:string;deletedUserId:string;canceledReservations:number};
export type AuthResult = {token:string;user:User};
export type Doctor = {id:string;hospitalId:string;departmentCode:string;name:string;specialty:string;isVirtual:boolean;active?:boolean};
export type HospitalAccountInfo = {id:string;name:string;address:string;postalCode:string;tel:string;businessNumber?:string;departments?:Department[]};
export type VerifiedHospitalLookup = {businessNumber:string;hospital:HospitalAccountInfo;verificationToken:string};
export type HospitalLookup = VerifiedHospitalLookup | {businessNumber:string;candidates:HospitalAccountInfo[];selectionToken:string};
export type HospitalReservation = Reservation & {patientName:string;patientPhone:string};
export type Slot = {id:string;doctorId:string;startAt:string;capacity:number;reservedCount:number;remaining:number;closed:boolean};
export type Reservation = {id:string;status:'RESERVED'|'CANCELED';startAt:string;slotId:string;doctorId:string;hospitalId:string;hospitalName:string;departmentName:string;doctorName:string;createdAt:string};
export type Message = {role:'user'|'assistant';content:string};
export type ChatLocation = {latitude:number;longitude:number};
export type ChatRequest = {messages:Message[];location?:ChatLocation};
export type CarePreference = 'NEARBY'|'SPECIALIST'|'UNDECIDED';
export type ChatResult = {reply:string;hospitals:Hospital[];departmentCode?:string;departmentName?:string;needsMoreInfo:boolean;emergency:boolean;mode:'AI'|'FALLBACK';source?:string;notice?:string;carePreference:CarePreference;careGuidance?:string;followUpOptions?:string[];specialty?:string;stage?:'CONTEXT'|'PREFERENCE'|'LOCATION'|'RESULTS'|'EMERGENCY';promptCare?:boolean};
export type Meta = {serviceName:string;model:string;virtualReservation:boolean;departments:Department[];integrations:{openai:boolean;hira:boolean}};
export const DEPARTMENTS:Department[] = [{code:'01',name:'내과'},{code:'05',name:'정형외과'},{code:'13',name:'이비인후과'},{code:'14',name:'피부과'},{code:'11',name:'소아청소년과'},{code:'23',name:'가정의학과'}];
const TOKEN = 'hospital-session-token';
const sessionRenewals=new Map<string,number>();
export function getToken(){ return sessionStorage.getItem(TOKEN); }
export function setToken(token:string){ sessionStorage.setItem(TOKEN,token); }
export function clearToken(){ sessionStorage.removeItem(TOKEN); }
export function beginSessionRenewal(token:string){
  sessionRenewals.set(token,(sessionRenewals.get(token)||0)+1);
  let active=true;
  return ()=>{
    if(!active)return;active=false;
    const remaining=(sessionRenewals.get(token)||1)-1;
    if(remaining)sessionRenewals.set(token,remaining);else sessionRenewals.delete(token);
  };
}
export function normalizePhone(value:string){return value.replace(/[ -]/g,'');}
export function validPhone(value:string){return /^0[0-9]{8,10}$/.test(normalizePhone(value));}
export class ApiError extends Error {
  status:number;code?:string;existingAuthProvider?:AuthProvider;
  constructor(status:number,message:string,code?:string,existingAuthProvider?:AuthProvider){super(message);this.status=status;this.code=code;this.existingAuthProvider=existingAuthProvider;}
}
function apiFailureMessage(status:number):string {
  if(status===401)return '로그인 후 다시 이용해 주세요.';
  if(status===403)return '이 요청을 처리할 권한이 없어요.';
  if(status===408||status===504)return '서버 응답 시간이 길어졌어요. 잠시 후 같은 내용으로 다시 시도해 주세요.';
  if(status===429)return '요청이 많아 잠시 기다린 뒤 다시 시도해 주세요.';
  if(status===502||status===503)return '서버를 일시적으로 이용할 수 없어요. 잠시 후 다시 시도해 주세요.';
  if(status>=500)return '서버에 일시적인 문제가 생겼어요. 잠시 후 다시 시도해 주세요.';
  return '요청을 처리하지 못했어요. 다시 시도해 주세요.';
}
export async function api<T>(path:string, options:RequestInit = {}):Promise<T>{
  const headers = new Headers(options.headers);
  if (options.body) headers.set('Content-Type','application/json');
  const token = getToken(); if(token) headers.set('Authorization',`Bearer ${token}`);
  let response:Response;
  try {response = await fetch(`/api${path}`,{...options,headers});}
  catch {throw new ApiError(0,'서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.');}
  let text:string;
  try {text=await response.text();} catch {throw new ApiError(response.status,apiFailureMessage(response.status));}
  let data:unknown,validJson=true;
  try {data = text ? JSON.parse(text) : {};} catch {validJson=false;}
  if(!response.ok){
    const message = data && typeof data === 'object' && 'message' in data && typeof data.message === 'string' ? data.message : apiFailureMessage(response.status);
    if(response.status === 401 && !path.startsWith('/auth/') && getToken()===token && (!token||!sessionRenewals.has(token))) {clearToken();window.dispatchEvent(new Event('auth-expired'));}
    const details=data&&typeof data==='object'?data as {code?:unknown;existingAuthProvider?:unknown}:{};
    const provider=details.existingAuthProvider;
    throw new ApiError(response.status,message,typeof details.code==='string'?details.code:undefined,provider==='LOCAL'||provider==='GOOGLE'||provider==='KAKAO'?provider:undefined);
  }
  if(!validJson)throw new ApiError(response.status,'서버 응답을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.');
  return data as T;
}
export function queryString(values:Record<string,string|undefined>){const q = new URLSearchParams();Object.entries(values).forEach(([key,value])=>{if(value)q.set(key,value);});return q.toString();}
export function today(offset=0){const d = new Date(Date.now()+offset*86400000);return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Seoul'}).format(d);}
export function dateLabel(value:string){return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',month:'long',day:'numeric',weekday:'short'}).format(new Date(value));}
export function timeLabel(value:string){return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value));}
export function fetchedLabel(value?:string){if(!value)return '조회 시각 미확인';const d=new Date(value);if(Number.isNaN(d.getTime()))return value;return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(d);}
export function departmentName(code?:string){return DEPARTMENTS.find(d=>d.code===code)?.name || '진료과';}
