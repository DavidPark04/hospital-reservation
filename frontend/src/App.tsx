import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarDays, Check, CheckCircle2, ChevronLeft, ChevronRight, ClipboardList, Clock3, Hospital as HospitalIcon, Info, LoaderCircle, LogOut, MapPin, Menu, Phone, Plus, Search, Send, Settings2, ShieldCheck, Sparkles, Stethoscope, UserRound, X, AlertCircle, RefreshCw, CalendarCheck2, ExternalLink, Building2 } from 'lucide-react';
import { api, ApiError, clearToken, dateLabel, DEPARTMENTS, departmentName, fetchedLabel, getToken, normalizePhone, queryString, setToken, timeLabel, today } from './api';
import MedipickHome from './medipick/MedipickHome';
import {DoctorPortrait} from './medipick/FilmVisual';
import type {BookingDraft, HomeRecommendation} from './medipick/flow';
import {HOME_HOSPITAL_FILTERS} from './medipick/flow';
import HospitalLocation from './HospitalLocation';
import PhoneInput from './PhoneInput';
import PasswordChange from './PasswordChange';
import EmailChange from './EmailChange';
import PhoneVerification from './PhoneVerification';
import HospitalAuthForm, {formatBusinessNumber} from './HospitalAuthForm';
import HospitalManagement from './HospitalManagement';
import EmailVerification from './EmailVerification';
import PasswordRecovery from './PasswordRecovery';
import {validMobilePhone} from './contactVerification';
import {CHAT_MESSAGE_LIMIT, chatConversationLimit, chatFollowUpOptions, chatLocation, chatLocationSelection, chatRecommendations, chatRequest, isEmergencyResult, safeRecommendationSources, straightLineDistance} from './chatPresentation';
import './chatRecommendations.css';
import {formatPhone} from './phoneFormatting';
import { loginMethod, SocialLoginButtons, SocialLoginCallback, socialErrorMessage } from './SocialLogin';
import SocialProfile, { needsSocialProfile } from './SocialProfile';
import type { AuthProvider, AuthResult, ChatLocation, ChatRequest, ChatResult, DeleteMemberResult, Doctor, Hospital, HospitalResult, Member, Message, Meta, Reservation, Slot, User } from './api';

