import { useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, LoaderCircle, LogOut } from 'lucide-react';
import { api, ApiError, normalizePhone, validPhone } from './api';
import type { AuthProvider, AuthResult, User } from './api';
import { loginMethod } from './SocialLogin';

export function needsSocialProfile(user:User|null){
  return !!user&&(user.authProvider==='GOOGLE'||user.authProvider==='KAKAO')&&user.profileComplete!==true;
}

export default function SocialProfile({user,onSuccess,onCancel,onExistingLogin}:{user:User;onSuccess:(result:AuthResult)=>void;onCancel:()=>Promise<void>;onExistingLogin:(provider:AuthProvider)=>Promise<void>}){
  const [name,setName]=useState('');
  const [email,setEmail]=useState('');
  const [phone,setPhone]=useState('');
  const [validationError,setValidationError]=useState('');
  const [canceling,setCanceling]=useState(false);
  const [switching,setSwitching]=useState(false);
  const mutation=useMutation({mutationFn:()=>api<AuthResult>('/auth/social/profile',{method:'POST',body:JSON.stringify({name:name.trim(),email:email.trim(),phone:normalizePhone(phone)})}),onSuccess});
  const busy=mutation.isPending||canceling||switching;
  const sessionExpired=mutation.error instanceof ApiError&&mutation.error.status===401;
  const existingProvider=mutation.error instanceof ApiError&&mutation.error.code==='MEMBER_ALREADY_EXISTS'?mutation.error.existingAuthProvider:undefined;
  function submit(event:FormEvent){
    event.preventDefault();
    if(busy||sessionExpired)return;
    const cleanName=name.trim();const cleanEmail=email.trim();
    if(cleanName.length<1||cleanName.length>40){setValidationError('이름은 공백을 제외하고 1~40자로 입력해 주세요.');return;}
    if(!cleanEmail||cleanEmail.length>254){setValidationError('이메일 주소를 254자 이내로 입력해 주세요.');return;}
    if(!validPhone(phone)){setValidationError('전화번호는 0으로 시작하는 9~11자리 국내 번호로 입력해 주세요.');return;}
    setValidationError('');mutation.mutate();
  }
  async function cancel(){
    if(busy)return;
    setCanceling(true);
    try{await onCancel();}finally{setCanceling(false);}
  }
  async function existingLogin(){
    if(busy||!existingProvider)return;
    setSwitching(true);
    try{await onExistingLogin(existingProvider);}finally{setSwitching(false);}
  }
  async function restartLogin(){
    if(busy||!user.authProvider||user.authProvider==='LOCAL')return;
    setSwitching(true);
    try{await onExistingLogin(user.authProvider);}finally{setSwitching(false);}
  }
  return <div className="page-wrap profile-signup-page"><section className="panel profile-signup-panel" aria-labelledby="profile-signup-title">
    <span className="profile-auth-complete"><CheckCircle2 size={18}/>{loginMethod(user.authProvider)} 인증 완료</span>
    <h1 id="profile-signup-title">회원가입을 마무리해 주세요</h1>
    <p className="profile-signup-copy">{loginMethod(user.authProvider)} 인증이 완료됐어요.<br/>이름, 이메일, 전화번호를 입력하면 가입이 완료돼요.</p>
    <form className="auth-form" onSubmit={submit}>
      <label className="field" htmlFor="social-profile-name">이름<input id="social-profile-name" name="name" autoComplete="name" value={name} onChange={event=>setName(event.target.value)} onBlur={()=>setName(name.trim())} required minLength={1} maxLength={40} placeholder="사용할 이름을 입력해 주세요" disabled={busy}/></label>
      <label className="field" htmlFor="social-profile-email">이메일<input id="social-profile-email" name="email" type="email" autoComplete="email" autoCapitalize="none" spellCheck={false} value={email} onChange={event=>setEmail(event.target.value)} onBlur={()=>setEmail(email.trim())} required maxLength={254} placeholder="example@email.com" aria-describedby="social-email-help" disabled={busy}/><small id="social-email-help" className="muted">이미 가입한 이메일이나 전화번호는 다시 사용할 수 없어요.</small></label>
      <label className="field" htmlFor="social-profile-phone">전화번호<input id="social-profile-phone" name="phone" type="tel" inputMode="tel" autoComplete="tel" value={phone} onChange={event=>setPhone(event.target.value)} required maxLength={20} placeholder="010-1234-5678" aria-describedby="social-phone-help" disabled={busy}/><small id="social-phone-help" className="muted">휴대전화 또는 지역번호를 포함한 국내 전화번호를 입력해 주세요.</small></label>
      {(validationError||mutation.isError)&&<div className="notice error" role="alert"><AlertCircle size={19}/><div>{sessionExpired?'가입 인증 시간이 지났어요. 소셜 로그인을 다시 진행해 주세요.':validationError||mutation.error?.message}</div></div>}
      {existingProvider&&<button type="button" className="button secondary full" onClick={existingLogin} disabled={busy}>{switching?<><LoaderCircle size={18} className="spin"/>로그인 화면으로 이동 중</>:`${loginMethod(existingProvider)}로 로그인하기`}</button>}
      {sessionExpired&&<button type="button" className="button secondary full" onClick={restartLogin} disabled={busy}>{switching?<><LoaderCircle size={18} className="spin"/>로그인 화면으로 이동 중</>:`${loginMethod(user.authProvider)}로 다시 인증하기`}</button>}
      <button type="submit" className="button primary full" disabled={busy||sessionExpired}>{mutation.isPending?<><LoaderCircle size={18} className="spin"/>가입 정보 저장 중</>:'가입 완료'}</button>
    </form>
    <button type="button" className="button quiet full profile-signup-cancel" onClick={cancel} disabled={busy}>{canceling?<LoaderCircle size={17} className="spin"/>:<LogOut size={17}/>}나중에 하기 · 인증 종료</button>
  </section></div>;
}
