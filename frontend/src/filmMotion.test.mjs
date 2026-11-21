import assert from 'node:assert/strict'
import test from 'node:test'
import { advanceFilmPosition } from './medipick/filmMotion.ts'

test('wheel steps settle continuously without overshooting in either direction', () => {
  for (const [start, target] of [[0, 180], [180, 0]]) {
    let position = start
    const first = advanceFilmPosition(position, target, 1000 / 60, 90)
    assert.ok(first > 0 && first < 180)
    for (let frame = 0; frame < 90; frame++) {
      const next = advanceFilmPosition(position, target, 1000 / 60, 90)
      assert.ok(Math.abs(target - next) <= Math.abs(target - position))
      assert.ok(next >= 0 && next <= 180)
      position = next
    }
    assert.equal(position, target)
  }
})

test('equal elapsed time produces the same movement at 60 Hz and 120 Hz', () => {
  const run = hz => {
    let position = 0
    for (let frame = 0; frame < hz / 5; frame++) {
      position = advanceFilmPosition(position, 180, 1000 / hz, 90)
    }
    return position
  }
  assert.ok(Math.abs(run(60) - run(120)) < .00001)
})

test('reversing direction responds on the next frame and touch has a shorter follow-through', () => {
  const desktop = advanceFilmPosition(0, 180, 16, 90)
  const touch = advanceFilmPosition(0, 180, 16, 65)
  assert.ok(touch > desktop && touch < 180)
  const reverse = advanceFilmPosition(desktop, 0, 16, 90)
  assert.ok(reverse < desktop && reverse > 0)
  assert.equal(advanceFilmPosition(180, 180, 16, 90), 180)
  assert.equal(advanceFilmPosition(0, 180, 16, 0), 180)
})