function currentRoute(){
  const hashRoute=window.location.hash.slice(1);
  if(hashRoute.startsWith('/'))return hashRoute;
  if(window.location.pathname==='/auth/social/callback')return '/auth/social/callback';
  if(window.location.pathname==='/mypage')return '/mypage';
  if(window.location.pathname==='/login')return '/login';
  return '/';
}
function go(path:string){
  if(currentRoute()===path&&window.location.pathname==='/'&&!window.location.search){window.scrollTo({top:0,behavior:'instant'});return;}
  if(window.location.pathname!=='/'||window.location.search){
    window.history.replaceState(null,'',`/#${path}`);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  }else window.location.hash=path;
}
function useRoute(){const [route,setRoute]=useState(currentRoute);useEffect(()=>{const changed=()=>setRoute(currentRoute());window.addEventListener('hashchange',changed);return()=>window.removeEventListener('hashchange',changed);},[]);return route;}
type BookingSelection={hospital:Hospital;department:string;reservation?:Reservation;draft?:BookingDraft};
const SOCIAL_RETURN='hospital-social-return';
const AUTH_RETURN_ROUTES=['/booking','/reservations','/admin','/admin/members','/mypage','/hospital'];
function readSocialReturn():{route:string;booking:BookingSelection|null}{
  try{
    const stored=JSON.parse(sessionStorage.getItem(SOCIAL_RETURN)||'null');
    if(!stored||!AUTH_RETURN_ROUTES.includes(stored.route))return {route:'/mypage',booking:null};
    const selection=stored.booking;
    const booking=selection&&typeof selection.department==='string'&&typeof selection.hospital?.id==='string'&&typeof selection.hospital?.name==='string'?selection as BookingSelection:null;
    return {route:stored.route==='/booking'&&!booking?'/mypage':stored.route,booking};
  }catch{return {route:'/mypage',booking:null};}
}
export default function App(){
  const route = useRoute();
  const isHome = route.split('?')[0]==='/';
  const homeScene = isHome ? new URLSearchParams(route.split('?')[1]).get('scene') || undefined : undefined;
  const [viewedRecommendation,setViewedRecommendation]=useState<(HomeRecommendation & {chatRoute:string})>();
  const [headerHidden,setHeaderHidden]=useState(false);
  const queryClient = useQueryClient();
  const [user,setUser]=useState<User|null>(null);
  const [authReady,setAuthReady]=useState(!getToken()||currentRoute()==='/auth/social/callback');
  const [authMode,setAuthMode]=useState<'login'|'register'|null>(()=>window.location.pathname==='/login'?'login':null);
  const [preferredLogin,setPreferredLogin]=useState<AuthProvider|undefined>();
  const [socialError,setSocialError]=useState(()=>{const code=new URLSearchParams(window.location.search).get('socialError');return code?socialErrorMessage(code):'';});
  const [mobileMenu,setMobileMenu]=useState(false);
  const [booking,setBooking]=useState<BookingSelection|null>(null);
  const [toast,setToast]=useState('');
  const [withdrawalReturn,setWithdrawalReturn]=useState(()=>window.location.pathname==='/mypage'?new URLSearchParams(window.location.search).get('withdrawal')||'':'');
  const profilePending=needsSocialProfile(user);
  const homeVisible=isHome&&!profilePending;
  const chatRoute=route.startsWith('/chat')?route:(homeScene&&viewedRecommendation?viewedRecommendation.chatRoute:undefined);
  useLayoutEffect(()=>{
    if(homeScene)return;
    const top=()=>window.scrollTo({top:0,behavior:'instant'});
    top();
    // Hash navigation and the outgoing scroll animation settle after React commits the new page.
    const frame=requestAnimationFrame(top);
    return ()=>cancelAnimationFrame(frame);
  },[route,homeScene]);
  useEffect(()=>{
    const update=(event:Event)=>{
      const hidden=(event as CustomEvent<boolean>).detail;
      setHeaderHidden(hidden);
      if(hidden)setMobileMenu(false);
    };
    window.addEventListener('medipick-header-visibility',update);
    return ()=>window.removeEventListener('medipick-header-visibility',update);
  },[]);
  useEffect(()=>{if(!homeVisible)setHeaderHidden(false);},[homeVisible]);
  const meta = useQuery({queryKey:['meta'],queryFn:()=>api<Meta>('/meta')});
  useEffect(()=>{
    if(!getToken()||currentRoute()==='/auth/social/callback')return;
    let active=true;
    api<User>('/auth/me').then(value=>{if(active)setUser(value);}).catch(error=>{
      if(!active)return;
      clearToken();
      if(withdrawalReturn){
        setWithdrawalReturn('');setSocialError('로그인 정보를 확인하지 못했어요. 다시 로그인한 뒤 회원 탈퇴를 진행해 주세요.');setAuthMode('login');
      }
      if(error instanceof ApiError&&error.status===401&&currentRoute()==='/complete-profile'){
        setSocialError('가입 인증 시간이 지났어요. 소셜 로그인을 다시 진행해 주세요.');setAuthMode('login');go('/login');
      }
    }).finally(()=>{if(active)setAuthReady(true);});
    return()=>{active=false;};
  },[]);
  useEffect(()=>{const expired=()=>{setUser(null);setViewedRecommendation(undefined);setWithdrawalReturn('');setAuthMode('login');queryClient.removeQueries({queryKey:['reservations']});queryClient.removeQueries({queryKey:['admin-members']});queryClient.removeQueries({queryKey:['hospital-manage']});};window.addEventListener('auth-expired',expired);return()=>window.removeEventListener('auth-expired',expired);},[queryClient]);
  useEffect(()=>{if(!toast)return;const timer=setTimeout(()=>setToast(''),5000);return()=>clearTimeout(timer);},[toast]);
  useEffect(()=>setMobileMenu(false),[route]);
  useEffect(()=>{
    if(window.location.pathname==='/mypage'&&new URLSearchParams(window.location.search).has('withdrawal')){
      window.history.replaceState(null,'','/#/mypage');
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    }
  },[]);
  useEffect(()=>{
    if(authReady&&profilePending){setAuthMode(null);if(route!=='/complete-profile')go('/complete-profile');}
    else if(authReady&&user&&route==='/complete-profile'&&currentRoute()==='/complete-profile')go('/mypage');
  },[authReady,profilePending,route,user]);
  function startBooking(hospital:Hospital, department?:string, reservation?:Reservation, draft?:BookingDraft){
    if(user?.role==='HOSPITAL'){go('/hospital');setToast('병원 계정에서는 우리 병원의 예약 관리 메뉴를 이용해 주세요.');return;}
    setBooking({hospital,department:department || hospital.departmentCode || hospital.departments?.[0]?.code || '01',reservation,draft});
    go('/booking');
  }
  function showRecommendation(hospital:Hospital,department?:string){
    setViewedRecommendation({hospital,department,chatRoute:route});
    go('/?scene=reasons');
  }
  function beforeSocialLogin(){
    const previous=readSocialReturn();
    const destination=AUTH_RETURN_ROUTES.includes(route)?route:(route==='/login'||route==='/auth/social/callback'?previous.route:'/mypage');
    sessionStorage.setItem(SOCIAL_RETURN,JSON.stringify({route:destination,booking:booking||previous.booking}));
    setSocialError('');
  }
  function completeLogin(result:AuthResult,destination?:string){
    setToken(result.token);setUser(result.user);setViewedRecommendation(undefined);setAuthReady(true);setAuthMode(null);setSocialError('');setPreferredLogin(undefined);setWithdrawalReturn('');
    queryClient.removeQueries({queryKey:['reservations']});queryClient.removeQueries({queryKey:['admin-members']});queryClient.removeQueries({queryKey:['hospital-manage']});
    if(needsSocialProfile(result.user)){setToast('');go('/complete-profile');return;}
    let next=destination||(AUTH_RETURN_ROUTES.includes(route)?route:'/mypage');
    if(!destination&&(route==='/login'||route==='/auth/social/callback')){
      const saved=readSocialReturn();setBooking(saved.booking);next=saved.route;
    }
    sessionStorage.removeItem(SOCIAL_RETURN);
    if(result.user.role!=='ADMIN'&&next.startsWith('/admin'))next='/mypage';
    if(result.user.role!=='HOSPITAL'&&next==='/hospital')next='/mypage';
    if(result.user.role==='HOSPITAL'){next='/hospital';setBooking(null);void queryClient.invalidateQueries({queryKey:['hospitals']});}
    go(next);setToast(`${result.user.name}님, 반가워요.`);
  }
  function completeSocialLogin(result:AuthResult){
    const saved=readSocialReturn();setBooking(saved.booking);completeLogin(result,saved.route);
  }
  function completePasswordChange(result:AuthResult,previousToken:string){
    if(getToken()!==previousToken)return;
    setToken(result.token);setUser(result.user);
  }
  function completeWithdrawal(result:DeleteMemberResult){
    clearToken();sessionStorage.removeItem(SOCIAL_RETURN);setUser(null);setBooking(null);setViewedRecommendation(undefined);
    setAuthMode(null);setPreferredLogin(undefined);setSocialError('');setWithdrawalReturn('');
    queryClient.clear();go('/');setToast(result.message);
  }
  async function logout(){try{await api('/auth/logout',{method:'POST'});}catch{/* Local session still ends. */}clearToken();sessionStorage.removeItem(SOCIAL_RETURN);setUser(null);setBooking(null);setViewedRecommendation(undefined);setWithdrawalReturn('');queryClient.removeQueries({queryKey:['reservations']});queryClient.removeQueries({queryKey:['admin-members']});queryClient.removeQueries({queryKey:['hospital-manage']});go('/');setToast(profilePending?'가입을 중단했어요. 다시 진행하려면 소셜 로그인을 시작해 주세요.':'로그아웃했어요.');}
  async function existingAccountLogin(provider:AuthProvider){
    const saved=readSocialReturn();
    await logout();
    sessionStorage.setItem(SOCIAL_RETURN,JSON.stringify(saved));
    setPreferredLogin(provider);setSocialError('');setToast('');go('/login');setAuthMode('login');
  }
  const active = route.startsWith('/chat')?'chat':route.startsWith('/reservations')?'reservations':(route==='/hospital'||route==='/mypage'&&user?.role==='HOSPITAL')?'hospital':route==='/mypage'?'mypage':route==='/admin/members'?'members':route==='/admin'?'admin':route.startsWith('/search')?'search':'home';
  return <><a className="skip-link" href="#main-content" onClick={e=>{e.preventDefault();document.getElementById('main-content')?.focus();}}>본문으로 이동</a><header className={homeVisible?'site-header home-header':'site-header'} inert={homeVisible&&headerHidden} aria-hidden={homeVisible&&headerHidden||undefined}><div className="header-inner">
    <a href="#/" className="brand" aria-label="메디픽 홈"><span className="brand-mark" aria-hidden="true"><Plus size={26} strokeWidth={4}/></span><span>메디픽</span></a>
    <nav className={mobileMenu?'nav open':'nav'} aria-label="주 메뉴">{profilePending?<a href="#/complete-profile" className="active">가입 정보 입력</a>:<>
      <a href="#/search" className={active==='search'?'active':''}>병원 찾기</a><a href="#/chat" className={active==='chat'?'active':''}>AI 상담</a>{user?.role!=='HOSPITAL'&&<a href="#/reservations" className={active==='reservations'?'active':''}>내 예약</a>}
      {user?.role==='HOSPITAL'?<a href="#/hospital" className={active==='hospital'?'active':''}>우리 병원 관리</a>:user&&<a href="#/mypage" className={active==='mypage'?'active':''}>마이페이지</a>}{user?.role==='ADMIN'&&<><a href="#/admin" className={active==='admin'?'active':''}>일정 관리</a><a href="#/admin/members" className={active==='members'?'active':''}>회원목록</a></>}
    </>}</nav>
    <div className="header-actions">
      {profilePending?<><span className="user-name signup-header-status">가입 정보 입력 중</span><button className="icon-button logout" onClick={logout} aria-label="가입 인증 종료"><LogOut size={19}/></button></>:user?<><a href={user.role==='HOSPITAL'?'#/hospital':'#/mypage'} className="user-name">{user.role==='HOSPITAL'?<Building2 size={16}/>:<UserRound size={16}/>}<span>{user.name}{user.role==='HOSPITAL'?'':'님'}</span></a><button className="icon-button logout" onClick={logout} aria-label="로그아웃"><LogOut size={19}/></button></>:<button className="button small dark" disabled={!authReady} onClick={()=>setAuthMode('login')}>{authReady?'로그인':'로그인 확인 중'}</button>}
      <button className="icon-button menu-button" aria-label={mobileMenu?'메뉴 닫기':'메뉴 열기'} aria-expanded={mobileMenu} onClick={()=>setMobileMenu(!mobileMenu)}>{mobileMenu?<X/>:<Menu/>}</button>
    </div>
  </div></header>
    <main id="main-content" tabIndex={-1}>
      {!authReady&&!isHome&&route!=='/auth/social/callback'?<div className="page-wrap"><Loading text="로그인 정보를 확인하고 있어요"/></div>:profilePending&&user?<SocialProfile key={user.id} user={user} onSuccess={completeSocialLogin} onCancel={logout} onExistingLogin={existingAccountLogin}/>:<>
      {isHome&&<><MedipickHome recommendation={homeScene?viewedRecommendation:undefined} initialScene={homeScene} onBooking={(h,d,draft)=>startBooking(h,d,undefined,draft)} onAccount={()=>go(user?.role==='HOSPITAL'?'/hospital':'/mypage')}/><FeaturedHospitals onBooking={startBooking}/></>}
      {route.startsWith('/search')&&<SearchPage key={route} route={route} onBooking={startBooking}/>}
      {chatRoute&&<div hidden={!route.startsWith('/chat')}><ChatPage key={chatRoute} route={chatRoute} onBooking={startBooking} onExplore={showRecommendation}/></div>}
      {route==='/booking'&&(user?.role==='HOSPITAL'?<EmptyPage icon={<Building2/>} title="병원 예약 관리 메뉴를 이용해 주세요" body="병원 계정에서는 소속 병원으로 들어온 예약을 확인하고 관리할 수 있어요." action="우리 병원 관리" onAction={()=>go('/hospital')}/>:booking?<BookingPage selection={booking} onDraft={(department,draft)=>setBooking(previous=>previous && {...previous,department,draft})} user={user} login={()=>setAuthMode('login')} onDone={()=>{setToast(booking.reservation?'예약 일정을 변경했어요.':'예약이 완료되었어요.');queryClient.invalidateQueries({queryKey:['reservations']});go('/reservations');}}/>:<EmptyPage icon={<CalendarDays/>} title="예약할 병원을 먼저 선택해 주세요" body="병원을 검색하거나 AI 상담에서 안내받은 병원의 예약을 시작할 수 있어요." action="병원 찾기" onAction={()=>go('/search')}/>)}
      {route==='/reservations'&&user?.role!=='HOSPITAL'&&<ReservationsPage user={user} ready={authReady} login={()=>setAuthMode('login')} onBooking={startBooking} notify={setToast}/>}
      {route==='/mypage'&&user?.role!=='HOSPITAL'&&<MyPage user={user} ready={authReady} login={()=>setAuthMode('login')} withdrawalReturn={withdrawalReturn} onWithdrawalReturnHandled={()=>setWithdrawalReturn('')} onWithdrawn={completeWithdrawal} onPasswordChanged={completePasswordChange}/>}
      {(route==='/hospital'||user?.role==='HOSPITAL'&&(route==='/mypage'||route==='/reservations'))&&<HospitalManagement user={user} login={()=>setAuthMode('login')} notify={setToast} onPasswordChanged={completePasswordChange}/>}
      {route==='/admin'&&<AdminPage user={user} login={()=>setAuthMode('login')} notify={setToast}/>}
      {route==='/admin/members'&&<AdminMembersPage user={user} ready={authReady} login={()=>setAuthMode('login')} notify={setToast}/>}
      {route==='/auth/social/callback'&&<SocialLoginCallback onSuccess={completeSocialLogin} onRetry={()=>{go('/login');setAuthMode('login');}}/>}
      {route==='/login'&&<EmptyPage icon={<UserRound/>} title="로그인하고 시작하세요" body="개인은 아이디·소셜 계정으로, 병원은 사업자등록번호로 로그인해 주세요." action="로그인하기" onAction={()=>setAuthMode('login')}/>}
      {route==='/complete-profile'&&!user&&<EmptyPage icon={<UserRound/>} title="로그인 후 가입을 이어서 진행해 주세요" body="소셜 계정으로 인증한 뒤 이름, 이메일, 전화번호를 입력할 수 있어요." action="로그인하기" onAction={()=>setAuthMode('login')}/>}
      {!isHome&&!['/hospital','/booking','/reservations','/mypage','/admin','/admin/members','/login','/auth/social/callback','/complete-profile'].includes(route)&&!route.startsWith('/search')&&!route.startsWith('/chat')&&<EmptyPage icon={<Search/>} title="페이지를 찾을 수 없어요" body="홈에서 다시 시작해 주세요." action="홈으로" onAction={()=>go('/')}/>}
      </>}
    </main>
    <footer className="site-footer"><div className="footer-inner"><div><strong><Plus size={19}/> 메디픽</strong><p>내 증상과 진료 목적에 맞는 병원 찾기</p></div><div className="footer-note"><span>백엔드 실무 프로젝트 · 김도형 · 박다윗</span><p>등록한 병원의 의료진과 진료 일정으로 예약을 관리합니다.</p></div></div></footer>
    {toast&&<div className="toast" role="status"><CheckCircle2 size={19}/>{toast}<button className="icon-button" onClick={()=>setToast('')} aria-label="알림 닫기"><X size={16}/></button></div>}
    {authMode&&authReady&&!profilePending&&<AuthDialog mode={authMode} setMode={setAuthMode} close={()=>{setAuthMode(null);setPreferredLogin(undefined);}} onSuccess={result=>completeLogin(result)} beforeSocialLogin={beforeSocialLogin} socialError={socialError} initialPreferredProvider={preferredLogin}/>}
    <div className="sr-only">AI 모델: {meta.data?.model || 'gpt-6.2-sol'}</div>
  </>;
}

