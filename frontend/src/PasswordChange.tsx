import {useRef,useState} from 'react';
import type {FormEvent} from 'react';
import {useMutation} from '@tanstack/react-query';
import {AlertCircle,CheckCircle2,KeyRound,LoaderCircle} from 'lucide-react';
import {api,ApiError,beginSessionRenewal,clearToken,getToken} from './api';
import type {AuthResult,User} from './api';
import {loginMethod} from './SocialLogin';
import {passwordChangeError} from './passwordValidation';

type PasswordRequest={currentPassword:string;newPassword:string;sessionToken:string};

export default function PasswordChange({user,onChanged}:{user:User;onChanged:(result:AuthResult,previousToken:string)=>void}){
  const [open,setOpen]=useState(false);
  const [currentPassword,setCurrentPassword]=useState('');
  const [newPassword,setNewPassword]=useState('');
  const [confirmation,setConfirmation]=useState('');
  const [error,setError]=useState('');
  const [success,setSuccess]=useState('');
  const busy=useRef(false);
  const finishRenewal=useRef<(()=>void)|null>(null);
  const mutation=useMutation({
    mutationFn:({currentPassword,newPassword}:PasswordRequest)=>api<AuthResult>('/auth/password',{method:'PATCH',body:JSON.stringify({currentPassword,newPassword})}),
    onSuccess:(result,request)=>{
      onChanged(result,request.sessionToken);resetFields();setOpen(false);
      setSuccess('비밀번호를 변경했어요. 다음 로그인부터 새 비밀번호를 사용해 주세요.');
    },
    onError:(reason,request)=>{
      if(reason instanceof ApiError&&reason.status===401&&getToken()===request.sessionToken){clearToken();window.dispatchEvent(new Event('auth-expired'));}
    },
    onSettled:()=>{finishRenewal.current?.();finishRenewal.current=null;busy.current=false;}
  });
  function resetFields(){setCurrentPassword('');setNewPassword('');setConfirmation('');setError('');}
  function close(){if(busy.current)return;resetFields();mutation.reset();setOpen(false);}
  function submit(event:FormEvent){
    event.preventDefault();if(busy.current)return;
    const message=passwordChangeError(currentPassword,newPassword,confirmation);
    if(message){setError(message);return;}
    const sessionToken=getToken();
    if(!sessionToken){window.dispatchEvent(new Event('auth-expired'));return;}
    setError('');setSuccess('');busy.current=true;finishRenewal.current=beginSessionRenewal(sessionToken);
    mutation.mutate({currentPassword,newPassword,sessionToken});
  }
  const social=user.authProvider==='GOOGLE'||user.authProvider==='KAKAO';
  return <section className="password-settings" aria-labelledby="password-settings-title">
    <div className="password-settings-heading"><div><h3 id="password-settings-title"><KeyRound size={18}/>비밀번호</h3><p>{social?`${loginMethod(user.authProvider)} 계정의 비밀번호는 해당 서비스에서 변경할 수 있어요.`:'현재 비밀번호를 확인한 뒤 새 비밀번호로 변경할 수 있어요.'}</p></div>
      {!social&&!open&&<button className="button outline small" type="button" onClick={()=>{mutation.reset();resetFields();setSuccess('');setOpen(true);}}>비밀번호 변경</button>}
    </div>
    {!social&&success&&<div className="notice password-success" role="status"><CheckCircle2 size={19}/><div>{success}</div></div>}
    {!social&&open&&<form className="auth-form password-change-form" onSubmit={submit}>
      <label className="field" htmlFor="password-current">현재 비밀번호<input id="password-current" name="currentPassword" type="password" autoComplete="current-password" value={currentPassword} onChange={event=>{setCurrentPassword(event.target.value);setError('');}} required maxLength={100} disabled={mutation.isPending} autoFocus/></label>
      <label className="field" htmlFor="password-new">새 비밀번호<input id="password-new" name="newPassword" type="password" autoComplete="new-password" value={newPassword} onChange={event=>{setNewPassword(event.target.value);setError('');}} required minLength={8} maxLength={72} disabled={mutation.isPending} aria-describedby="password-new-help"/><small className="muted" id="password-new-help">8자 이상 입력해 주세요. 영문·숫자는 최대 72자, 한글은 최대 24자까지 사용할 수 있어요.</small></label>
      <label className="field" htmlFor="password-confirm">새 비밀번호 확인<input id="password-confirm" name="newPasswordConfirmation" type="password" autoComplete="new-password" value={confirmation} onChange={event=>{setConfirmation(event.target.value);setError('');}} required minLength={8} maxLength={72} disabled={mutation.isPending}/></label>
      {(error||mutation.isError)&&<div className="notice error" role="alert"><AlertCircle size={19}/><div>{error||mutation.error?.message}</div></div>}
      <p className="password-settings-help">변경 후 현재 로그인은 유지되고, 다른 곳에서 로그인한 세션은 종료돼요.</p>
      <div className="password-change-actions"><button className="button secondary" type="button" onClick={close} disabled={mutation.isPending}>취소</button><button className="button primary" type="submit" disabled={mutation.isPending}>{mutation.isPending?<><LoaderCircle size={17} className="spin"/>변경 중</>:'변경하기'}</button></div>
    </form>}
  </section>;
}
