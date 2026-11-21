// Interpolate the visual position, leaving native page scrolling and its distance unchanged.
// Elapsed time keeps the response consistent on 60 Hz and high-refresh-rate screens.
export function advanceFilmPosition(current: number, target: number, elapsedMs: number, responseMs: number) {
  if (responseMs <= 0) return target
  const next = current + (target - current) * -Math.expm1(-Math.max(0, elapsedMs) / responseMs)
  return Math.abs(target - next) < .1 ? target : next
}