function Notice({children,tone='info'}:{children:ReactNode;tone?:'info'|'warning'|'error'|'success'}){return <div className={`notice ${tone}`} role={tone==='error'?'alert':undefined}>{tone==='success'?<CheckCircle2 size={19}/>:tone==='error'?<AlertCircle size={19}/>:<Info size={19}/>}<div>{children}</div></div>;}
function Loading({text='불러오는 중이에요'}:{text?:string}){return <div className="loading" role="status"><LoaderCircle className="spin" size={25}/><span>{text}</span></div>;}
function ErrorState({error,retry}:{error:unknown;retry?:()=>void}){return <div className="error-state" role="alert"><AlertCircle/><p>{error instanceof Error?error.message:'정보를 불러오지 못했어요.'}</p>{retry&&<button className="button secondary small" onClick={retry}><RefreshCw size={16}/>다시 시도</button>}</div>;}
function EmptyPage({icon,title,body,action,onAction}:{icon:ReactNode;title:string;body:string;action:string;onAction:()=>void}){return <div className="page-wrap"><div className="empty-page"><div className="empty-icon">{icon}</div><h1>{title}</h1><p>{body}</p><button className="button primary" onClick={onAction}>{action}</button></div></div>;}
function SourceBadge({source}:{source:Hospital['source']}){return <span className={`source-badge ${source?.toLowerCase()}`}>{source==='LIVE'?'공공데이터':source==='CACHE'?'저장된 공공데이터':'시연용 샘플'}</span>;}
function HospitalCard({hospital,onBooking,onExplore,compact=false,department}:{hospital:Hospital;onExplore?:(hospital:Hospital,department?:string)=>void;onBooking:(hospital:Hospital,department?:string)=>void;compact?:boolean;department?:string}){
  const sources=safeRecommendationSources(hospital.recommendationSources);
  const distance=straightLineDistance(hospital.distanceKm);
  return <article className={`hospital-card ${compact?'compact':''}`}>
    <div className="hospital-card-top"><div className="hospital-symbol"><Building2 size={24}/></div><SourceBadge source={hospital.source}/></div>
    <div className="hospital-category">{hospital.departmentName || hospital.departments?.slice(0,2).map(d=>d.name).join(' · ') || '진료과 정보 확인'}</div>
    <h3>{hospital.name}</h3>
    {(hospital.hospitalType||hospital.specialty)&&<div className="hospital-care-tags">{hospital.hospitalType&&<span>{hospital.hospitalType}</span>}{hospital.specialty&&<span>상담 분야 · {hospital.specialty}</span>}</div>}
    <p className="icon-text"><MapPin size={16}/><span>{hospital.address || '주소 정보 미제공'}</span></p>
    {distance&&<p className="hospital-distance"><MapPin size={14}/><span>{distance}<small>이동 시간·실제 이동 거리와 달라요.</small></span></p>}
    {hospital.tel&&<p className="icon-text"><Phone size={15}/><a href={`tel:${hospital.tel}`}>{hospital.tel}</a></p>}
    {hospital.recommendationReason&&<p className="recommendation-reason"><Sparkles size={14}/>{hospital.recommendationReason}</p>}
    {sources.length>0&&<div className="recommendation-sources"><strong>추천에 참고한 자료</strong><p className="recommendation-source-note">AI가 검색 자료를 요약한 안내예요. 후기·SNS는 개인 경험이며 치료 성적을 보장하지 않아요.</p><ul>{sources.map(source=><li key={source.url}><div className="recommendation-source-link"><span className={`recommendation-source-kind ${(source.kind||'WEB').toLowerCase()}`}>{source.label}</span><a href={source.url} target="_blank" rel="noopener noreferrer">{source.title}<ExternalLink size={12}/></a></div><span>조회 · {fetchedLabel(source.checkedAt)}</span></li>)}</ul></div>}
    {hospital.notice&&<p className="hospital-notice">{hospital.notice}</p>}
    <div className="hospital-card-bottom"><span><ShieldCheck size={14}/>{hospital.source==='SAMPLE'?'가상 병원 데이터':'심평원 제공 정보'}</span><div className="hospital-card-actions">{onExplore&&<button className="button secondary small" onClick={()=>onExplore(hospital,department)}>추천 이유·지도 보기</button>}<button className="button outline small" onClick={()=>onBooking(hospital,department)}>상세·예약</button></div></div>
  </article>;
}

function FeaturedHospitals({onBooking}:{onBooking:(hospital:Hospital,department?:string)=>void}){
  const section=useRef<HTMLElement>(null);
  const [visible,setVisible]=useState(false);
  useEffect(()=>{
    if(!section.current)return;
    const observer=new IntersectionObserver(([entry])=>{
      if(entry.isIntersecting){setVisible(true);observer.disconnect();}
    },{rootMargin:'500px'});
    observer.observe(section.current);
    return ()=>observer.disconnect();
  },[]);
  const hospitals=useQuery({queryKey:['hospitals','home','registered'],queryFn:()=>api<HospitalResult>('/hospitals'),enabled:visible,staleTime:120_000});
  const defaultHospital=useQuery({queryKey:['hospitals','search',HOME_HOSPITAL_FILTERS],queryFn:()=>api<HospitalResult>('/hospitals?'+queryString(HOME_HOSPITAL_FILTERS)),enabled:visible,staleTime:120_000});
  const firstHospital=defaultHospital.data?.items[0];
  const featuredItems=[...(firstHospital?[firstHospital]:[]),...(hospitals.data?.items||[]).filter(h=>h.id!==firstHospital?.id)].slice(0,3);
  return <section ref={section} className="featured-hospitals">
    <div className="section-heading"><div><h2>메디픽에 등록한 병원</h2><p>병원이 직접 등록한 의료진과 진료 일정을 확인해 보세요.</p></div><a className="text-button" href="#/search">전체 병원 찾기</a></div>
    {!visible||hospitals.isLoading||defaultHospital.isLoading?<Loading text="병원 정보를 확인하고 있어요"/>:hospitals.isError?<ErrorState error={hospitals.error} retry={()=>hospitals.refetch()}/>:<>
      {hospitals.data?.message&&<Notice tone={hospitals.data.source==='SAMPLE'?'warning':'info'}>{hospitals.data.message}</Notice>}
      <div className="hospital-grid">{featuredItems.map(h=><HospitalCard hospital={h} key={h.id} onBooking={onBooking}/>)}</div>
      {featuredItems.length===0&&<p className="empty-inline">아직 메디픽에 등록한 병원이 없어요. 병원 회원가입을 완료하면 이곳에 표시돼요.</p>}
    </>}
  </section>;
}
function SearchPage({route,onBooking}:{route:string;onBooking:(hospital:Hospital,department?:string)=>void}){
  const params = new URLSearchParams(route.split('?')[1]);
  const initialRegion=params.get('region')||'';
  const [query,setQuery]=useState(params.get('query')||''); const [region,setRegion]=useState(initialRegion); const [department,setDepartment]=useState(params.get('department')||'');
  const [filters,setFilters]=useState({query:params.get('query')||'',region:initialRegion,department:params.get('department')||''});
  const hospitals=useQuery({queryKey:['hospitals','search',filters],queryFn:()=>api<HospitalResult>(`/hospitals?${queryString(filters)}`)});
  return <div className="page-wrap"><div className="page-heading"><h1>나에게 맞는 병원 찾기</h1><p>메디픽에 등록한 병원을 이름, 지역, 진료과로 찾아보세요.</p></div><form className="search-filters panel" onSubmit={e=>{e.preventDefault();setFilters({query,region,department});}}><label className="field search-query">병원명<div className="input-icon"><Search size={19}/><input placeholder="병원 이름을 입력해 주세요" value={query} onChange={e=>setQuery(e.target.value)}/></div></label><label className="field">희망 지역<div className="input-icon"><MapPin size={18}/><input placeholder="예) 서울, 종로구" value={region} onChange={e=>setRegion(e.target.value)}/></div></label><label className="field">진료과<select value={department} onChange={e=>setDepartment(e.target.value)}><option value="">전체 진료과</option>{DEPARTMENTS.map(d=><option key={d.code} value={d.code}>{d.name}</option>)}</select></label><button className="button primary" type="submit"><Search size={18}/>검색</button></form><div className="search-results-heading"><h2>검색 결과 <span>{hospitals.data?.items.length ?? 0}</span></h2>{hospitals.data&&<SourceBadge source={hospitals.data.source}/>}</div>{hospitals.isLoading?<Loading text="조건에 맞는 병원을 찾고 있어요"/>:hospitals.isError?<ErrorState error={hospitals.error} retry={()=>hospitals.refetch()}/>:<>{hospitals.data?.message&&<Notice tone={hospitals.data.source==='SAMPLE'?'warning':'info'}>{hospitals.data.message}</Notice>}{hospitals.data?.items.length?<div className="hospital-grid">{hospitals.data.items.map(h=><HospitalCard key={h.id} hospital={h} onBooking={onBooking} department={filters.department}/>)}</div>:<div className="empty-inline"><Search size={32}/><h3>조건에 맞는 병원을 찾지 못했어요</h3><p>병원명 일부를 입력하거나 지역·진료과 조건을 줄여 보세요.</p></div>}</>}<p className="source-footnote">병원 정보는 건강보험심사평가원 제공 자료를 사용합니다. 실제 방문 전 진료 여부를 병원에 확인해 주세요.</p></div>;
}

