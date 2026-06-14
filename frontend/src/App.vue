<script setup>
import { computed, onMounted, ref } from 'vue'

// Vue 3 Composition API：
// ref 類似一個可被畫面追蹤的變數；值要透過 .value 存取。
// 對後端工程師來說，可以把這些 ref 想成畫面 state。
const seats = ref([])
const employees = ref([])
const snapshotVersion = ref('0')
const selectedEmpId = ref('')
// pending 是尚未送到後端的暫存異動：empId -> toSeatSeq。
const pending = ref(new Map())
const error = ref('')
const saving = ref(false)

// computed 是衍生狀態；employees 或 selectedEmpId 改變時會自動重算。
const selectedEmployee = computed(() => employees.value.find((employee) => employee.empId === selectedEmpId.value))
const floors = computed(() => {
  const grouped = new Map()
  for (const seat of seats.value) {
    if (!grouped.has(seat.floorNo)) grouped.set(seat.floorNo, [])
    grouped.get(seat.floorNo).push(seat)
  }
  return [...grouped.entries()].sort(([a], [b]) => a - b)
})

// 統一呼叫後端 API。
// Nginx 會把 /api/* proxy 到 Spring Boot，所以前端不需要知道 app container 的位址。
function api(path, options) {
  return fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  }).then(async (response) => {
    const body = await response.json()
    if (!response.ok || body.code !== 'OK') {
      // 409 時後端可能附上最新座位盤，前端直接套用以避免畫面停在舊狀態。
      if (body.data?.seats) applySeatSnapshot(body.data)
      throw new Error(body.message || body.code)
    }
    return body.data
  })
}

// 套用後端回傳的完整座位快照。成功套用後清空 pending。
function applySeatSnapshot(snapshot) {
  snapshotVersion.value = snapshot.snapshotVersion
  seats.value = snapshot.seats
  pending.value = new Map()
}

async function load() {
  error.value = ''
  // 初始載入時同時取得座位盤和員工下拉選單。
  const [seatData, employeeData] = await Promise.all([api('/seats'), api('/employees')])
  applySeatSnapshot(seatData)
  employees.value = employeeData
}

function displaySeat(seat) {
  // 若某個員工已在 pending 中被指到這個座位，畫面先顯示待送出的員編。
  const pendingEmp = [...pending.value.entries()].find(([, toSeatSeq]) => toSeatSeq === seat.floorSeatSeq)?.[0]
  if (pendingEmp) return pendingEmp
  // 若原本在這個座位的人已被 pending 指到別處，舊位要即時顯示為空。
  if ([...pending.value.keys()].includes(seat.occupiedBy)) return ''
  return seat.occupiedBy || ''
}

function seatClass(seat) {
  // CSS 三態：pending=綠色、occupied=紅色、empty=灰色。
  if ([...pending.value.values()].includes(seat.floorSeatSeq)) return 'pending'
  return displaySeat(seat) ? 'occupied' : 'empty'
}

function assignTo(seat) {
  error.value = ''
  if (!selectedEmployee.value) return
  // Map 不直接 mutate，而是建立新 Map，確保 Vue 能追蹤到狀態變更。
  pending.value = new Map(pending.value).set(selectedEmployee.value.empId, seat.floorSeatSeq)
}

function clearSelected() {
  if (!selectedEmployee.value) return
  pending.value = new Map(pending.value).set(selectedEmployee.value.empId, null)
}

async function submit() {
  error.value = ''
  saving.value = true
  try {
    // 後端 API 需要的 changes 格式：[{ empId, toSeatSeq }]。
    const changes = [...pending.value.entries()].map(([empId, toSeatSeq]) => ({ empId, toSeatSeq }))
    const updated = await api('/seats/assignments', {
      method: 'PUT',
      body: JSON.stringify({ snapshotVersion: snapshotVersion.value, changes }),
    })
    applySeatSnapshot(updated)
    employees.value = await api('/employees')
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="shell">
    <section class="toolbar">
      <h1>員工座位安排系統</h1>
      <div class="controls">
        <select v-model="selectedEmpId">
          <option value="">選擇員工</option>
          <option v-for="employee in employees" :key="employee.empId" :value="employee.empId">
            {{ employee.empId }} {{ employee.name }}
          </option>
        </select>
        <button type="button" @click="clearSelected" :disabled="!selectedEmpId">清除座位</button>
        <button type="button" class="primary" @click="submit" :disabled="pending.size === 0 || saving">
          {{ saving ? '送出中' : '送出' }}
        </button>
      </div>
    </section>

    <p v-if="error" class="error">{{ error }}</p>

    <section class="legend">
      <span><b class="dot empty"></b>空位</span>
      <span><b class="dot occupied"></b>已佔用</span>
      <span><b class="dot pending"></b>待送出</span>
    </section>

    <section class="floors">
      <div v-for="[floorNo, floorSeats] in floors" :key="floorNo" class="floor">
        <h2>{{ floorNo }} 樓</h2>
        <div class="seat-grid">
          <button
            v-for="seat in floorSeats"
            :key="seat.floorSeatSeq"
            type="button"
            class="seat"
            :class="seatClass(seat)"
            @click="assignTo(seat)"
          >
            <span>座 {{ seat.seatNo }}</span>
            <strong>{{ displaySeat(seat) || '空位' }}</strong>
          </button>
        </div>
      </div>
    </section>
  </main>
</template>
