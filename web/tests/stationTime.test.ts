import assert from 'node:assert/strict'
import test from 'node:test'
import { createStationTime } from '../src/stationTime.ts'

test('江岸班次按北京时间录入并转换为 UTC', () => {
  const stationTime = createStationTime('Asia/Shanghai')
  assert.equal(stationTime.fromInput('2026-11-25T08:00'), '2026-11-25T00:00:00.000Z')
  assert.equal(stationTime.fromInput('2026-11-25T12:00'), '2026-11-25T04:00:00.000Z')
  assert.equal(stationTime.toInput('2026-11-25T00:00:00Z'), '2026-11-25T08:00')
  assert.match(stationTime.formatRange('2026-11-25T00:00:00Z', '2026-11-25T04:00:00Z'), /08:00.*12:00/)
})

test('换算处理跨日期和非整点偏移', () => {
  const stationTime = createStationTime('Asia/Kathmandu')
  assert.equal(stationTime.fromInput('2026-11-25T00:30'), '2026-11-24T18:45:00.000Z')
  assert.equal(stationTime.toInput('2026-11-24T18:45:00Z'), '2026-11-25T00:30')
})

test('同一网点按班次日期采用当时的偏移', () => {
  const stationTime = createStationTime('America/Los_Angeles')
  assert.equal(stationTime.fromInput('2026-01-15T08:00'), '2026-01-15T16:00:00.000Z')
  assert.equal(stationTime.fromInput('2026-07-15T08:00'), '2026-07-15T15:00:00.000Z')
})

test('时钟跳过或重复的本地时间须重新选择', () => {
  const stationTime = createStationTime('America/Los_Angeles')
  assert.equal(stationTime.fromInput('2026-03-08T02:30'), null)
  assert.equal(stationTime.fromInput('2026-11-01T01:30'), null)
  assert.equal(stationTime.fromInput('2026-11-01T02:30'), '2026-11-01T10:30:00.000Z')
})

test('拒绝无效日期，而不是自动顺延', () => {
  const stationTime = createStationTime('Asia/Shanghai')
  assert.equal(stationTime.fromInput('2026-02-30T08:00'), null)
  assert.equal(stationTime.fromInput('2026-11-25T24:00'), null)
  assert.equal(stationTime.fromInput(''), null)
})
