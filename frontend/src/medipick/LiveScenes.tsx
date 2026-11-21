import {useState} from 'react'
import type {FormEvent} from 'react'
import {useQuery} from '@tanstack/react-query'
import {ArrowRight, CalendarDays, Check, ChevronRight, ExternalLink, LoaderCircle, MapPin, Search, Send, Sparkles} from 'lucide-react'
import {api, DEPARTMENTS, fetchedLabel, queryString, timeLabel, today} from '../api'
import type {Doctor, Hospital, HospitalResult, Slot} from '../api'
import {CHAT_MESSAGE_LIMIT, safeRecommendationSources, straightLineDistance} from '../chatPresentation'
import HospitalLocation from '../HospitalLocation'
import {DoctorPortrait} from './FilmVisual'
import {HOME_HOSPITAL_FILTERS, type HomeProps} from './flow'
import './live-scenes.css'

type LiveProps = Pick<HomeProps,'onBooking'|'onAccount'|'recommendation'> & {phase:number;goToChapter:(phase:number)=>void}

function ConsultScene() {
  const [message,setMessage] = useState('')
  function start(event:FormEvent) {
    event.preventDefault()
    const content = message.trim()
    window.location.hash = content ? '/chat?message=' + encodeURIComponent(content) : '/chat'
  }
  return <div className="ls-stack ls-chat">
    <div className="ls-demo-bubble">내 증상에는 어느 진료과가 맞을까?</div>
    <div className="ls-chat-reply"><span className="ls-avatar" data-film-anchor="chat"><Sparkles/></span><p>불편한 곳이나 찾고 있는 진료 분야를 알려주세요.</p></div>
    <form className="ls-compose" onSubmit={start}>
      <label htmlFor="medipick-chat" className="sr-only">상담에 가져갈 증상이나 진료 목적</label>
      <textarea id="medipick-chat" rows={2} value={message} maxLength={CHAT_MESSAGE_LIMIT} onChange={e=>setMessage(e.target.value)} placeholder="예) 서울에서 무릎 진료를 받고 싶어요."/>
      <button type="submit">상담으로 이동 <Send size={16}/></button>
    </form>
    <div className="ls-prompts" role="group" aria-label="상담 질문 예시">{['목이 붓고 아파요','허리가 불편해요','피부가 가려워요'].map(text=><button type="button" key={text} onClick={()=>setMessage(text)}>{text}</button>)}</div>
  </div>
}

function QueryStatus({loading,error,message,retry}:{loading?:boolean;error?:Error|null;message:string;retry?:()=>void}) {
  return <div className="ls-status" role="status">{loading&&<LoaderCircle className="spin" size={17}/>}<span>{error?.message||message}</span>{error&&retry&&<button type="button" className="ls-link" onClick={retry}>다시 시도</button>}</div>
}

