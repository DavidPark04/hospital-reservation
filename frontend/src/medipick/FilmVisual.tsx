import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties, ReactNode, RefObject } from 'react'
import './film-visual.css'

type Props = { phase: number; progress: number; opening?: number; children: ReactNode }
type Variables = CSSProperties & Record<`--${string}`, number | string>

function smooth(value: number) {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
}

// Two original illustrations, painted once at double resolution and scaled by the scene layout.
export function DoctorPortrait({ index }: { index: number }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const g = ref.current?.getContext('2d')
    if (!g) return
    const glasses = index % 2 === 1
    const ink = glasses ? '#353142' : '#263d56'
    const hair = glasses ? '#373344' : '#273d51'
    const skin = glasses ? '#eba98b' : '#eeb999'
    const shade = glasses ? '#cb806b' : '#cf9378'
    const shirt = glasses ? '#69758d' : '#477995'
    const fill = (color: string, shape: string) => {
      g.fillStyle = color
      g.fill(new Path2D(shape))
    }
    const line = (color: string, width: number, shape: string) => {
      g.strokeStyle = color
      g.lineWidth = width
      g.stroke(new Path2D(shape))
    }
    const dot = (x: number, y: number, r: number, color: string) => {
      g.fillStyle = color
      g.beginPath()
      g.arc(x, y, r, 0, 2 * Math.PI)
      g.fill()
    }
    g.setTransform(2, 0, 0, 2, 0, 0)
    g.clearRect(0, 0, 256, 230)
    g.lineJoin = 'round'
    g.lineCap = 'round'
    if (!glasses) {
      fill(hair, 'M83 78Q79 41 110 28Q144 13 164 41Q179 60 174 87L182 120Q183 133 166 145Q153 151 142 142L115 141Q93 151 77 135Q68 126 78 108Z')
      fill('#3c5162', 'M89 72Q91 36 120 32Q101 41 98 72L94 111Q92 126 83 134Q97 124 99 113Z')
    }
    fill('#bfcad0', 'M23 233Q26 206 40 182Q53 164 83 153L106 145L148 143L177 153Q207 161 219 178Q233 198 235 233Z')
    fill('#f4f5ee', 'M29 233Q36 187 55 175Q75 161 104 151L152 148Q186 156 205 171Q224 186 230 233Z')
    fill('#dce4e4', 'M33 233Q41 204 47 197Q55 212 63 233ZM190 233Q196 211 210 193L222 233Z')
    fill(shirt, 'M100 154L127 142L156 152Q150 194 138 233L114 233Q104 195 100 154Z')
    fill(skin, 'M110 116L149 116L149 144Q150 151 158 154Q151 163 139 168L123 171Q111 164 102 155Q111 152 111 145Z')
    fill(shade, 'M110 121L149 120L149 136Q134 150 112 150Z')
    fill('#fafbf4', 'M102 147L81 158L73 186L87 189L80 199L108 233L118 233Q111 197 102 147ZM155 147L174 157L182 183L168 190L175 198L146 233L133 233Q145 194 155 147Z')
    line('#aabdc3', 1.5, 'M101 151L113 216M155 150L139 215M76 185L88 189L82 197M178 183L168 190L173 197')
    if (glasses) {
      fill(skin, 'M91 73Q89 42 129 40Q163 40 167 69L165 99Q163 120 148 132Q139 141 124 140Q102 137 94 115L89 96Z')
      fill(hair, 'M89 93Q77 78 82 59Q76 49 91 41Q92 28 107 27Q118 18 133 26Q146 19 159 31Q176 35 170 52Q175 65 166 81L162 90L160 68Q147 64 140 49Q122 62 104 57Q103 67 95 73L94 94Z')
      fill('#534a59', 'M87 58Q90 42 106 42Q108 33 120 33Q133 29 139 36Q123 36 115 45Q99 44 87 58ZM140 33Q155 30 164 48Q159 40 147 40Z')
    } else {
      fill(skin, 'M91 77Q91 48 126 42Q153 42 165 72L162 100Q160 118 145 131Q136 139 124 138Q106 135 96 118Q88 106 88 91Z')
      fill(hair, 'M87 86Q80 57 99 40Q123 20 146 34Q164 44 169 69L164 93L159 82L157 69Q139 66 126 45Q112 64 96 69L93 90Z')
      fill('#42586b', 'M91 60Q98 40 121 37Q108 43 102 52ZM125 37Q143 37 157 55Q141 44 135 45Z')
    }
    fill(skin, 'M92 88Q87 79 83 85Q79 94 86 105Q90 111 95 106ZM163 86Q169 82 171 89Q172 98 164 106Z')
    line(shade, 1.7, 'M85 91Q90 89 91 99M167 91L165 99M129 89Q128 99 125 104Q128 108 132 105')
    line(ink, 2.2, 'M99 83Q106 79 114 82M137 82Q144 78 153 82')
    dot(108, 90, 1.9, ink)
    dot(145, 90, 1.9, ink)
    if (glasses) {
      line(ink, 2.7, 'M96 83Q105 80 119 83L120 89Q118 99 108 98Q99 98 97 91ZM135 83Q146 81 157 83L155 92Q153 98 144 98Q137 97 135 92ZM119 86Q126 83 135 86M96 85L90 83M157 85L164 81')
    }
    line('#a36458', 1.8, 'M118 114Q128 120 140 112')
    line('#d09a84', 1.3, 'M126 122Q132 123 135 121')
    // Collar, working stethoscope and pocket.
    line('#344c62', 3.4, 'M99 151Q84 162 82 178Q79 197 91 203Q103 209 109 194Q112 183 105 170M155 150Q170 153 177 175L184 201')
    line('#78969f', 1.5, 'M83 182Q82 195 88 199M102 202Q109 198 110 188')
    dot(185, 204, 6.1, ink)
    dot(185, 204, 3.4, '#b6d3d3')
    line('#99afb6', 1.4, 'M163 217L192 213L192 230M171 211L170 205')
  }, [index])
  return <canvas ref={ref} className="fv-portrait" width="512" height="460" aria-hidden="true"/>
}

