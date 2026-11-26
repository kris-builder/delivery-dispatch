import assert from 'node:assert/strict'
import test from 'node:test'
import { createStationTime } from '../src/stationTime.ts'

test('江岸班次按北京时间录入、传输和显示', () => {
  const stationTime = createStationTime()
  assert.equal(stationTime.fromInput('2026-11-25T08:00'), '2026-11-25T08:00:00+08:00')
  assert.equal(stationTime.fromInput('2026-11-25T12:00'), '2026-11-25T12:00:00+08:00')
  assert.equal(stationTime.toInput('2026-11-25T08:00:00+08:00'), '2026-11-25T08:00')
  assert.match(stationTime.formatRange('2026-11-25T08:00:00+08:00', '2026-11-25T12:00:00+08:00'), /08:00.*12:00/)
})

test('北京时间跨午夜的输入和回显保持业务日期', () => {
  const stationTime = createStationTime()
  assert.equal(stationTime.fromInput('2026-11-26T00:30'), '2026-11-26T00:30:00+08:00')
  assert.equal(stationTime.toInput('2026-11-25T16:30:00Z'), '2026-11-26T00:30')
})

test('拒绝无效日期，而不是自动顺延', () => {
  const stationTime = createStationTime()
  assert.equal(stationTime.fromInput('2026-02-30T08:00'), null)
  assert.equal(stationTime.fromInput('2026-11-25T24:00'), null)
  assert.equal(stationTime.fromInput(''), null)
})