function ClinicStages({hospital,department,phase,goToChapter,onBooking,onAccount}:Omit<LiveProps,'recommendation'>&{hospital?:Hospital;department:string}) {
  const [selectedDoctor,setDoctor] = useState('')
  const [date,setDate] = useState(()=>{
    const tomorrow = today(1)
    // Generated virtual schedules begin on the next non-Sunday. Users can still select any valid date.
    return new Date(tomorrow+'T00:00:00Z').getUTCDay()===0 ? today(2) : tomorrow
  })
  const [selection,setSelection] = useState('')
  const doctors = useQuery({
    queryKey:['doctors',hospital?.id,department],
    queryFn:()=>api<{items:Doctor[]}>(`/hospitals/${encodeURIComponent(hospital!.id)}/doctors?department=${encodeURIComponent(department)}`),
    enabled:!!hospital && !!department && phase>=4
  })
  const staff = doctors.data?.items||[]
  const doctor = staff.find(d=>d.id===selectedDoctor)||staff[0]
  const slots = useQuery({
    queryKey:['slots',doctor?.id,date],
    queryFn:()=>api<{items:Slot[]}>(`/slots?${queryString({doctorId:doctor!.id,date})}`),
    enabled:!!doctor && phase===5
  })
  const chosen = slots.data?.items.find(s=>s.id===selection && !s.closed && s.remaining>0 && Date.parse(s.startAt)>Date.now())
  const calendarDay = new Date(date+'T12:00:00+09:00')
  function continueBooking() {
    if (!hospital || !doctor || !chosen) return
    if (Date.parse(chosen.startAt)<=Date.now()) {setSelection('');void slots.refetch();return}
    onBooking(hospital,department,{doctorId:doctor.id,date,slotId:chosen.id})
  }
  return <>
    <section className="sc-pane sc-doctors-stage" data-film-page="doctors" inert={phase!==4} aria-label="가상 의료진">
      <div className="ls-stack ls-staff">
        <header className="ls-heading"><small>가상 의료진</small><h3>{hospital?.name||'진료받을 병원을 선택해 주세요'}</h3></header>
        {doctor ? <>
          <div className="ls-doctors" role="group" aria-label="가상 의료진 선택">
            {staff.map((item,i)=><button type="button" key={item.id} aria-pressed={item.id===doctor.id} onClick={()=>{setDoctor(item.id);setSelection('')}}>
              <span className="ls-portrait"><DoctorPortrait index={i}/></span>
              <strong>{item.name} <small>의사</small></strong><span>{item.specialty}</span>
              {item.id===doctor.id&&<i data-film-anchor="doctors"><Check size={16}/></i>}
            </button>)}
          </div>
          <button type="button" className="ls-link" onClick={()=>goToChapter(5)}>이 의료진의 모의 일정 보기 <ArrowRight size={16}/></button>
        </>:<>
          <QueryStatus loading={doctors.isLoading} error={doctors.error} retry={()=>doctors.refetch()} message={hospital?(doctors.isLoading?'가상 의료진을 불러오는 중이에요.':'이 진료과에서 선택할 의료진이 없어요.'):'병원을 찾으면 해당 진료과의 가상 의료진을 볼 수 있어요.'}/>
          <button type="button" className="ls-link" onClick={()=>goToChapter(2)}>병원 선택하기 <ArrowRight size={16}/></button>
        </>}
      </div>
    </section>
    <section className="sc-pane sc-book-stage" data-film-page="booking" inert={phase!==5} aria-label="모의 예약 일정">
      <div className="ls-stack ls-ticket"><div className="ls-ticket-stub" aria-hidden="true"><span>{date.slice(0,4)}년 {Number(date.slice(5,7))}월</span><b>{Number(date.slice(8))}</b><span>{calendarDay.toLocaleDateString('ko-KR',{weekday:'long',timeZone:'Asia/Seoul'})}</span></div>
        <div className="ls-ticket-body"><header className="ls-heading"><small>{hospital?.name||'모의 예약'}</small><h3><CalendarDays size={21} data-film-anchor="booking"/>{doctor?doctor.name+' 의사 일정':'일정 선택'}</h3></header>
          {doctor ? <>
            <label className="ls-date">예약 날짜<input type="date" min={today()} max={today(90)} value={date} onChange={e=>{if(e.target.validity.valid && e.target.value){setDate(e.target.value);setSelection('')}}}/></label>
            {slots.isLoading||slots.isError ? <QueryStatus loading={slots.isLoading} error={slots.error} retry={()=>slots.refetch()} message="일정을 확인하고 있어요."/> :
              slots.data?.items.length ? <div className="ls-times" role="group" aria-label="모의 예약 시간">{slots.data.items.map(slot=>{
                const elapsed = Date.parse(slot.startAt)<=Date.now()
                return <button key={slot.id} type="button" aria-pressed={slot.id===chosen?.id} disabled={slot.closed||slot.remaining<1||elapsed} onClick={()=>setSelection(slot.id)}>{timeLabel(slot.startAt)}<small>{slot.closed?'휴진':slot.remaining<1?'마감':elapsed?'지난 시간':'잔여 '+slot.remaining}</small></button>
              })}</div>
              : <p className="ls-note">선택할 수 있는 일정이 없어요. 다른 날짜를 확인해 주세요.</p>}
            <button type="button" className="ls-primary" disabled={!chosen} onClick={continueBooking}>선택한 일정으로 예약 확인 <ChevronRight size={17}/></button>
          </>:<><p className="ls-note">병원과 가상 의료진을 선택하면 예약 가능한 시간과 잔여 정원이 표시돼요.</p><button type="button" className="ls-primary" onClick={()=>goToChapter(hospital?4:2)}>{hospital?'의료진 선택':'병원 선택'} <ArrowRight size={16}/></button></>}
          <button type="button" className="ls-link" onClick={onAccount}>마이페이지 <ArrowRight size={14}/></button>
        </div>
      </div>
    </section>
  </>
}

