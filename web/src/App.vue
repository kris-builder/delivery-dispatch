<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, requestJson } from './api'
import StationQueryPanel from './StationQueryPanel.vue'
import fixture from '../../fixtures/mvp.json'

const status = ref('等待检查')
const ready = ref(false)
const checking = ref(false)

async function checkConnection() {
  checking.value = true
  ready.value = false
  status.value = '正在检查'

  try {
    const health = await requestJson<{ status: string }>('/system/health', {
      signal: AbortSignal.timeout(5000),
    })
    ready.value = health.status === 'UP'
    status.value = ready.value ? 'Java 与数据库连接正常' : `后端状态 ${health.status}`
  } catch (error) {
    status.value = error instanceof ApiError
      ? `暂不可用 · ${error.message}${error.requestId ? ` · 请求号 ${error.requestId}` : ''}`
      : '暂不可用，请确认本地 Java 服务已启动'
  } finally {
    checking.value = false
  }
}

onMounted(checkConnection)
</script>

<template>
  <header class="top"><strong>末端配送</strong><span>开发联调</span></header>
  <main>
    <h1>网点调度</h1>
    <StationQueryPanel />
    <section class="panel connection" aria-labelledby="connection-title">
      <div>
        <h2 id="connection-title">服务连接</h2>
        <p role="status" :class="{ ready }">{{ status }}</p>
      </div>
      <button type="button" :disabled="checking" @click="checkConnection">重新检查</button>
    </section>
    <section class="panel" aria-labelledby="sample-title">
      <h2 id="sample-title">开发样例</h2>
      <dl>
        <div><dt>网点</dt><dd>{{ fixture.organization.name }}</dd></div>
        <div><dt>片区</dt><dd>{{ fixture.areas.map(area => area.name).join('、') }}</dd></div>
        <div><dt>演示批次</dt><dd>{{ fixture.inboundBatch.code }}</dd></div>
      </dl>
    </section>
  </main>
</template>

<style>
:root {
  font: 15px/1.5 system-ui, 'Microsoft YaHei', sans-serif;
  color: #192c26;
  background: #f1f5f2;
}

* { box-sizing: border-box; }
body, h1, h2, p, dl, dd { margin: 0; }
h1 { font-size: 26px; }
h2 { font-size: 17px; }

.top {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px max(18px, calc((100% - 760px) / 2));
  color: #f4fcf6;
  background: #17382f;
}

.top span { color: #b9d8cc; font-size: 13px; }

main {
  width: min(calc(100% - 32px), 760px);
  margin: 30px auto;
  display: grid;
  gap: 14px;
}

.panel {
  padding: 16px 18px;
  border: 1px solid #d7e3dd;
  border-radius: 10px;
  background: white;
}

.connection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.connection p { margin-top: 5px; color: #555c52; }
.connection p.ready { color: #0c6650; }

button {
  font: inherit;
  padding: 9px 13px;
  min-height: 44px;
  border: 1px solid #acc9bd;
  border-radius: 7px;
  color: #0c5946;
  background: #edf7f1;
  cursor: pointer;
}

button:disabled { opacity: .6; cursor: default; }
dl { display: grid; gap: 8px; margin-top: 9px; }
dl div { display: flex; gap: 16px; flex-wrap: wrap; }
dt { min-width: 75px; color: #51645b; }
</style>
