<script setup lang="ts">
import { computed, ref } from 'vue'
import example from '../../fixtures/station-01-query.json'

const scenario = ref<keyof typeof example.workers>('onDuty')
const workers = computed(() => example.workers[scenario.value])
const queryTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: example.timeZone,
  dateStyle: 'medium',
  timeStyle: 'short',
  hourCycle: 'h23',
}).format(new Date(example.at))
</script>

<template>
  <section class="panel station" aria-labelledby="station-title">
    <div>
      <h2 id="station-title">片区与当班人员</h2>
      <p class="query-context">
        {{ example.organizationName }} · <time :datetime="example.at">{{ queryTime }}</time>（北京时间）
      </p>
    </div>

    <fieldset>
      <legend>演示情景</legend>
      <label><input v-model="scenario" type="radio" name="station-scenario" value="onDuty"> 正常在岗</label>
      <label><input v-model="scenario" type="radio" name="station-scenario" value="onLeave"> 东片区负责人请假</label>
    </fieldset>

    <div class="station-results">
      <table>
        <caption>启用片区</caption>
        <thead>
          <tr><th scope="col">片区</th><th scope="col">常规负责人</th></tr>
        </thead>
        <tbody>
          <tr v-for="area in example.areas.items" :key="area.id">
            <th scope="row">{{ area.name }} <span class="area-code">{{ area.code }}</span></th>
            <td>{{ area.regularWorker?.displayName ?? '未配置' }}</td>
          </tr>
        </tbody>
      </table>
      <section class="roster" aria-labelledby="roster-title">
        <h3 id="roster-title">当班配送员</h3>
        <div aria-live="polite">
          <p>{{ workers.total }} 人在岗</p>
          <ul>
            <li v-for="worker in workers.items" :key="worker.id">{{ worker.displayName }}</li>
          </ul>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.station { display: grid; gap: 16px; }
.query-context { color: #51645b; margin-top: 4px; }

fieldset {
  min-width: 0;
  margin: 0;
  padding: 4px 12px 8px;
  border: 1px solid #d7e3dd;
  border-radius: 7px;
}

legend { padding: 0 4px; color: #51645b; }
label { display: inline-flex; align-items: center; min-height: 38px; gap: 5px; margin-right: 14px; }
input { accent-color: #0c6650; }

.station-results { display: grid; grid-template-columns: 1.5fr 1fr; gap: 20px; align-items: start; }
table { width: 100%; border-collapse: collapse; }
caption, h3 { margin: 0 0 5px; text-align: left; font-size: 15px; font-weight: 700; }
th, td { text-align: left; padding: 6px 8px 6px 0; border-bottom: 1px solid #d7e3dd; }
thead th { color: #51645b; font-weight: 500; }
tbody th { font-weight: 500; }
.area-code { color: #51645b; font-size: 12px; }
.roster p { color: #0c6650; }
.roster ul { margin: 5px 0 0; padding-left: 22px; }

@media (max-width: 520px) {
  .station-results { grid-template-columns: 1fr; gap: 14px; }
}
</style>