export default function LiveScenes({phase,goToChapter,onBooking,onAccount,recommendation}:LiveProps) {
  const [filters,setFilters] = useState(HOME_HOSPITAL_FILTERS)
  const [region,setRegion] = useState(HOME_HOSPITAL_FILTERS.region)
  const [departmentFilter,setDepartmentFilter] = useState(HOME_HOSPITAL_FILTERS.department)
  const [browse,setBrowse] = useState(false)
  const [hospitalId,setHospitalId] = useState('')
  const consultation = !browse && recommendation
  const list = useQuery({
    queryKey:['hospitals','search',filters],
    queryFn:()=>api<HospitalResult>('/hospitals?'+queryString(filters)),
    enabled:!consultation && phase>=2,
    staleTime:120_000
  })
  const results = list.data?.items||[]
  const items = consultation ? [consultation.hospital] : browse ? results : results.filter(h=>h.name===HOME_HOSPITAL_FILTERS.query && h.source!=='SAMPLE')
  const hospital = items.find(h=>h.id===hospitalId)||items[0]
  const department = (consultation ? consultation.department : filters.department)||hospital?.departmentCode||hospital?.departments?.[0]?.code||''
  const departmentLabel = hospital?.departments?.find(d=>d.code===department)?.name || DEPARTMENTS.find(d=>d.code===department)?.name || hospital?.departmentName
  const sources = safeRecommendationSources(hospital?.recommendationSources)
  const distance = straightLineDistance(hospital?.distanceKm)
  const pending = !consultation && list.isLoading
  const error = !consultation ? list.error : null
  function browseHospitals() {
    setBrowse(true)
    setHospitalId('')
    setFilters({query:'',region:region.trim(),department:departmentFilter})
  }
  function candidates() {
    if (!items.length) return null
    return <label className="ls-candidate">병원 선택<select value={hospital?.id||''} onChange={e=>setHospitalId(e.target.value)}>
      {items.map(h=><option key={h.id} value={h.id}>{h.name}</option>)}
    </select></label>
  }
  const statusText = pending?'병원 정보를 불러오고 있어요.':'조건에 맞는 병원을 찾지 못했어요. 지역이나 진료과를 바꿔 보세요.'
  return <div className="film-panes sc-root">
    <section className="sc-pane sc-chat-stage" data-film-page="chat" inert={phase!==1} aria-label="AI 상담"><ConsultScene/></section>
    <section className="sc-pane sc-reasons-stage" data-film-page="reasons" inert={phase!==2} aria-label="병원과 추천 근거">
      <div className="ls-stack sc-reason-sheet">
        <header className="ls-heading"><small>{consultation?'상담에서 선택한 병원':'희망 지역 · 진료과'}</small>
          <h3>{hospital?.name || '어디에서 진료받고 싶으세요?'}</h3>
        </header>
        {!consultation&&<form className="ls-filter" onSubmit={e=>{e.preventDefault();browseHospitals()}}><label><span>지역</span><input maxLength={80} value={region} onChange={e=>setRegion(e.target.value)} placeholder="시·구·동"/></label>
          <label><span>진료과</span><select value={departmentFilter} onChange={e=>setDepartmentFilter(e.target.value)}>{DEPARTMENTS.map(d=><option key={d.code} value={d.code}>{d.name}</option>)}</select></label>
          <button type="submit" aria-label="이 조건으로 병원 검색"><Search size={18}/></button>
        </form>}
        <div className="ls-reason-info"><span><i className="fv-pin-source" aria-hidden="true"/>{hospital?.address||'병원 위치'}</span>
          {hospital&&<><p className="ls-focal" data-film-anchor="reasons">{hospital.specialty||departmentLabel||'진료 정보'}</p>
            <p>{hospital.recommendationReason || (hospital.source==='SAMPLE'?'예약 과정을 체험할 수 있는 가상 병원이에요.':consultation?'상담에서 선택한 병원의 등록 정보를 살펴보세요.':(filters.region?filters.region+' 지역의 ':'')+(departmentLabel||'해당 진료과')+' 등록 의료기관이에요.')}</p>
            {distance&&<small>{distance}</small>}
            {sources.length>0&&<ul aria-label="추천에 참고한 웹 자료">{sources.map(source=><li key={source.url}><a href={source.url} target="_blank" rel="noopener noreferrer"><small>{source.label}</small> {source.title} <ExternalLink size={12}/></a></li>)}</ul>}
            <small>{hospital.source==='SAMPLE'?'가상 병원':sources.length?'자료 조회 · '+fetchedLabel(sources[0].checkedAt):'건강보험심사평가원 병원 정보'}</small>
          </>}
        </div>
        {!hospital&&<QueryStatus loading={pending} error={error} message={statusText} retry={()=>list.refetch()}/>}
        {hospital&&<><div className="ls-reason-choices">{candidates()}</div><button type="button" className="ls-link" onClick={()=>goToChapter(3)}>이 병원 위치 보기 <ArrowRight size={16}/></button></>}
        {consultation&&<button className="ls-link ls-secondary-link" onClick={browseHospitals}>다른 지역·진료과 찾기</button>}
      </div>
    </section>
    <section className="sc-pane sc-map-stage" data-film-page="map" inert={phase!==3} aria-label="선택한 병원의 지도와 길찾기">
      <div className="ls-map">
        {hospital ? <>
          {phase>=2 && phase<=4 && <HospitalLocation hospital={hospital}/>}
          <div className="ls-map-controls">{candidates()}<button type="button" className="ls-link" onClick={()=>goToChapter(4)}>가상 의료진 보기 <ArrowRight size={15}/></button></div>
        </> : <div className="ls-map-empty"><MapPin size={31}/><QueryStatus loading={pending} error={error} retry={()=>list.refetch()} message={statusText}/><button type="button" className="ls-link" onClick={()=>goToChapter(2)}>병원 찾기 <ArrowRight size={15}/></button></div>}
      </div>
    </section>
    <ClinicStages key={(hospital?.id||'none')+'-'+department} phase={phase} hospital={hospital} department={department} goToChapter={goToChapter} onBooking={onBooking} onAccount={onAccount}/>
  </div>
}
