<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError, postJson, requestJson } from './api'
import { createStationTime } from './stationTime'

interface Page<T> { items: T[]; page: number; pageSize: number; total: number }
interface Courier { id: string; displayName: string }
interface Member {
  shiftId: string
  workerId: string
  displayName: string
  code: string
  startsAt: string
  endsAt: string
  status: 'ON_DUTY' | 'ON_LEAVE'
  version: string
}

const props = defineProps<{ at: string }>()
const emit = defineEmits<{ updated: []; sessionExpired: [] }>()
const stationTime = createStationTime()
const lifetime = new AbortController()
let memberRequest: AbortController | undefined
const members = ref<Page<Member> | null>(null)
const couriers = ref<Page<Courier> | null>(null)
const memberLoading = ref(false)
const courierLoading = ref(false)
const saving = ref(false)
const memberError = ref('')
const courierError = ref('')
const writeError = ref('')
const confirmation = ref('')
const workerId = ref('')
const code = ref('')
const startsAt = ref('')
const endsAt = ref('')

function errorMessage(reason: unknown) {
  if (lifetime.signal.aborted) return ''
  if (reason instanceof ApiError && reason.status === 401) emit('sessionExpired')
  return reason instanceof ApiError ? reason.message : '连接失败，请重试。'
}

async function loadMembers(page: number) {
  memberRequest?.abort()
  const request = new AbortController()
  memberRequest = request
  memberLoading.value = true
  memberError.value = ''
  try {
    const query = new URLSearchParams({ at: props.at, page: String(page) })
    const result = await requestJson<Page<Member>>(`/staff/shifts?${query}`, { signal: request.signal })
    if (!request.signal.aborted) members.value = result
  } catch (reason) {
    if (!request.signal.aborted) memberError.value = errorMessage(reason)
  } finally {
    if (!request.signal.aborted) memberLoading.value = false
  }
}

async function loadCouriers(page: number) {
  courierLoading.value = true
  courierError.value = ''
  try {
    couriers.value = await requestJson<Page<Courier>>(`/staff/shift-couriers?page=${page}`, { signal: lifetime.signal })
    workerId.value = ''
  } catch (reason) {
    courierError.value = errorMessage(reason)
  } finally {
    courierLoading.value = false
  }
}

async function save(action: () => Promise<unknown>, success: string, refreshOnConflict = false) {
  saving.value = true
  writeError.value = ''
  confirmation.value = ''
  try {
    await action()
    if (!lifetime.signal.aborted) {
      confirmation.value = success
      emit('updated')
      await loadMembers(members.value?.page ?? 1)
    }
  } catch (reason) {
    writeError.value = errorMessage(reason)
    if (!lifetime.signal.aborted && refreshOnConflict && reason instanceof ApiError && reason.status === 409) {
      emit('updated')
      await loadMembers(members.value?.page ?? 1)
    }
  } finally {
    saving.value = false
  }
}

async function schedule() {
  const start = stationTime.fromInput(startsAt.value)
  const end = stationTime.fromInput(endsAt.value)
  if (!start || !end) {
    confirmation.value = ''
    writeError.value = '请选择有效的北京时间。'
    return
  }
  if (Date.parse(start) >= Date.parse(end) || !code.value.trim() || !workerId.value) {
    confirmation.value = ''
    writeError.value = '请选择配送员、填写班次名称，并确认开始时间早于结束时间。'
    return
  }
  await save(() => postJson('/staff/shifts', {
    workerId: workerId.value, code: code.value.trim(), startsAt: start, endsAt: end,
  }), '班次已登记。')
}

function changeStatus(member: Member) {
  const leave = member.status === 'ON_DUTY'
  return save(() => postJson(`/staff/shifts/${member.shiftId}/members/${member.workerId}/duty-status`, {
    status: leave ? 'ON_LEAVE' : 'ON_DUTY', expectedVersion: member.version,
  }), leave ? `${member.displayName}已登记整班请假。` : `${member.displayName}已恢复在岗。`, true)
}

