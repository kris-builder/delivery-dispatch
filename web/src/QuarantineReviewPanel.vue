<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, postJson } from './api'
import { createStationTime } from './stationTime'

const props = defineProps<{
  batchId: string
  quarantineId: string
  missingLabel: boolean
  batches: { id: string; sourceBatchNo: string }[]
}>()
const emit = defineEmits<{ cancel: []; reviewed: []; sessionExpired: [] }>()
const stationTime = createStationTime()
const decision = ref<'RELEASE' | 'RETURN'>('RELEASE')
const targetBatchId = ref('')
const waybillNo = ref('')
const identityConfirmed = ref(false)
const evidence = ref('')
const verifiedAt = ref('')
const street = ref('')
const community = ref('')
const saving = ref(false)
const error = ref('')
const attempt = ref<{ key: string; body: Record<string, string | boolean | undefined> } | null>(null)

async function submit() {
  if (saving.value) return
  if (!attempt.value) {
    const time = verifiedAt.value ? stationTime.fromInput(verifiedAt.value) : undefined
    if (time === null) {
      error.value = '请输入有效的北京时间。'
      return
    }
    const release = decision.value === 'RELEASE'
    attempt.value = {
      key: crypto.randomUUID(),
      body: {
        decision: decision.value, evidence: evidence.value.trim(), verifiedAt: time,
        targetBatchId: release ? targetBatchId.value : undefined,
        identityConfirmed: release ? identityConfirmed.value : undefined,
        waybillNo: release && props.missingLabel ? waybillNo.value.trim() : undefined,
        street: release && !props.missingLabel ? street.value.trim() : undefined,
        community: release && !props.missingLabel ? community.value.trim() : undefined,
      },
    }
  }
  const request = attempt.value
  saving.value = true
  error.value = ''
  try {
    await postJson(`/staff/inbound-batches/${props.batchId}/quarantines/${props.quarantineId}/reviews`,
      request.body, { operationKey: request.key, signal: AbortSignal.timeout(8000) })
    attempt.value = null
    emit('reviewed')
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) emit('sessionExpired')
    if (failure instanceof ApiError && failure.status < 500 && failure.code !== 'CSRF_INVALID') attempt.value = null
    error.value = failure instanceof ApiError ? failure.message : '核对结果待确认，请用原操作重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <form class="quarantine-review" @submit.prevent="submit">
    <fieldset :disabled="!!attempt">
      <legend>上游核对</legend>
      <label>核对结果
        <select v-model="decision">
          <option value="RELEASE">本站应收，放行入站</option>
          <option value="RETURN">错发，已交回上游</option>
        </select>
      </label>
      <template v-if="decision === 'RELEASE'">
        <label>核实后的应到批次
          <select v-model="targetBatchId" required>
            <option value="" disabled>选择批次</option>
            <option v-for="batch in batches" :key="batch.id" :value="batch.id">{{ batch.sourceBatchNo }}</option>
          </select>
        </label>
        <label v-if="missingLabel">真实运单号 <input v-model="waybillNo" maxlength="64" required></label>
        <template v-else>
          <label>上游确认街道（可选） <input v-model="street" maxlength="128"></label>
          <label>上游确认小区（可选） <input v-model="community" maxlength="128"></label>
        </template>
        <label class="identity-confirmation">
          <input v-model="identityConfirmed" type="checkbox" required> 已核对运单与现场实物为同一件
        </label>
      </template>
      <label>上游核实依据 <input v-model="evidence" maxlength="500" required></label>
      <label>核实时间（北京时间，留空使用当前时间）
        <input v-model="verifiedAt" type="datetime-local">
      </label>
    </fieldset>
    <div class="review-actions">
      <button :disabled="saving">{{ attempt ? '重试原核对' : decision === 'RELEASE' ? '确认放行' : '确认已交回' }}</button>
      <button v-if="!attempt" type="button" @click="emit('cancel')">取消</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </form>
</template>

<style scoped>
.quarantine-review, fieldset, label { display: grid; gap: 8px; }
fieldset { border: 1px solid #d7e3dd; min-width: 0; }
select { min-height: 40px; font: inherit; }
.identity-confirmation, .review-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
input[type=checkbox] { width: auto; min-height: 0; }
</style>
