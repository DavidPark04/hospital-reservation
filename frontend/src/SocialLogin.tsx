import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertCircle, LoaderCircle, RefreshCw } from 'lucide-react';
import { api } from './api';
import type { AuthProvider, AuthResult, SocialProvider } from './api';

export function loginMethod(provider?:AuthProvider){
  if(provider==='GOOGLE')return '구글';
  if(provider==='KAKAO')return '카카오';
  return '아이디';
}

export function socialErrorMessage(code:string){
  if(code==='cancelled')return '소셜 로그인이 취소되었어요. 원하는 방법으로 다시 로그인해 주세요.';
  if(code==='session')return '로그인 연결 시간이 지났어요. 다시 로그인해 주세요.';
  return '소셜 로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.';
}

// Official brand colors and marks: developers.google.com/identity/branding-guidelines
// and developers.kakao.com/docs/ko/kakaologin/design-guide.
function ProviderIcon({provider}:{provider:SocialProvider['id']}){
  if(provider==='kakao')return <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 3C5.925 3 1 6.827 1 11.549c0 3.073 2.09 5.768 5.228 7.275l-1.062 3.851a.293.293 0 0 0 .448.324l4.654-3.088a14.03 14.03 0 0 0 1.732.107c6.075 0 11-3.827 11-8.549C23 6.827 18.075 3 12 3Z"/></svg>;
  return <svg width="20" height="20" viewBox="0 0 48 48" aria-hidden="true"><path fill="#4285F4" d="M43.61 24.46c0-1.36-.12-2.67-.35-3.93H24v7.44h11a9.4 9.4 0 0 1-4.07 6.17v5h6.59c3.86-3.56 6.09-8.8 6.09-14.68Z"/><path fill="#34A853" d="M24 44c5.51 0 10.13-1.83 13.51-4.96l-6.59-5c-1.83 1.23-4.17 1.98-6.92 1.98-5.32 0-9.84-3.59-11.45-8.42h-6.8v5.16A20 20 0 0 0 24 44Z"/><path fill="#FBBC05" d="M12.55 27.6a11.98 11.98 0 0 1 0-7.2v-5.16h-6.8a20 20 0 0 0 0 17.52l6.8-5.16Z"/><path fill="#EA4335" d="M24 11.98c3 0 5.68 1.03 7.8 3.06l5.85-5.85A19.65 19.65 0 0 0 24 4 20 20 0 0 0 5.75 15.24l6.8 5.16c1.61-4.83 6.13-8.42 11.45-8.42Z"/></svg>;
}

type ProviderId=SocialProvider['id'];
const providerNames={kakao:'카카오',google:'Google'};

function availableLoginUrls({providers}:{providers:SocialProvider[]}){
  const urls:Partial<Record<ProviderId,string>>={};
  for(const provider of providers){
    if(provider?.enabled!==true || (provider.id!=='kakao' && provider.id!=='google') || !provider.authorizationUrl)continue;
    try{
      const url=new URL(provider.authorizationUrl,window.location.origin);
      if((url.protocol==='https:'||url.protocol==='http:') && url.pathname==='/oauth2/authorization/'+provider.id && !url.username && !url.password){
        urls[provider.id]=url.href;
      }
    }catch{
      // An invalid URL only affects this provider.
    }
  }
  return urls;
}

