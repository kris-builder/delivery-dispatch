<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, postJson, requestJson } from './api'
import StationQueryPanel from './StationQueryPanel.vue'
import AreaRulePanel from './AreaRulePanel.vue'

interface EmployeeSession {
  type: 'EMPLOYEE'
  employeeId: string
  role: keyof typeof roleNames
  unitId: string
  unitName: string
  timeZone: string
  networkId: string
  workerId: string | null
  mustChangePassword: boolean
}

const roleNames = {
  NETWORK_DISPATCHER: '网点调度',
  NETWORK_OPERATOR: '网点操作员',
  NETWORK_COURIER: '网点配送员',
  NETWORK_TRANSFER_DRIVER: '网点转运司机',
  PICKUP_STATION_SORTER: '驿站分拣员',
  PICKUP_STATION_LEADER: '驿站站长',
  PICKUP_STATION_COURIER: '驿站配送员',
}

const employee = ref<EmployeeSession | null>(null)
const loading = ref(true)
const working = ref(false)
const error = ref('')
const loginName = ref('')
const password = ref('')
const temporaryPassword = ref('')
const newPassword = ref('')

onMounted(async () => {
  try {
    employee.value = await requestJson<EmployeeSession>('/auth/session')
  } catch (reason) {
    if (!(reason instanceof ApiError && reason.status === 401)) displayError(reason)
  } finally {
    loading.value = false
  }
})

function displayError(reason: unknown) {
  error.value = reason instanceof ApiError ? reason.message : '服务连接失败，请重试。'
}

function sessionExpired() {
  employee.value = null
  error.value = '员工会话已失效，请重新登录。'
}

async function login() {
  working.value = true
  error.value = ''
  try {
    employee.value = await postJson<EmployeeSession>('/employee-auth/login', { loginName: loginName.value, password: password.value })
  } catch (reason) {
    displayError(reason)
  } finally {
    password.value = ''
    working.value = false
  }
}

async function changePassword() {
  working.value = true
  error.value = ''
  try {
    employee.value = await postJson<EmployeeSession>('/employee-auth/password-changes', {
      temporaryPassword: temporaryPassword.value,
      newPassword: newPassword.value,
    })
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) {
      try {
        employee.value = await requestJson<EmployeeSession>('/auth/session')
        displayError(reason)
      } catch {
        sessionExpired()
      }
    } else displayError(reason)
  } finally {
    temporaryPassword.value = ''
    newPassword.value = ''
    working.value = false
  }
}

async function logout() {
  working.value = true
  error.value = ''
  try {
    await postJson<void>('/auth/logout')
    employee.value = null
    loginName.value = ''
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) employee.value = null
    else displayError(reason)
  } finally {
    working.value = false
  }
}
</script>

<template>
  <header class="top">
    <strong>末端配送</strong>
    <button v-if="employee" type="button" :disabled="working" @click="logout">退出登录</button>
  </header>
  <main>
    <p v-if="loading" role="status">正在读取会话</p>
    <form v-else-if="!employee" class="panel auth" @submit.prevent="login">
      <h1>员工登录</h1>
      <label>登录名 <input v-model="loginName" required maxlength="64" autocomplete="username"></label>
      <label>密码 <input v-model="password" required type="password" autocomplete="current-password"></label>
      <button :disabled="working">登录</button>
    </form>
    <form v-else-if="employee.mustChangePassword" class="panel auth" @submit.prevent="changePassword">
      <h1>修改临时密码</h1>
      <p>{{ employee.unitName }} · {{ roleNames[employee.role] }}</p>
      <label>临时密码 <input v-model="temporaryPassword" required type="password" autocomplete="current-password"></label>
      <label>新密码 <input v-model="newPassword" required type="password" autocomplete="new-password"></label>
      <button :disabled="working">保存新密码</button>
    </form>
    <section v-else class="panel">
      <h1>{{ roleNames[employee.role] }}</h1>
      <p>{{ employee.unitName }}</p>
    </section>
    <StationQueryPanel
      v-if="employee && !employee.mustChangePassword && ['NETWORK_DISPATCHER', 'NETWORK_OPERATOR'].includes(employee.role)"
      :key="employee.employeeId"
      :dispatcher="employee.role === 'NETWORK_DISPATCHER'"
      :time-zone="employee.timeZone"
      @session-expired="sessionExpired"
    />
    <AreaRulePanel
      v-if="employee?.role === 'NETWORK_DISPATCHER' && !employee.mustChangePassword"
      :key="employee.employeeId"
      @session-expired="sessionExpired"
    />
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </main>
</template>

<style>
:root {
  font: 15px/1.5 system-ui, 'Microsoft YaHei', sans-serif;
  color: #192c26;
  background: #f1f5f2;
}
* { box-sizing: border-box; }
body, h1, h2, p { margin: 0; }
h1 { font-size: 26px; }
h2 { font-size: 17px; }
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px max(16px, calc((100% - 760px) / 2));
  color: #f4fcf6;
  background: #17382f;
  min-height: 66px;
}
main {
  width: min(calc(100% - 32px), 760px);
  margin: 30px auto;
  display: grid;
  gap: 16px;
}
.panel {
  padding: 16px 18px;
  border: 1px solid #d7e3dd;
  border-radius: 7px;
  background: white;
}
.auth { display: grid; gap: 14px; max-width: 420px; width: 100%; justify-self: center; }
.auth label { display: grid; gap: 4px; }
input, button { font: inherit; }
input {
  width: 100%;
  min-height: 40px;
  padding: 6px 9px;
  border: 1px solid #a2b0a9;
  border-radius: 5px;
}
button {
  padding: 6px 13px;
  min-height: 40px;
  border: 1px solid #acc9bd;
  border-radius: 5px;
  color: #0c5946;
  background: #edf7f1;
  cursor: pointer;
}
button:disabled { opacity: 0.6; cursor: default; }
.error { color: #972b2b; }
</style>
