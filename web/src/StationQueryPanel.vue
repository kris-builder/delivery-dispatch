<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, requestJson } from './api'
import StationSchedulingPanel from './StationSchedulingPanel.vue'
import { createStationTime } from './stationTime'
import example from '../../fixtures/station-01-query.json'

interface Page<T> { items: T[]; page: number; pageSize: number; total: number }
interface Worker { id: string; displayName: string }
interface Area { id: string; code: string; name: string; regularWorker: Worker | null }

const props = defineProps<{ dispatcher: boolean }>()
const emit = defineEmits<{ sessionExpired: [] }>()
const lifetime = new AbortController()
const areas = ref<Page<Area> | null>(null)
const workers = ref<Page<Worker> | null>(null)
const areaLoading = ref(false)
const workerLoading = ref(false)
const areaError = ref('')
const workerError = ref('')
const stationTime = createStationTime()
const localInput = ref(stationTime.toInput(example.at))
const currentAt = ref('')

function reportError(reason: unknown) {
  if (lifetime.signal.aborted) return ''
  if (reason instanceof ApiError && reason.status === 401) emit('sessionExpired')
  return reason instanceof ApiError ? reason.message : '查询失败，请重试。'
}

async function loadAreas(page: number) {
  areaLoading.value = true
  areaError.value = ''
  try {
    areas.value = await requestJson<Page<Area>>(`/staff/areas?page=${page}`, { signal: lifetime.signal })
  } catch (reason) {
    areaError.value = reportError(reason)
  } finally {
    areaLoading.value = false
  }
}

async function loadWorkers(page: number, at: string) {
  workerLoading.value = true
  workerError.value = ''
  try {
    const query = new URLSearchParams({ at, page: String(page) })
    workers.value = await requestJson<Page<Worker>>(`/staff/workers?${query}`, { signal: lifetime.signal })
    currentAt.value = at
  } catch (reason) {
    workerError.value = reportError(reason)
  } finally {
    workerLoading.value = false
  }
}

function queryWorkers() {
  const at = stationTime.fromInput(localInput.value)
  if (!at) {
    workerError.value = '请选择有效的北京时间。'
    return
  }
  void loadWorkers(1, at)
}

onMounted(() => {
  void loadAreas(1)
  if (props.dispatcher) queryWorkers()
})
onBeforeUnmount(() => lifetime.abort())
</script>

<template>
  <section class="panel station" aria-labelledby="station-title">
    <h2 id="station-title">{{ dispatcher ? '片区与当班人员' : '本网点片区' }}</h2>
    <div class="station-results" :class="{ dispatcher }">
      <section aria-label="启用片区">
        <table v-if="areas?.items.length">
          <caption>启用片区</caption>
          <thead>
            <tr><th scope="col">片区</th><th scope="col">常规负责人</th></tr>
          </thead>
          <tbody>
            <tr v-for="area in areas.items" :key="area.id">
              <th scope="row">{{ area.name }}</th>
              <td>{{ area.regularWorker?.displayName ?? '未配置' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="areaLoading" role="status">正在查询片区</p>
        <p v-else-if="areas && !areas.total">当前没有启用片区。</p>
        <p v-if="areaError" role="alert" class="error">{{ areaError }}</p>
        <button v-if="areaError && !areas" type="button" :disabled="areaLoading" @click="loadAreas(1)">重查片区</button>
        <div v-if="areas && areas.total > areas.pageSize" class="pager">
          <button type="button" :disabled="areaLoading || areas.page <= 1" @click="loadAreas(areas.page - 1)">上一页</button>
          <button type="button" :disabled="areaLoading || areas.page * areas.pageSize >= areas.total" @click="loadAreas(areas.page + 1)">下一页</button>
        </div>
      </section>
      <section v-if="dispatcher" class="roster" aria-labelledby="roster-title">
        <h3 id="roster-title">在岗配送员</h3>
        <form class="query" @submit.prevent="queryWorkers">
          <label>查询时间（北京时间） <input v-model="localInput" required type="datetime-local"></label>
          <button :disabled="workerLoading">查当班</button>
        </form>
        <p v-if="workerLoading" role="status">正在查询当班人员</p>
        <p v-if="workerError" role="alert" class="error">{{ workerError }}</p>
        <div v-if="workers" aria-live="polite">
          <p><time :datetime="currentAt">{{ stationTime.format(currentAt) }}（北京时间）</time> · {{ workers.total }} 人在岗</p>
          <ul v-if="workers.items.length">
            <li v-for="worker in workers.items" :key="worker.id">{{ worker.displayName }}</li>
          </ul>
          <div v-if="workers.total > workers.pageSize" class="pager">
            <button type="button" :disabled="workerLoading || workers.page <= 1" @click="loadWorkers(workers.page - 1, currentAt)">上一页</button>
            <button type="button" :disabled="workerLoading || workers.page * workers.pageSize >= workers.total" @click="loadWorkers(workers.page + 1, currentAt)">下一页</button>
          </div>
        </div>
      </section>
    </div>
    <StationSchedulingPanel
      v-if="dispatcher && currentAt"
      :at="currentAt"
      @updated="loadWorkers(1, currentAt)"
      @session-expired="emit('sessionExpired')"
    />
  </section>
</template>

<style scoped>
.station, .roster { display: grid; gap: 12px; }
.station-results { display: grid; gap: 20px; align-items: start; }
.station-results.dispatcher { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
table { width: 100%; border-collapse: collapse; }
caption, h3 { margin: 0 0 5px; text-align: left; font-size: 15px; font-weight: 700; }
th, td { text-align: left; padding: 6px 8px 6px 0; border-bottom: 1px solid #d7e3dd; }
thead th { color: #51645b; font-weight: 500; }
tbody th { font-weight: 500; }
.query { display: flex; flex-wrap: wrap; gap: 6px; align-items: end; }
.query label { display: grid; gap: 4px; min-width: 0; }
.roster ul { margin: 5px 0 0; padding-left: 22px; }
.pager { display: flex; gap: 6px; margin-top: 8px; }
@media (max-width: 560px) {
  .station-results.dispatcher { grid-template-columns: minmax(0, 1fr); }
}
</style>
