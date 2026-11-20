import { useEffect, useRef, useState } from 'react';
import { CalendarCheck2, Check, CheckCircle2, HeartPulse, MapPin, MessageCircle, Plus, ShieldCheck, Sparkles } from 'lucide-react';

const STORIES = [
  {label:'편안한 시작',title:<>어려운 의학 용어 없이.<br/>내 말로, 편하게.</>,description:<>어디가 어떻게 불편한지 알려주세요.<br/>필요한 정보를 하나씩 확인하고,<br/>찾아볼 진료과를 함께 정리해요.</>,note:'진단을 대신하지 않는 진료과 안내'},
  {label:'이유를 아는 선택',title:<>왜 이 병원인지.<br/>근거부터 살펴봐요.</>,description:<>확인된 진료과와 병원 정보,<br/>어디에서 가져온 정보인지까지.<br/>내게 맞는 선택을 직접 할 수 있도록.</>,note:'공공데이터 출처와 조회 시각 제공'},
  {label:'내 일정에 맞게',title:<>찾는 데서 끝나지 않게.<br/>예약까지, 한 번에.</>,description:<>의료진을 고르고 가능한 시간을 선택하세요.<br/>예약한 일정은 내 예약에서<br/>간편하게 확인하고 변경할 수 있어요.</>,note:'의료진·시간표는 가상 데이터인 모의 예약'},
];

export default function ScrollStory(){
  const root = useRef<HTMLElement>(null);
  const [active,setActive]=useState(0);
  useEffect(()=>{
    const element=root.current;if(!element)return;
    const cards=Array.from(element.querySelectorAll<HTMLElement>('.story-copy'));
    const reveal=new IntersectionObserver(entries=>entries.forEach(entry=>{if(entry.isIntersecting)entry.target.classList.add('revealed');}),{threshold:.2});
    cards.forEach(card=>reveal.observe(card));
    let frame=0;
    function update(){frame=0;let nearest=0;let distance=Infinity;cards.forEach((card,index)=>{const r=card.getBoundingClientRect();const d=Math.abs(r.top+r.height/2-window.innerHeight/2);if(d<distance){distance=d;nearest=index;}});setActive(nearest);const r=element!.getBoundingClientRect();const progress=Math.max(0,Math.min(1,-r.top/(r.height-window.innerHeight)));element!.style.setProperty('--story-progress',String(progress));}
    function scroll(){if(!frame)frame=requestAnimationFrame(update);}
    window.addEventListener('scroll',scroll,{passive:true});window.addEventListener('resize',scroll);update();
    return()=>{reveal.disconnect();window.removeEventListener('scroll',scroll);window.removeEventListener('resize',scroll);cancelAnimationFrame(frame);};
  },[]);
  return <section className="scroll-story" ref={root} aria-label="병원예약도우미 이용 흐름"><div className="story-intro"><span>복잡했던 병원 찾기</span><h2>세 번의 선택으로<br/><em>더 편안해지도록.</em></h2></div><div className="story-grid"><div className="story-copies">{STORIES.map((story,index)=><div className="story-copy" key={story.label}><div className="story-copy-inner"><span className="story-label">{story.label}</span><h3>{story.title}</h3><p>{story.description}</p><div className="story-assurance"><ShieldCheck size={17}/>{story.note}</div><div className="story-mobile-art"><StoryVisual stage={index}/></div></div></div>)}</div><div className="story-sticky" aria-hidden="true"><div className="story-art-surface"><div className="story-orb orb-a"/><div className="story-orb orb-b"/><div className="story-visual-stack">{STORIES.map((story,index)=><div key={story.label} className={`story-stage ${active===index?'active':''}`}><StoryVisual stage={index}/></div>)}</div><div className="story-stage-indicator">{STORIES.map((story,index)=><span className={active===index?'active':''} key={story.label}>{index===0?<MessageCircle size={15}/>:index===1?<ShieldCheck size={15}/>:<CalendarCheck2 size={15}/>}<b>{['상담','근거','예약'][index]}</b></span>)}</div></div></div></div><div className="story-end"><HeartPulse size={28}/><p>조금 덜 고민하고,<br/><strong>내 건강에 더 집중할 수 있도록.</strong></p><a className="button primary" href="#/chat">AI 상담으로 시작하기</a></div></section>;
}

function StoryVisual({stage}:{stage:number}){
  if(stage===0)return <div className="story-phone chat-phone"><div className="phone-top"><span className="phone-brand"><Plus size={19}/></span><span>진료과 안내</span><span className="phone-mini">AI</span></div><div className="story-phone-body"><span className="phone-greeting">안녕하세요.<br/><strong>어디가 불편하세요?</strong></span><div className="story-chat user">목이 붓고 아파요.<br/>수원 영통 근처면 좋겠어요.</div><div className="story-chat assistant"><span className="small-spark"><Sparkles size={17}/></span><span>증상은 언제부터 시작됐나요?</span></div><div className="story-chat user short">어제 저녁부터요.</div><div className="story-condition"><span><Check size={14}/>증상 확인</span><span><Check size={14}/>지역 확인</span></div></div><div className="phone-input"><span>메시지를 입력해 주세요</span><Plus size={18}/></div><span className="story-example-label">이용 흐름 예시</span></div>;
  if(stage===1)return <div className="story-evidence"><div className="evidence-floating"><ShieldCheck size={22}/><span>정보의 출처까지<br/><strong>확인할 수 있어요</strong></span></div><div className="story-evidence-card"><div className="story-hospital-icon"><Plus size={34}/></div><span className="story-card-eyebrow">내게 맞는 진료과</span><h4>이비인후과</h4><p>희망 지역의 병원을 살펴보세요.</p><div className="story-evidence-row"><CheckCircle2 size={18}/><span>진료과 정보 확인</span><b>확인</b></div><div className="story-evidence-row"><MapPin size={18}/><span>선택한 지역</span><b>수원</b></div><div className="story-source-label"><ShieldCheck size={15}/><div>건강보험심사평가원<small>출처와 조회 시각 함께 표시</small></div></div></div><span className="story-example-label">화면 구성 예시 · 실제 검색 결과 아님</span></div>;
  return <div className="story-booking"><div className="story-booking-card"><div className="story-calendar-title"><span>나에게 맞는 시간</span><CalendarCheck2 size={23}/></div><div className="story-week">{['월','화','수','목','금'].map((day,index)=><div className={index===2?'picked':''} key={day}><span>{day}</span><strong>{23+index}</strong></div>)}</div><div className="story-times">{['09:00','09:30','10:00','10:30','11:00','14:00'].map((time,index)=><span key={time} className={index===3?'picked':''}>{time}</span>)}</div><div className="story-confirmed"><span><Check size={24}/></span><strong>모의 예약 완료</strong><p>내 예약에서 다시 확인할 수 있어요.</p></div></div><span className="story-example-label">가상 의료진·시간표를 사용하는 모의 예약</span></div>;
}
