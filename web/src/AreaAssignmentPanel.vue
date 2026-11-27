<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError, postJson, requestJson } from './api'
import { pendingAreaReasons, type PendingAreaReason } from './inboundAreaReasons'

interface PendingParcel {
  parcelId: string
  waybillNo: string
  sourceBatchNo: string
  pendingAreaReason: PendingAreaReason
}

interface Area { id: string; name: string }
interface Attempt { parcelId: string; areaId: string; key: string }

const props = defineProps<{ scanning: boolean }>()
const emit = defineEmits<{ assigned: []; sessionExpired: [] }>()
const pending = ref<PendingParcel[]>([])
const areas = ref<Area[]>([])
const selectedParcel = ref('')
const areaId = ref('')
const selected = computed(() => pending.value.find(parcel => parcel.parcelId === selectedParcel.value))
const loading = ref(false)
const saving = ref(false)
const attempt = ref<Attempt | null>(null)
const error = ref('')
const confirmation = ref('')

function showError(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('sessionExpired')
  else error.value = reason instanceof ApiError ? reason.message : '暂时无法连接服务，请重试。'
}

async function loadAreas() {
  const active: Area[] = []
  for (let page = 1; ; page++) {
    const result = await requestJson<{ items: Area[]; total: number; pageSize: number }>(
      `/staff/areas?page=${page}&pageSize=100`,
    )
    active.push(...result.items)
    if (page * result.pageSize >= result.total) return active
  }
}

async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [parcels, active] = await Promise.all([
      requestJson<PendingParcel[]>('/staff/parcels/pending-area'), loadAreas(),
    ])
    pending.value = parcels
    areas.value = active
    if (!parcels.some(parcel => parcel.parcelId === selectedParcel.value)) selectedParcel.value = ''
    if (!active.some(area => area.id === areaId.value)) areaId.value = ''
  } catch (reason) {
    showError(reason)
  } finally {
    loading.value = false
  }
}

async function assign() {
  if (saving.value) return
  if (!attempt.value && selected.value && areaId.value) {
    attempt.value = { parcelId: selected.value.parcelId, areaId: areaId.value, key: crypto.randomUUID() }
  }
  const request = attempt.value
  if (!request) return
  saving.value = true
  error.value = ''
  confirmation.value = ''
  try {
    const result = await postJson<{ waybillNo: string; area: Area }>(
      `/staff/parcels/${request.parcelId}/area-assignments`, { areaId: request.areaId },
      { operationKey: request.key, signal: AbortSignal.timeout(8000) },
    )
    attempt.value = null
    confirmation.value = `${result.waybillNo} 已补到${result.area.name}。`
    pending.value = pending.value.filter(parcel => parcel.parcelId !== request.parcelId)
    selectedParcel.value = ''
    areaId.value = ''
    emit('assigned')
    await refresh()
  } catch (reason) {
    if (reason instanceof ApiError && reason.status < 500 && reason.code !== 'CSRF_INVALID') {
      attempt.value = null
      if (reason.status === 409) {
        await refresh()
        emit('assigned')
      }
    }
    showError(reason)
  } finally {
    saving.value = false
  }
}

onMounted(refresh)
watch(() => props.scanning, scanning => { if (!scanning) void refresh() })
</script>

<template>
  <section class="assignment" aria-labelledby="assignment-title">
    <div class="assignment-header">
      <h2 id="assignment-title">人工补分</h2>
      <button type="button" :disabled="loading || saving || !!attempt" @click="refresh">刷新待分区</button>
    </div>
    <p v-if="loading" role="status">正在读取待分区件</p>
    <p v-else-if="!pending.length && !error">本网点暂无待分区件。</p>
    <form v-if="pending.length || attempt" class="assignment-form" @submit.prevent="assign">
      <label>
        待分区运单
        <select v-model="selectedParcel" :disabled="loading || saving || !!attempt" required>
          <option value="" disabled>选择运单</option>
          <option v-for="parcel in pending" :key="parcel.parcelId" :value="parcel.parcelId">
            {{ parcel.waybillNo }} · {{ parcel.sourceBatchNo }}
          </option>
        </select>
      </label>
      <label>
        目标片区
        <select v-model="areaId" :disabled="loading || saving || !!attempt" required>
          <option value="" disabled>选择本站片区</option>
          <option v-for="area in areas" :key="area.id" :value="area.id">{{ area.name }}</option>
        </select>
      </label>
      <button v-if="attempt" type="button" :disabled="loading || saving" @click="assign">重试原补分</button>
      <button v-else :disabled="loading || saving || !selected || !areaId">确认补分</button>
      <p v-if="selected" class="assignment-reason">待分区原因 · {{ pendingAreaReasons[selected.pendingAreaReason] }}</p>
    </form>
    <p v-if="confirmation" role="status">{{ confirmation }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.assignment { display: grid; gap: 8px; border-top: 1px solid #d7e3dd; padding-top: 12px; }
.assignment-header, .assignment-form { display: flex; gap: 8px; align-items: end; flex-wrap: wrap; }
.assignment-header { align-items: center; justify-content: space-between; }
.assignment-form label { flex: 1 1 auto; display: grid; gap: 4px; }
.assignment-form select { min-height: 40px; padding-inline: 6px; font: inherit; }
.assignment-reason { flex-basis: 100%; }
</style>