const chapters = ['chat', 'reasons', 'map', 'doctors', 'booking'] as const

// Coordinates of the location pin, including mobile layout and an alternate recommendation.
function useMapOrigin(scene: RefObject<HTMLDivElement | null>, phase: number, reasonsSettled: boolean) {
  const [origin, setOrigin] = useState({ x: .53, y: .66, diameter: 17, screen: 1280, irisScale: 3 })
  useLayoutEffect(() => {
    if (phase !== 2 && phase !== 3) return
    const root = scene.current
    const marker = root?.querySelector<HTMLElement>('.fv-pin-source')
    if (!root || !marker) return
    function measure() {
      if (!root || !marker) return
      const area = root.getBoundingClientRect(), icon = marker.getBoundingClientRect()
      if (!area.width || !area.height || !icon.width) return
      const screen = Math.max(area.width, area.height)
      const measured = {
        x: (icon.x + icon.width / 2 - area.x) / area.width,
        y: (icon.y + icon.height / 2 - area.y) / area.height,
        diameter: icon.width,
        screen,
        // CSS circle percentages use the normalized diagonal of the reference box.
        irisScale: 1.45 * Math.hypot(area.width, area.height) / (Math.SQRT2 * screen * .43),
      }
      setOrigin(last => Math.abs(last.x-measured.x) + Math.abs(last.y-measured.y) < .0004 &&
        last.diameter === measured.diameter && last.irisScale === measured.irisScale && last.screen === measured.screen ? last : measured)
    }
    const resize = new ResizeObserver(measure)
    resize.observe(root)
    resize.observe(marker)
    const sheet = marker.closest('.sc-reason-sheet')
    // Live search results and cited sources can reposition the pin while the viewport stays still.
    const content = new MutationObserver(measure)
    if (sheet) {
      resize.observe(sheet)
      content.observe(sheet, { childList: true, characterData: true, subtree: true })
    }
    root.addEventListener('scroll', measure, true)
    measure()
    return () => { resize.disconnect(); content.disconnect(); root.removeEventListener('scroll', measure, true) }
  }, [scene, phase, reasonsSettled])
  return origin
}

