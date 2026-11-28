import type {ConfirmationResult,RecaptchaVerifier} from 'firebase/auth';
import {verificationConfig} from './contactVerification';

export type PhoneChallenge = {send:(phone:string)=>Promise<void>;confirm:(code:string)=>Promise<string>;dispose:()=>void};

export async function createPhoneChallenge(container:HTMLElement):Promise<PhoneChallenge>{
  const [config,appModule,authModule]=await Promise.all([verificationConfig(),import('firebase/app'),import('firebase/auth')]);
  if(!config.phoneEnabled)throw new Error('문자 인증 서비스 준비 중이에요. 잠시 후 다시 시도해 주세요.');
  const app=appModule.initializeApp(config.firebase,'medipick-phone-'+crypto.randomUUID());
  const auth=authModule.initializeAuth(app,{persistence:authModule.inMemoryPersistence});
  auth.languageCode='ko';
  let verifier:RecaptchaVerifier|null=null;
  let confirmation:ConfirmationResult|null=null;
  let disposed=false;
  function dispose(){
    if(disposed)return;
    disposed=true;verifier?.clear();confirmation=null;
    void appModule.deleteApp(app);
  }
  try {
    verifier=new authModule.RecaptchaVerifier(auth,container,{size:'normal'});
    await verifier.render();
    return {
      async send(phone){
        if(disposed||!verifier)throw new Error('문자 인증을 다시 시작해 주세요.');
        confirmation=await authModule.signInWithPhoneNumber(auth,'+82'+phone.slice(1),verifier);
      },
      async confirm(code){
        if(disposed||!confirmation)throw new Error('인증번호를 먼저 받아 주세요.');
        const credential=await confirmation.confirm(code);
        return credential.user.getIdToken();
      },
      dispose
    };
  } catch(error){dispose();throw error;}
}