watch(() => props.at, () => {
  members.value = null
  void loadMembers(1)
}, { immediate: true })
onMounted(() => void loadCouriers(1))
onBeforeUnmount(() => {
  memberRequest?.abort()
  lifetime.abort()
})
</script>

<template>
  <section class="scheduling" aria-labelledby="scheduling-title">
    <h3 id="scheduling-title">班次与请假</h3>
    <p v-if="memberLoading" role="status">正在查询班次</p>
    <p v-if="memberError" class="error" role="alert">{{ memberError }}</p>
    <button v-if="memberError" type="button" :disabled="memberLoading" @click="loadMembers(members?.page ?? 1)">重新查询班次</button>
    <ul v-if="members?.items.length" class="shift-members">
      <li v-for="member in members.items" :key="`${member.shiftId}/${member.workerId}`">
        <div>
          <strong>{{ member.displayName }}</strong> · {{ member.code }} · {{ member.status === 'ON_LEAVE' ? '请假' : '在岗' }}
          <div>{{ stationTime.formatRange(member.startsAt, member.endsAt) }}</div>
        </div>
        <button
          type="button" :disabled="saving || memberLoading || !!memberError"
          :aria-label="`${member.status === 'ON_DUTY' ? '登记请假' : '恢复在岗'} · ${member.displayName}`"
          @click="changeStatus(member)"
        >{{ member.status === 'ON_DUTY' ? '登记请假' : '恢复在岗' }}</button>
      </li>
    </ul>
    <p v-else-if="members && !memberLoading">所选时点没有已登记的班次成员。</p>
    <div v-if="members && members.total > members.pageSize" class="actions">
      <button type="button" :disabled="memberLoading || saving || members.page <= 1" @click="loadMembers(members.page - 1)">班次上一页</button>
      <button type="button" :disabled="memberLoading || saving || members.page * members.pageSize >= members.total" @click="loadMembers(members.page + 1)">班次下一页</button>
    </div>
    <details>
      <summary>登记班次</summary>
      <form class="schedule-form" @submit.prevent="schedule">
        <label>配送员
          <select v-model="workerId" required :disabled="courierLoading || saving">
            <option disabled value="">请选择</option>
            <option v-for="courier in couriers?.items" :key="courier.id" :value="courier.id">{{ courier.displayName }}</option>
          </select>
        </label>
        <label>班次名称 <input v-model="code" required maxlength="32"></label>
        <label>开始时间（北京时间） <input v-model="startsAt" type="datetime-local" required></label>
        <label>结束时间（北京时间） <input v-model="endsAt" type="datetime-local" required></label>
        <div v-if="couriers && couriers.total > couriers.pageSize" class="actions">
          <button type="button" :disabled="courierLoading || saving || couriers.page <= 1" @click="loadCouriers(couriers.page - 1)">人员上一页</button>
          <button type="button" :disabled="courierLoading || saving || couriers.page * couriers.pageSize >= couriers.total" @click="loadCouriers(couriers.page + 1)">人员下一页</button>
        </div>
        <p v-if="courierError" class="error" role="alert">{{ courierError }}</p>
        <button v-if="courierError" type="button" @click="loadCouriers(couriers?.page ?? 1)">重新查询配送员</button>
        <button :disabled="saving || courierLoading || !workerId">保存班次</button>
      </form>
    </details>
    <p v-if="writeError" class="error" role="alert">{{ writeError }}</p>
    <p v-if="confirmation" role="status">{{ confirmation }}</p>
  </section>
</template>

<style scoped>
.scheduling { display: grid; gap: 8px; padding-top: 12px; border-top: 1px solid #d7e3dd; }
h3 { margin: 0; }
.shift-members { list-style: none; padding: 0; margin: 0; }
.shift-members li {
  display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap;
  gap: 8px; padding: 8px 0; border-bottom: 1px solid #d7e3dd;
}
.schedule-form { display: grid; gap: 8px; max-width: 390px; margin-top: 8px; }
.schedule-form label { display: grid; gap: 4px; }
.schedule-form select { min-height: 40px; font: inherit; }
.actions { display: flex; gap: 8px; }
summary { cursor: pointer; }
</style>
