<script setup>
import { computed, onMounted, ref } from "vue";

const seats = ref([]);
const employees = ref([]);
const snapshotVersion = ref("0");
const selectedEmpId = ref("");
const pending = ref(new Map());
const error = ref("");
const saving = ref(false);

const selectedEmployee = computed(() =>
  employees.value.find((employee) => employee.empId === selectedEmpId.value),
);
const floors = computed(() => {
  const grouped = new Map();
  for (const seat of seats.value) {
    if (!grouped.has(seat.floorNo)) grouped.set(seat.floorNo, []);
    grouped.get(seat.floorNo).push(seat);
  }
  return [...grouped.entries()].sort(([a], [b]) => a - b);
});

function api(path, options) {
  return fetch(`/api${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  }).then(async (response) => {
    const body = await response.json();
    if (!response.ok || body.code !== "OK") {
      if (body.data?.seats) applySeatSnapshot(body.data);
      throw new Error(body.message || body.code);
    }
    return body.data;
  });
}

function applySeatSnapshot(snapshot) {
  snapshotVersion.value = snapshot.snapshotVersion;
  seats.value = snapshot.seats;
  pending.value = new Map();
}

async function load() {
  error.value = "";
  const [seatData, employeeData] = await Promise.all([
    api("/seats"),
    api("/employees"),
  ]);
  applySeatSnapshot(seatData);
  employees.value = employeeData;
}

onMounted(() => {
  load().catch((e) => {
    error.value = e.message;
  });
});

function displaySeat(seat) {
  const pendingEmp = [...pending.value.entries()].find(
    ([, toSeatSeq]) => toSeatSeq === seat.floorSeatSeq,
  )?.[0];
  if (pendingEmp) return pendingEmp;
  if ([...pending.value.keys()].includes(seat.occupiedBy)) return "";
  return seat.occupiedBy || "";
}

function seatClass(seat) {
  if ([...pending.value.values()].includes(seat.floorSeatSeq)) return "pending";
  return displaySeat(seat) ? "occupied" : "empty";
}

function canAssignTo(seat) {
  return selectedEmployee.value && !displaySeat(seat);
}

function assignTo(seat) {
  error.value = "";
  if (!canAssignTo(seat)) return;
  pending.value = new Map(pending.value).set(
    selectedEmployee.value.empId,
    seat.floorSeatSeq,
  );
}

function clearSelected() {
  if (!selectedEmployee.value) return;
  pending.value = new Map(pending.value).set(
    selectedEmployee.value.empId,
    null,
  );
}

async function submit() {
  error.value = "";
  saving.value = true;
  try {
    const changes = [...pending.value.entries()].map(([empId, toSeatSeq]) => ({
      empId,
      toSeatSeq,
    }));
    const updated = await api("/seats/assignments", {
      method: "PUT",
      body: JSON.stringify({ snapshotVersion: snapshotVersion.value, changes }),
    });
    applySeatSnapshot(updated);
    employees.value = await api("/employees");
  } catch (e) {
    error.value = e.message;
  } finally {
    saving.value = false;
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
          <option
            v-for="employee in employees"
            :key="employee.empId"
            :value="employee.empId"
          >
            {{ employee.empId }} {{ employee.name }}
          </option>
        </select>
        <button type="button" @click="clearSelected" :disabled="!selectedEmpId">
          清除座位
        </button>
        <button
          type="button"
          class="primary"
          @click="submit"
          :disabled="pending.size === 0 || saving"
        >
          {{ saving ? "送出中" : "送出" }}
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
            :disabled="!canAssignTo(seat)"
            @click="assignTo(seat)"
          >
            <span>座 {{ seat.seatNo }}</span>
            <strong>{{ displaySeat(seat) || "空位" }}</strong>
          </button>
        </div>
      </div>
    </section>
  </main>
</template>
