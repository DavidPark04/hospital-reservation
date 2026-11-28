import {useEffect,useId,useRef,useState} from 'react';
import type {FormEvent,ReactNode} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {AlertCircle,Building2,CalendarDays,CalendarCheck2,CheckCircle2,Clock3,ClipboardList,LoaderCircle,MapPin,Plus,RefreshCw,Settings2,ShieldCheck,Stethoscope,X} from 'lucide-react';
import {api,dateLabel,DEPARTMENTS,queryString,timeLabel,today} from './api';
import type {AuthResult,DeleteMemberResult,Department,Doctor,HospitalAccountInfo,HospitalReservation,Slot,User} from './api';
import {formatPhone} from './phoneFormatting';
import {formatBusinessNumber} from './HospitalAuthForm';
import PasswordChange from './PasswordChange';
import './hospitalManagement.css';

type ManagedHospital=HospitalAccountInfo & {availableDepartments?:Department[]};
type PortalProps={user:User|null;login:()=>void;notify:(message:string)=>void;onPasswordChanged:(result:AuthResult,previousToken:string)=>void;onWithdrawn:(result:DeleteMemberResult)=>void};
type Refresh=()=>void;
const base='/hospital/manage';
function ErrorNotice({error,retry}:{error:unknown;retry?:()=>void}){return <div className="notice error" role="alert"><AlertCircle size={19}/><div>{error instanceof Error?error.message:'요청을 처리하지 못했어요.'}{retry&&<button type="button" className="text-button" onClick={retry}><RefreshCw size={14}/>다시 시도</button>}</div></div>;}
function Loading(){return <div className="loading" role="status"><LoaderCircle className="spin" size={23}/><span>병원 정보를 불러오고 있어요</span></div>;}
function Modal({title,children,busy,close}:{title:string;children:ReactNode;busy:boolean;close:()=>void}){
  const ref=useRef<HTMLDivElement>(null);const heading=useId();const closeRef=useRef(close);closeRef.current=close;
  const busyRef=useRef(busy);busyRef.current=busy;
  useEffect(()=>{const previous=document.activeElement as HTMLElement|null;const overflow=document.body.style.overflow;document.body.style.overflow='hidden';ref.current?.focus();
    const key=(event:KeyboardEvent)=>{if(event.key==='Escape'&&!busyRef.current)closeRef.current();if(event.key==='Tab'){const controls=ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]');if(!controls?.length)return;const first=controls[0],last=controls[controls.length-1];if(event.shiftKey&&(document.activeElement===first||document.activeElement===ref.current)){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}};
    document.addEventListener('keydown',key);return()=>{document.body.style.overflow=overflow;document.removeEventListener('keydown',key);previous?.focus();};},[]);
  return <div className="dialog-overlay" onMouseDown={e=>{if(e.target===e.currentTarget&&!busy)close();}}><div className="dialog hospital-dialog" ref={ref} role="dialog" aria-modal="true" aria-labelledby={heading} tabIndex={-1}><div className="dialog-heading"><h2 id={heading}>{title}</h2><button type="button" className="icon-button" onClick={close} disabled={busy} aria-label="창 닫기"><X size={21}/></button></div>{children}</div></div>;
}

export default function HospitalManagement({user,login,notify,onPasswordChanged,onWithdrawn}:PortalProps){
  const queryClient=useQueryClient();const [tab,setTab]=useState<'doctors'|'schedule'|'reservations'|'account'>('doctors');
  const hospital=useQuery({queryKey:['hospital-manage',user?.id,'hospital'],queryFn:()=>api<ManagedHospital>(base+'/hospital'),enabled:user?.role==='HOSPITAL'});
  const doctors=useQuery({queryKey:['hospital-manage',user?.id,'doctors'],queryFn:()=>api<{items:Doctor[]}>(base+'/doctors'),enabled:user?.role==='HOSPITAL'});
  function refresh(){void queryClient.invalidateQueries({queryKey:['hospital-manage']});void queryClient.invalidateQueries({queryKey:['doctors']});void queryClient.invalidateQueries({queryKey:['slots']});void queryClient.invalidateQueries({queryKey:['hospitals']});void queryClient.invalidateQueries({queryKey:['reservations']});}
  if(user?.role!=='HOSPITAL')return <div className="page-wrap"><div className="empty-page"><div className="empty-icon"><Building2/></div><h1>병원 계정으로 이용해 주세요</h1><p>병원 회원으로 로그인하면 소속 병원의 의료진과 예약을 관리할 수 있어요.</p>{!user&&<button className="button primary" onClick={login}>로그인하기</button>}</div></div>;
  const profile=hospital.data;const staff=doctors.data?.items||[];
  return <div className="page-wrap hospital-management">
    <div className="page-heading compact-heading"><div><span className="eyebrow">HOSPITAL MANAGEMENT</span><h1>우리 병원 관리</h1><p>의료진과 진료 일정을 등록하고 병원으로 들어온 예약을 관리하세요.</p></div><span className="admin-badge"><Building2 size={17}/>병원 회원</span></div>
    {hospital.isPending?<Loading/>:hospital.isError?<ErrorNotice error={hospital.error} retry={()=>hospital.refetch()}/>:profile&&<>
      <section className="panel hospital-summary"><span className="hospital-summary-icon"><Building2 size={30}/></span><div><h2>{profile.name}</h2><p><MapPin size={15}/>{profile.address}</p><small>사업자등록번호 {formatBusinessNumber(profile.businessNumber||user.businessNumber||'')}</small></div><span className="hospital-registered"><CheckCircle2 size={16}/>등록된 병원</span></section>
      <div className="tabs hospital-tabs" role="tablist" aria-label="병원 관리 메뉴">{([{id:'doctors',label:'의료진',icon:<Stethoscope size={17}/>},{id:'schedule',label:'진료 일정',icon:<CalendarDays size={17}/>},{id:'reservations',label:'예약 관리',icon:<ClipboardList size={17}/>},{id:'account',label:'병원 정보',icon:<Settings2 size={17}/>} ] as const).map(item=><button key={item.id} role="tab" aria-selected={tab===item.id} className={tab===item.id?'selected':''} onClick={()=>setTab(item.id)}>{item.icon}{item.label}</button>)}</div>
      {doctors.isError&&tab!=='account'&&<ErrorNotice error={doctors.error} retry={()=>doctors.refetch()}/>}
      {doctors.isPending&&tab!=='account'?<Loading/>:<>
        {tab==='doctors'&&<DoctorsPanel doctors={staff} departments={profile.availableDepartments||DEPARTMENTS} refresh={refresh} notify={notify}/>}
        {tab==='schedule'&&<SchedulePanel userId={user.id} doctors={staff} refresh={refresh} notify={notify}/>}
        {tab==='reservations'&&<ReservationsPanel userId={user.id} doctors={staff} refresh={refresh} notify={notify}/>}
      </>}
      {tab==='account'&&<section className="panel hospital-info-panel"><h2>병원 기본정보</h2><p className="muted">가입 시 공공정보와 대조한 병원 정보예요. 이 화면에서는 수정할 수 없어요.</p><dl className="profile-details"><div><dt>사업자등록번호</dt><dd>{formatBusinessNumber(profile.businessNumber||user.businessNumber||'')}</dd></div><div><dt>병원명</dt><dd>{profile.name}</dd></div><div><dt>주소</dt><dd>{profile.address}</dd></div><div><dt>우편번호</dt><dd>{profile.postalCode||'미제공'}</dd></div><div><dt>전화번호</dt><dd>{profile.tel?formatPhone(profile.tel):<span className="muted">미제공</span>}</dd></div><div><dt>등록한 진료과</dt><dd>{profile.departments?.map(d=>d.name).join(' · ')||'의료진 등록 후 표시돼요'}</dd></div></dl><PasswordChange user={user} onChanged={onPasswordChanged}/><HospitalWithdrawal onWithdrawn={onWithdrawn}/></section>}
    </>}
  </div>;
}

function HospitalWithdrawal({onWithdrawn}:{onWithdrawn:(result:DeleteMemberResult)=>void}){
  const [open,setOpen]=useState(false);const [password,setPassword]=useState('');const requestInFlight=useRef(false);
  const remove=useMutation({mutationFn:()=>api<DeleteMemberResult>('/members/me',{method:'DELETE',body:JSON.stringify({password})}),onSuccess:result=>{setPassword('');onWithdrawn(result);},onSettled:()=>{requestInFlight.current=false;}});
  function close(){if(remove.isPending||requestInFlight.current)return;setOpen(false);setPassword('');remove.reset();}
  function submit(event:FormEvent){event.preventDefault();if(!password||remove.isPending||requestInFlight.current)return;requestInFlight.current=true;remove.mutate();}
  return <div className="profile-withdrawal">
    <p>병원 회원 이용을 마치려면 회원 탈퇴를 진행할 수 있어요.</p>
    <button className="text-button withdrawal-link" type="button" onClick={()=>{setPassword('');remove.reset();setOpen(true);}}>탈퇴하기</button>
    {open&&<Modal title="병원 회원 탈퇴" close={close} busy={remove.isPending}>
      <p className="dialog-copy">탈퇴하면 병원 계정이 삭제되고 예약 중인 예약이 모두 취소돼요. 등록한 의료진과 진료 일정은 종료되며, 지난 예약과 취소 내역은 유지돼요. 탈퇴한 계정은 복구할 수 없어요.</p>
      <form className="auth-form withdrawal-form" onSubmit={submit}>
        <label className="field">현재 비밀번호<input name="currentPassword" type="password" autoComplete="current-password" value={password} onChange={event=>setPassword(event.target.value)} required maxLength={100} disabled={remove.isPending} placeholder="현재 비밀번호를 입력해 주세요"/></label>
        {remove.isError&&<ErrorNotice error={remove.error}/>}
        <div className="dialog-actions withdrawal-actions"><button className="button secondary" type="button" onClick={close} disabled={remove.isPending}>취소</button><button className="button danger" type="submit" disabled={remove.isPending||!password}>{remove.isPending?<><LoaderCircle size={17} className="spin"/>탈퇴 처리 중</>:'탈퇴하기'}</button></div>
      </form>
    </Modal>}
  </div>;
}

function DoctorsPanel({doctors,departments,refresh,notify}:{doctors:Doctor[];departments:Department[];refresh:Refresh;notify:(message:string)=>void}){
  const [editing,setEditing]=useState<Doctor|null|undefined>();
  return <section className="panel hospital-section"><div className="section-heading"><div><h2>등록한 의료진 <span className="count-pill">{doctors.length}</span></h2><p>의료진의 진료과와 전문 분야를 설정하세요.</p></div><button className="button primary small" onClick={()=>setEditing(null)}><Plus size={17}/>의료진 등록</button></div>
    {doctors.length?<div className="managed-doctors">{doctors.map(doctor=><article className="managed-doctor" key={doctor.id}><div className="managed-doctor-icon"><Stethoscope size={27}/></div><div><span className={`status-badge ${doctor.active===false?'canceled':''}`}>{doctor.active===false?'예약 중지':'진료 중'}</span><h3>{doctor.name}</h3><p>{departments.find(d=>d.code===doctor.departmentCode)?.name||doctor.departmentCode}</p><small>{doctor.specialty||'전문 분야 미등록'}</small></div><button className="button outline small" onClick={()=>setEditing(doctor)}>수정</button></article>)}</div>:<div className="empty-inline"><Stethoscope size={35}/><h3>첫 의료진을 등록해 주세요</h3><p>의료진을 등록한 다음 진료 일정과 예약 정원을 설정할 수 있어요.</p></div>}
    {editing!==undefined&&<DoctorEditor doctor={editing} departments={departments} close={()=>setEditing(undefined)} saved={()=>{setEditing(undefined);refresh();notify(editing?'의료진 정보를 수정했어요.':'의료진을 등록했어요.');}} deleted={()=>{setEditing(undefined);refresh();notify('의료진을 삭제했어요.');}}/>}
  </section>;
}

function DoctorEditor({doctor,departments,close,saved,deleted}:{doctor:Doctor|null;departments:Department[];close:()=>void;saved:()=>void;deleted:()=>void}){
  const [name,setName]=useState(doctor?.name||'');const [departmentCode,setDepartment]=useState(doctor?.departmentCode||departments[0]?.code||'01');const [specialty,setSpecialty]=useState(doctor?.specialty||'');const [active,setActive]=useState(doctor?.active!==false);
  const [confirmDelete,setConfirmDelete]=useState(false);const requestInFlight=useRef(false);const previousConfirm=useRef(false);
  const deleteButton=useRef<HTMLButtonElement>(null);const keepButton=useRef<HTMLButtonElement>(null);
  const save=useMutation({mutationFn:()=>api<Doctor>(base+'/doctors'+(doctor?'/'+encodeURIComponent(doctor.id):''),{method:doctor?'PATCH':'POST',body:JSON.stringify({name:name.trim(),departmentCode,specialty:specialty.trim(),...(doctor?{active}:{})})}),onSuccess:saved,onSettled:()=>{requestInFlight.current=false;}});
  const remove=useMutation({mutationFn:()=>api<{deleted:boolean}>(base+'/doctors/'+encodeURIComponent(doctor!.id),{method:'DELETE'}),onSuccess:deleted,onError:()=>setConfirmDelete(false),onSettled:()=>{requestInFlight.current=false;}});
  const busy=save.isPending||remove.isPending;
  useEffect(()=>{if(confirmDelete)keepButton.current?.focus();else if(previousConfirm.current)deleteButton.current?.focus();previousConfirm.current=confirmDelete;},[confirmDelete]);
  function submit(event:FormEvent){event.preventDefault();if(name.trim()&&!confirmDelete&&!busy&&!requestInFlight.current){requestInFlight.current=true;remove.reset();save.mutate();}}
  function askDelete(){if(doctor&&!busy&&!requestInFlight.current){save.reset();remove.reset();setConfirmDelete(true);}}
  function deleteDoctor(){if(doctor&&confirmDelete&&!busy&&!requestInFlight.current){requestInFlight.current=true;remove.mutate();}}
  function dismiss(){if(!busy&&!requestInFlight.current){if(confirmDelete)setConfirmDelete(false);else close();}}
  return <Modal title={confirmDelete?'의료진을 삭제할까요?':doctor?'의료진 정보 수정':'의료진 등록'} close={dismiss} busy={busy}>
    {confirmDelete&&doctor?<>
      <p className="dialog-copy"><strong>{doctor.name}</strong> 의료진을 등록 목록에서 삭제해요.</p>
      <p className="muted">예정된 예약이 있다면 먼저 예약을 변경하거나 취소해 주세요. 삭제하면 남은 진료 일정은 닫히고, 지난 예약과 취소 내역은 유지돼요.</p>
      <div className="dialog-actions"><button type="button" className="button secondary" ref={keepButton} onClick={dismiss} disabled={busy}>유지하기</button><button type="button" className="button danger" onClick={deleteDoctor} disabled={busy}>{remove.isPending?'삭제 중':'삭제하기'}</button></div>
    </>:<form className="auth-form" onSubmit={submit}>
      <label className="field">의료진 이름<input value={name} onChange={e=>setName(e.target.value)} required maxLength={50} autoComplete="off" disabled={busy}/></label>
      <label className="field">진료과<select value={departmentCode} onChange={e=>setDepartment(e.target.value)} required disabled={busy}>{departments.map(d=><option key={d.code} value={d.code}>{d.name}</option>)}</select></label>
      <label className="field">전문 분야 <small>선택</small><input value={specialty} onChange={e=>setSpecialty(e.target.value)} maxLength={100} placeholder="예) 무릎·어깨 진료" disabled={busy}/></label>
      {doctor&&<label className="check-label"><input type="checkbox" checked={active} onChange={e=>setActive(e.target.checked)} disabled={busy}/><span>이 의료진의 신규 예약 허용</span></label>}
      {doctor&&<p className="muted">예약을 중지해도 기존 예약은 유지돼요. 앞으로의 예약이 있는 의료진은 진료과를 변경할 수 없어요.</p>}
      {save.isError&&<ErrorNotice error={save.error}/>}{remove.isError&&<ErrorNotice error={remove.error}/>}
      <div className="dialog-actions doctor-editor-actions">
        {doctor&&<button type="button" className="button quiet doctor-delete-action" ref={deleteButton} onClick={askDelete} disabled={busy}>삭제하기</button>}
        <div className="doctor-save-actions"><button type="button" className="button secondary" onClick={dismiss} disabled={busy}>취소</button><button type="submit" className="button primary" disabled={busy||!name.trim()}>{save.isPending?'저장 중':'저장하기'}</button></div>
      </div>
    </form>}
  </Modal>;
}

function SchedulePanel({userId,doctors,refresh,notify}:{userId:string;doctors:Doctor[];refresh:Refresh;notify:(message:string)=>void}){
  const [doctorId,setDoctor]=useState(doctors[0]?.id||'');const [date,setDate]=useState(today(1));const [generate,setGenerate]=useState(false);const [editing,setEditing]=useState<Slot|null>(null);
  const doctor=doctors.find(d=>d.id===doctorId);
  const slots=useQuery({queryKey:['hospital-manage',userId,'slots',doctorId,date],queryFn:()=>api<{items:Slot[]}>(base+'/slots?'+queryString({doctorId,date})),enabled:!!doctorId&&!!date});
  return <section className="panel hospital-section"><div className="section-heading"><div><h2>진료 일정과 정원</h2><p>예약 가능한 시간을 만들고 시간대별 정원을 관리하세요.</p></div><button className="button primary small" disabled={!doctor||doctor.active===false||!date} onClick={()=>setGenerate(true)}><Plus size={17}/>시간표 등록</button></div>
    <div className="hospital-schedule-filters"><label className="field">의료진<select value={doctorId} onChange={e=>setDoctor(e.target.value)}><option value="">의료진 선택</option>{doctors.map(d=><option value={d.id} key={d.id}>{d.name}{d.active===false?' (예약 중지)':''}</option>)}</select></label><label className="field">날짜<input type="date" value={date} min={today()} max={today(365)} onChange={e=>{if(e.target.value&&e.target.validity.valid)setDate(e.target.value);}}/></label></div>
    {!doctor?<div className="empty-inline"><CalendarDays size={35}/><h3>의료진을 먼저 등록해 주세요</h3><p>등록된 의료진을 선택하면 일정을 설정할 수 있어요.</p></div>:slots.isLoading?<Loading/>:slots.isError?<ErrorNotice error={slots.error} retry={()=>slots.refetch()}/>:<>
      <div className="admin-slots">{slots.data?.items.map(slot=><button key={slot.id} className={`admin-slot ${slot.closed?'closed':slot.remaining<=0?'full':''}`} onClick={()=>setEditing(slot)}><span>{timeLabel(slot.startAt)}</span><strong>{slot.closed?'휴진':`${slot.reservedCount} / ${slot.capacity}`}</strong><small>{slot.closed?'예약 불가':`잔여 ${slot.remaining}명`}</small><Settings2 size={15}/></button>)}</div>
      {!slots.data?.items.length&&<div className="empty-inline"><Clock3 size={31}/><h3>등록된 진료 일정이 없어요</h3><p>선택한 날짜의 시간표를 등록해 주세요.</p></div>}
    </>}
    <p className="hospital-panel-help"><ShieldCheck size={16}/>예약 수보다 정원을 줄일 수 없고, 예약이 있는 시간은 휴진으로 바꿀 수 없어요.</p>
    {generate&&doctor&&<ScheduleCreator doctor={doctor} date={date} close={()=>setGenerate(false)} saved={()=>{setGenerate(false);refresh();notify('진료 시간표를 등록했어요.');}}/>}
    {editing&&<SlotEditor slot={editing} close={()=>setEditing(null)} saved={()=>{setEditing(null);refresh();notify('시간대 정원과 휴진 설정을 저장했어요.');}}/>}
  </section>;
}

function ScheduleCreator({doctor,date,close,saved}:{doctor:Doctor;date:string;close:()=>void;saved:()=>void}){
  const [startTime,setStart]=useState('09:00');const [endTime,setEnd]=useState('17:00');const [intervalMinutes,setInterval]=useState(30);const [capacity,setCapacity]=useState(4);
  const save=useMutation({mutationFn:()=>api<{items:Slot[]}>(base+'/slots',{method:'POST',body:JSON.stringify({doctorId:doctor.id,date,startTime,endTime,intervalMinutes,capacity})}),onSuccess:saved});
  function submit(event:FormEvent){event.preventDefault();if(!save.isPending&&startTime<endTime)save.mutate();}
  return <Modal title="진료 시간표 등록" close={close} busy={save.isPending}><p className="dialog-copy">{doctor.name} · {dateLabel(date+'T12:00:00+09:00')}</p><form className="auth-form" onSubmit={submit}><div className="form-grid"><label className="field">시작 시간<input type="time" value={startTime} onChange={e=>setStart(e.target.value)} required/></label><label className="field">종료 시간<input type="time" value={endTime} onChange={e=>setEnd(e.target.value)} required/></label><label className="field">시간 간격<select value={intervalMinutes} onChange={e=>setInterval(Number(e.target.value))}>{[15,30,60,90,120].map(n=><option key={n} value={n}>{n}분</option>)}</select></label><label className="field">시간대별 정원<input type="number" min={1} max={100} value={capacity} onChange={e=>setCapacity(Number(e.target.value))} required/></label></div><p className="muted">종료 시간 직전까지 선택한 간격으로 예약 시간을 만들어요. 점심시간은 오전과 오후를 나누어 등록해 주세요.</p>{save.isError&&<ErrorNotice error={save.error}/>}<div className="dialog-actions"><button type="button" className="button secondary" onClick={close} disabled={save.isPending}>취소</button><button type="submit" className="button primary" disabled={save.isPending||!startTime||startTime>=endTime||capacity<1||capacity>100}>{save.isPending?'등록 중':'시간표 등록'}</button></div></form></Modal>;
}

function SlotEditor({slot,close,saved}:{slot:Slot;close:()=>void;saved:()=>void}){
  const [capacity,setCapacity]=useState(slot.capacity);const [closed,setClosed]=useState(slot.closed);
  const save=useMutation({mutationFn:()=>api<Slot>(base+'/slots/'+encodeURIComponent(slot.id),{method:'PATCH',body:JSON.stringify({capacity,closed})}),onSuccess:saved});
  return <Modal title={`${timeLabel(slot.startAt)} 일정 수정`} close={close} busy={save.isPending}><form className="auth-form" onSubmit={(e:FormEvent)=>{e.preventDefault();if(!save.isPending)save.mutate();}}><p className="dialog-copy">{dateLabel(slot.startAt)} · 현재 예약 {slot.reservedCount}명</p><label className="field">총 정원<input type="number" min={Math.max(1,slot.reservedCount)} max={100} value={capacity} onChange={e=>setCapacity(Number(e.target.value))} required/></label><label className="check-label"><input type="checkbox" checked={closed} onChange={e=>setClosed(e.target.checked)} disabled={slot.reservedCount>0}/><span>이 시간대를 휴진으로 설정</span></label>{save.isError&&<ErrorNotice error={save.error}/>}<div className="dialog-actions"><button type="button" className="button secondary" onClick={close} disabled={save.isPending}>취소</button><button type="submit" className="button primary" disabled={save.isPending||capacity<Math.max(1,slot.reservedCount)||capacity>100}>{save.isPending?'저장 중':'변경 저장'}</button></div></form></Modal>;
}

function ReservationsPanel({userId,doctors,refresh,notify}:{userId:string;doctors:Doctor[];refresh:Refresh;notify:(message:string)=>void}){
  const [date,setDate]=useState('');const [status,setStatus]=useState('RESERVED');const [changing,setChanging]=useState<HospitalReservation|null>(null);const [canceling,setCanceling]=useState<HospitalReservation|null>(null);const [cancelKey,setCancelKey]=useState('');
  const reservations=useQuery({queryKey:['hospital-manage',userId,'reservations',date,status],queryFn:()=>api<{items:HospitalReservation[]}>(base+'/reservations?'+queryString({date,status}))});
  const cancel=useMutation({mutationFn:()=>api<HospitalReservation>(base+'/reservations/'+encodeURIComponent(canceling!.id),{method:'DELETE',headers:{'Idempotency-Key':cancelKey}}),onSuccess:()=>{setCanceling(null);refresh();notify('예약을 취소했어요.');}});
  const items=reservations.data?.items||[];
  return <section className="panel hospital-section"><div className="section-heading"><div><h2>병원 예약 관리</h2><p>우리 병원으로 들어온 예약만 표시돼요.</p></div><span className="count-pill">{items.length}건</span></div><div className="hospital-schedule-filters"><label className="field">예약 상태<select value={status} onChange={e=>setStatus(e.target.value)}><option value="RESERVED">예약 중</option><option value="CANCELED">취소 내역</option><option value="">전체</option></select></label><label className="field">예약 날짜<input type="date" value={date} onChange={e=>setDate(e.target.value)}/></label><button className="button secondary small" disabled={!date} onClick={()=>setDate('')}>전체 날짜</button></div>
    {reservations.isLoading?<Loading/>:reservations.isError?<ErrorNotice error={reservations.error} retry={()=>reservations.refetch()}/>:items.length?<div className="managed-reservations">{items.map(reservation=>{
      const editable=reservation.status==='RESERVED'&&Date.parse(reservation.startAt)>Date.now();
      return <article className="managed-reservation" key={reservation.id}><div className="managed-reservation-time"><CalendarCheck2 size={22}/><strong>{dateLabel(reservation.startAt)}</strong><span>{timeLabel(reservation.startAt)}</span></div><div className="managed-reservation-detail"><span className={`status-badge ${reservation.status==='CANCELED'?'canceled':''}`}>{reservation.status==='CANCELED'?'취소 완료':'예약 중'}</span><h3>{reservation.patientName||'예약자'}</h3><p>{reservation.patientPhone?formatPhone(reservation.patientPhone):'연락처 미등록'}</p><p>{reservation.departmentName} · {reservation.doctorName}</p><small>예약 번호 {reservation.id.slice(0,8).toUpperCase()}</small></div>{editable&&<div className="managed-reservation-actions"><button className="button outline small" onClick={()=>setChanging(reservation)}>일정 변경</button><button className="button quiet small" onClick={()=>{cancel.reset();setCancelKey(crypto.randomUUID());setCanceling(reservation);}}>예약 취소</button></div>}</article>;
    })}</div>:<div className="empty-inline"><ClipboardList size={34}/><h3>조건에 맞는 예약이 없어요</h3><p>날짜와 예약 상태를 변경해 확인해 보세요.</p></div>}
    {changing&&<ReservationChanger reservation={changing} doctors={doctors} userId={userId} close={()=>setChanging(null)} saved={()=>{setChanging(null);refresh();notify('예약 일정을 변경했어요.');}}/>}
    {canceling&&<Modal title="예약을 취소할까요?" close={()=>setCanceling(null)} busy={cancel.isPending}><p className="dialog-copy">{canceling.patientName} · {canceling.doctorName}<br/>{dateLabel(canceling.startAt)} {timeLabel(canceling.startAt)}</p><p className="muted">취소하면 해당 시간의 예약 가능 인원이 복구돼요.</p>{cancel.isError&&<ErrorNotice error={cancel.error}/>}<div className="dialog-actions"><button className="button secondary" onClick={()=>setCanceling(null)} disabled={cancel.isPending}>유지하기</button><button className="button danger" onClick={()=>cancel.mutate()} disabled={cancel.isPending}>{cancel.isPending?'취소 중':'예약 취소'}</button></div></Modal>}
  </section>;
}

function ReservationChanger({reservation,doctors,userId,close,saved}:{reservation:HospitalReservation;doctors:Doctor[];userId:string;close:()=>void;saved:()=>void}){
  const [doctorId,setDoctor]=useState(reservation.doctorId);const [date,setDate]=useState(new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Seoul'}).format(new Date(reservation.startAt)));const [slotId,setSlot]=useState('');const [requestKey,setRequestKey]=useState(crypto.randomUUID());
  const activeDoctors=doctors.filter(d=>d.active!==false);const validDoctor=activeDoctors.some(d=>d.id===doctorId);
  const slots=useQuery({queryKey:['hospital-manage',userId,'slots',doctorId,date],queryFn:()=>api<{items:Slot[]}>(base+'/slots?'+queryString({doctorId,date})),enabled:validDoctor&&!!date});
  const available=slots.data?.items.filter(s=>!s.closed&&s.remaining>0&&s.id!==reservation.slotId&&Date.parse(s.startAt)>Date.now())||[];
  const save=useMutation({mutationFn:()=>api<HospitalReservation>(base+'/reservations/'+encodeURIComponent(reservation.id),{method:'PATCH',headers:{'Idempotency-Key':requestKey},body:JSON.stringify({slotId})}),onSuccess:saved,onError:()=>void slots.refetch()});
  function resetSelection(){setSlot('');setRequestKey(crypto.randomUUID());save.reset();}
  return <Modal title="예약 일정 변경" close={close} busy={save.isPending}><p className="dialog-copy">{reservation.patientName}님의 예약<br/>기존 {dateLabel(reservation.startAt)} {timeLabel(reservation.startAt)} · {reservation.doctorName}</p><form className="auth-form" onSubmit={(event:FormEvent)=>{event.preventDefault();if(!save.isPending&&available.some(s=>s.id===slotId))save.mutate();}}><label className="field">의료진<select value={validDoctor?doctorId:''} onChange={e=>{setDoctor(e.target.value);resetSelection();}} required><option value="">의료진 선택</option>{activeDoctors.map(d=><option key={d.id} value={d.id}>{d.name} · {d.specialty}</option>)}</select></label><label className="field">변경할 날짜<input type="date" min={today()} max={today(365)} value={date} onChange={e=>{if(e.target.value&&e.target.validity.valid){setDate(e.target.value);resetSelection();}}} required/></label>{slots.isLoading?<Loading/>:slots.isError?<ErrorNotice error={slots.error} retry={()=>slots.refetch()}/>:validDoctor&&<label className="field">변경할 시간<select value={slotId} onChange={e=>{setSlot(e.target.value);setRequestKey(crypto.randomUUID());save.reset();}} required><option value="">{available.length?'시간 선택':'예약 가능한 시간이 없어요'}</option>{available.map(slot=><option key={slot.id} value={slot.id}>{timeLabel(slot.startAt)} · 잔여 {slot.remaining}명</option>)}</select></label>}{save.isError&&<ErrorNotice error={save.error}/>}<div className="dialog-actions"><button type="button" className="button secondary" onClick={close} disabled={save.isPending}>취소</button><button type="submit" className="button primary" disabled={save.isPending||!validDoctor||!available.some(s=>s.id===slotId)}>{save.isPending?'변경 중':'일정 변경'}</button></div></form></Modal>;
}
