import {api} from './api';
import type {AuthProvider} from './api';

export type EmailPurpose = 'SIGNUP'|'EMAIL_CHANGE';
export type PhonePurpose = 'SIGNUP'|'PASSWORD_CHANGE'|'WITHDRAWAL'|'PASSWORD_RESET';
export type VerificationProof = {verificationToken:string;expiresIn:number;contact:string;existingAuthProvider?:AuthProvider};
export type VerificationConfig = {emailEnabled:boolean;phoneEnabled:boolean;firebase:{apiKey:string;authDomain:string;projectId:string}};

export function validMobilePhone(value:string){return /^01[016789][0-9]{7,8}$/.test(value.replace(/\D/g,''));}
export function canonicalEmail(value:string){return value.trim().toLowerCase();}
export function canonicalPhone(value:string){return value.replace(/\D/g,'');}

export function verificationConfig(){return api<VerificationConfig>('/auth/verification/config');}

export function phoneVerificationError(error:unknown){
  const code=error&&typeof error==='object'&&'code' in error?String(error.code):'';
  if(code==='auth/invalid-verification-code')return '문자로 받은 인증번호를 확인해 주세요.';
  if(code==='auth/code-expired'||code==='auth/session-expired')return '문자 인증번호가 만료됐어요. 인증번호를 다시 받아 주세요.';
  if(code==='auth/too-many-requests'||code==='auth/quota-exceeded')return '문자 요청이 많아요. 잠시 후 다시 시도해 주세요.';
  if(code==='auth/invalid-phone-number')return '문자를 받을 수 있는 휴대전화번호를 입력해 주세요.';
  if(code==='auth/captcha-check-failed'||code==='auth/missing-app-credential'||code==='auth/invalid-app-credential')return '자동입력 방지 확인을 다시 진행해 주세요.';
  if(code==='auth/unauthorized-domain'||code==='auth/operation-not-allowed'||code==='auth/billing-not-enabled'||code==='auth/invalid-api-key'||code==='auth/app-not-authorized')return '문자 인증 서비스 설정을 확인해야 해요. 잠시 후 다시 시도해 주세요.';
  if(code==='auth/network-request-failed')return '문자 인증 서비스에 연결할 수 없어요. 연결 상태를 확인해 주세요.';
  return error instanceof Error&&!code?error.message:'문자 인증을 처리하지 못했어요. 다시 시도해 주세요.';
}
