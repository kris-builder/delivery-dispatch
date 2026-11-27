<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ApiError, postJson, requestJson } from './api'
import { pendingAreaReasons, type PendingAreaReason } from './inboundAreaReasons'
import AreaAssignmentPanel from './AreaAssignmentPanel.vue'
import demo from '../../fixtures/mvp.json'

interface BatchSummary {
  id: string
  sourceBatchNo: string
  expectedCount: number
  receivedCount: number
}

interface BatchDetail extends BatchSummary {
  assignedCount: number
  pendingAreaCount: number
  sortCreditCount: number
  expected: { waybillNo: string; street: string | null; community: string | null }[]
}

interface Receipt {
  status: 'RECEIVED' | 'PENDING_AREA' | 'ALREADY_RECEIVED'
  waybillNo: string
  receivedBy: string
  area: { id: string; name: string } | null
  pendingAreaReason: PendingAreaReason | null
}

interface Scan {
  waybillNo: string
  key: string
}

const props = defineProps<{ dispatcher: boolean }>()
const emit = defineEmits<{ sessionExpired: [] }>()
const root = '/staff/inbound-batches'
const batches = ref<BatchSummary[]>([])
const selected = ref('')
const detail = ref<BatchDetail | null>(null)
const loading = ref(false)
const error = ref('')
const scanning = ref(false)
const scanInput = ref<HTMLInputElement | null>(null)
const waybill = ref('')
const queue = ref<Scan[]>([])
const sending = ref(false)
const blocked = ref('')
const history = ref<{ key: string; waybill: string; message: string; failed: boolean }[]>([])
const batchLocked = computed(() => scanning.value || sending.value || queue.value.length > 0)

function showError(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) {
    emit('sessionExpired')
    return
  }
  const message = reason instanceof ApiError ? reason.message : '无法读取批次，请重试。'
  error.value = reason instanceof ApiError && reason.requestId ? `${message}（请求号 ${reason.requestId}）` : message
}

async function loadBatches() {
  batches.value = await requestJson<BatchSummary[]>(root)
  if (!batches.value.some(batch => batch.id === selected.value)) {
    selected.value = batches.value[0]?.id ?? ''
  }
  detail.value = selected.value ? await requestJson<BatchDetail>(`${root}/${selected.value}`) : null
}

async function run(action: () => Promise<void>) {
  error.value = ''
  loading.value = true
  try {
    await action()
  } catch (reason) {
    showError(reason)
  } finally {
    loading.value = false
  }
}

onMounted(() => run(loadBatches))

function loadSelected() {
  detail.value = null
  history.value = []
  return run(async () => {
    detail.value = selected.value ? await requestJson<BatchDetail>(`${root}/${selected.value}`) : null
  })
}

function importDemo() {
  return run(async () => {
    const batch = await postJson<BatchDetail>(root, {
      targetNetworkCode: demo.organization.code,
      sourceBatchNo: demo.inboundBatch.code,
      parcels: demo.inboundBatch.parcels.map(parcel => ({
        waybillNo: parcel.waybillNo,
        street: parcel.street,
        community: parcel.community,
        recipientPhone: demo.recipients.find(recipient => recipient.code === parcel.recipient)?.phone,
      })),
    })
    selected.value = batch.id
    await loadBatches()
  })
}

function startScanning() {
  scanning.value = true
  void nextTick(() => scanInput.value?.focus())
}

function stopScanning() {
  scanning.value = false
}

function queueScan() {
  const value = waybill.value.trim()
  if (!value || !scanning.value) return
  queue.value.push({ waybillNo: value, key: crypto.randomUUID() })
  waybill.value = ''
  scanInput.value?.focus()
  void sendQueued()
}

function resultMessage(receipt: Receipt) {
  const area = receipt.area?.name
  if (receipt.status === 'ALREADY_RECEIVED') {
    return area ? `重复扫码 · ${area}` : '重复扫码 · 待补分'
  }
  if (area) return area
  return receipt.pendingAreaReason ? `已实收待补分 · ${pendingAreaReasons[receipt.pendingAreaReason]}` : '已实收待补分'
}

function addResult(scan: Scan, message: string, failed = false) {
  history.value = [{ key: scan.key, waybill: scan.waybillNo, message, failed }, ...history.value].slice(0, 8)
}