function ChatPage({route,onBooking,onExplore}:{route:string;onExplore:(hospital:Hospital,department?:string)=>void;onBooking:(hospital:Hospital,department?:string)=>void}){
  const initial = (new URLSearchParams(route.split('?')[1]).get('message')||'').slice(0,CHAT_MESSAGE_LIMIT);
  const [input,setInput]=useState(initial);
  const [messages,setMessages]=useState<Message[]>([]);
  const [result,setResult]=useState<ChatResult|null>(null);
  const [location,setLocation]=useState<ChatLocation|null>(null);
  const [locationPending,setLocationPending]=useState(false);
  const [locationMessage,setLocationMessage]=useState('');
  const [conversationNotice,setConversationNotice]=useState('');
  const endRef = useRef<HTMLDivElement>(null);
  const lastRequest=useRef<ChatRequest|null>(null);
  const active=useRef(true);
  const mutation=useMutation({
    mutationFn:(request:ChatRequest)=>api<ChatResult>('/chat',{method:'POST',body:JSON.stringify(request)}),
    onSuccess:(data,request)=>{
      if(!active.current)return;
      setResult(data);
      setMessages([...request.messages,{role:'assistant',content:data.reply}]);
    }
  });
  const emergency=isEmergencyResult(result);
  const recommendations=chatRecommendations(result);
  const options=mutation.isPending?[]:chatFollowUpOptions(result);
  const busy=mutation.isPending||locationPending;
  const capacityNotice=chatConversationLimit(messages,input)||conversationNotice;
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  useEffect(()=>{endRef.current?.scrollIntoView({behavior:'smooth',block:'nearest'});},[messages,mutation.isPending,result,capacityNotice]);
  function sendText(text:string,nextLocation:ChatLocation|null=location){
    const content=text.trim();
    if(!content||content.length>CHAT_MESSAGE_LIMIT||busy)return;
    const limit=chatConversationLimit(messages,content);
    if(limit){setConversationNotice(limit);return;}
    const request=chatRequest([...messages,{role:'user',content}],nextLocation);
    setMessages(request.messages);setResult(null);setInput('');setConversationNotice('');
    lastRequest.current=request;mutation.mutate(request);
  }
  function send(event:FormEvent){event.preventDefault();sendText(input);}
  function retry(){
    if(!lastRequest.current||busy)return;
    setResult(null);mutation.mutate(lastRequest.current);
  }
  function reset(){
    setMessages([]);setResult(null);setInput('');setLocation(null);setLocationMessage('');setConversationNotice('');
    lastRequest.current=null;mutation.reset();
  }
  function useCurrentLocation(){
    if(busy)return;
    if(!navigator.geolocation){setLocationMessage('이 브라우저에서는 위치를 사용할 수 없어요. 원하는 시·구·동을 직접 입력해 주세요.');return;}
    setLocationPending(true);setLocationMessage('브라우저에서 위치 사용을 허용하면 가까운 병원을 찾는 데 사용해요.');
    navigator.geolocation.getCurrentPosition(position=>{
      if(!active.current)return;
      setLocationPending(false);
      const nextLocation=chatLocation(position.coords.latitude,position.coords.longitude);
      if(!nextLocation){setLocationMessage('국내 병원만 안내할 수 있어요. 원하는 국내 지역을 직접 입력해 주세요.');return;}
      const selection=chatLocationSelection(result);
      setLocation(nextLocation);setLocationMessage(selection.message);
      if(selection.reply)sendText(selection.reply,nextLocation);
    },error=>{
      if(!active.current)return;
      setLocationPending(false);
      setLocationMessage(error.code===1?'위치 사용을 허용하지 않았어요. 원하는 시·구·동을 직접 입력해 주세요.':'위치를 가져오지 못했어요. 다시 시도하거나 원하는 지역을 직접 입력해 주세요.');
    },{enableHighAccuracy:false,maximumAge:300000,timeout:10000});
  }
  function removeLocation(){
    setLocation(null);setResult(null);setLocationMessage('현재 위치 사용을 해제했어요. 원하는 지역을 직접 입력해 주세요.');
    if(lastRequest.current)lastRequest.current=chatRequest(lastRequest.current.messages,null);
  }
  return <div className="page-wrap chat-page">
    <div className="page-heading compact-heading"><div><h1>편하게 이야기해 주세요</h1><p>증상과 진료 목적을 이야기하면, 필요한 진료 분야와 병원 후보를 함께 살펴봐요.</p></div><button className="button secondary small" onClick={reset} disabled={busy}><RefreshCw size={16}/>새 상담</button></div>
    <Notice>AI 안내는 진단이 아닌 진료과 안내입니다. 증상이 위급하거나 응급 상황이면 119 또는 응급실을 이용해 주세요.</Notice>
    <div className="chat-layout"><section className="chat-panel panel">
      <div className="chat-panel-heading"><span className="assistant-icon"><Sparkles size={22}/></span><div><strong>AI 진료과 안내</strong><small>증상과 희망하는 진료에 맞춰 함께 찾아요</small></div><span className="model-label">6.2 Sol</span></div>
      <div className="messages" aria-live="polite" aria-busy={mutation.isPending}>
        <div className="message assistant"><span className="message-avatar"><Plus size={20}/></span><div className="message-bubble"><strong>안녕하세요. 메디픽이에요.</strong><p>어디가 불편하신가요? 증상이 언제 시작됐는지, 더 심해졌는지, 어떤 진료를 받고 싶은지 편하게 이야기해 주세요. 필요한 내용을 하나씩 여쭤볼게요.</p><p className="message-example">“사흘 전부터 속이 쓰려요. 어제보다 심해져서 처음 진료를 받아보려 해요.”</p></div></div>
        {messages.map((message,i)=><div key={i} className={`message ${message.role}`}>{message.role==='assistant'&&<span className="message-avatar"><Plus size={20}/></span>}<div className="message-bubble">{message.content}</div></div>)}
        {mutation.isPending&&<div className="message assistant"><span className="message-avatar"><Plus size={20}/></span><div className="message-bubble chat-working" role="status"><div className="thinking" aria-hidden="true"><span/><span/><span/></div><p>상담 내용을 살펴보고 있어요. 병원과 웹 출처를 찾는 경우에는 조금 더 걸릴 수 있어요.</p></div></div>}
        {mutation.isError&&<div className="chat-error"><Notice tone="error">{mutation.error.message}</Notice><button className="text-button" onClick={retry} disabled={busy}>같은 내용으로 다시 시도</button></div>}
        {options.length>0&&<div className="chat-follow-up"><span>선택하거나 아래에 자유롭게 답해 주세요.</span><div>{options.map(option=><button key={option} type="button" className="chat-choice" disabled={busy} onClick={()=>sendText(option)}>{option}<ChevronRight size={14}/></button>)}</div></div>}
        {capacityNotice&&<div className="chat-conversation-limit"><Notice tone="warning">{capacityNotice} 현재 대화와 입력은 그대로 남아 있어요. 새 상담에서는 증상과 진료 목적을 다시 알려주세요.</Notice><button type="button" className="button secondary small" onClick={reset} disabled={busy}><RefreshCw size={14}/>새 상담 시작</button></div>}
        <div ref={endRef}/>
      </div>
      {!emergency&&<div className="chat-location-control"><div><button type="button" className="text-button" disabled={busy} onClick={useCurrentLocation}>{locationPending?<LoaderCircle className="spin" size={14}/>:<MapPin size={14}/>} {location?'현재 위치 다시 확인':result?.carePreference==='SPECIALIST'?'내 위치를 거리 표시 기준으로 사용':'내 위치로 가까운 병원 찾기'}</button>{location&&<button type="button" className="text-button location-clear" onClick={removeLocation} disabled={busy}>위치 해제</button>}</div><p role="status">{locationMessage||'위치 사용은 선택이에요. 원하는 지역을 직접 입력해도 돼요.'}</p></div>}
      <form className="chat-compose" onSubmit={send}><label htmlFor="chat-input" className="sr-only">상담 메시지</label><textarea id="chat-input" rows={2} maxLength={CHAT_MESSAGE_LIMIT} placeholder="증상, 달라진 점, 원하는 진료를 편하게 이야기해 주세요" value={input} onChange={e=>{setInput(e.target.value);setConversationNotice('');}} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();send(e);}}}/><button className="button primary send-button" disabled={!input.trim()||busy||!!capacityNotice} type="submit" aria-label="메시지 보내기">{mutation.isPending?<LoaderCircle className="spin" size={20}/>:<Send size={20}/>}</button></form>
      <p className="chat-privacy"><ShieldCheck size={14}/>증상 원문과 현재 위치는 서버에 기본 저장하지 않아요.</p>
    </section><aside className="chat-results">
      <div className="chat-results-title"><HospitalIcon size={21}/><h2>함께 살펴볼 병원</h2>{recommendations.length>0&&<span className="count-pill">{recommendations.length}</span>}</div>
      {result?.notice&&<Notice tone={result.mode==='FALLBACK'?'warning':'info'}>{result.notice}</Notice>}
      {result?.mode==='FALLBACK'&&!result.notice&&<Notice tone="warning">AI 연결이 원활하지 않아 기본 안내를 표시하고 있어요. 직접 검색도 이용할 수 있어요.</Notice>}
      {result?.careGuidance&&<Notice tone={result.promptCare?'warning':'info'}>{result.careGuidance}</Notice>}
      {emergency?<Notice tone="error">응급 상황이 의심되면 병원을 비교하거나 예약하기보다 즉시 도움을 요청하는 것이 우선이에요.</Notice>:recommendations.length>0?<><div className="chat-results-context">{result?.specialty&&<strong>상담 분야 · {result.specialty}</strong>}<p>{result?.carePreference==='NEARBY'?(location?'현재 위치와의 직선거리를 참고한 후보예요. 실제 이동 시간과 진료 가능 여부는 별도로 확인해 주세요.':'희망 지역의 병원 후보예요. 현재 위치를 사용하지 않아 가까운 순서와는 다를 수 있어요.'):'추천 이유와 출처를 함께 살펴보세요. 병원 간 우열이나 치료 결과를 보장하지 않아요.'}</p></div><div className="chat-hospital-list">{recommendations.map(h=><HospitalCard key={h.id} hospital={h} compact onBooking={onBooking} onExplore={onExplore} department={h.departmentCode}/>)}</div><p className="chat-booking-note">메디픽에 등록한 병원의 의료진과 진료 일정으로 예약할 수 있어요.</p></>:<div className="recommendation-empty"><div className="recommendation-art"><HospitalIcon size={42}/><Search size={24}/></div><h3>{mutation.isPending?'상담에 맞는 안내를 준비하고 있어요':result?.needsMoreInfo?'조금만 더 알려주세요':result?'조건에 맞는 후보를 아직 찾지 못했어요':'이야기를 듣고 찾아드릴게요'}</h3><p>{mutation.isPending?'새로운 답변을 준비하는 동안 잠시 기다려 주세요.':result?.needsMoreInfo?'증상과 진료 선호를 확인한 뒤 병원 후보를 보여드려요.':'대화로 조건을 더 알려주시거나 직접 병원을 검색해 보세요.'}</p>{!mutation.isPending&&<button className="button outline small" onClick={()=>go('/search')}>직접 병원 찾기</button>}</div>}
      {!emergency&&<div className="chat-tip"><ShieldCheck size={20}/><p>저장된 공공 병원 정보를 바탕으로 안내해요.<br/>전문 진료 후보에는 참고한 웹 출처와 조회 시각을 함께 표시해요.</p></div>}
    </aside></div>
  </div>;
}