export default function FilmVisual({ phase, progress, opening = 1, children }: Props) {
  const visual = useRef<HTMLDivElement>(null)
  const origin = useMapOrigin(visual, phase, progress >= 1.96)
  const open = smooth(opening)
  const flight = smooth(progress / .75)
  const orbitAngle = (-151 + 231 * open + 79 * flight) * Math.PI / 180
  const travel = smooth((progress - 2.015) / .285)
  const through = smooth((progress - 2.30) / .54)
  const pinInFlight = progress > 2.005 && progress < 2.86
  const pinWidth = origin.diameter + travel * Math.min(74, origin.screen * .073)
  const style: Variables = {
    '--fv-art-scale': .72 + .36 * open + 3.8 * flight ** 2,
    '--fv-art-y': (4 - 6 * open - 27 * flight) + '%',
    '--fv-art-x': (-4 + 4 * open - 30 * flight) + '%',
    '--fv-roll': (-34 + 43 * open + 54 * flight) + 'deg',
    '--fv-pitch': (17 - 26 * open - 12 * flight) + 'deg',
    '--fv-yaw': (-32 + 45 * open + 22 * flight) + 'deg',
    '--fv-pass': 1 - smooth((progress - .29) / .45),
    '--fv-orbit-tilt': (-32 + 49 * open + 34 * flight) + 'deg',
    '--fv-orbit-scale': .82 + .23 * open + 2.4 * flight,
    '--fv-bead-x': (50 + 50 * Math.cos(orbitAngle)) + '%',
    '--fv-bead-y': (50 + 50 * Math.sin(orbitAngle)) + '%',
    '--fv-crown-scale': .82 + .26 * open + 1.4 * flight,
    '--fv-pin-x': (100 * (origin.x + (.7 - origin.x) * travel)) + '%',
    '--fv-pin-y': (100 * (origin.y + (.45 - origin.y) * travel - .055 * Math.sin(travel * Math.PI))) + '%',
    '--fv-pin-scale': pinWidth / origin.screen,
    '--fv-pin-size': origin.screen + 'px',
    '--fv-iris-scale': origin.irisScale,
    '--fv-pin-twist': (-16 * Math.sin(Math.PI * travel)) + 'deg',
    '--fv-pin-wash': 100 * smooth(travel * 1.5) + '%',
    '--fv-local-aperture': through,
    '--fv-pin-opacity': 1 - smooth((progress - 2.49) / .34),
  }
  for (const [i, name] of chapters.entries()) {
    const reveal = smooth((progress - i - .24) / .54)
    style[`--fv-${name}-fade`] = reveal
    style[`--fv-${name}-parallax`] = (Math.max(-1, Math.min(1, progress - i - 1)) * -8) + '%'
  }
  return <div ref={visual} className="film-visual" style={style} data-pin-in-flight={pinInFlight || undefined}>
    <div className="fv-backdrops" aria-hidden="true">
      <div className="fv-sheet fv-chat"><i className="fv-chat-frequency"/></div>
      <div className="fv-sheet fv-reasons"><div className="fv-optic"><i/><i/><i/><b/></div></div>
      <div className="fv-sheet fv-map"><i className="fv-map-water"/></div>
      <div className="fv-sheet fv-doctors"><i className="fv-clinic-light"/></div>
      <div className="fv-sheet fv-booking"><div className="fv-paper"><i/><i/><i/></div></div>
    </div>
    <div className="fv-flight" aria-hidden="true" hidden={progress >= .75}>
      <div className="fv-crown"/>
      <div className="fv-orbit"><i/><b/></div>
      <img className="fv-art" src="/medipick/hero.webp" width="1200" height="1043" alt="" fetchPriority="high" draggable="false"/>
    </div>
    <div className="fv-frame" inert={phase === 0} aria-hidden={phase === 0 || undefined}>{children}</div>
    <div className="fv-pin-transfer" aria-hidden="true" hidden={!pinInFlight}><i/></div>
  </div>
}
