import { memo, useCallback, useEffect, useRef, useState } from 'react'
import type { CSSProperties, FormEvent } from 'react'
import { ArrowDown, ArrowRight, ArrowUp, Search, Sparkles } from 'lucide-react'
import { DEPARTMENTS } from '../api'
import type { HomeProps } from './flow'
import './film.css'
import ScenePanes from './LiveScenes'
import FilmVisual from './FilmVisual'

type FrameStyle = CSSProperties & Record<string, string | number>
const Scenes = memo(ScenePanes)
const chapters = [
  { id: 'top', pane: '', name: '시작', short: '시작', title: ['내게 맞는 병원,', '여기서 찾아요.'], detail: '' },
  { id: 'consult', pane: 'chat', name: 'AI 상담', short: '상담', title: ['어디가', '불편하세요?'], detail: '불편한 점과 진료 목적을 이야기해 주세요. 가까운 의원과 전문 진료기관 중 원하는 방향을 함께 찾아요.' },
  { id: 'reasons', pane: 'reasons', name: '추천 이유', short: '추천 이유', title: ['이 병원을', '살펴보는 이유.'], detail: '내가 찾는 지역과 진료과를 비교해요. 전문 진료기관을 추천받았다면 AI가 참고한 자료도 살펴보세요.' },
  { id: 'discover', pane: 'map', name: '병원 찾기', short: '지도', title: ['내가 찾던 병원,', '지도 위에.'], detail: '병원을 선택해 주소를 보고, 카카오맵에서 출발지를 정해 경로와 예상 시간을 확인해요.' },
  { id: 'doctors', pane: 'doctors', name: '의료진 선택', short: '의료진', title: ['누구에게', '진료받을까요?'], detail: '선택한 병원의 가상 의료진과 진료 분야를 살펴보세요.' },
  { id: 'reserve', pane: 'book', name: '모의 예약', short: '예약', title: ['원하는 시간에,', '진료 약속을.'], detail: '가상 시간표에서 일정을 골라 모의 예약을 진행해요. 저장한 일정은 내 예약에서 변경하거나 취소할 수 있어요.' },
]
const lastChapter = chapters.length - 1
const clamp = (v: number, maximum = 1) => Math.max(0, Math.min(maximum, v))
function smooth(a: number, b: number, value: number) {
  const t = clamp((value - a) / (b - a))
  return t * t * (3 - 2 * t)
}
function exposure(distance: number) { return 1 - smooth(.18, .52, Math.abs(distance)) }

function chapterWipe(key: string, amount: number) {
  const open = 'inset(0)', shut = 'inset(100%)'
  if (amount <= 0) return { enter: shut, leave: open, cutout: 'none' }
  if (amount >= 1) return { enter: open, leave: shut, cutout: 'none' }
  const percent = (n: number) => n.toFixed(2) + '%'
  if (key === 'reasons') {
    const top = percent(136 * amount), bottom = percent(136 * amount - 36)
    return {
      enter: `polygon(0 0, ${top} 0, ${bottom} 100%, 0 100%)`,
      leave: `polygon(${top} 0, 100% 0, 100% 100%, ${bottom} 100%)`, cutout: 'none',
    }
  }
  if (key === 'map') {
    const radius = percent(145 * amount)
    // A percentage circle uses the normalized diagonal. Match that radius for the outgoing content.
    const length = `calc(hypot(100cqw, 100cqh) * ${(1.45 * amount / Math.SQRT2).toFixed(5)})`
    return { enter: `circle(${radius} at 70% 45%)`, leave: open,
      cutout: `radial-gradient(circle at 70% 45%, transparent ${length}, black calc(${length} + 1px))` }
  }
  if (key === 'doctors') {
    const axes = percent(35 + 85 * amount) + ' ' + percent(155 * amount) + ' at 54% 104%'
    return { enter: `ellipse(${axes})`, leave: open,
      cutout: `radial-gradient(ellipse ${axes}, transparent 99.8%, black 100%)` }
  }
  if (key === 'book') {
    const edge = 123 * amount - 17
    const fold = `100% ${percent(edge)}, 77% ${percent(edge + 13)}, 0 ${percent(edge + 6)}`
    return { enter: `polygon(0 0, 100% 0, ${fold})`, leave: `polygon(${fold}, 0 100%, 100% 100%)`, cutout: 'none' }
  }
  return { enter: open, leave: open, cutout: 'none' }
}