function BookingPage({selection,onDraft,user,login,onDone}:{selection:BookingSelection;onDraft:(department:string,draft:BookingDraft)=>void;user:User|null;login:()=>void;onDone:()=>void}){
  const queryClient=useQueryClient();
  const department=selection.department;
  const doctorId=selection.draft?.doctorId??selection.reservation?.doctorId??'';
  const date=selection.draft?.date||today(1);
  const slotId=selection.draft?.slotId||'';
  const [confirmed,setConfirmed]=useState(false);
  const [idempotency,setIdempotency]=useState(crypto.randomUUID());
  const [step,setStep]=useState(selection.draft?.slotId?3:selection.draft?.doctorId?2:1);
  const hospitalQuery=useQuery({queryKey:['hospital',selection.hospital.id],queryFn:()=>api<Hospital>(`/hospitals/${encodeURIComponent(selection.hospital.id)}`)});
  const hospital=hospitalQuery.data || selection.hospital;
  const doctors=useQuery({queryKey:['doctors',hospital.id,department],queryFn:()=>api<{items:Doctor[]}>(`/hospitals/${encodeURIComponent(hospital.id)}/doctors?department=${encodeURIComponent(department)}`)});
  const slots=useQuery({queryKey:['slots',doctorId,date],queryFn:()=>api<{items:Slot[]}>(`/slots?${queryString({doctorId,date})}`),enabled:!!doctorId});
  const doctor=doctors.data?.items.find(d=>d.id===doctorId);
  const slot=slots.data?.items.find(s=>s.id===slotId&&!s.closed&&s.remaining>0&&new Date(s.startAt)>new Date());
  const save=useMutation({mutationFn:()=>api<Reservation>(selection.reservation?`/reservations/${selection.reservation.id}`:'/reservations',{method:selection.reservation?'PATCH':'POST',headers:{'Idempotency-Key':idempotency},body:JSON.stringify({slotId})}),onSuccess:()=>{queryClient.invalidateQueries({queryKey:['slots']});onDone();},onError:()=>queryClient.invalidateQueries({queryKey:['slots',doctorId,date]})});
  function changeDraft(nextDepartment:string,nextDoctorId:string,nextDate:string,nextSlotId?:string){
    onDraft(nextDepartment,{doctorId:nextDoctorId,date:nextDate,slotId:nextSlotId});
    setConfirmed(false);setIdempotency(crypto.randomUUID());save.reset();
  }
  const departmentOptions=hospital.departments?.length?hospital.departments:DEPARTMENTS;
  return <div className="page-wrap booking-page"><button className="back-link" onClick={()=>go('/search')}><ChevronLeft size={18}/>병원 목록</button><div className="booking-heading"><div><SourceBadge source={hospital.source}/><h1>{hospital.name}</h1><p className="icon-text"><MapPin size={17}/>{hospital.address}</p></div><span className="booking-mode"><CalendarCheck2 size={19}/>{selection.reservation?'예약 일정 변경':'예약'}</span></div><Notice>병원이 등록한 의료진과 진료 시간표를 확인하고 예약해 주세요.</Notice><div className="booking-layout"><div className="booking-main"><HospitalLocation hospital={hospital}/><div className="stepper" aria-label="예약 진행"><span className={step>=1?'current':''}><b>1</b>의료진 선택</span><ChevronRight size={16}/><span className={step>=2?'current':''}><b>2</b>일정 선택</span><ChevronRight size={16}/><span className={step>=3?'current':''}><b>3</b>최종 확인</span></div><section className="panel booking-section"><div className="section-title-row"><h2>진료과와 의료진을 선택해 주세요</h2><Stethoscope size={23}/></div><label className="field department-select">진료과<select value={department} onChange={e=>{changeDraft(e.target.value,'',date);setStep(1);}}>{!departmentOptions.some(d=>d.code===department)&&<option value={department}>{departmentName(department)}</option>}{departmentOptions.map(d=><option key={d.code} value={d.code}>{d.name}</option>)}</select></label>{doctors.isLoading?<Loading/>:doctors.isError?<ErrorState error={doctors.error} retry={()=>doctors.refetch()}/>:<div className="doctor-grid">{doctors.data?.items.map((d,i)=><button key={d.id} className={`doctor-card ${doctorId===d.id?'selected':''}`} onClick={()=>{changeDraft(department,d.id,date);setStep(2);}}><span className="doctor-portrait" aria-hidden="true"><DoctorPortrait index={i}/></span><strong>{d.name}<small>{d.isVirtual?'가상 의료진':'의료진'}</small></strong><span>{d.specialty||departmentName(d.departmentCode)}</span><span className="select-circle">{doctorId===d.id&&<Check size={14}/>}</span></button>)}</div>}{doctors.data?.items.length===0&&<p className="empty-inline">선택 가능한 의료진이 없어요.</p>}</section><section className="panel booking-section"><div className="section-title-row"><h2>편한 날짜와 시간을 골라 주세요</h2><CalendarDays size={23}/></div><label className="field date-field">진료 날짜<input type="date" min={today()} max={today(90)} value={date} onChange={e=>{if(e.target.value&&e.target.validity.valid)changeDraft(department,doctorId,e.target.value);}}/></label><div className="date-shortcuts">{Array.from({length:7},(_,i)=>today(i+1)).map(day=><button key={day} className={date===day?'selected':''} onClick={()=>{changeDraft(department,doctorId,day);}}><span>{new Intl.DateTimeFormat('ko-KR',{weekday:'short'}).format(new Date(`${day}T12:00:00+09:00`))}</span><strong>{day.slice(8)}</strong></button>)}</div>{!doctorId?<div className="slot-placeholder"><Clock3 size={26}/><p>의료진을 선택하면 예약 가능한 시간이 보여요.</p></div>:slots.isLoading?<Loading text="시간대별 잔여 정원을 확인하고 있어요"/>:slots.isError?<ErrorState error={slots.error} retry={()=>slots.refetch()}/>:slots.data?.items.length?<div className="slot-grid">{slots.data.items.map(s=>{const unavailable=s.closed||s.remaining<=0||new Date(s.startAt)<new Date();return <button disabled={unavailable} key={s.id} className={`slot-button ${slotId===s.id?'selected':''}`} onClick={()=>{changeDraft(department,doctorId,date,s.id);setStep(3);}}><strong>{timeLabel(s.startAt)}</strong><span>{s.closed?'휴진':s.remaining<=0?'예약 마감':new Date(s.startAt)<new Date()?'지난 시간':`잔여 ${s.remaining}명`}</span></button>;})}</div>:<div className="empty-inline">이 날짜에 등록된 진료 일정이 없어요. 다른 날짜를 선택해 주세요.</div>}</section><section className="panel evidence-panel"><h2><ShieldCheck size={20}/>병원 정보와 출처</h2>{hospital.tel&&<p className="icon-text"><Phone size={16}/><a href={`tel:${hospital.tel}`}>{hospital.tel}</a></p>}{hospitalQuery.isError&&<Notice tone="warning">상세 정보를 불러오지 못해 검색 결과의 기본 정보를 표시하고 있어요.</Notice>}{hospital.evidence?.length?<ul className="evidence-list">{hospital.evidence.map((e,i)=><li key={i}><strong>{e.label||e.kind}</strong><span>{typeof e.value==='string'?e.value:JSON.stringify(e.value)}</span>{e.sourceUrl&&<a href={e.sourceUrl} target="_blank" rel="noreferrer">출처 확인<ExternalLink size={13}/></a>}{e.basePeriod&&<small>기준: {e.basePeriod}</small>}</li>)}</ul>:<p className="muted">추가로 확인된 진료 근거가 없어요.</p>}<p className="source-footnote">조회 시각: {fetchedLabel(hospital.fetchedAt)} · {hospital.source==='SAMPLE'?'시연용 샘플 데이터':'건강보험심사평가원'}</p></section></div><aside className="booking-summary panel"><div className="booking-date-stub" aria-hidden="true"><span>{date.slice(0,4)}년 {Number(date.slice(5,7))}월</span><b>{date.slice(8)}</b></div><h2>{selection.reservation?'변경할 예약 정보':'예약 정보 확인'}</h2><dl><div><dt>병원</dt><dd>{hospital.name}</dd></div><div><dt>진료과</dt><dd>{departmentOptions.find(d=>d.code===department)?.name||departmentName(department)}</dd></div><div><dt>의료진</dt><dd>{doctor?doctor.name:'선택해 주세요'}</dd></div><div><dt>날짜</dt><dd>{slot?dateLabel(slot.startAt):'선택해 주세요'}</dd></div><div><dt>시간</dt><dd>{slot?timeLabel(slot.startAt):'선택해 주세요'}</dd></div><div><dt>예약자</dt><dd>{user?.name||'로그인 필요'}</dd></div></dl>{selection.reservation&&<div className="previous-booking">기존 일정<br/>{dateLabel(selection.reservation.startAt)} {timeLabel(selection.reservation.startAt)}</div>}<label className="check-label"><input type="checkbox" checked={confirmed} disabled={!slot} onChange={e=>setConfirmed(e.target.checked)}/><span>병원, 의료진, 날짜와 시간을 확인했으며 선택한 일정으로 예약할게요.</span></label>{slotId&&slots.isSuccess&&!slot&&<Notice tone="warning">선택한 시간의 예약이 마감됐어요. 다른 일정을 골라 주세요.</Notice>}{save.isError&&<Notice tone="error">{save.error.message}</Notice>}{user?<button className="button primary full" disabled={!doctor||!slot||!confirmed||save.isPending} onClick={()=>save.mutate()}>{save.isPending?<><LoaderCircle size={18} className="spin"/>예약 처리 중</>:selection.reservation?'일정 변경 확정':'예약 확정'}</button>:<button className="button primary full" onClick={login}>로그인하고 예약하기</button>}<p className="summary-note"><ShieldCheck size={15}/>예약 전 잔여 정원을 다시 확인해요.</p></aside></div></div>;
}

function ReservationsPage({user,ready,login,onBooking,notify}:{user:User|null;ready:boolean;login:()=>void;onBooking:(hospital:Hospital,department?:string,reservation?:Reservation)=>void;notify:(message:string)=>void}){
  const queryClient=useQueryClient();
  const [tab,setTab]=useState<'RESERVED'|'CANCELED'>('RESERVED');
  const [cancelTarget,setCancelTarget]=useState<Reservation|null>(null);
  const [changeError,setChangeError]=useState('');
  const [changing,setChanging]=useState('');
  const reservations=useQuery({queryKey:['reservations',user?.id],queryFn:()=>api<{items:Reservation[]}>('/reservations'),enabled:!!user});
  const cancel=useMutation({mutationFn:(r:Reservation)=>api<Reservation>(`/reservations/${r.id}`,{method:'DELETE',headers:{'Idempotency-Key':`cancel-${r.id}`}}),onSuccess:()=>{setCancelTarget(null);queryClient.invalidateQueries({queryKey:['reservations']});queryClient.invalidateQueries({queryKey:['slots']});notify('예약을 취소했어요.');}});
  async function change(r:Reservation){setChanging(r.id);setChangeError('');try{const hospital=await api<Hospital>(`/hospitals/${r.hospitalId}`);const dept=DEPARTMENTS.find(d=>d.name===r.departmentName)?.code||hospital.departmentCode||'01';onBooking(hospital,dept,r);}catch(e){setChangeError(e instanceof Error?e.message:'병원 정보를 불러오지 못했어요.');}finally{setChanging('');}}
  if(!ready)return <div className="page-wrap"><Loading/></div>;
  if(!user)return <EmptyPage icon={<ClipboardList/>} title="내 예약을 한눈에 확인하세요" body="로그인하면 예약한 일정을 확인하고 변경하거나 취소할 수 있어요." action="로그인하기" onAction={login}/>;
  const items=reservations.data?.items.filter(r=>r.status===tab)||[];
  const upcoming=reservations.data?.items.filter(r=>r.status==='RESERVED').length||0;
  return <div className="page-wrap reservations-page"><div className="page-heading"><span className="eyebrow">MY APPOINTMENTS</span><h1>{user.name}님의 예약</h1><p>예약한 일정을 확인하고, 필요한 경우 변경하거나 취소하세요.</p></div><div className="reservation-overview"><span className="overview-icon"><CalendarCheck2 size={30}/></span><div><span>나의 예약</span><h2>예약 중인 일정 <strong>{upcoming}건</strong></h2></div><button className="button secondary" onClick={()=>go('/search')}><Plus size={17}/>새 예약</button></div><div className="tabs" role="tablist" aria-label="예약 상태"><button role="tab" aria-selected={tab==='RESERVED'} className={tab==='RESERVED'?'selected':''} onClick={()=>setTab('RESERVED')}>예약 중 <span>{upcoming}</span></button><button role="tab" aria-selected={tab==='CANCELED'} className={tab==='CANCELED'?'selected':''} onClick={()=>setTab('CANCELED')}>취소 내역 <span>{reservations.data?.items.filter(r=>r.status==='CANCELED').length||0}</span></button></div>{changeError&&<Notice tone="error">{changeError}</Notice>}{reservations.isLoading?<Loading text="예약 내역을 불러오고 있어요"/>:reservations.isError?<ErrorState error={reservations.error} retry={()=>reservations.refetch()}/>:items.length?<div className="reservation-list">{items.map(r=><article className="reservation-card panel" key={r.id}><div className="reservation-date"><span>{new Date(r.startAt).toLocaleDateString('ko-KR',{month:'short',timeZone:'Asia/Seoul'})}</span><strong>{new Date(r.startAt).toLocaleDateString('ko-KR',{day:'numeric',timeZone:'Asia/Seoul'}).replace('일','')}</strong><small>{new Date(r.startAt).toLocaleDateString('ko-KR',{weekday:'long',timeZone:'Asia/Seoul'})}</small></div><div className="reservation-detail"><span className={`status-badge ${r.status==='CANCELED'?'canceled':''}`}>{r.status==='CANCELED'?'취소 완료':'예약 완료'} · 예약</span><h2>{r.hospitalName}</h2><p>{r.departmentName} · {r.doctorName}</p><p className="icon-text"><Clock3 size={15}/>{dateLabel(r.startAt)} {timeLabel(r.startAt)}</p><small className="reservation-number">예약 번호 {r.id.slice(0,8).toUpperCase()}</small></div>{r.status==='RESERVED'&&<div className="reservation-actions"><button className="button outline small" onClick={()=>change(r)} disabled={!!changing}>{changing===r.id?<LoaderCircle className="spin" size={16}/>:<CalendarDays size={16}/>}일정 변경</button><button className="button quiet small" onClick={()=>{cancel.reset();setCancelTarget(r);}}>예약 취소</button></div>}</article>)}</div>:<div className="empty-inline reservation-empty"><CalendarDays size={39}/><h3>{tab==='RESERVED'?'아직 예약한 일정이 없어요':'취소한 예약이 없어요'}</h3><p>{tab==='RESERVED'?'원하는 병원과 시간을 선택해 첫 예약을 해보세요.':'취소한 예약은 여기에 표시됩니다.'}</p>{tab==='RESERVED'&&<button className="button primary" onClick={()=>go('/search')}>병원 찾기</button>}</div>}<Notice>병원에서 일정을 변경하거나 취소할 수 있어요. 방문 전에 최신 예약 상태를 확인해 주세요.</Notice>{cancelTarget&&<Dialog title="예약을 취소할까요?" close={()=>{if(!cancel.isPending)setCancelTarget(null);}}><p className="dialog-copy">{cancelTarget.hospitalName}<br/>{dateLabel(cancelTarget.startAt)} {timeLabel(cancelTarget.startAt)}</p><p className="muted">취소한 예약은 다시 예약해야 이용할 수 있어요.</p>{cancel.isError&&<Notice tone="error">{cancel.error.message}</Notice>}<div className="dialog-actions"><button className="button secondary" onClick={()=>setCancelTarget(null)} disabled={cancel.isPending}>유지하기</button><button className="button danger" onClick={()=>cancel.mutate(cancelTarget)} disabled={cancel.isPending}>{cancel.isPending?'취소 중':'예약 취소'}</button></div></Dialog>}</div>;
}

