<script setup>
// Hotpatch (mani fork): edit an application class's source, compile it with javac (the JDK's, or the one the application bundles on a JRE), and redefine
// the class through the BootUI agent, method bodies only. A development tool: a patch is lost when the app restarts.
import {getJson} from '../api.js'
import {computed, ref, watch} from 'vue'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import JavaEditor from './components/JavaEditor.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const {confirm} = useConfirm()
const {message: banner, flash, clear} = useFlashMessage(10000)

const report = ref(null)
const error = ref(null)
const loading = ref(false)
const lastFetched = ref(null)
const query = ref('')
const matches = ref([])
const loaded = ref(null)
const fromPatch = ref(false)
const source = ref('')
const reason = ref('')
const compiled = ref(null)
const busy = ref(null)
let searchTimer = null
// CodeMirror's Mod key: Command on Apple platforms, Control elsewhere.
const modKey = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent) ? '⌘' : 'Ctrl+'

const patches = computed(() => report.value?.patches ?? [])
const canApply = computed(() => !readOnly.value && report.value?.available && compiled.value?.ok && !busy.value)

async function load() {
  if (!manifestAvailable.value) return
  loading.value = true
  error.value = null
  try {
    report.value = await getJson('api/hotpatch', {cache: 'no-store'})
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Hotpatch')
  } finally {
    loading.value = false
  }
}

async function call(path, init, failure) {
  try {
    return await getJson(path, init)
  } catch (e) {
    flash(e?.body?.detail || e?.body?.message || describeLoadError(e, failure), 'danger')
    return null
  }
}

watch(query, (value) => {
  clearTimeout(searchTimer)
  if (value.trim().length < 2) {
    matches.value = []
    return
  }
  searchTimer = setTimeout(async () => {
    matches.value =
      (await call(`api/hotpatch/classes?q=${encodeURIComponent(value.trim())}`, undefined, 'Search failed')) ?? []
  }, 250)
})

async function open(className) {
  busy.value = 'source'
  const result = await call(
    `api/hotpatch/source?class=${encodeURIComponent(className)}`,
    {cache: 'no-store'},
    'Cannot read the source'
  )
  busy.value = null
  if (!result) return
  loaded.value = result
  matches.value = []
  query.value = ''
  show(!!result.patchedSource)
}

function show(patched) {
  fromPatch.value = patched && !!loaded.value?.patchedSource
  source.value = fromPatch.value ? loaded.value.patchedSource : loaded.value.source
  compiled.value = null
}

function edited() {
  compiled.value = null
}

function body(extra = {}) {
  return {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({className: loaded.value.className, source: source.value, ...extra})
  }
}

async function compile() {
  busy.value = 'compile'
  compiled.value = await call('api/hotpatch/compile', body(), 'Compilation failed')
  busy.value = null
}

async function apply() {
  if (!reason.value.trim()) {
    flash('Give a reason before applying the patch.', 'warning')
    return
  }
  if (
    !(await confirm({
      title: 'Apply hotpatch?',
      message: `Redefine ${simpleName(loaded.value.className)} in this running JVM? The next call runs the new method bodies. The patch is lost when the application restarts — commit the real fix too.`,
      confirmLabel: 'Apply',
      danger: true
    }))
  )
    return
  busy.value = 'apply'
  const patch = await call('api/hotpatch/patches', body({reason: reason.value.trim()}), 'The patch was refused')
  busy.value = null
  if (!patch) return
  flash(`Patched ${patch.className} (sha ${patch.sha256.slice(0, 10)})`, 'success')
  reason.value = ''
  await load()
  await open(patch.className)
}

async function revert(className) {
  if (
    !(await confirm({
      title: 'Revert hotpatch?',
      message: `Restore ${simpleName(className)} to the class file it was loaded from?`,
      confirmLabel: 'Revert',
      danger: true
    }))
  )
    return
  busy.value = 'revert'
  const patch = await call(`api/hotpatch/patches/${encodeURIComponent(className)}`, {method: 'DELETE'}, 'Revert failed')
  busy.value = null
  if (!patch) return
  flash(`Reverted ${className}`, 'success')
  await load()
  if (loaded.value?.className === className) await open(className)
}

// Long package names do not wrap inside the confirm dialog: the dialog names the class by its simple name.
function simpleName(className) {
  return className.slice(className.lastIndexOf('.') + 1)
}

function time(millis) {
  return new Date(millis).toLocaleTimeString()
}

