<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { postJson, requestJson } from './api'
import { createStationTime } from './stationTime'

type Session = { role: string; mustChangePassword: boolean }
type Batch = { id: string; sourceBatchNo: string; receivedCount: number }
type Shift = { shiftId: string; code: string; startsAt: string; endsAt: string }
type Item = {
  parcelId: string; waybillNo: string; parcelVersion: string
  proposedWorkerName: string | null; pendingReason: string | null
}
type Group = { areaId: string | null; areaName: string | null; total: number; assigned: number; pending: number; items: Item[] }
type Worker = { id: string; displayName: string }
type Adjustment = {
  parcelId: string; waybillNo: string; planVersion: string; oldWorkerName: string | null
  newWorkerName: string | null; reason: string; changedByName: string; changedAt: string
}
type Draft = {
  id: string; version: string; createdBy: string; createdAt: string; evaluatedAt: string
  batchNumber: string; shiftId: string; total: number; assigned: number; pending: number; groups: Group[]
  adjustments: Adjustment[]
}

const time = createStationTime()
const verified = ref(false)
const ready = ref(false)
const busy = ref(false)
const error = ref('')
const status = ref('')
const batches = ref<Batch[]>([])
const shifts = ref<Shift[]>([])
const batchId = ref('')
const shiftId = ref('')
const at = ref('2026-11-25T08:00')
const queriedAt = ref('')
const reopenId = ref('')
const draft = ref<Draft | null>(null)
const workers = ref<Worker[]>([])
const selected = ref<string[]>([])
const supportWorkerId = ref('')
const reason = ref('')
const pendingLabels: Record<string, string> = {
  AREA_UNCONFIRMED: '片区待确认',
  NO_REGULAR_WORKER: '无常规责任人',
  REGULAR_WORKER_UNAVAILABLE: '责任人缺岗',
  DISPATCHER_HOLD: '调度留站',
}

async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    await action()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '操作失败。'
  } finally {
    busy.value = false
  }
}

async function queryShifts() {
  shifts.value = []
  shiftId.value = ''
  queriedAt.value = ''
  const timestamp = time.fromInput(at.value)
  if (!timestamp) throw new Error('请输入有效的北京时间。')
  const found: Shift[] = []
  let page = 1
  for (;;) {
    const result = await requestJson<{ items: Shift[]; total: number }>(
      `/staff/delivery-plans/eligible-shifts?${new URLSearchParams({ at: timestamp, page: String(page), pageSize: '100' })}`,
    )
    found.push(...result.items)
    if (found.length >= result.total || result.items.length === 0) break
    page++
  }
  shifts.value = found
  shiftId.value = shifts.value[0]?.shiftId ?? ''
  queriedAt.value = timestamp
}

watch(at, () => {
  queriedAt.value = ''
  shifts.value = []
  shiftId.value = ''
})

onMounted(() => run(async () => {
  const session = await requestJson<Session>('/auth/session')
  if (session.role !== 'NETWORK_DISPATCHER' || session.mustChangePassword) {
    throw new Error('请返回员工入口，使用已改密的网点调度账号登录。')
  }
  verified.value = true
  batches.value = (await requestJson<Batch[]>('/staff/inbound-batches'))
    .filter(batch => batch.sourceBatchNo === 'PLANNING-03-DEMO')
  batchId.value = batches.value[0]?.id ?? ''
  ready.value = true
  await queryShifts()
}))

function createDraft() {
  return run(async () => {
    const saved = await postJson<Draft>('/staff/delivery-plans', {
      batchId: batchId.value, shiftId: shiftId.value, at: queriedAt.value,
    })
    await showDraft(saved)
    status.value = '草案已保存。'
  })
}

function reopenDraft() {
  return run(async () => {
    await showDraft(await requestJson<Draft>(`/staff/delivery-plans/${encodeURIComponent(reopenId.value)}`))
    status.value = '已重新读取保存的草案。'
  })
}

