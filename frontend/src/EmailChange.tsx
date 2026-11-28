import {useRef,useState} from 'react';
import type {FormEvent} from 'react';
import {useMutation} from '@tanstack/react-query';
import {AlertCircle,CheckCircle2,LoaderCircle,Mail} from 'lucide-react';
import {api,beginSessionRenewal,getToken} from './api';
import type {AuthResult,User} from './api';
import EmailVerification from './EmailVerification';

type EmailRequest={email:string;emailVerificationToken:string;sessionToken:string};

export default function EmailChange({user,onChanged}:{user:User;onChanged:(result:AuthResult,previousToken:string)=>void}){
  const [open,setOpen]=useState(false);
  const [email,setEmail]=useState('');
  const [proof,setProof]=useState('');
  const [verifying,setVerifying]=useState(false);
  const [message,setMessage]=useState('');
  const busy=useRef(false);
  const finishRenewal=useRef<(()=>void)|null>(null);
  const update=useMutation({
    mutationFn:({email,emailVerificationToken}:EmailRequest)=>api<AuthResult>('/auth/email',{method:'PATCH',body:JSON.stringify({email,emailVerificationToken})}),
    onSuccess:(result,request)=>{
      onChanged(result,request.sessionToken);setOpen(false);setEmail('');setProof('');
      setMessage('이메일 주소를 변경했어요.');
    },
    onSettled:()=>{busy.current=false;finishRenewal.current?.();finishRenewal.current=null;}
  });
  function close(){if(busy.current||verifying)return;setOpen(false);setEmail('');setProof('');update.reset();}
  function submit(event:FormEvent){
    event.preventDefault();
    if(busy.current||verifying||!proof)return;
    const sessionToken=getToken();
    if(!sessionToken){window.dispatchEvent(new Event('auth-expired'));return;}
    busy.current=true;finishRenewal.current=beginSessionRenewal(sessionToken);
    update.mutate({email:email.trim(),emailVerificationToken:proof,sessionToken});
  }
  return <section className="password-settings" aria-labelledby="email-settings-title">
    <div className="password-settings-heading"><div><h3 id="email-settings-title"><Mail size={18}/>이메일</h3><p>{user.email||'이메일 미등록'} · 새 이메일 주소를 인증한 뒤 변경할 수 있어요.</p></div>
      {!open&&<button className="button outline small" type="button" onClick={()=>{setMessage('');update.reset();setOpen(true);}}>이메일 변경</button>}
    </div>
    {!open&&message&&<div className="notice password-success" role="status"><CheckCircle2 size={19}/><div>{message}</div></div>}
    {open&&<form className="auth-form password-change-form" onSubmit={submit}>
      <label className="field" htmlFor="change-email">새 이메일<input id="change-email" type="email" autoComplete="email" maxLength={254} value={email}
        onChange={event=>{setEmail(event.target.value);setProof('');update.reset();}} disabled={update.isPending||verifying||!!proof} required autoFocus/></label>
      <EmailVerification purpose="EMAIL_CHANGE" email={email} onVerified={setProof} onBusy={setVerifying} disabled={update.isPending}/>
      {update.isError&&<div className="notice error" role="alert"><AlertCircle size={19}/><div>{update.error.message}</div></div>}
      <div className="dialog-actions"><button type="button" className="button secondary" onClick={close} disabled={update.isPending||verifying}>취소</button>
        <button className="button primary" type="submit" disabled={update.isPending||verifying||!proof}>{update.isPending?<><LoaderCircle size={17} className="spin"/>변경 중</>:'이메일 변경'}</button></div>
    </form>}
  </section>;
}
