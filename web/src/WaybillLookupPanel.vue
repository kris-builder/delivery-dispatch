<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, requestJson } from './api'
import { pendingAreaReasons, type PendingAreaReason } from './inboundAreaReasons'
import { BEIJING_ZONE } from './stationTime'

interface Batch {
  id: string
  sourceBatchNo: string
}

interface Waybill {
  waybillNo: string
  status: 'EXPECTED' | 'PENDING_AREA' | 'RECEIVED' | 'QUARANTINED' | 'RETURNED_UPSTREAM'
  firstReceivedAt: string | null
  receivedBatch: Batch | null
  area: { name: string } | null
  pendingAreaReason: PendingAreaReason | null
  expectedBatches: Batch[]
  events: { type: string; occurredAt: string }[]
}

const emit = defineEmits<{ sessionExpired: [] }>()
const waybillNo = ref('')
const result = ref<Waybill | null>(null)
const loading = ref(false)
const error = ref('')
const statuses = {
  EXPECTED: '仅应到', PENDING_AREA: '已实收待补分', RECEIVED: '已实收已分区',
  QUARANTINED: '隔离待核', RETURNED_UPSTREAM: '已交回上游',
}
const eventNames: Record<string, string> = {
  INBOUND_RECEIVED: '入站实收', MANUAL_AREA_ASSIGNED: '人工补分', INBOUND_QUARANTINED: '破损实收隔离',
  INBOUND_RETURNED_UPSTREAM: '交回上游',
}
const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'medium', timeZone: BEIJING_ZONE,
})

async function lookup() {
  const waybill = waybillNo.value.trim()
  if (!waybill || loading.value) return
  result.value = null
  error.value = ''
  loading.value = true
  try {
    const query = new URLSearchParams({ waybillNo: waybill })
    result.value = await requestJson<Waybill>(`/staff/parcels?${query}`)
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) emit('sessionExpired')
    else error.value = failure instanceof ApiError ? failure.message : '暂时无法查件，请重试。'
  } finally {
    loading.value = false
  }
}

function formatTime(value: string) {
  return timeFormatter.format(new Date(value))
}
</script>

<template>
  <section class="panel lookup" aria-labelledby="waybill-heading">
    <h2 id="waybill-heading">网点内部查件</h2>
    <form @submit.prevent="lookup">
      <label>
        完整运单号
        <input v-model="waybillNo" required maxlength="64" autocomplete="off" spellcheck="false">
      </label>
      <button :disabled="loading">查询运单</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="result" class="lookup-result" aria-live="polite">
      <p><code>{{ result.waybillNo }}</code> · {{ statuses[result.status] }}</p>
      <dl>
        <dt>首次有效实收</dt>
        <dd><time v-if="result.firstReceivedAt" :datetime="result.firstReceivedAt">{{ formatTime(result.firstReceivedAt) }}</time><span v-else>尚未实收</span></dd>
        <template v-if="result.receivedBatch">
          <dt>实收批次</dt>
          <dd>{{ result.receivedBatch.sourceBatchNo }}</dd>
        </template>
        <template v-else>
          <dt>应到批次</dt>
          <dd>{{ result.expectedBatches.map(batch => batch.sourceBatchNo).join('、') || '暂无' }}</dd>
        </template>
        <template v-if="result.area">
          <dt>当前片区</dt>
          <dd>{{ result.area.name }}</dd>
        </template>
        <template v-if="result.pendingAreaReason">
          <dt>待补分原因</dt>
          <dd>{{ pendingAreaReasons[result.pendingAreaReason] }}</dd>
        </template>
      </dl>
      <details v-if="result.events.length">
        <summary>履约流水</summary>
        <ul>
          <li v-for="(event, index) in result.events" :key="index">
            <time :datetime="event.occurredAt">{{ formatTime(event.occurredAt) }}</time>
            {{ eventNames[event.type] ?? event.type }}
          </li>
        </ul>
      </details>
    </div>
  </section>
</template>

<style scoped>
.lookup, .lookup-result, label { display: grid; gap: 8px; }
form { display: flex; gap: 8px; align-items: end; flex-wrap: wrap; }
label { flex: 1 1 180px; }
dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 0; }
dd { margin: 0; overflow-wrap: anywhere; }
</style>
