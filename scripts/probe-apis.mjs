import {loadLocalEnv} from './local-env.mjs';
const env=loadLocalEnv();
const report={model:env.OPENAI_MODEL,configured:{hospital:!!env.HIRA_HOSPITAL_KEY,detail:!!env.HIRA_DETAIL_KEY,openai:!!env.OPENAI_API_KEY}};
try {
  const url=new URL('https://apis.data.go.kr/B551182/hospInfoServicev2/getHospBasisList');
  url.searchParams.set('serviceKey',decodeURIComponent(env.HIRA_HOSPITAL_KEY || ''));
  url.searchParams.set('numOfRows','1'); url.searchParams.set('dgsbjtCd','01');
  const res=await fetch(url,{signal:AbortSignal.timeout(45000)}); const body=await res.text();
  report.hospital={status:res.status,resultCode:body.match(/<resultCode>(.*?)<\/resultCode>/)?.[1]||null,hasItems:body.includes('<item>')};
  const id=body.match(/<ykiho>(.*?)<\/ykiho>/)?.[1];
  if(id) {
    const detail=new URL('https://apis.data.go.kr/B551182/MadmDtlInfoService2.8/getDgsbjtInfo2.8');
    detail.searchParams.set('serviceKey',decodeURIComponent(env.HIRA_DETAIL_KEY||''));detail.searchParams.set('ykiho',id);detail.searchParams.set('numOfRows','20');
    const dr=await fetch(detail,{signal:AbortSignal.timeout(45000)});const db=await dr.text();
    report.detail={status:dr.status,resultCode:db.match(/<resultCode>(.*?)<\/resultCode>/)?.[1]||null,hasItems:db.includes('<item>')};
  }
} catch(error) {report.hiraError={type:error.name,code:error.cause?.code||null};}
try {
  const res=await fetch('https://api.openai.com/v1/responses',{method:'POST',headers:{Authorization:`Bearer ${env.OPENAI_API_KEY}`,'Content-Type':'application/json'},body:JSON.stringify({model:env.OPENAI_MODEL,input:'Reply with OK only.',reasoning:{effort:'low'},max_output_tokens:128,store:false}),signal:AbortSignal.timeout(45000)});
  const body=await res.json();
  report.openai={status:res.status,model:body.model||env.OPENAI_MODEL,errorCode:body.error?.code||null,responseStatus:body.status||null,hasOutput:!!body.output?.some(item=>item.type==='message')};
} catch(error) {report.openaiError={type:error.name,code:error.cause?.code||null};}
console.log(JSON.stringify(report,null,2));
