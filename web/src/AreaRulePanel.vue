<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, patchJson, postJson, requestJson } from './api'

interface Page<T> { items: T[]; page: number; pageSize: number; total: number }
interface Area { id: string; name: string }
interface Rule {
  id: string
  street: string
  community: string
  areaId: string
  areaName: string | null
  areaActive: boolean
  active: boolean
}

const emit = defineEmits<{ sessionExpired: [] }>()
const lifetime = new AbortController()
const rules = ref<Page<Rule> | null>(null)
const areas = ref<Area[]>([])
const loadingRules = ref(false)
const loadingAreas = ref(false)
const saving = ref(false)
const rulesError = ref('')
const areasError = ref('')
const saveError = ref('')
const confirmation = ref('')
const editing = ref<Rule | null>(null)
const street = ref('')
const community = ref('')
const areaId = ref('')
const active = ref(true)

function message(reason: unknown) {
  if (lifetime.signal.aborted) return ''
  if (reason instanceof ApiError && reason.status === 401) emit('sessionExpired')
  return reason instanceof ApiError ? reason.message : '请求失败，请重试。'
}

async function loadRules(page: number) {
  rulesError.value = ''
  loadingRules.value = true
  try {
    rules.value = await requestJson<Page<Rule>>(`/staff/area-rules?page=${page}`, { signal: lifetime.signal })
  } catch (reason) {
    rulesError.value = message(reason)
  } finally {
    loadingRules.value = false
  }
}

async function loadAreas() {
  areasError.value = ''
  loadingAreas.value = true
  try {
    const options = []
    let pages = 1
    for (let page = 1; page <= pages; page++) {
      const response = await requestJson<Page<Area>>(`/staff/areas?page=${page}&pageSize=100`, { signal: lifetime.signal })
      if (page === 1) pages = Math.ceil(response.total / response.pageSize)
      options.push(...response.items)
    }
    areas.value = options
  } catch (reason) {
    areasError.value = message(reason)
  } finally {
    loadingAreas.value = false
  }
}

function resetForm() {
  editing.value = null
  street.value = ''
  community.value = ''
  areaId.value = ''
  active.value = true
  saveError.value = ''
}

function beginEdit(rule: Rule) {
  editing.value = rule
  street.value = rule.street
  community.value = rule.community
  areaId.value = rule.areaId
  active.value = rule.active
  saveError.value = ''
  confirmation.value = ''
}

async function saveRule() {
  const rule = editing.value
  const page = rule ? rules.value?.page ?? 1 : Math.floor((rules.value?.total ?? 0) / (rules.value?.pageSize ?? 20)) + 1
  saving.value = true
  saveError.value = ''
  confirmation.value = ''
  try {
    if (rule) {
      await patchJson<Rule>(`/staff/area-rules/${rule.id}`, { areaId: areaId.value, active: active.value })
    } else {
      await postJson<Rule>('/staff/area-rules', {
        street: street.value, community: community.value, areaId: areaId.value,
      })
    }
    resetForm()
    confirmation.value = rule ? '规则已更新。' : '规则已新增。'
    await loadRules(page)
  } catch (reason) {
    saveError.value = message(reason)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadRules(1)
  void loadAreas()
})
onBeforeUnmount(() => lifetime.abort())
</script>

<template>
  <section class="panel area-rules" aria-labelledby="area-rule-title">
    <h2 id="area-rule-title">地址分区规则</h2>
    <ul v-if="rules?.items.length" class="rules">
      <li v-for="rule in rules.items" :key="rule.id">
        <div>
          <strong>{{ rule.street }} · {{ rule.community }}</strong>
          <div>{{ rule.areaName ?? '片区不可用' }} · {{ rule.active ? '规则启用' : '规则停用' }}<span v-if="rule.active && !rule.areaActive"> · 目标片区不可用</span></div>
        </div>
        <button type="button" :disabled="saving" @click="beginEdit(rule)">修改规则</button>
      </li>
    </ul>
    <p v-if="loadingRules" role="status">正在查询地址规则</p>
    <p v-else-if="rules && rules.total === 0">本网点还没有地址规则。</p>
    <p v-if="rulesError" class="error" role="alert">{{ rulesError }}</p>
    <button v-if="rulesError" type="button" :disabled="loadingRules" @click="loadRules(rules?.page ?? 1)">重查规则</button>
    <div v-if="rules && rules.total > rules.pageSize" class="actions">
      <button type="button" :disabled="loadingRules || saving || rules.page <= 1" @click="loadRules(rules.page - 1)">上一页</button>
      <button type="button" :disabled="loadingRules || saving || rules.page * rules.pageSize >= rules.total" @click="loadRules(rules.page + 1)">下一页</button>
    </div>

    <form class="rule-form" @submit.prevent="saveRule">
      <fieldset :disabled="saving">
        <legend>{{ editing ? '修改规则' : '新增规则' }}</legend>
        <label>街道 <input v-model="street" required maxlength="128" :readonly="!!editing"></label>
        <label>小区 <input v-model="community" required maxlength="128" :readonly="!!editing"></label>
        <label>片区
          <select v-model="areaId" required :disabled="loadingAreas">
            <option value="" disabled>选择本网点启用片区</option>
            <option v-if="editing && !areas.some(area => area.id === editing?.areaId)" :value="editing.areaId">
              {{ editing.areaName ?? '原片区' }}（不可用）
            </option>
            <option v-for="area in areas" :key="area.id" :value="area.id">{{ area.name }}</option>
          </select>
        </label>
        <label v-if="editing" class="toggle"><input v-model="active" type="checkbox"> 启用规则</label>
        <p v-if="areasError" class="error" role="alert">{{ areasError }}</p>
        <button v-if="areasError" type="button" :disabled="loadingAreas" @click="loadAreas">重查片区</button>
        <div class="actions">
          <button :disabled="loadingAreas || !areaId">保存规则</button>
          <button v-if="editing" type="button" @click="resetForm">取消修改</button>
        </div>
      </fieldset>
      <p v-if="saveError" class="error" role="alert">{{ saveError }}</p>
      <p v-if="confirmation" role="status">{{ confirmation }}</p>
    </form>
  </section>
</template>

<style scoped>
.area-rules, .rule-form, fieldset { display: grid; gap: 10px; }
.rules { list-style: none; padding: 0; margin: 0; }
.rules li {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between;
  gap: 8px; padding: 8px 0; border-bottom: 1px solid #d7e3dd;
}
fieldset { border: 1px solid #d7e3dd; border-radius: 5px; margin: 0; min-width: 0; }
label { display: grid; gap: 4px; }
select { min-height: 40px; font: inherit; }
.actions, .toggle { display: flex; align-items: center; gap: 8px; }
.toggle input { width: auto; min-height: 0; }
</style>
