import {useEffect,useRef,useState} from 'react';
import type {FormEvent} from 'react';
import {useMutation} from '@tanstack/react-query';
import {AlertCircle,CheckCircle2,ChevronRight,LoaderCircle,Search,ShieldCheck} from 'lucide-react';
import {api,ApiError} from './api';
import type {AuthResult,HospitalAccountInfo,HospitalLookup,VerifiedHospitalLookup} from './api';
import {formatPhone} from './phoneFormatting';
import './hospitalManagement.css';

export function businessNumberDigits(value:string){return value.replace(/\D/g,'').slice(0,10);}
export function formatBusinessNumber(value:string){const digits=businessNumberDigits(value);return digits.length<=3?digits:digits.length<=5?digits.slice(0,3)+'-'+digits.slice(3):digits.slice(0,3)+'-'+digits.slice(3,5)+'-'+digits.slice(5);}

type LookupRequest = {businessNumber:string} | {businessNumber:string;selectionToken:string;hospitalId:string};

export default function HospitalAuthForm({mode,onSuccess,onBusy}:{mode:'login'|'register';onSuccess:(result:AuthResult)=>void;onBusy:(busy:boolean)=>void}){
  const [businessNumber,setBusinessNumber]=useState('');
  const [password,setPassword]=useState('');
  const [result,setResult]=useState<HospitalLookup|null>(null);
  const [candidate,setCandidate]=useState<HospitalAccountInfo|null>(null);
  const [error,setError]=useState('');
  const activeRequest=useRef<LookupRequest|null>(null);
  const resultHeading=useRef<HTMLParagraphElement>(null);
  const confirmation=useRef<HTMLDialogElement>(null);
  const choiceButton=useRef<HTMLButtonElement|null>(null);
  const number=businessNumberDigits(businessNumber);
  const current=result?.businessNumber===number?result:null;
  const verified=current&&'hospital' in current?current:null;
  const choices=current&&'candidates' in current?current:null;
  const lookup=useMutation({
    mutationFn:(request:LookupRequest)=>{
      const options={method:'POST',body:JSON.stringify(request)};
      return 'hospitalId' in request
        ?api<VerifiedHospitalLookup>('/auth/hospital/lookup/select',options)
        :api<HospitalLookup>('/auth/hospital/lookup',options);
    },
    onSuccess:(response,request)=>{
      if(activeRequest.current!==request)return;
      closeConfirmation();
      if(response.businessNumber!==request.businessNumber||
          ('hospitalId' in request&&(!('hospital' in response)||response.hospital.id!==request.hospitalId))){
        setResult(null);setError('선택한 병원을 확인할 수 없어요. 사업자등록번호를 다시 조회해 주세요.');return;
      }
      setResult(response);
    },
    onError:(failure,request)=>{
      if(activeRequest.current!==request)return;
      if('hospitalId' in request&&failure instanceof ApiError&&[400,404,409,410].includes(failure.status)){
        closeConfirmation();setResult(null);
      }
      setError(failure.message);
    },
    onSettled:(_response,_failure,request)=>{
      if(activeRequest.current===request)activeRequest.current=null;
    }
  });
  const auth=useMutation({
    mutationFn:()=>api<AuthResult>(`/auth/hospital/${mode}`,{method:'POST',body:JSON.stringify({businessNumber:number,password,...(mode==='register'?{verificationToken:verified?.verificationToken}:{})})}),
    onSuccess
  });
  const busy=lookup.isPending||auth.isPending;
  useEffect(()=>{onBusy(busy);return()=>onBusy(false);},[busy,onBusy]);
  useEffect(()=>{if(result)resultHeading.current?.focus();},[result]);
  useEffect(()=>{
    const dialog=confirmation.current;
    if(!candidate||!dialog)return;
    dialog.showModal();
    return ()=>dialog.close();
  },[candidate]);
  function closeConfirmation(restoreFocus=false){
    confirmation.current?.close();
    setCandidate(null);
    if(restoreFocus)choiceButton.current?.focus();
    choiceButton.current=null;
  }
  function cancelChoice(){
    if(busy||activeRequest.current)return;
    setError('');closeConfirmation(true);
  }
  function changeNumber(value:string){
    activeRequest.current=null;closeConfirmation();setBusinessNumber(formatBusinessNumber(value));setResult(null);setError('');lookup.reset();auth.reset();
  }
  function startLookup(request:LookupRequest){
    if(busy||activeRequest.current)return;
    activeRequest.current=request;
    if(!('hospitalId' in request)){closeConfirmation();setResult(null);}
    setError('');auth.reset();lookup.mutate(request);
  }
  function findHospital(){
    if(number.length!==10){setError('사업자등록번호 10자리를 입력해 주세요.');return;}
    startLookup({businessNumber:number});
  }
  function submit(event:FormEvent){
    event.preventDefault();if(busy||activeRequest.current)return;
    if(number.length!==10){setError('사업자등록번호 10자리를 입력해 주세요.');return;}
    if(mode==='register'&&!verified){setError(choices?'가입할 병원을 먼저 선택해 주세요.':'사업자등록번호로 병원을 먼저 조회해 주세요.');return;}
    if(new TextEncoder().encode(password).length>72){setError('비밀번호는 72바이트 이하로 입력해 주세요.');return;}
    setError('');auth.mutate();
  }
  return <form className="auth-form hospital-auth-form" onSubmit={submit}>
    <label className="field" htmlFor="hospital-business-number">사업자등록번호
      <div className="business-lookup-row"><input id="hospital-business-number" name="businessNumber" inputMode="numeric" autoComplete="username" value={businessNumber} onChange={e=>changeNumber(e.target.value)} placeholder="000-00-00000" maxLength={12} required pattern="[0-9]{3}-[0-9]{2}-[0-9]{5}" disabled={busy}/>
        {mode==='register'&&<button className="button secondary" type="button" disabled={busy||number.length!==10} onClick={findHospital}>{lookup.isPending&&!choices?<LoaderCircle size={17} className="spin"/>:<Search size={17}/>} {current?'다시 조회':'조회'}</button>}
      </div><small className="muted">{mode==='register'?'번호를 조회해 가입할 병원을 확인해 주세요.':'가입할 때 사용한 병원 사업자등록번호를 입력해 주세요.'}</small>
    </label>
    {mode==='register'&&choices&&<section className="verified-hospital" aria-labelledby="hospital-choice-title">
      <p className="verified-title" id="hospital-choice-title" ref={resultHeading} tabIndex={-1}>
        {choices.candidates.length===1?'가입할 병원을 확인해 주세요':`조회된 ${choices.candidates.length}곳 중 가입할 병원을 선택해 주세요`}
      </p>
      <ul className="hospital-candidates" aria-busy={lookup.isPending}>
        {choices.candidates.map(hospital=><li key={hospital.id}><button type="button" disabled={busy} aria-haspopup="dialog"
            onClick={event=>{choiceButton.current=event.currentTarget;setError('');setCandidate(hospital);}}>
            <span className="hospital-candidate-info"><strong>{hospital.name}</strong><span>{hospital.address}</span>
              {hospital.tel&&<span>전화 {formatPhone(hospital.tel)}</span>}</span>
            <ChevronRight size={18} aria-hidden="true"/>
          </button></li>)}
      </ul>
      {candidate&&<dialog className="hospital-choice-confirm" role="alertdialog" aria-labelledby="hospital-confirm-title"
        aria-describedby="hospital-confirm-description" ref={confirmation}
        onCancel={event=>{event.preventDefault();cancelChoice();}}
        onKeyDown={event=>{
          if(event.key==='Escape'){event.preventDefault();event.stopPropagation();cancelChoice();}
          if(event.key==='Tab')event.stopPropagation();
        }}>
        <h3 id="hospital-confirm-title">이 병원을 선택하시겠습니까?</h3>
        <div className="hospital-candidate-info" id="hospital-confirm-description">
          <strong>{candidate.name}</strong><span>{candidate.address}</span>
          {candidate.tel&&<span>전화 {formatPhone(candidate.tel)}</span>}
        </div>
        {error&&<div className="notice error" role="alert"><AlertCircle size={19}/><div>{error}</div></div>}
        <div className="dialog-actions">
          <button type="button" className="button secondary" disabled={busy} onClick={cancelChoice}>취소</button>
          <button type="button" className="button primary" disabled={busy}
            onClick={()=>startLookup({businessNumber:number,selectionToken:choices.selectionToken,hospitalId:candidate.id})}>
            {lookup.isPending&&<LoaderCircle size={17} className="spin"/>}{lookup.isPending?'확인 중':'선택'}
          </button>
        </div>
      </dialog>}
    </section>}
    {mode==='register'&&verified&&<div className="verified-hospital" aria-label="조회된 병원 기본정보">
      <p className="verified-title" role="status" ref={resultHeading} tabIndex={-1}><CheckCircle2 size={18}/>병원 정보를 확인했어요</p>
      <label className="field">병원명<input value={verified.hospital.name} readOnly aria-readonly="true"/></label>
      <label className="field">주소<textarea value={verified.hospital.address} readOnly aria-readonly="true" rows={2}/></label>
      <div className="form-grid"><label className="field">우편번호<input value={verified.hospital.postalCode||''} readOnly aria-readonly="true"/></label><label className="field">전화번호<input value={verified.hospital.tel?formatPhone(verified.hospital.tel):''} readOnly aria-readonly="true"/></label></div>
      <small className="muted">조회된 기본정보는 수정할 수 없어요. 전화번호가 제공되지 않으면 빈칸으로 등록돼요.</small>
    </div>}
    <label className="field" htmlFor="hospital-password">비밀번호<input id="hospital-password" name="password" type="password" autoComplete={mode==='login'?'current-password':'new-password'} value={password} onChange={e=>{setPassword(e.target.value);setError('');}} required minLength={8} maxLength={72} disabled={auth.isPending} placeholder={mode==='register'?'8자 이상 입력해 주세요':'비밀번호'}/></label>
    {!candidate&&(error||auth.isError)&&<div className="notice error" role="alert"><AlertCircle size={19}/><div>{error||auth.error?.message}</div></div>}
    <button className="button primary full" type="submit" disabled={busy||(mode==='register'&&!verified)}>{auth.isPending?<><LoaderCircle size={18} className="spin"/>처리 중</>:mode==='login'?'병원 로그인':'병원 회원가입'}</button>
    <div className="auth-note"><ShieldCheck size={16}/><span>병원 계정은 사업자등록번호로 로그인하고 소속 병원의 의료진·일정·예약을 관리해요.</span></div>
  </form>;
}