export function SocialLoginButtons({beforeStart,disabled=false,preferredProvider}:{beforeStart:()=>void;disabled?:boolean;preferredProvider?:AuthProvider}){
  const providers=useQuery({queryKey:['social-providers'],queryFn:()=>api<{providers:SocialProvider[]}>('/auth/social/providers'),select:availableLoginUrls,staleTime:60_000});
  const [selected,setSelected]=useState<ProviderId|null>(null);
  const navigating=useRef(false);
  const [error,setError]=useState('');
  const checking=providers.isPending||providers.isFetching;
  const orderedIds:ProviderId[]=preferredProvider==='GOOGLE'?['google','kakao']:['kakao','google'];
  const selectedUrl=selected&&!providers.isError?providers.data?.[selected]:undefined;
  const status=checking?'연결 확인 중':selectedUrl?'로그인 페이지로 이동 중':'';

  useEffect(()=>{
    if(!selected||navigating.current)return;
    if(disabled||providers.isError){setSelected(null);return;}
    if(checking)return;
    if(!selectedUrl){
      setSelected(null);
      setError(`${providerNames[selected]} 로그인을 현재 이용할 수 없어요.`);
      return;
    }
    try{
      navigating.current=true;
      beforeStart();
      window.location.assign(selectedUrl);
    }catch{
      navigating.current=false;
      setSelected(null);
      setError('로그인을 시작하지 못했어요. 브라우저 설정을 확인한 뒤 다시 시도해 주세요.');
    }
  },[selected,disabled,providers.isError,selectedUrl,checking,beforeStart]);

  return <section className="social-login" aria-label="소셜 계정으로 로그인">
    <div className="auth-divider"><span>{preferredProvider&&preferredProvider!=='LOCAL'?'소셜 계정으로 로그인':'또는 소셜 계정으로'}</span></div>
    <div className="social-login-buttons">{orderedIds.map(id=>{
      const unavailable=providers.isError||(!!providers.data&&!providers.data[id]);
      const label=providerNames[id]+'로 로그인';
      return <button key={id} type="button" className={`social-login-button ${id}`} disabled={disabled||!!selected||unavailable} onClick={()=>{setError('');setSelected(id);}} aria-label={label+(unavailable?' (현재 이용할 수 없음)':'')}>
        {selected===id&&status?<LoaderCircle size={20} className="spin"/>:<ProviderIcon provider={id}/>}<span>{selected===id&&status?status:label}</span>
      </button>;
    })}</div>
    <span className="sr-only" role="status">{selected && status && `${providerNames[selected]} ${status}`}</span>
    {providers.isError&&<div className="social-login-error" role="alert"><p>소셜 로그인 정보를 불러오지 못했어요.</p><button type="button" className="text-button" onClick={()=>providers.refetch()}><RefreshCw size={15}/>다시 시도</button></div>}
    {error&&<p className="social-login-error" role="alert"><AlertCircle size={17}/>{error}</p>}
    <p className="social-login-help">처음 로그인할 때 이름, 이메일, 전화번호를 입력하면 가입이 완료돼요.</p>
  </section>;
}

// React StrictMode replays effects in development. Share the one-time exchange
// request so the HttpOnly handoff cookie is consumed only once per page load.
let exchangeRequest:Promise<AuthResult>|undefined;
function exchangeSession(){
  if(!exchangeRequest){
    exchangeRequest=api<AuthResult>('/auth/social/exchange',{method:'POST',body:'{}',credentials:'same-origin'});
    const pending=exchangeRequest;
    void pending.finally(()=>{if(exchangeRequest===pending)exchangeRequest=undefined;}).catch(()=>{});
  }
  return exchangeRequest;
}

export function SocialLoginCallback({onSuccess,onRetry}:{onSuccess:(result:AuthResult)=>void;onRetry:()=>void}){
  const [error,setError]=useState('');
  const successRef=useRef(onSuccess);successRef.current=onSuccess;
  useEffect(()=>{
    let active=true;
    exchangeSession().then(result=>{if(active)successRef.current(result);}).catch(reason=>{if(active)setError(reason instanceof Error?reason.message:socialErrorMessage('failed'));});
    return()=>{active=false;};
  },[]);
  return <div className="page-wrap"><section className="empty-page social-callback" aria-live="polite">{error?<><div className="empty-icon"><AlertCircle/></div><h1>로그인을 완료하지 못했어요</h1><p>{error}</p><button className="button primary" onClick={onRetry}>로그인 다시 하기</button></>:<><LoaderCircle size={36} className="spin"/><h1>로그인 정보를 확인하고 있어요</h1><p>회원 정보를 불러온 뒤 이동할게요.</p></>}</section></div>;
}