function HomeSearch() {
  const [query, setQuery] = useState('')
  const [field, setField] = useState('query')
  function search(event: FormEvent) {
    event.preventDefault()
    window.location.hash = '/search?' + new URLSearchParams({ [field]: query.trim() })
  }
  return <section className="film-search" aria-labelledby="film-search-title">
    <h2 id="film-search-title">찾는 병원이 있으신가요?</h2>
    <form onSubmit={search}>
      <Search size={21} aria-hidden="true"/>
      <select value={field} onChange={event => setField(event.target.value)} aria-label="검색 기준">
        <option value="query">병원명</option><option value="region">지역</option>
      </select>
      <label className="sr-only" htmlFor="film-search-query">{field === 'region' ? '찾고 싶은 지역' : '병원 이름'}</label>
      <input id="film-search-query" type="search" placeholder={field === 'region' ? '예) 서울, 종로구' : '병원 이름 입력'} value={query} onChange={event => setQuery(event.target.value)}/>
      <button type="submit">검색</button>
    </form>
    <nav aria-label="진료과별 병원 찾기">{DEPARTMENTS.map(department =>
      <a key={department.code} href={'#/search?department=' + department.code}>{department.name}</a>)}</nav>
  </section>
}

export default function MedipickHome({ onBooking, onAccount, recommendation, initialScene }: HomeProps) {
  const [{ opening, progress }, setPosition] = useState({ opening: 0, progress: 0 })
  const track = useRef<HTMLElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const openingEnd = useRef<HTMLSpanElement>(null)
  const phase = Math.round(progress)
  const goToChapter = useCallback((index: number) => {
    const anchor = track.current?.querySelector<HTMLElement>('[data-film-anchor-index="' + index + '"]')
    if (!anchor) return
    anchor.scrollIntoView({ block: 'start', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
  }, [])

  useEffect(() => {
    const line = track.current, screen = stage.current, end = openingEnd.current
    if (!line || !screen || !end) return
    const motion = matchMedia('(prefers-reduced-motion: reduce)')
    let frame = 0
    let headerHidden = false
    const root = document.documentElement
    const measure = () => {
      frame = 0
      const y = Math.max(0, -line.getBoundingClientRect().top)
      const lead = Math.max(1, end.offsetTop)
      const journey = Math.max(1, line.offsetHeight - screen.offsetHeight - lead)
      const openingRatio = clamp(y / lead)
      const chapterPosition = clamp((y - lead) / journey * lastChapter, lastChapter)
      const opening = motion.matches ? Math.round(openingRatio) : openingRatio
      const progress = motion.matches ? Math.round(chapterPosition) : chapterPosition
      setPosition(previous => previous.opening === opening && previous.progress === progress ? previous : { opening, progress })
      const pastFilm = y >= line.offsetHeight - 1
      const slide = pastFilm ? 0 : smooth(0, .34, opening)
      root.style.setProperty('--medipick-header-offset', (-100 * slide).toFixed(2) + '%')
      const hidden = slide === 1
      if (hidden !== headerHidden) {
        headerHidden = hidden
        window.dispatchEvent(new CustomEvent('medipick-header-visibility', { detail: hidden }))
      }
    }
    const schedule = () => { if (!frame) frame = requestAnimationFrame(measure) }
    measure()
    const observer = new ResizeObserver(schedule)
    observer.observe(line)
    observer.observe(screen)
    window.addEventListener('scroll', schedule, { passive: true })
    window.addEventListener('resize', schedule)
    motion.addEventListener('change', schedule)
    return () => {
      cancelAnimationFrame(frame); observer.disconnect()
      window.removeEventListener('scroll', schedule); window.removeEventListener('resize', schedule)
      motion.removeEventListener('change', schedule)
      root.style.removeProperty('--medipick-header-offset')
      if (headerHidden) window.dispatchEvent(new CustomEvent('medipick-header-visibility', { detail: false }))
    }
  }, [])

  useEffect(() => {
    const index = chapters.findIndex(chapter => chapter.id === initialScene)
    if (index < 0) return
    const frame = requestAnimationFrame(() => {
      track.current?.querySelector('[data-film-anchor-index="' + index + '"]')?.scrollIntoView({ behavior: 'instant' })
    })
    return () => cancelAnimationFrame(frame)
  }, [initialScene])

  const expanded = smooth(0, 1, opening)
  const mapOpening = smooth(2.30, 2.84, progress)
  const vars: FrameStyle = {
    '--opening': expanded, '--poster-inset': 1 - expanded,
    '--intro': 1 - smooth(.05, .58, progress),
    '--track-progress': (opening * .4 + progress) / (lastChapter + .4),
    '--intro-rise': (-90 * smooth(0, .75, progress)) + 'px',
    '--map-opening': mapOpening,
  }
  const masks = chapters.map((chapter, index) => chapterWipe(chapter.pane,
    chapter.pane === 'map' ? mapOpening : smooth(index - .79, index - .12, progress)))
  for (const [index, chapter] of chapters.entries()) {
    const key = chapter.pane
    if (!key) continue
    const delta = progress - index
    const outgoing = delta > 0 ? masks[index + 1] : undefined
    vars['--' + key + '-mask'] = masks[index].enter
    vars['--' + key + '-ui-mask'] = outgoing ? outgoing.leave : masks[index].enter
    vars['--' + key + '-cutout'] = outgoing?.cutout ?? 'none'
    const arrival = key === 'chat' ? smooth(-.72, -.12, delta) : 1
    vars['--' + key + '-opacity'] = arrival * (index === lastChapter ? 1 : 1 - smooth(.53, .88, delta))
    vars['--' + key + '-reveal'] = smooth(-.66, -.025, delta)
  }
  return <div className="medipick-home">
    <section ref={track} className="film-scroll" style={{ '--travel-steps': lastChapter } as FrameStyle} aria-label="스크롤로 둘러보는 메디픽">
      <span ref={openingEnd} className="film-opening-end" aria-hidden="true"/>
      {chapters.map((item, i) => <span key={item.id} className="film-anchor" data-film-anchor-index={i} style={{ top: i ? 'calc(var(--opening-travel) + var(--travel) * ' + i + ')' : 0 }} aria-hidden="true"/>)}
      <div className="film-stage" ref={stage} style={vars} data-phase={phase} data-opening={opening.toFixed(4)} data-progress={progress.toFixed(4)}>
        <div className="film-screen">
        <div className="film-layout">
          <div className="film-camera"><FilmVisual phase={phase} progress={progress} opening={opening}>
            <Scenes phase={phase} onBooking={onBooking} onAccount={onAccount} goToChapter={goToChapter} recommendation={recommendation}/>
          </FilmVisual></div>
        </div>
        <div className="film-intro" aria-hidden={progress > .44} inert={progress > .44}>
          <h1><span>내게 맞는 병원,</span>{' '}<br className="intro-break"/><span>여기서 찾아요.</span></h1>
          <p>증상에 맞는 병원 찾기부터 모의 예약까지.</p>
          <div className="film-intro-actions">
            <a href="#/chat"><Sparkles size={17} aria-hidden="true"/>AI 상담 시작</a>
            <a href="#/search"><Search size={17} aria-hidden="true"/>병원 직접 찾기</a>
          </div>
        </div>
          <div className="film-story-copy">
            {chapters.slice(1).map((chapter, i) => {
              const index = i + 1, delta = progress - index
              return <div className="film-word-frame" key={chapter.id} data-pane={chapter.pane} aria-hidden={phase !== index} inert={phase !== index}
                style={{ opacity: exposure(delta), transform: 'translate3d(0,' + (-Math.max(-1, Math.min(1, delta)) * 54) + 'px,0)',
                  '--word-lift': (1 - smooth(-.67, -.09, delta)) * 65 + '%',
                  '--word-trail': (1 - smooth(-.49, -.01, delta)) * 75 + '%',
                } as FrameStyle}>
                <p className="film-kicker">{chapter.name}</p>
                <h2><span><b>{chapter.title[0]}</b></span><br/>{' '}<span><b>{chapter.title[1]}</b></span></h2>
                <p className="film-description">{chapter.detail}</p>
                {index < lastChapter ? <button type="button" className="film-link" onClick={() => goToChapter(index + 1)}>{chapters[index + 1].name} 보기 <ArrowRight size={15}/></button>
                  : <button type="button" className="film-link" onClick={onAccount}>마이페이지 보기 <ArrowRight size={15}/></button>}
              </div>
            })}
          </div>
        <div className="film-controls">
          <nav className="film-steps" aria-label="장면 선택">
            {chapters.map((item, i) => <button key={item.id} type="button" onClick={() => goToChapter(i)} aria-label={item.name} aria-current={phase === i ? 'step' : undefined}><i aria-hidden="true"/><span>{item.short}</span></button>)}
            <span className="film-line" aria-hidden="true"><i/></span>
          </nav>
          <button type="button" className="film-scroll-hint" onClick={() => goToChapter((phase + 1) % chapters.length)} aria-label={phase === lastChapter ? '처음으로' : '다음 장면으로'}>
            <span>{phase === lastChapter ? '처음으로' : '스크롤해서 둘러보기'}</span>{phase === lastChapter ? <ArrowUp size={15}/> : <ArrowDown size={15}/>}
          </button>
        </div>
        </div>
      </div>
    </section>
    <HomeSearch/>
  </div>
}
