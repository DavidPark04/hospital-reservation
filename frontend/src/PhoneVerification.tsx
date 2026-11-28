import {useEffect,useId,useRef,useState} from 'react';
import {CheckCircle2,LoaderCircle} from 'lucide-react';
import {api,ApiError} from './api';
import type {AuthProvider} from './api';
import {canonicalPhone,phoneVerificationError,validMobilePhone} from './contactVerification';
import type {PhonePurpose,VerificationProof} from './contactVerification';
import {createPhoneChallenge} from './firebasePhone';
import type {PhoneChallenge} from './firebasePhone';
import useVerificationCountdown from './useVerificationCountdown';
import './contactVerification.css';

type Props={purpose:PhonePurpose;phone:string;loginId?:string;onVerified:(token:string)=>void;disabled?:boolean;onBusy?:(busy:boolean)=>void;onExistingAccount?:(provider:AuthProvider|undefined)=>void};

export default function PhoneVerification({purpose,phone,loginId='',onVerified,disabled=false,onBusy,onExistingAccount}:Props){
  const inputId=useId();
  const contact=canonicalPhone(phone);
  const account=loginId.trim().toLowerCase();
  const context=purpose+'|'+phone+'|'+loginId;
  const current=useRef(context);current.current=context;
  const callbacks=useRef({onVerified,onBusy,onExistingAccount});callbacks.current={onVerified,onBusy,onExistingAccount};
  const recaptcha=useRef<HTMLDivElement>(null);
  const challenge=useRef<PhoneChallenge|null>(null);
  const idToken=useRef('');
  const operation=useRef(0);
  const active=useRef(false);
  const [stage,setStage]=useState<'idle'|'sending'|'sent'|'confirming'>('idle');
  const [code,setCode]=useState('');
  const [error,setError]=useState('');
  const [retryAt,setRetryAt]=useState(0);
  const [verifiedUntil,setVerifiedUntil]=useState(0);
  const retrySeconds=useVerificationCountdown(retryAt);
  const verifiedSeconds=useVerificationCountdown(verifiedUntil);
  const verified=verifiedUntil>Date.now();
  const busy=stage==='sending'||stage==='confirming';
  useEffect(()=>{
    current.current=context;operation.current++;active.current=false;challenge.current?.dispose();challenge.current=null;idToken.current='';
    setStage('idle');setCode('');setError('');setRetryAt(0);setVerifiedUntil(0);callbacks.current.onVerified('');callbacks.current.onExistingAccount?.(undefined);
    return()=>{current.current='';operation.current++;challenge.current?.dispose();challenge.current=null;};
  },[context]);
  useEffect(()=>{callbacks.current.onBusy?.(busy);return()=>callbacks.current.onBusy?.(false);},[busy]);
  useEffect(()=>{if(verifiedUntil&&verifiedSeconds===0){callbacks.current.onVerified('');callbacks.current.onExistingAccount?.(undefined);setVerifiedUntil(0);setError('문자 인증 시간이 지났어요. 다시 인증해 주세요.');}},[verifiedUntil,verifiedSeconds]);
  function cancel(){
    operation.current++;active.current=false;challenge.current?.dispose();challenge.current=null;idToken.current='';
    setStage('idle');setCode('');setError('');callbacks.current.onExistingAccount?.(undefined);
  }
  async function send(){
    if(active.current||disabled||retrySeconds||!recaptcha.current)return;
    if(!validMobilePhone(contact)){setError('문자를 받을 수 있는 휴대전화번호를 입력해 주세요.');return;}
    if(purpose==='PASSWORD_RESET'&&!/^[a-z0-9_-]{4,30}$/.test(account)){setError('가입한 아이디를 먼저 입력해 주세요.');return;}
    const requested=context;const requestId=++operation.current;active.current=true;setStage('sending');setError('');setCode('');setVerifiedUntil(0);callbacks.current.onVerified('');callbacks.current.onExistingAccount?.(undefined);
    challenge.current?.dispose();challenge.current=null;idToken.current='';
    try{
      const session=await createPhoneChallenge(recaptcha.current);
      if(current.current!==requested||operation.current!==requestId){session.dispose();return;}
      challenge.current=session;
      await session.send(contact);
      if(current.current!==requested||operation.current!==requestId)return;
      setStage('sent');setRetryAt(Date.now()+60000);
    }catch(reason){
      if(current.current!==requested||operation.current!==requestId)return;
      challenge.current?.dispose();challenge.current=null;setStage('idle');setError(phoneVerificationError(reason));
    }finally{if(operation.current===requestId)active.current=false;}
  }
  async function confirm(){
    if(active.current||disabled||!challenge.current)return;
    if(!/^\d{6}$/.test(code)){setError('문자로 받은 인증번호 6자리를 입력해 주세요.');return;}
    const requested=context;const requestId=++operation.current;active.current=true;setStage('confirming');setError('');
    try{
      if(!idToken.current)idToken.current=await challenge.current.confirm(code);
      if(current.current!==requested||operation.current!==requestId)return;
      const result=await api<VerificationProof>('/auth/verification/phone/confirm',{method:'POST',body:JSON.stringify({purpose,idToken:idToken.current,...(purpose==='PASSWORD_RESET'?{loginId:account}:{})})});
      if(current.current!==requested||operation.current!==requestId)return;
      if(canonicalPhone(result.contact)!==contact)throw new Error('휴대전화번호가 변경됐어요. 다시 인증해 주세요.');
      setVerifiedUntil(Date.now()+result.expiresIn*1000);callbacks.current.onVerified(result.verificationToken);
      const provider=result.existingAuthProvider;
      callbacks.current.onExistingAccount?.(purpose==='SIGNUP'&&(provider==='LOCAL'||provider==='GOOGLE'||provider==='KAKAO')?provider:undefined);
      challenge.current?.dispose();challenge.current=null;idToken.current='';setCode('');setStage('idle');
    }catch(reason){
      if(current.current!==requested||operation.current!==requestId)return;
      setError(phoneVerificationError(reason));setStage('sent');
      if(reason instanceof ApiError&&[400,401,403,404,409,410].includes(reason.status)){
        challenge.current?.dispose();challenge.current=null;idToken.current='';setStage('idle');setRetryAt(0);
      }
    }finally{if(operation.current===requestId)active.current=false;}
  }
  return <div className="contact-verification" aria-label="문자 인증">
    {verified?<p className="contact-verified" role="status"><CheckCircle2 size={16}/>휴대전화 인증 완료</p>:<>
      <button className="button secondary small" type="button" onClick={send} disabled={disabled||busy||!contact||retrySeconds>0}>
        {stage==='sending'&&<LoaderCircle size={16} className="spin"/>}{stage==='sent'?'인증번호 다시 받기':'문자 인증번호 받기'}{retrySeconds>0?` (${retrySeconds}초)`:''}
      </button>
      <p className="verification-help">문자 인증을 요청하면 전화번호가 Google에 전달돼요.</p>
      {stage==='sending'&&<><p className="verification-help" role="status">자동입력 방지 확인을 완료해 주세요.</p><button className="text-button" type="button" onClick={cancel}>인증 요청 취소</button></>}
      {(stage==='sent'||stage==='confirming')&&<><p className="verification-help" role="status">문자로 받은 6자리 인증번호를 입력해 주세요.</p>
        <div className="verification-code-row"><label className="field" htmlFor={inputId}>문자 인증번호<input id={inputId} inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e=>{setCode(e.target.value.replace(/\D/g,'').slice(0,6));setError('');}} maxLength={6} disabled={disabled||busy} placeholder="6자리 인증번호"/></label>
          <button className="button secondary" type="button" onClick={confirm} disabled={disabled||busy||code.length!==6}>{stage==='confirming'?<LoaderCircle size={16} className="spin"/>:'인증 확인'}</button></div></>}
    </>}
    <div className="verification-recaptcha" ref={recaptcha} hidden={stage!=='sending'}/>
    {error&&<p className="verification-error" role="alert">{error}</p>}
  </div>;
}