async function sendQueued() {
  if (sending.value || blocked.value || !queue.value.length || props.dispatcher) return
  sending.value = true
  const path = `${root}/${selected.value}`
  try {
    while (queue.value.length) {
      const scan = queue.value[0]
      let receipt: Receipt
      try {
        receipt = await postJson<Receipt>(`${path}/receipts`, { waybillNo: scan.waybillNo }, {
          operationKey: scan.key,
          signal: AbortSignal.timeout(8000),
        })
      } catch (reason) {
        if (reason instanceof ApiError && reason.status === 401) {
          emit('sessionExpired')
          return
        }
        if (!(reason instanceof ApiError) || reason.status >= 500 || reason.code === 'CSRF_INVALID') {
          const note = reason instanceof ApiError ? reason.message : '提交结果待确认。'
          blocked.value = `${scan.waybillNo} · ${note}`
          return
        }
        addResult(scan, reason.message, true)
        queue.value.shift()
        continue
      }
      addResult(scan, resultMessage(receipt))
      queue.value.shift()
      try {
        detail.value = await requestJson<BatchDetail>(path, { signal: AbortSignal.timeout(5000) })
        error.value = ''
      } catch (reason) {
        showError(reason)
      }
    }
  } finally {
    sending.value = false
  }
}

function retryScan() {
  blocked.value = ''
  if (scanning.value) scanInput.value?.focus()
  void sendQueued()
}

function refreshAfterAssignment() {
  return run(loadBatches)
}
</script>

<template>
  <section class="panel inbound">
    <div class="inbound-header">
      <h2>网点入站</h2>
      <button v-if="dispatcher" :disabled="loading" type="button" @click="importDemo">导入江岸合成清单</button>
    </div>
    <label v-if="batches.length">
      应到批次
      <select v-model="selected" :disabled="loading || batchLocked" @change="loadSelected">
        <option v-for="batch in batches" :key="batch.id" :value="batch.id">{{ batch.sourceBatchNo }}</option>
      </select>
    </label>
    <p v-else-if="!loading && !error">本网点暂无应到批次。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="detail" class="inbound-detail">
      <div class="inbound-totals">
        <p>应到 <strong>{{ detail.expectedCount }}</strong></p>
        <p>实收 <strong>{{ detail.receivedCount }}</strong></p>
        <p>已分区 <strong>{{ detail.assignedCount }}</strong></p>
        <p>待分区 <strong>{{ detail.pendingAreaCount }}</strong></p>
        <p>计件 <strong>{{ detail.sortCreditCount }}</strong></p>
      </div>
      <div v-if="!dispatcher" class="scan-controls">
        <button v-if="!scanning" type="button" :disabled="loading" @click="startScanning">开始扫码</button>
        <form v-else class="scan-form" @submit.prevent="queueScan">
          <label>
            扫码或手输运单号
            <input ref="scanInput" v-model="waybill" maxlength="64" autocomplete="off" spellcheck="false">
          </label>
          <button type="submit">录入</button>
          <button type="button" @click="stopScanning">退出扫码</button>
        </form>
        <div v-if="blocked" class="scan-retry">
          <p class="error" role="alert">{{ blocked }}</p>
          <button type="button" :disabled="sending" @click="retryScan">重试原扫码</button>
        </div>
        <p v-if="queue.length" role="status">待处理 {{ queue.length }} 件</p>
        <ul v-if="history.length" class="scan-results" aria-live="polite" aria-label="最近扫码结果">
          <li v-for="result in history" :key="result.key" :class="{ error: result.failed }">
            <code>{{ result.waybill }}</code>
            <strong>{{ result.message }}</strong>
          </li>
        </ul>
      </div>
      <details>
        <summary>应到明细</summary>
        <ul class="expected-lines">
          <li v-for="line in detail.expected" :key="line.waybillNo">
            <code>{{ line.waybillNo }}</code>
            <span>{{ [line.street, line.community].filter(Boolean).join(' ') || '缺地址' }}</span>
          </li>
        </ul>
      </details>
    </div>
    <AreaAssignmentPanel
      v-if="!dispatcher"
      v-show="!scanning"
      :scanning="scanning"
      @assigned="refreshAfterAssignment"
      @session-expired="emit('sessionExpired')"
    />
  </section>
</template>

<style scoped>
.inbound, .inbound-detail { display: grid; gap: 12px; }
.inbound-header, .inbound-totals { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; }
.inbound-header { justify-content: space-between; }
.inbound select { padding: 6px 8px; font: inherit; margin-inline-start: 8px; }
.expected-lines { display: grid; gap: 8px; padding: 0; list-style: none; }
.expected-lines li { display: flex; gap: 4px 12px; flex-wrap: wrap; }
.scan-controls, .scan-form label { display: grid; gap: 8px; }
.scan-controls > button { justify-self: start; }
.scan-form { display: flex; align-items: end; flex-wrap: wrap; gap: 8px; }
.scan-form label { flex: 1 1 200px; }
.scan-retry { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.scan-results { padding: 0; margin: 0; list-style: none; }
.scan-results li { display: flex; gap: 6px 12px; flex-wrap: wrap; padding: 7px 0; border-bottom: 1px solid #e7edea; }
</style>
