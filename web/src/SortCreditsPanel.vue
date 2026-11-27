<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError, requestJson } from './api'
import { createStationTime } from './stationTime'

interface Credit {
  receiptId: string
  waybillNo: string
  creditedAt: string
  source: string
}

interface Statement {
  businessDate: string
  effectiveCount: number
  items: Credit[]
}

const props = defineProps<{ revision: number }>()
const emit = defineEmits<{ sessionExpired: [] }>()
const stationTime = createStationTime()
const sourceNames: Record<string, string> = {
  INBOUND_RECEIVED: '扫码分拣',
  MANUAL_AREA_ASSIGNED: '人工补分',
}
const statement = ref<Statement | null>(null)
const loading = ref(false)
const error = ref('')
let request: AbortController | undefined

async function refresh() {
  request?.abort()
  const current = new AbortController()
  request = current
  loading.value = true
  error.value = ''
  try {
    const result = await requestJson<Statement>('/staff/me/sort-credits', { signal: current.signal })
    if (request === current) statement.value = result
  } catch (failure) {
    if (current.signal.aborted) return
    if (failure instanceof ApiError && failure.status === 401) emit('sessionExpired')
    else error.value = failure instanceof ApiError ? failure.message : '暂时无法刷新计件，请重试。'
  } finally {
    if (request === current) loading.value = false
  }
}

onMounted(refresh)
watch(() => props.revision, refresh)
onBeforeUnmount(() => request?.abort())
</script>

<template>
  <section class="sort-credits" aria-labelledby="sort-credit-title" :aria-busy="loading">
    <div class="credits-heading">
      <h2 id="sort-credit-title">本人今日分拣计件</h2>
      <button type="button" :disabled="loading" @click="refresh">刷新计件</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="loading && !statement" role="status">正在读取计件</p>
    <template v-if="statement">
      <p class="credit-count" aria-live="polite">
        {{ statement.businessDate }}（北京时间） · 有效计件 <strong>{{ statement.effectiveCount }}</strong> 件
      </p>
      <details v-if="statement.items.length">
        <summary>逐票明细</summary>
        <table>
          <thead><tr><th scope="col">运单号</th><th scope="col">计件来源</th><th scope="col">计件时间</th></tr></thead>
          <tbody>
            <tr v-for="item in statement.items" :key="item.receiptId">
              <td><code>{{ item.waybillNo }}</code></td>
              <td>{{ sourceNames[item.source] ?? item.source }}</td>
              <td><time :datetime="item.creditedAt">{{ stationTime.format(item.creditedAt) }}</time></td>
            </tr>
          </tbody>
        </table>
      </details>
    </template>
  </section>
</template>

<style scoped>
.sort-credits { display: grid; gap: 8px; border-top: 1px solid #d7e3dd; padding-top: 12px; }
.credits-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 5px; text-align: left; border-bottom: 1px solid #e7edea; }
</style>
