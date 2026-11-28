import {AlertCircle,LoaderCircle} from 'lucide-react';
import type {AuthProvider} from './api';
import {loginMethod} from './SocialLogin';

export default function MemberConflictNotice({provider,onLogin,disabled=false,loading=false}:{provider:AuthProvider;onLogin:()=>void;disabled?:boolean;loading?:boolean}){
  const signupMethod=provider==='LOCAL'?'일반 가입':`${loginMethod(provider)} 가입`;
  return <div className="member-conflict-notice">
    <div className="notice" role="status"><AlertCircle size={19}/><div>이 휴대전화번호는 {signupMethod}으로 등록되어 있어요. {loginMethod(provider)}로 로그인해 주세요.</div></div>
    <button className="button secondary full" type="button" onClick={onLogin} disabled={disabled}>{loading?<><LoaderCircle size={18} className="spin"/>로그인 화면으로 이동 중</>:`${loginMethod(provider)}로 로그인하기`}</button>
  </div>;
}
