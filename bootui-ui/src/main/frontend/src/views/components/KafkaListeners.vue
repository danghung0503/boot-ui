<script setup>
// mani fork: the Kafka panel's listener controls. Pausing a @KafkaListener stops polling while the consumer keeps its
// partitions, so records wait on the topic; resuming continues from the committed offset. This JVM only.
import {apiFetch, getJson} from '../../api.js'
import {onMounted, ref} from 'vue'
import {describeLoadError} from '../../utils/loadError.js'
import {useConfirm} from '../../utils/useConfirm.js'

const props = defineProps({
  readOnly: {type: Boolean, default: false}
})
const emit = defineEmits(['flash'])
const {confirm} = useConfirm()

const report = ref(null)
const busy = ref(null)

async function load() {
  try {
    report.value = await getJson('api/kafka/listeners', {cache: 'no-store'})
  } catch (e) {
    report.value = {
      available: false,
      unavailableReason: describeLoadError(e, 'Unable to load Kafka listeners'),
      listeners: []
    }
  }
}

async function change(listener, action) {
  const pause = action === 'pause'
  if (
    !(await confirm({
      title: pause ? 'Pause listener?' : 'Resume listener?',
      message: pause
        ? `Pause ${listener.id}? It stops polling; records wait on the topic and its lag grows until it is resumed.`
        : `Resume ${listener.id}? It continues from the committed offset.`,
      confirmLabel: pause ? 'Pause' : 'Resume',
      danger: pause
    }))
  )
    return
  busy.value = listener.id
  try {
    const res = await apiFetch(`api/kafka/listeners/${encodeURIComponent(listener.id)}/${action}`, {method: 'POST'})
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    emit('flash', `${pause ? 'Paused' : 'Resumed'} ${listener.id}`, 'success')
  } catch (e) {
    emit('flash', describeLoadError(e, `Could not ${action} ${listener.id}`), 'danger')
  } finally {
    busy.value = null
    await load()
  }
}

function state(listener) {
  if (listener.paused) return {label: 'paused', css: 'text-bg-warning'}
  if (listener.pauseRequested) return {label: 'pausing…', css: 'text-bg-warning'}
  if (listener.running) return {label: 'running', css: 'text-bg-success'}
  return {label: 'stopped', css: 'text-bg-secondary'}
}

defineExpose({load})
onMounted(load)
</script>

<template>
  <div v-if="report && (report.available || report.listeners.length)" class="card mb-3">
    <div class="card-header d-flex align-items-center gap-2">
      <h3 class="h6 mb-0">Listeners</h3>
      <span class="text-muted small">pause stops polling; records wait on the topic</span>
      <button
        class="btn btn-sm btn-outline-secondary ms-auto"
        type="button"
        aria-label="Refresh listeners"
        @click="load"
      >
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>
    <div class="table-responsive">
      <table class="table table-sm mb-0 align-middle">
        <thead>
          <tr>
            <th>Listener</th>
            <th>Topics / group</th>
            <th class="text-end">Threads</th>
            <th class="text-end">Partitions</th>
            <th>State</th>
            <th class="text-end">Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="listener in report.listeners" :key="listener.id">
            <td class="font-monospace small">{{ listener.id }}</td>
            <td class="small">
              <div v-for="topic in listener.topics" :key="topic" class="font-monospace">{{ topic }}</div>
              <div class="text-muted">group {{ listener.groupId || '—' }}</div>
            </td>
            <td class="text-end small">{{ listener.concurrency }}</td>
            <td class="text-end small" :title="listener.assignedPartitions.join(', ')">
              {{ listener.assignedPartitions.length }}
            </td>
            <td>
              <span class="badge" :class="state(listener).css">{{ state(listener).label }}</span>
            </td>
            <td class="text-end text-nowrap">
              <button
                v-if="!listener.pauseRequested"
                class="btn btn-sm btn-outline-warning"
                type="button"
                :disabled="props.readOnly || busy === listener.id"
                @click="change(listener, 'pause')"
              >
                <i class="bi bi-pause-fill"></i> Pause
              </button>
              <button
                v-else
                class="btn btn-sm btn-outline-success"
                type="button"
                :disabled="props.readOnly || busy === listener.id"
                @click="change(listener, 'resume')"
              >
                <i class="bi bi-play-fill"></i> Resume
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