function MyPage({user,ready,login,withdrawalReturn,onWithdrawalReturnHandled,onWithdrawn,onPasswordChanged}:{user:User|null;ready:boolean;login:()=>void;withdrawalReturn:string;onWithdrawalReturnHandled:()=>void;onWithdrawn:(result:DeleteMemberResult)=>void;onPasswordChanged:(result:AuthResult,previousToken:string)=>void}){
  if(!ready)return <div className="page-wrap"><Loading text="내 정보를 확인하고 있어요"/></div>;
  if(!user)return <EmptyPage icon={<UserRound/>} title="로그인 후 내 정보를 확인해 주세요" body="마이페이지에서 가입한 아이디와 회원 정보를 확인할 수 있어요." action="로그인하기" onAction={login}/>;
  return <div className="page-wrap my-page"><div className="page-heading"><span className="eyebrow">MY ACCOUNT</span><h1>마이페이지</h1><p>{user.name}님의 회원 정보를 확인해 보세요.</p></div>
    <section className="panel profile-panel" aria-labelledby="profile-title">
      <div className="profile-heading"><span className="profile-icon"><UserRound size={28}/></span><div><h2 id="profile-title">내 회원 정보</h2><p>가입한 계정으로 예약을 관리할 수 있어요.</p></div></div>
      <dl className="profile-details">
        <div><dt>이름</dt><dd>{user.name}</dd></div>
        <div><dt>아이디</dt><dd>{user.loginId}</dd></div>
        <div><dt>로그인 방식</dt><dd><span className={`login-method ${(user.authProvider||'LOCAL').toLowerCase()}`}>{loginMethod(user.authProvider)}</span></dd></div>
        <div><dt>이메일</dt><dd>{user.email||<span className="muted">등록하지 않음</span>}</dd></div>
        <div><dt>전화번호</dt><dd>{user.phone?formatPhone(user.phone):<span className="muted">미등록</span>}</dd></div>
        <div><dt>회원 구분</dt><dd>{user.role==='ADMIN'?'관리자':'일반 회원'}</dd></div>
      </dl>
      <div className="profile-actions"><a className="button primary" href="#/reservations"><CalendarDays size={18}/>내 예약 보기</a>{user.role==='ADMIN'&&<a className="button secondary" href="#/admin/members">회원목록</a>}</div>
      <PasswordChange key={user.id} user={user} onChanged={onPasswordChanged}/>
      {user.role==='USER'&&<EmailChange key={'email-'+user.id} user={user} onChanged={onPasswordChanged}/>}
      {user.role==='USER'&&<MembershipWithdrawal key={user.id} user={user} returnCode={withdrawalReturn} onReturnHandled={onWithdrawalReturnHandled} onSuccess={onWithdrawn}/>}
    </section>
  </div>;
}

function MembershipWithdrawal({user,returnCode,onReturnHandled,onSuccess}:{user:User;returnCode:string;onReturnHandled:()=>void;onSuccess:(result:DeleteMemberResult)=>void}){
  const social=user.authProvider==='GOOGLE'||user.authProvider==='KAKAO';
  const [open,setOpen]=useState(!!returnCode);
  const [password,setPassword]=useState('');
  const [phoneVerificationToken,setPhoneVerificationToken]=useState('');
  const [verifying,setVerifying]=useState(false);
  const [error,setError]=useState('');
  const busy=useRef(false);
  const remove=useMutation({
    mutationFn:()=>api<DeleteMemberResult>('/members/me',{method:'DELETE',body:JSON.stringify({password,phoneVerificationToken})}),
    onSuccess:result=>{setPassword('');setPhoneVerificationToken('');onSuccess(result);},
    onError:reason=>{setError(reason.message);},
    onSettled:()=>{busy.current=false;}
  });
  useEffect(()=>{if(returnCode)onReturnHandled();},[returnCode,onReturnHandled]);
  function close(){
    if(busy.current||verifying)return;
    setOpen(false);setPassword('');setError('');setPhoneVerificationToken('');remove.reset();
  }
  function submit(event:FormEvent){
    event.preventDefault();if(busy.current||verifying)return;
    if(!social&&!password){setError('현재 비밀번호를 입력해 주세요.');return;}
    if(!phoneVerificationToken){setError('가입한 휴대전화 번호로 문자 인증을 완료해 주세요.');return;}
    busy.current=true;setError('');remove.mutate();
  }
  return <div className="profile-withdrawal">
    <p>서비스 이용을 마치려면 회원 탈퇴를 진행할 수 있어요.</p>
    <button className="text-button withdrawal-link" type="button" onClick={()=>{setPassword('');setError('');setPhoneVerificationToken('');setOpen(true);}}>회원 탈퇴</button>
    {open&&<Dialog title="회원 탈퇴" close={close}>
      <p className="dialog-copy">탈퇴하면 회원 정보가 삭제되고 예약 중인 예약이 모두 취소돼요. 탈퇴한 계정과 예약은 복구할 수 없어요.</p>
      <form className="auth-form withdrawal-form" onSubmit={submit}>
        <div className="field"><span>가입한 휴대전화</span><span>{user.phone?formatPhone(user.phone):'등록된 전화번호가 없어요.'}</span>
          <PhoneVerification purpose="WITHDRAWAL" phone={user.phone||''} onVerified={setPhoneVerificationToken} onBusy={setVerifying} disabled={remove.isPending}/>
        </div>
        {!social&&<label className="field">현재 비밀번호<input name="currentPassword" type="password" autoComplete="current-password" value={password} onChange={event=>setPassword(event.target.value)} required maxLength={100} disabled={remove.isPending} placeholder="현재 비밀번호를 입력해 주세요"/></label>}
        {error&&<Notice tone="error">{error}</Notice>}
        <div className="dialog-actions withdrawal-actions">
          <button className="button secondary" type="button" onClick={close} disabled={remove.isPending||verifying}>취소</button>
          <button className="button danger" type="submit" disabled={remove.isPending||verifying||!phoneVerificationToken||(!social&&!password)}>{remove.isPending?<><LoaderCircle size={17} className="spin"/>탈퇴 처리 중</>:'탈퇴하기'}</button>
        </div>
      </form>
    </Dialog>}
  </div>;
}

