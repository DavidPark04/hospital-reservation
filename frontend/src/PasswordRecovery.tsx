import {useRef,useState} from 'react';
import type {FormEvent} from 'react';
import {CheckCircle2,LoaderCircle} from 'lucide-react';
import {api} from './api';
import PhoneInput from './PhoneInput';
import PhoneVerification from './PhoneVerification';
import {validMobilePhone} from './contactVerification';

export default function PasswordRecovery({onBack,onBusy}:{onBack:()=>void;onBusy:(busy:boolean)=>void}){
  const [loginId,setLoginId]=useState('');
  const [phone,setPhone]=useState('');
  const [phoneToken,setPhoneToken]=useState('');
  const [password,setPassword]=useState('');
  const [confirmation,setConfirmation]=useState('');
  const [verifyBusy,setVerifyBusy]=useState(false);
  const [saving,setSaving]=useState(false);
  const [complete,setComplete]=useState(false);
  const [error,setError]=useState('');
  const active=useRef(false);
  const busy=saving||verifyBusy;
  function verificationBusy(value:boolean){setVerifyBusy(value);onBusy(value||saving);}
  async function submit(event:FormEvent){
    event.preventDefault();if(active.current||busy)return;
    if(!phoneToken||!validMobilePhone(phone)){setError('가입한 휴대전화번호로 문자 인증을 완료해 주세요.');return;}
    if(password.length<8||new TextEncoder().encode(password).length>72){setError('새 비밀번호는 8자 이상, 72바이트 이하로 입력해 주세요.');return;}
    if(password!==confirmation){setError('새 비밀번호가 서로 다릅니다. 다시 확인해 주세요.');return;}
    active.current=true;setSaving(true);onBusy(true);setError('');
    try{
      await api<{message:string}>('/auth/password/reset',{method:'POST',body:JSON.stringify({loginId:loginId.trim(),newPassword:password,phoneVerificationToken:phoneToken})});
      setPassword('');setConfirmation('');setPhoneToken('');setComplete(true);
    }catch(reason){setError(reason instanceof Error?reason.message:'비밀번호를 재설정하지 못했어요. 다시 시도해 주세요.');}
    finally{active.current=false;setSaving(false);onBusy(false);}
  }
  if(complete)return <div className="password-recovery-success"><p role="status" className="contact-verified"><CheckCircle2 size={19}/>비밀번호를 재설정했어요.</p><p>새 비밀번호로 로그인해 주세요.</p><button className="button primary full" type="button" onClick={onBack}>로그인으로 돌아가기</button></div>;
  return <>
    <p className="dialog-copy">가입한 아이디와 휴대전화번호로 인증하면 비밀번호를 다시 설정할 수 있어요.</p>
    <form className="auth-form" onSubmit={submit}>
      <label className="field">아이디<input value={loginId} onChange={e=>{setLoginId(e.target.value);setPhoneToken('');setError('');}} autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={30} required disabled={busy}/></label>
      <div className="auth-contact-block">
        <label className="field">가입한 휴대전화번호<PhoneInput className={phoneToken?'contact-locked':undefined} value={phone} onValueChange={value=>{setPhone(value);setPhoneToken('');setError('');}} placeholder="010-1234-5678" required disabled={busy||!!phoneToken}/></label>
        <PhoneVerification compact purpose="PASSWORD_RESET" phone={phone} loginId={loginId} onVerified={setPhoneToken} onBusy={verificationBusy} disabled={saving}/>
      </div>
      {phoneToken&&<><label className="field">새 비밀번호<input type="password" autoComplete="new-password" value={password} onChange={e=>{setPassword(e.target.value);setError('');}} minLength={8} maxLength={72} required disabled={saving}/></label>
        <label className="field">새 비밀번호 확인<input type="password" autoComplete="new-password" value={confirmation} onChange={e=>{setConfirmation(e.target.value);setError('');}} minLength={8} maxLength={72} required disabled={saving}/></label></>}
      {error&&<p className="verification-error" role="alert">{error}</p>}
      <button className="button primary full" type="submit" disabled={busy||!phoneToken}>{saving?<><LoaderCircle size={17} className="spin"/>변경 중</>:'비밀번호 재설정'}</button>
    </form>
    <button className="text-button password-recovery-link" type="button" disabled={busy} onClick={onBack}>로그인으로 돌아가기</button>
  </>;
}