watch(manifestAvailable, (available) => (available ? load() : (report.value = null)), {immediate: true})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-bandaid"
      title="Hotpatch"
      subtitle="Edit a method body and redefine the class in this running JVM — development only"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      @refresh="load"
    />
    <FlashBanner :message="banner" with-icon @dismiss="clear" />

    <UnavailableState v-if="!manifestAvailable" :message="manifestUnavailableReason" icon="bi-plug" variant="warning" />
    <PanelSkeleton v-else-if="!report" />

    <template v-else>
      <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Hotpatch actions are read-only.</ReadOnlyNotice>
      <div v-if="!report.available" class="alert alert-warning">{{ report.unavailableReason }}</div>

      <div v-if="patches.length" class="card border-warning-subtle mb-3">
        <div class="card-header">
          <h3 class="h6 mb-0">Patches in effect</h3>
          <span class="text-muted small">The running code differs from the build until the application restarts.</span>
        </div>
        <div class="table-responsive">
          <table class="table table-sm mb-0 align-middle">
            <thead>
              <tr>
                <th>Class</th>
                <th>Applied</th>
                <th>Reason</th>
                <th>SHA-256</th>
                <th class="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="patch in patches" :key="patch.className">
                <td class="font-monospace small">{{ patch.className }}</td>
                <td class="small">{{ time(patch.appliedAt) }}</td>
                <td class="small">{{ patch.reason }}</td>
                <td class="font-monospace small">{{ patch.sha256.slice(0, 12) }}</td>
                <td class="text-end text-nowrap">
                  <button class="btn btn-sm btn-outline-secondary me-1" type="button" @click="open(patch.className)">
                    Open
                  </button>
                  <button
                    class="btn btn-sm btn-outline-danger"
                    type="button"
                    :disabled="readOnly || !!busy"
                    @click="revert(patch.className)"
                  >
                    Revert
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card mb-3">
        <div class="card-body">
          <label class="form-label small text-muted" for="hotpatch-search">
            Application class ({{ report.packages.join(', ') || 'no package known yet' }})
          </label>
          <input
            id="hotpatch-search"
            v-model="query"
            class="form-control font-monospace"
            type="search"
            placeholder="Type at least 2 characters, e.g. PriceService"
            autocomplete="off"
          />
          <div v-if="matches.length" class="list-group mt-2 hotpatch-matches">
            <button
              v-for="match in matches"
              :key="match"
              class="list-group-item list-group-item-action font-monospace small"
              type="button"
              @click="open(match)"
            >
              {{ match }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="loaded" class="card mb-3">
        <div class="card-header d-flex flex-wrap align-items-center gap-2">
          <span class="font-monospace">{{ loaded.className }}</span>
          <span v-if="loaded.decompiled" class="badge text-bg-warning" title="The deployment carries no source">
            decompiled
          </span>
          <span class="text-muted small text-truncate">{{ loaded.path }}</span>
          <span class="ms-auto"></span>
          <div v-if="loaded.patchedSource" class="btn-group btn-group-sm" role="group">
            <button
              class="btn"
              :class="fromPatch ? 'btn-primary' : 'btn-outline-primary'"
              type="button"
              @click="show(true)"
            >
              Patch in effect
            </button>
            <button
              class="btn"
              :class="fromPatch ? 'btn-outline-primary' : 'btn-primary'"
              type="button"
              @click="show(false)"
            >
              Source file
            </button>
          </div>
        </div>
        <div class="card-body">
          <p v-if="loaded.decompiled" class="small text-warning-emphasis mb-2">
            No source file in this deployment: the class was decompiled from its bytecode. Comments are lost; the line
            numbers of stack traces follow the original class, not this text.
          </p>
          <p class="small text-muted mb-2">
            Only method bodies can change: a new method, field, lambda, or signature is refused by the JVM. Compiled
            with {{ report.compiler || "this JVM's compiler" }}<span v-if="report.annotationProcessor"
              >, Lombok {{ report.annotationProcessor.split('/').pop() }}</span
            >.
          </p>
          <JavaEditor
            v-model="source"
            :original="loaded.source"
            :diagnostics="compiled?.diagnostics ?? []"
            :compiled-ok="compiled?.ok === true"
            :read-only="readOnly"
            @update:model-value="edited"
            @compile="compile"
          />
          <p class="hotpatch-hints mt-2 mb-0">
            <span
              ><span class="hotpatch-key">{{ modKey }}S</span> compile</span
            >
            <span
              ><span class="hotpatch-key">{{ modKey }}F</span> find</span
            >
            <span
              ><span class="hotpatch-key">{{ modKey }}Z</span> undo</span
            >
            <span><span class="hotpatch-changed-swatch"></span> changed from the source file</span>
          </p>
          <div v-if="compiled && compiled.diagnostics.length" class="mt-2 small font-monospace hotpatch-diagnostics">
            <div
              v-for="(line, index) in compiled.diagnostics"
              :key="index"
              :class="compiled.ok ? 'text-muted' : 'text-danger'"
            >
              {{ line }}
            </div>
          </div>
          <div class="d-flex flex-wrap align-items-center gap-2 mt-3">
            <button class="btn btn-outline-secondary" type="button" :disabled="!!busy" @click="compile">
              <i class="bi bi-hammer"></i> Compile
            </button>
            <span v-if="compiled?.ok" class="badge text-bg-success">compiled</span>
            <span v-else-if="compiled" class="badge text-bg-danger">compilation failed</span>
            <input
              v-model="reason"
              class="form-control hotpatch-reason"
              type="text"
              placeholder="Reason (required), e.g. null amount breaks the fee"
              aria-label="Reason"
            />
            <button class="btn btn-danger" type="button" :disabled="!canApply" @click="apply">
              <i class="bi bi-bandaid"></i> Apply to this JVM
            </button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hotpatch-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1rem;
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
}
.hotpatch-key {
  font-family: var(--bs-font-monospace);
  padding: 0 0.3rem;
  border: 1px solid var(--bootui-border);
  border-radius: 4px;
}
.hotpatch-changed-swatch {
  display: inline-block;
  width: 3px;
  height: 0.9em;
  vertical-align: -0.1em;
  background: #d4a72c;
}
.hotpatch-matches {
  max-height: 16rem;
  overflow: auto;
}
.hotpatch-diagnostics {
  max-height: 10rem;
  overflow: auto;
}
.hotpatch-reason {
  flex: 1 1 18rem;
  width: auto;
}
</style>