function AdminMembersPage({user,ready,login,notify}:{user:User|null;ready:boolean;login:()=>void;notify:(message:string)=>void}){
  const queryClient=useQueryClient();
  const [deleteTarget,setDeleteTarget]=useState<Member|null>(null);
  const members=useQuery({queryKey:['admin-members',user?.id],queryFn:()=>api<{items:Member[]}>('/admin/members'),enabled:ready&&user?.role==='ADMIN'});
  const remove=useMutation({
    mutationFn:(member:Member)=>{
      if(user?.role!=='ADMIN'||member.id===user.id||member.role!=='USER')throw new Error('본인과 관리자 계정은 삭제할 수 없어요.');
      return api<DeleteMemberResult>(`/admin/members/${encodeURIComponent(member.id)}`,{method:'DELETE'});
    },
    onSuccess:result=>{
      queryClient.setQueryData<{items:Member[]}>(['admin-members',user?.id],current=>current?{items:current.items.filter(member=>member.id!==result.deletedUserId)}:current);
      queryClient.invalidateQueries({queryKey:['admin-members']});
      queryClient.invalidateQueries({queryKey:['reservations']});
      queryClient.invalidateQueries({queryKey:['slots']});
      queryClient.invalidateQueries({queryKey:['admin-slots']});
      setDeleteTarget(null);
      notify(result.canceledReservations>0?`회원을 삭제하고 진행 중인 예약 ${result.canceledReservations}건을 취소했어요.`:result.message);
    }
  });
  if(!ready)return <div className="page-wrap"><Loading text="관리자 권한을 확인하고 있어요"/></div>;
  if(!user)return <EmptyPage icon={<ShieldCheck/>} title="관리자 로그인이 필요해요" body="관리자 계정으로 회원목록을 확인하고 일반 회원을 삭제할 수 있어요." action="로그인하기" onAction={login}/>;
  if(user.role!=='ADMIN')return <EmptyPage icon={<ShieldCheck/>} title="관리자 전용 메뉴예요" body="내 회원 정보는 마이페이지에서 확인해 주세요." action="마이페이지로" onAction={()=>go('/mypage')}/>;
  return <div className="page-wrap members-page">
    <div className="page-heading compact-heading"><div><span className="eyebrow">MEMBER MANAGEMENT</span><h1>회원목록</h1><p>가입한 회원을 확인하고 일반 회원 계정을 관리해요.</p></div><span className="admin-badge"><ShieldCheck size={17}/>관리자</span></div>
    <section className="panel members-panel" aria-labelledby="member-list-title">
      <div className="section-heading"><div><h2 id="member-list-title">전체 회원 {members.data&&<span className="member-count">{members.data.items.length}명</span>}</h2><p>본인과 관리자 계정은 삭제할 수 없어요.</p></div><button className="button secondary small" onClick={()=>members.refetch()} disabled={members.isFetching}><RefreshCw size={16} className={members.isFetching?'spin':''}/>새로고침</button></div>
      {members.isLoading?<Loading text="회원목록을 불러오고 있어요"/>:members.isError?<ErrorState error={members.error} retry={()=>members.refetch()}/>:members.data?.items.length?<div className="member-table-wrap"><table className="member-table"><thead><tr><th scope="col">이름</th><th scope="col">아이디</th><th scope="col">로그인 방식</th><th scope="col">이메일</th><th scope="col">전화번호</th><th scope="col">회원 구분</th><th scope="col">가입일</th><th scope="col">관리</th></tr></thead><tbody>{members.data.items.map(member=><tr key={member.id}>
        <td data-label="이름"><strong>{member.name}</strong>{member.id===user.id&&<span className="self-badge">본인</span>}</td>
        <td data-label="아이디">{member.role==='HOSPITAL'?formatBusinessNumber(member.businessNumber||''):member.loginId}</td>
        <td data-label="로그인 방식"><span className={`login-method ${(member.authProvider||'LOCAL').toLowerCase()}`}>{member.role==='HOSPITAL'?'사업자등록번호':loginMethod(member.authProvider)}</span></td>
        <td data-label="이메일">{member.role==='HOSPITAL'?<span className="muted">해당 없음</span>:member.email||<span className="muted">등록하지 않음</span>}</td>
        <td data-label="전화번호">{member.phone?formatPhone(member.phone):<span className="muted">미등록</span>}</td>
        <td data-label="회원 구분">{member.role==='ADMIN'?'관리자':member.role==='HOSPITAL'?'병원 회원':'일반 회원'}</td>
        <td data-label="가입일">{member.createdAt?<time dateTime={member.createdAt}>{fetchedLabel(member.createdAt)}</time>:'확인 불가'}</td>
        <td data-label="관리">{member.role==='USER'&&member.id!==user.id?<button className="button quiet small" aria-label={`${member.name} (${member.loginId}) 회원 삭제`} onClick={()=>{remove.reset();setDeleteTarget(member);}} disabled={remove.isPending}>회원 삭제</button>:<span className="protected-member"><ShieldCheck size={14}/>삭제 불가</span>}</td>
      </tr>)}</tbody></table></div>:<div className="empty-inline"><UserRound size={35}/><h3>등록된 회원이 없어요</h3><p>새 회원이 가입하면 이곳에 표시돼요.</p></div>}
    </section>
    {deleteTarget&&<Dialog title="회원 계정을 삭제할까요?" close={()=>{if(!remove.isPending)setDeleteTarget(null);}}><p className="dialog-copy"><strong>{deleteTarget.name}</strong> · 아이디 {deleteTarget.loginId}</p><Notice tone="warning">계정을 삭제하면 이 회원은 더 이상 로그인할 수 없어요. 진행 중인 예약은 모두 취소되고, 해당 시간대의 예약 가능 인원이 복구돼요. 삭제한 계정은 되돌릴 수 없어요.</Notice>{remove.isError&&<Notice tone="error">{remove.error.message}</Notice>}<div className="dialog-actions"><button className="button secondary" onClick={()=>setDeleteTarget(null)} disabled={remove.isPending}>취소</button><button className="button danger" onClick={()=>remove.mutate(deleteTarget)} disabled={remove.isPending}>{remove.isPending?<><LoaderCircle size={17} className="spin"/>삭제 중</>:'회원 삭제'}</button></div></Dialog>}
  </div>;
}

function AdminPage({user,login,notify}:{user:User|null;login:()=>void;notify:(message:string)=>void}){
  const queryClient=useQueryClient();
  const [hospitalId,setHospitalId]=useState('');const [department,setDepartment]=useState('01');const [doctorId,setDoctorId]=useState('');const [date,setDate]=useState(today(1));
  const [editing,setEditing]=useState<Slot|null>(null);const [capacity,setCapacity]=useState(4);const [closed,setClosed]=useState(false);const [generate,setGenerate]=useState(false);
  const [startTime,setStartTime]=useState('09:00');const [endTime,setEndTime]=useState('17:00');const [interval,setInterval]=useState(30);const [newCapacity,setNewCapacity]=useState(4);
  const hospitals=useQuery({queryKey:['hospitals','admin'],queryFn:()=>api<HospitalResult>('/hospitals'),enabled:user?.role==='ADMIN'});
  const doctors=useQuery({queryKey:['doctors',hospitalId,department],queryFn:()=>api<{items:Doctor[]}>(`/hospitals/${hospitalId}/doctors?department=${department}`),enabled:!!hospitalId&&user?.role==='ADMIN'});
  const slots=useQuery({queryKey:['admin-slots',doctorId,date],queryFn:()=>api<{items:Slot[]}>(`/admin/slots?${queryString({doctorId,date})}`),enabled:!!doctorId&&user?.role==='ADMIN'});
  const update=useMutation({mutationFn:()=>api<Slot>(`/admin/slots/${editing!.id}`,{method:'PATCH',body:JSON.stringify({capacity,closed})}),onSuccess:()=>{setEditing(null);queryClient.invalidateQueries({queryKey:['admin-slots']});queryClient.invalidateQueries({queryKey:['slots']});notify('진료 일정을 저장했어요.');}});
  const create=useMutation({mutationFn:()=>api<{items:Slot[]}>('/admin/slots',{method:'POST',body:JSON.stringify({doctorId,date,startTime,endTime,intervalMinutes:interval,capacity:newCapacity})}),onSuccess:()=>{setGenerate(false);queryClient.invalidateQueries({queryKey:['admin-slots']});queryClient.invalidateQueries({queryKey:['slots']});notify('새 시간표를 생성했어요.');}});
  if(!user)return <EmptyPage icon={<Settings2/>} title="관리자 로그인이 필요해요" body="관리자 계정으로 진료 일정과 정원을 관리할 수 있어요." action="로그인하기" onAction={login}/>;
  if(user.role!=='ADMIN')return <EmptyPage icon={<ShieldCheck/>} title="관리자 전용 메뉴예요" body="일반 회원은 내 예약에서 본인의 예약을 관리해 주세요." action="내 예약 보기" onAction={()=>go('/reservations')}/>;
  return <div className="page-wrap admin-page"><div className="page-heading compact-heading"><div><span className="eyebrow">SCHEDULE MANAGEMENT</span><h1>진료 일정 관리</h1><p>등록된 의료진의 시간표, 예약 정원, 휴진 여부를 관리해요.</p></div><span className="admin-badge"><ShieldCheck size={17}/>관리자</span></div><div className="admin-filters panel"><label className="field">병원<select value={hospitalId} onChange={e=>{setHospitalId(e.target.value);setDoctorId('');}}><option value="">병원 선택</option>{hospitals.data?.items.map(h=><option value={h.id} key={h.id}>{h.name}</option>)}</select></label><label className="field">진료과<select value={department} onChange={e=>{setDepartment(e.target.value);setDoctorId('');}}>{DEPARTMENTS.map(d=><option key={d.code} value={d.code}>{d.name}</option>)}</select></label><label className="field">의료진<select value={doctorId} disabled={!hospitalId} onChange={e=>setDoctorId(e.target.value)}><option value="">의료진 선택</option>{doctors.data?.items.map(d=><option key={d.id} value={d.id}>{d.name}</option>)}</select></label><label className="field">날짜<input type="date" value={date} min={today()} max={today(90)} onChange={e=>setDate(e.target.value)}/></label></div>{(hospitals.isError||doctors.isError)&&<ErrorState error={hospitals.error||doctors.error}/>}<section className="panel admin-schedule"><div className="section-heading"><div><h2>{dateLabel(`${date}T12:00:00+09:00`)} 진료 일정</h2><p>시간대를 선택하면 정원과 휴진 여부를 수정할 수 있어요.</p></div><button className="button primary small" disabled={!doctorId} onClick={()=>{create.reset();setGenerate(true);}}><Plus size={17}/>시간표 생성</button></div>{!doctorId?<div className="empty-inline"><CalendarDays size={35}/><h3>병원과 의료진을 선택해 주세요</h3><p>선택한 의료진의 진료 시간표가 표시됩니다.</p></div>:slots.isLoading?<Loading/>:slots.isError?<ErrorState error={slots.error} retry={()=>slots.refetch()}/>:<><div className="schedule-legend"><span><i className="legend-open"/>예약 가능</span><span><i className="legend-full"/>정원 마감</span><span><i className="legend-closed"/>휴진</span></div><div className="admin-slots">{slots.data?.items.map(s=><button key={s.id} className={`admin-slot ${s.closed?'closed':s.remaining<=0?'full':''}`} onClick={()=>{update.reset();setEditing(s);setCapacity(s.capacity);setClosed(s.closed);}}><span>{timeLabel(s.startAt)}</span><strong>{s.closed?'휴진':`${s.reservedCount} / ${s.capacity}`}</strong><small>{s.closed?'예약 불가':`잔여 ${s.remaining}명`}</small><Settings2 size={15}/></button>)}</div>{slots.data?.items.length===0&&<div className="empty-inline">등록된 시간이 없어요. 시간표를 생성해 주세요.</div>}</>}</section><Notice>기존 예약 수보다 정원을 줄일 수 없으며, 예약이 있는 시간은 휴진으로 변경할 수 없어요.</Notice>{editing&&<Dialog title={`${timeLabel(editing.startAt)} 일정 수정`} close={()=>{if(!update.isPending)setEditing(null);}}><p className="dialog-copy">현재 예약 {editing.reservedCount}명 / 정원 {editing.capacity}명</p><label className="field">총 정원<input type="number" min={Math.max(1,editing.reservedCount)} max={100} value={capacity} onChange={e=>setCapacity(Number(e.target.value))}/></label><label className="check-label"><input type="checkbox" checked={closed} onChange={e=>setClosed(e.target.checked)}/><span>이 시간대를 휴진으로 설정</span></label>{update.isError&&<Notice tone="error">{update.error.message}</Notice>}<div className="dialog-actions"><button className="button secondary" onClick={()=>setEditing(null)} disabled={update.isPending}>닫기</button><button className="button primary" onClick={()=>update.mutate()} disabled={update.isPending||!Number.isFinite(capacity)||capacity<1||capacity>100}>{update.isPending?'저장 중':'변경 저장'}</button></div></Dialog>}{generate&&<Dialog title="진료 시간표 생성" close={()=>{if(!create.isPending)setGenerate(false);}}><p className="dialog-copy">{doctors.data?.items.find(d=>d.id===doctorId)?.name} · {dateLabel(`${date}T12:00:00+09:00`)}</p><div className="form-grid"><label className="field">시작 시간<input type="time" value={startTime} onChange={e=>setStartTime(e.target.value)}/></label><label className="field">종료 시간<input type="time" value={endTime} onChange={e=>setEndTime(e.target.value)}/></label><label className="field">시간 간격<select value={interval} onChange={e=>setInterval(Number(e.target.value))}><option value={15}>15분</option><option value={30}>30분</option><option value={60}>60분</option></select></label><label className="field">시간당 정원<input type="number" min={1} max={100} value={newCapacity} onChange={e=>setNewCapacity(Number(e.target.value))}/></label></div><p className="muted">이미 등록된 시간과 중복되는 경우 서버의 처리 결과를 안내해요.</p>{create.isError&&<Notice tone="error">{create.error.message}</Notice>}<div className="dialog-actions"><button className="button secondary" onClick={()=>setGenerate(false)} disabled={create.isPending}>닫기</button><button className="button primary" onClick={()=>create.mutate()} disabled={create.isPending||startTime>=endTime||newCapacity<1||newCapacity>100}>{create.isPending?'생성 중':'시간표 생성'}</button></div></Dialog>}</div>;
}

