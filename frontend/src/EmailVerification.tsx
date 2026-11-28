import {useEffect,useId,useRef,useState} from 'react';
import {CheckCircle2,LoaderCircle} from 'lucide-react';
import {api,ApiError} from './api';
import {canonicalEmail} from './contactVerification';
import type {EmailPurpose,VerificationProof} from './contactVerification';
import useVerificationCountdown from './useVerificationCountdown';
import './contactVerification.css';

type Props={purpose:EmailPurpose;email:string;onVerified:(token:string)=>void;disabled?:boolean;onBusy?:(busy:boolean)=>void;compact?:boolean};
type Challenge={challengeId:string;expiresIn:number;retryAfter:number};

export default function EmailVerification({purpose,email,onVerified,disabled=false,onBusy,compact=false}:Props){
  const inputId=useId();
  const contact=canonicalEmail(email);
  const context=purpose+'|'+email;
  const current=useRef(context);current.current=context;
  const callbacks=useRef({onVerified,onBusy});callbacks.current={onVerified,onBusy};
  const active=useRef(false);
  const operation=useRef(0);
  const [busy,setBusy]=useState(false);
  const [challenge,setChallenge]=useState<Challenge|null>(null);
  const [code,setCode]=useState('');
  const [error,setError]=useState('');
  const [retryAt,setRetryAt]=useState(0);
  const [expiresAt,setExpiresAt]=useState(0);
  const [verifiedUntil,setVerifiedUntil]=useState(0);
  const retrySeconds=useVerificationCountdown(retryAt);
  const codeSeconds=useVerificationCountdown(expiresAt);
  const verifiedSeconds=useVerificationCountdown(verifiedUntil);
  const verified=verifiedUntil>Date.now();
  useEffect(()=>{
    current.current=context;operation.current++;active.current=false;setBusy(false);setChallenge(null);setCode('');setError('');setRetryAt(0);setExpiresAt(0);setVerifiedUntil(0);
    callbacks.current.onVerified('');
    return()=>{current.current='';operation.current++;};
  },[context]);
  useEffect(()=>{callbacks.current.onBusy?.(busy);return()=>callbacks.current.onBusy?.(false);},[busy]);
  useEffect(()=>{if(verifiedUntil&&verifiedSeconds===0){callbacks.current.onVerified('');setVerifiedUntil(0);setError('이메일 인증 시간이 지났어요. 다시 인증해 주세요.');}},[verifiedUntil,verifiedSeconds]);
  async function send(){
    if(active.current||disabled||retrySeconds)return;
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact)){setError('이메일 주소를 먼저 입력해 주세요.');return;}
    const requested=context;const requestId=++operation.current;active.current=true;setBusy(true);setError('');callbacks.current.onVerified('');setVerifiedUntil(0);
    try{
      const result=await api<Challenge>('/auth/verification/email/send',{method:'POST',body:JSON.stringify({purpose,email:contact})});
      if(current.current!==requested||operation.current!==requestId)return;
      setChallenge(result);setCode('');setRetryAt(Date.now()+result.retryAfter*1000);setExpiresAt(Date.now()+result.expiresIn*1000);
    }catch(reason){if(current.current===requested&&operation.current===requestId)setError(reason instanceof Error?reason.message:'이메일을 보내지 못했어요. 다시 시도해 주세요.');}
    finally{if(operation.current===requestId){active.current=false;setBusy(false);}}
  }
  async function confirm(){
    if(active.current||disabled||!challenge)return;
    if(!/^\d{6}$/.test(code)){setError('이메일로 받은 인증번호 6자리를 입력해 주세요.');return;}
    const requested=context;const requestId=++operation.current;active.current=true;setBusy(true);setError('');
    try{
      const result=await api<VerificationProof>('/auth/verification/email/confirm',{method:'POST',body:JSON.stringify({challengeId:challenge.challengeId,code})});
      if(current.current!==requested||operation.current!==requestId)return;
      if(canonicalEmail(result.contact)!==contact)throw new Error('이메일 주소가 변경됐어요. 다시 인증해 주세요.');
      setChallenge(null);setCode('');setVerifiedUntil(Date.now()+result.expiresIn*1000);callbacks.current.onVerified(result.verificationToken);
    }catch(reason){
      if(current.current!==requested||operation.current!==requestId)return;
      if(reason instanceof ApiError&&[404,410].includes(reason.status)){setChallenge(null);setRetryAt(0);}
      setError(reason instanceof Error?reason.message:'이메일 인증을 완료하지 못했어요. 다시 시도해 주세요.');
    }finally{if(operation.current===requestId){active.current=false;setBusy(false);}}
  }
  return <div className="contact-verification" aria-label="이메일 인증">
    {verified?<p className="contact-verified" role="status"><CheckCircle2 size={16}/>이메일 인증 완료</p>:<>
      <button className="button secondary small" type="button" onClick={send} disabled={disabled||busy||!contact||retrySeconds>0}
        aria-label={compact ? (challenge?'이메일 인증번호 다시 받기':'이메일 인증 요청') : undefined}>
        {busy&&!challenge?<LoaderCircle size={16} className="spin"/>:null}{compact ? (challenge?'다시 받기':'인증 요청') : (challenge?'인증번호 다시 보내기':'이메일 인증번호 받기')}{retrySeconds>0?` (${retrySeconds}초)`:''}
      </button>
      {challenge&&<><p className="verification-help" role="status">이메일로 인증번호를 보냈어요. {codeSeconds>0?`${Math.floor(codeSeconds/60)}분 ${codeSeconds%60}초 안에 입력해 주세요.`:'인증번호가 만료됐어요. 다시 받아 주세요.'}</p>
        <div className="verification-code-row"><label className="field" htmlFor={inputId}>이메일 인증번호<input id={inputId} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e=>{setCode(e.target.value.replace(/\D/g,'').slice(0,6));setError('');}} maxLength={6} disabled={disabled||busy} placeholder="6자리 인증번호"/></label>
          <button className="button secondary" type="button" onClick={confirm} disabled={disabled||busy||code.length!==6||codeSeconds===0}>{busy?<LoaderCircle size={16} className="spin"/>:'인증 확인'}</button></div></>}
    </>}
    {error&&<p className="verification-error" role="alert">{error}</p>}
  </div>;
}
