import {useEffect,useRef,useState} from 'react';
import type {FormEvent} from 'react';
import {useMutation} from '@tanstack/react-query';
import {AlertCircle,CheckCircle2,LoaderCircle,Search,ShieldCheck} from 'lucide-react';
import {api} from './api';
import type {AuthResult,HospitalLookup} from './api';
import {formatPhone} from './phoneFormatting';
import './hospitalManagement.css';

export function businessNumberDigits(value:string){return value.replace(/\D/g,'').slice(0,10);}
export function formatBusinessNumber(value:string){const digits=businessNumberDigits(value);return digits.length<=3?digits:digits.length<=5?digits.slice(0,3)+'-'+digits.slice(3):digits.slice(0,3)+'-'+digits.slice(3,5)+'-'+digits.slice(5);}

export default function HospitalAuthForm({mode,onSuccess,onBusy}:{mode:'login'|'register';onSuccess:(result:AuthResult)=>void;onBusy:(busy:boolean)=>void}){
  const [businessNumber,setBusinessNumber]=useState('');
  const [password,setPassword]=useState('');
  const [verified,setVerified]=useState<HospitalLookup|null>(null);
  const [error,setError]=useState('');
  const numberRef=useRef('');
  const lookup=useMutation({
    mutationFn:(number:string)=>api<HospitalLookup>('/auth/hospital/lookup',{method:'POST',body:JSON.stringify({businessNumber:number})}),
    onSuccess:(result,number)=>{if(numberRef.current===number){setVerified(result);setError('');}}
  });
  const registrationValid=!!verified&&verified.businessNumber===businessNumberDigits(businessNumber);
  const auth=useMutation({
    mutationFn:()=>api<AuthResult>(`/auth/hospital/${mode}`,{method:'POST',body:JSON.stringify({businessNumber:businessNumberDigits(businessNumber),password,...(mode==='register'?{verificationToken:verified?.verificationToken}:{})})}),
    onSuccess
  });
  const busy=lookup.isPending||auth.isPending;
  useEffect(()=>{onBusy(busy);return()=>onBusy(false);},[busy,onBusy]);
  function changeNumber(value:string){const formatted=formatBusinessNumber(value);setBusinessNumber(formatted);numberRef.current=businessNumberDigits(formatted);setVerified(null);setError('');lookup.reset();auth.reset();}
  function findHospital(){
    if(numberRef.current.length!==10){setError('사업자등록번호 10자리를 입력해 주세요.');return;}
    setError('');setVerified(null);auth.reset();lookup.mutate(numberRef.current);
  }
  function submit(event:FormEvent){
    event.preventDefault();if(busy)return;
    if(numberRef.current.length!==10){setError('사업자등록번호 10자리를 입력해 주세요.');return;}
    if(mode==='register'&&!registrationValid){setError('사업자등록번호로 병원을 먼저 조회해 주세요.');return;}
    if(new TextEncoder().encode(password).length>72){setError('비밀번호는 72바이트 이하로 입력해 주세요.');return;}
    setError('');auth.mutate();
  }
  return <form className="auth-form hospital-auth-form" onSubmit={submit}>
    <label className="field" htmlFor="hospital-business-number">사업자등록번호
      <div className="business-lookup-row"><input id="hospital-business-number" name="businessNumber" inputMode="numeric" autoComplete="username" value={businessNumber} onChange={e=>changeNumber(e.target.value)} placeholder="000-00-00000" maxLength={12} required pattern="[0-9]{3}-[0-9]{2}-[0-9]{5}" disabled={busy}/>
        {mode==='register'&&<button className="button secondary" type="button" disabled={busy||numberRef.current.length!==10} onClick={findHospital}>{lookup.isPending?<LoaderCircle size={17} className="spin"/>:<Search size={17}/>}조회</button>}
      </div><small className="muted">{mode==='register'?'번호를 조회하면 등록할 병원의 기본정보가 자동으로 입력돼요.':'가입할 때 사용한 병원 사업자등록번호를 입력해 주세요.'}</small>
    </label>
    {mode==='register'&&registrationValid&&verified&&<div className="verified-hospital" aria-label="조회된 병원 기본정보">
      <p className="verified-title" role="status"><CheckCircle2 size={18}/>병원 정보를 확인했어요</p>
      <label className="field">병원명<input value={verified.hospital.name} readOnly aria-readonly="true"/></label>
      <label className="field">주소<textarea value={verified.hospital.address} readOnly aria-readonly="true" rows={2}/></label>
      <div className="form-grid"><label className="field">우편번호<input value={verified.hospital.postalCode||''} readOnly aria-readonly="true"/></label><label className="field">전화번호<input value={verified.hospital.tel?formatPhone(verified.hospital.tel):''} readOnly aria-readonly="true"/></label></div>
      <small className="muted">조회된 기본정보는 수정할 수 없어요. 전화번호가 제공되지 않으면 빈칸으로 등록돼요.</small>
    </div>}
    <label className="field" htmlFor="hospital-password">비밀번호<input id="hospital-password" name="password" type="password" autoComplete={mode==='login'?'current-password':'new-password'} value={password} onChange={e=>{setPassword(e.target.value);setError('');}} required minLength={8} maxLength={72} disabled={auth.isPending} placeholder={mode==='register'?'8자 이상 입력해 주세요':'비밀번호'}/></label>
    {(error||lookup.isError||auth.isError)&&<div className="notice error" role="alert"><AlertCircle size={19}/><div>{error||auth.error?.message||lookup.error?.message}</div></div>}
    <button className="button primary full" type="submit" disabled={busy||(mode==='register'&&!registrationValid)}>{auth.isPending?<><LoaderCircle size={18} className="spin"/>처리 중</>:mode==='login'?'병원 로그인':'병원 회원가입'}</button>
    <div className="auth-note"><ShieldCheck size={16}/><span>병원 계정은 사업자등록번호로 로그인하고 소속 병원의 의료진·일정·예약을 관리해요.</span></div>
  </form>;
}