async function showDraft(saved: Draft) {
  draft.value = saved
  reopenId.value = saved.id
  selected.value = []
  supportWorkerId.value = ''
  reason.value = ''
  workers.value = []
  const found: Worker[] = []
  for (let page = 1; ; page++) {
    const result = await requestJson<{ items: Worker[]; total: number }>(
      `/staff/delivery-plans/${saved.id}/support-workers?page=${page}&pageSize=100`,
    )
    found.push(...result.items)
    if (found.length >= result.total || result.items.length === 0) break
  }
  workers.value = found
}

function toggleGroup(group: Group) {
  const ids = group.items.map(item => item.parcelId)
  if (ids.every(id => selected.value.includes(id))) {
    selected.value = selected.value.filter(id => !ids.includes(id))
  } else {
    selected.value = [...new Set([...selected.value, ...ids])]
  }
}

function assignSupport() {
  if (supportWorkerId.value) return submitAdjustment({ workerId: supportWorkerId.value })
}

function holdSelected() {
  return submitAdjustment({ action: 'HOLD' })
}

function submitAdjustment(action: { workerId: string } | { action: 'HOLD' }) {
  const current = draft.value
  if (!current || !selected.value.length || !reason.value.trim()) return
  const hold = 'action' in action
  return run(async () => {
    const csrf = await requestJson<{ headerName: string; token: string }>('/auth/csrf')
    const updated = await requestJson<Draft>(`/staff/delivery-plans/${current.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        ...action, expectedVersion: current.version, parcelIds: selected.value, reason: reason.value,
      }),
    })
    if (updated.version === current.version) {
      status.value = hold ? '所选运单已留站。' : '所选运单的拟领件人已是该配送员。'
    } else {
      status.value = hold ? '留站已保存。' : '调整已保存。'
    }
    try {
      await showDraft(updated)
    } catch (failure) {
      error.value = failure instanceof Error ? `候选人刷新失败。 ${failure.message}` : '候选人刷新失败。'
    }
  })
}
</script>

<template>
  <main>
    <header>
      <h1>配送草案验收</h1>
      <a href="/">员工入口</a>
    </header>
    <p v-if="status" role="status">{{ status }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <section v-if="ready" aria-labelledby="create-title">
      <h2 id="create-title">保存草案</h2>
      <p v-if="!batches.length">当前网点没有 PLANNING-03-DEMO 样例批次。</p>
      <form @submit.prevent="createDraft">
        <label>入站批次
          <select v-model="batchId" :disabled="busy" required>
            <option v-for="batch in batches" :key="batch.id" :value="batch.id">
              {{ batch.sourceBatchNo }} · 已实收 {{ batch.receivedCount }} 票
            </option>
          </select>
        </label>
        <label>北京时间
          <input v-model="at" type="datetime-local" :disabled="busy" required>
        </label>
        <button type="button" :disabled="busy" @click="run(queryShifts)">查班次</button>
        <label>班次
          <select v-model="shiftId" :disabled="busy" required>
            <option v-for="shift in shifts" :key="shift.shiftId" :value="shift.shiftId">
              {{ shift.code }} · {{ time.formatRange(shift.startsAt, shift.endsAt) }}
            </option>
          </select>
        </label>
        <button type="submit" :disabled="busy || !batchId || !shiftId || !queriedAt">生成并保存</button>
      </form>
    </section>
    <section v-if="verified" aria-labelledby="reopen-title">
      <h2 id="reopen-title">重新打开</h2>
      <form @submit.prevent="reopenDraft">
        <label>草案编号 <input v-model.trim="reopenId" inputmode="numeric" pattern="[1-9][0-9]*" required></label>
        <button type="submit" :disabled="busy">查草案</button>
      </form>
    </section>
    <section v-if="draft" aria-labelledby="draft-title">
      <h2 id="draft-title">草案 {{ draft.id }} · 版本 {{ draft.version }}</h2>
      <p>{{ draft.batchNumber }} · 班次 {{ draft.shiftId }} · 创建人账号 {{ draft.createdBy }}</p>
      <p>北京时间 {{ time.format(draft.createdAt) }} 创建 · 按 {{ time.format(draft.evaluatedAt) }} 的班次计算</p>
      <p>总数 {{ draft.total }} / 已拟派 {{ draft.assigned }} / 待安排 {{ draft.pending }}</p>
      <details v-for="group in draft.groups" :key="group.areaId ?? 'unconfirmed'" open>
        <summary>{{ group.areaName ?? '待分区' }} · 总数 {{ group.total }} / 已拟派 {{ group.assigned }} / 待安排 {{ group.pending }}</summary>
        <button v-if="group.areaId" type="button" :disabled="busy" @click="toggleGroup(group)">
          {{ group.items.every(item => selected.includes(item.parcelId)) ? '取消整组选中' : '选中整组' }}
        </button>
        <table>
          <thead><tr><th scope="col">选择</th><th scope="col">运单号</th><th scope="col">包裹版本</th><th scope="col">拟领件人</th><th scope="col">草案状态</th></tr></thead>
          <tbody><tr v-for="item in group.items" :key="item.parcelId">
            <td>
              <input v-if="group.areaId" v-model="selected" type="checkbox" :value="item.parcelId"
                :aria-label="`选择运单 ${item.waybillNo}`" :disabled="busy">
            </td>
            <td>{{ item.waybillNo }}</td><td>{{ item.parcelVersion }}</td><td>{{ item.proposedWorkerName ?? '—' }}</td>
            <td>{{ item.pendingReason ? (pendingLabels[item.pendingReason] ?? item.pendingReason) : '已拟派' }}</td>
          </tr></tbody>
        </table>
      </details>
      <h3>调整选中运单</h3>
      <form @submit.prevent="assignSupport">
        <label v-if="workers.length">当班配送员
          <select v-model="supportWorkerId" :disabled="busy">
            <option value="" disabled>请选择</option>
            <option v-for="worker in workers" :key="worker.id" :value="worker.id">{{ worker.displayName }}</option>
          </select>
        </label>
        <p v-else>该班次暂无在岗配送员。</p>
        <label>调整原因 <input v-model="reason" maxlength="500" :disabled="busy" required></label>
        <button v-if="workers.length" type="submit" :disabled="busy || !selected.length || !supportWorkerId || !reason.trim()">
          安排选中 {{ selected.length }} 票
        </button>
        <button type="button" :disabled="busy || !selected.length || !reason.trim()" @click="holdSelected">
          留站选中 {{ selected.length }} 票
        </button>
      </form>
      <template v-if="draft.adjustments.length">
        <h3>调整记录</h3>
        <table>
          <thead><tr><th scope="col">运单号</th><th scope="col">前后拟领件人</th><th scope="col">原因</th><th scope="col">操作人与时间（北京时间）</th></tr></thead>
          <tbody>
            <tr v-for="change in draft.adjustments" :key="`${change.planVersion}-${change.parcelId}`">
              <td>{{ change.waybillNo }}</td><td>{{ change.oldWorkerName ?? '待安排' }} → {{ change.newWorkerName ?? '调度留站' }}</td>
              <td>{{ change.reason }}</td><td>{{ change.changedByName }} · {{ time.format(change.changedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </template>
    </section>
  </main>
</template>

<style>
body { margin: 0; background: #f2f5f8; color: #162235; font: 16px/1.5 system-ui, sans-serif; }
main { max-width: 980px; margin: auto; padding: 24px 16px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
section { background: white; border: 1px solid #ccd6e0; border-radius: 10px; padding: 16px; margin-bottom: 16px; }
h2 { margin-top: 0; }
form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
label { display: grid; gap: 4px; }
button, input, select { font: inherit; padding: 5px 8px; }
details { margin-top: 14px; }
table { width: 100%; border-collapse: collapse; margin-top: 8px; }
th, td { text-align: left; padding: 5px; border-bottom: 1px solid #e1e6ee; }
[role=alert] { color: #8c1c18; }
</style>
