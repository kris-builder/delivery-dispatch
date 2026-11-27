import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import * as stationTime from '../src/stationTime.ts'

test('调整已保存后，候选人刷新失败单独提示', async () => {
  const draft = { id: '7', version: '0', evaluatedAt: '2026-11-25T08:00:00+08:00' }
  const saved = { ...draft, version: '1' }
  let visibleAtRefresh
  async function requestJson(path, options) {
    if (path === '/auth/csrf') return { headerName: 'X-CSRF-TOKEN', token: 'test' }
    if (path === '/staff/delivery-plans/7') {
      assert.equal(options.method, 'PATCH')
      assert.equal(JSON.parse(options.body).expectedVersion, '0')
      return saved
    }
    assert.match(path, /\/support-workers\?/)
    visibleAtRefresh = { version: page.draft.value.version, status: page.status.value }
    throw new Error('暂时无法连接')
  }

  const filename = 'PlanningDraftPage.vue'
  const source = readFileSync(new URL('../src/' + filename, import.meta.url), 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.deepEqual(errors, [])
  const { content } = compileScript(descriptor, { id: 'planning-draft-page' })
  const { outputText } = ts.transpileModule(content, { compilerOptions: { module: ts.ModuleKind.CommonJS } })
  const dependencies = {
    vue: { ...vue, onMounted: () => {} },
    './api': { requestJson },
    './stationTime': stationTime,
  }
  const exports = {}
  new Function('require', 'exports', outputText)((name) => {
    assert.ok(Object.hasOwn(dependencies, name), 'unexpected import: ' + name)
    return dependencies[name]
  }, exports)
  const page = exports.default.setup({}, { expose: () => {} })

  page.draft.value = draft
  page.selected.value = ['12']
  page.supportWorkerId.value = '21'
  page.reason.value = '东区临时支援'

  await page.assignSupport()
  assert.deepEqual(visibleAtRefresh, { version: '1', status: '调整已保存。' })
  assert.equal(page.status.value, '调整已保存。')
  assert.match(page.error.value, /候选人刷新失败.*暂时无法连接/)
  assert.equal(page.busy.value, false)
})