function Dialog({title,children,close}:{title:string;children:ReactNode;close:()=>void}){
  const panel=useRef<HTMLDivElement>(null);
  const closeRef=useRef(close);closeRef.current=close;
  useEffect(()=>{const previous=document.activeElement as HTMLElement|null;const old=document.body.style.overflow;document.body.style.overflow='hidden';panel.current?.focus();function key(e:KeyboardEvent){if(e.key==='Escape')closeRef.current();if(e.key==='Tab'){const nodes=panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]');if(!nodes?.length)return;const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&(document.activeElement===first||document.activeElement===panel.current)){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}}document.addEventListener('keydown',key);return()=>{document.body.style.overflow=old;document.removeEventListener('keydown',key);previous?.focus();};},[]);
  return <div className="dialog-overlay" onMouseDown={e=>{if(e.target===e.currentTarget)close();}}><div className="dialog" ref={panel} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div className="dialog-heading"><h2 id="dialog-title">{title}</h2><button className="icon-button" aria-label="창 닫기" onClick={close}><X size={22}/></button></div>{children}</div></div>;
}
function AuthDialog({mode,setMode,close,onSuccess,beforeSocialLogin,socialError,initialPreferredProvider}:{mode:'login'|'register';setMode:(mode:'login'|'register')=>void;close:()=>void;onSuccess:(result:AuthResult)=>void;beforeSocialLogin:()=>void;socialError:string;initialPreferredProvider?:AuthProvider}){
  const [accountType,setAccountType]=useState<'personal'|'hospital'>('personal');
  const [hospitalBusy,setHospitalBusy]=useState(false);
  const [emailToken,setEmailToken]=useState('');const [phoneToken,setPhoneToken]=useState('');
  const [emailBusy,setEmailBusy]=useState(false);const [phoneBusy,setPhoneBusy]=useState(false);
  const [recovery,setRecovery]=useState(false);const [recoveryBusy,setRecoveryBusy]=useState(false);
  const [name,setName]=useState('');const [loginId,setLoginId]=useState('');const [password,setPassword]=useState('');const [email,setEmail]=useState('');const [phone,setPhone]=useState('');const [validationError,setValidationError]=useState('');
  const mutation=useMutation({mutationFn:()=>api<AuthResult>(`/auth/${mode}`,{method:'POST',body:JSON.stringify(mode==='login'?{loginId:loginId.trim(),password}:{name,loginId:loginId.trim(),password,email:email.trim(),phone:normalizePhone(phone),emailVerificationToken:emailToken,phoneVerificationToken:phoneToken})}),onSuccess});
  const busy=mutation.isPending||hospitalBusy||emailBusy||phoneBusy||recoveryBusy;
  const [preferredProvider,setPreferredProvider]=useState(initialPreferredProvider);
  const existingProvider=mutation.error instanceof ApiError&&mutation.error.code==='MEMBER_ALREADY_EXISTS'?mutation.error.existingAuthProvider:undefined;
  function existingLogin(){if(!existingProvider)return;setPreferredProvider(existingProvider);setMode('login');mutation.reset();setPassword('');setValidationError('');}
  function submit(e:FormEvent){
    e.preventDefault();if(busy)return;
    if(mode==='register'&&(!email.trim()||email.trim().length>254)){setValidationError('이메일 주소를 254자 이내로 입력해 주세요.');return;}
    if(mode==='register'&&!validMobilePhone(phone)){setValidationError('문자를 받을 수 있는 휴대전화번호를 입력해 주세요.');return;}
    if(mode==='register'&&(!emailToken||!phoneToken)){setValidationError('이메일 인증과 휴대전화 인증을 완료해 주세요.');return;}
    setValidationError('');mutation.mutate();
  }
  return <Dialog title={recovery?'비밀번호 찾기':mode==='login'?'반가워요. 로그인해 주세요':'함께 시작해 볼까요?'} close={()=>{if(!busy)close();}}>
    <div className="auth-account-tabs" role="tablist" aria-label="회원 유형"><button type="button" role="tab" aria-selected={accountType==='personal'} disabled={busy} onClick={()=>{if(accountType==='personal'&&!recovery)return;setAccountType('personal');setRecovery(false);mutation.reset();setValidationError('');setPassword('');setEmailToken('');setPhoneToken('');}}>개인</button><button type="button" role="tab" aria-selected={accountType==='hospital'} disabled={busy} onClick={()=>{setAccountType('hospital');setRecovery(false);mutation.reset();setValidationError('');setPassword('');setPreferredProvider(undefined);setEmailToken('');setPhoneToken('');}}>병원</button></div>
    {accountType==='hospital'?<><p className="dialog-copy">{mode==='login'?'사업자등록번호로 로그인하고 우리 병원을 관리하세요.':'사업자등록번호로 병원을 조회한 뒤 비밀번호를 설정해 주세요.'}</p><HospitalAuthForm key={mode} mode={mode} onSuccess={onSuccess} onBusy={setHospitalBusy}/></>:<>
    {recovery?<PasswordRecovery onBack={()=>setRecovery(false)} onBusy={setRecoveryBusy}/>:<>
    <p className="dialog-copy">{mode==='login'?'로그인하고 예약 일정을 편하게 관리하세요.':'이메일과 휴대전화를 인증한 뒤 가입해 주세요.'}</p>
    {socialError&&<Notice tone="error">{socialError}</Notice>}
    {preferredProvider&&<Notice>{`${loginMethod(preferredProvider)}로 로그인해 주세요.`}</Notice>}
    {preferredProvider&&preferredProvider!=='LOCAL'&&<SocialLoginButtons beforeStart={beforeSocialLogin} disabled={busy} preferredProvider={preferredProvider}/>}
    <form onSubmit={submit} className="auth-form">
      {mode==='register'&&<label className="field">이름<input autoComplete="name" value={name} onChange={e=>setName(e.target.value)} required minLength={1} maxLength={40} placeholder="이름"/></label>}
      {mode==='register'&&<><label className="field">이메일<input name="email" type="email" autoComplete="email" autoCapitalize="none" spellCheck={false} value={email} onChange={e=>{setEmail(e.target.value);setEmailToken('');}} onBlur={()=>setEmail(email.trim())} required maxLength={254} placeholder="example@email.com" aria-describedby="signup-contact-help" disabled={busy||!!emailToken}/><small id="signup-contact-help" className="muted">이미 가입한 이메일이나 전화번호는 다시 사용할 수 없어요.</small></label>
        <EmailVerification purpose="SIGNUP" email={email} onVerified={setEmailToken} onBusy={setEmailBusy} disabled={mutation.isPending||phoneBusy}/></>}
      {mode==='register'&&<><label className="field">휴대전화번호<PhoneInput name="phone" value={phone} onValueChange={value=>{setPhone(value);setPhoneToken('');}} required placeholder="010-1234-5678" disabled={busy||!!phoneToken}/></label>
        <PhoneVerification purpose="SIGNUP" phone={phone} onVerified={setPhoneToken} onBusy={setPhoneBusy} disabled={mutation.isPending||emailBusy}/></>}
      <label className="field">아이디<input type="text" autoComplete="username" autoCapitalize="none" spellCheck={false} value={loginId} onChange={e=>setLoginId(e.target.value)} onBlur={()=>setLoginId(loginId.trim())} required minLength={4} maxLength={30} pattern={'[A-Za-z0-9_\\-]{4,30}'} title="영문, 숫자, 밑줄(_) 또는 하이픈(-)을 사용한 4~30자 아이디" aria-describedby="login-id-help" placeholder="아이디"/><small id="login-id-help" className="muted">영문, 숫자, _ 또는 -를 사용해 4~30자로 입력해 주세요. 영문 대소문자는 구분하지 않아요.</small></label>
      <label className="field">비밀번호<input type="password" autoComplete={mode==='login'?'current-password':'new-password'} value={password} onChange={e=>setPassword(e.target.value)} required minLength={8} maxLength={100} placeholder={mode==='register'?'8자 이상 입력해 주세요':'비밀번호'}/></label>
      {(validationError||mutation.isError)&&<Notice tone="error">{validationError||mutation.error?.message}</Notice>}
      {existingProvider&&<button className="button secondary full" type="button" onClick={existingLogin} disabled={mutation.isPending}>{loginMethod(existingProvider)}로 로그인하기</button>}
      <button className="button primary full" type="submit" disabled={busy||(mode==='register'&&(!emailToken||!phoneToken))}>{mutation.isPending?<><LoaderCircle className="spin" size={18}/>처리 중</>:mode==='login'?'로그인':'회원가입'}</button>
    </form>
    {mode==='login'&&<button className="text-button password-recovery-link" type="button" disabled={busy} onClick={()=>{setRecovery(true);mutation.reset();setValidationError('');setPassword('');}}>비밀번호 찾기</button>}
    {(!preferredProvider||preferredProvider==='LOCAL')&&<SocialLoginButtons beforeStart={beforeSocialLogin} disabled={busy}/>}
    </>}
    </>}
    {!recovery&&<div className="auth-switch">{mode==='login'?'처음 방문하셨나요?':'이미 계정이 있으신가요?'}<button className="text-button" disabled={busy} onClick={()=>{setMode(mode==='login'?'register':'login');mutation.reset();setPassword('');setValidationError('');setPreferredProvider(undefined);setEmailToken('');setPhoneToken('');}}>{mode==='login'?'회원가입':'로그인'}</button></div>}
    <div className="auth-note"><ShieldCheck size={16}/><span>{accountType==='hospital'?'병원 계정에는 이메일과 소셜 로그인을 사용하지 않아요.':'회원가입 후에는 본인의 예약만 조회할 수 있어요.'}</span></div>
  </Dialog>;
}
