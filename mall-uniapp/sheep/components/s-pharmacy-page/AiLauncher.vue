<template>
  <div class="assistant-launcher" :style="positionStyle">
    <div v-if="hintOpen" class="assistant-tip" :class="{ 'on-left': !position.right }">
      <span>{{ hint }}</span>
      <component :is="'button'" type="button" aria-label="关闭助手提示" @click="dismiss">×</component>
    </div>
    <component :is="'button'"
      type="button" class="assistant-ball" :aria-label="label" aria-haspopup="dialog"
      :class="{ 'is-compact': compact }" :title="compact ? label : undefined"
      @pointerdown="start" @pointermove="move" @pointerup="end" @pointercancel="cancel"
      @click="activate" @keydown.left.prevent="place(false)" @keydown.right.prevent="place(true)"
    >
      <svg class="assistant-symbol" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M6 17.4 3 20l.9-4A8.2 8.2 0 0 1 2.5 11c0-4.5 4.1-8 9.5-8s9.5 3.5 9.5 8-4.1 8-9.5 8a11.2 11.2 0 0 1-6-1.6Z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M12 6.7c.5 2.3 1.4 3.3 3.7 3.8-2.3.5-3.2 1.4-3.7 3.7-.5-2.3-1.5-3.2-3.8-3.7 2.3-.5 3.3-1.5 3.8-3.8Z" fill="currentColor" />
      </svg>
      <span v-if="!compact">{{ label }}</span>
    </component>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  identity: { type: String, required: true },
  label: { type: String, required: true },
  hint: { type: String, required: true },
  bottom: { type: Number, default: 24 }
})
const emit = defineEmits(['open'])
const position = ref({ right: true, ratio: 1 })
const point = ref(null)
const hintOpen = ref(false)
const compact = ref(false)
let hintTimer
let pointer = null
let suppressClick = false
const storageKey = computed(() => 'firstsun-ai-launcher:' + props.identity)
const read = () => {
  try { return JSON.parse(localStorage.getItem(storageKey.value) || '{}') } catch { return {} }
}
const save = (change) => {
  try { localStorage.setItem(storageKey.value, JSON.stringify({ ...read(), ...change })) } catch {}
}
watch(storageKey, () => {
  const saved = read()
  position.value = { right: saved.right !== false, ratio: Number.isFinite(saved.ratio) ? Math.min(1, Math.max(0, saved.ratio)) : 1 }
  clearTimeout(hintTimer)
  hintOpen.value = !saved.seenLauncherIntro
  if (hintOpen.value) {
    save({ seenLauncherIntro: true })
    hintTimer = setTimeout(dismiss, 7500)
  }
  compact.value = saved.compact === true
  point.value = null
  pointer = null
}, { immediate: true })
onUnmounted(() => clearTimeout(hintTimer))
const positionStyle = computed(() => point.value
  ? { left: point.value.x + 'px', top: point.value.y + 'px' }
  : {
      [position.value.right ? 'right' : 'left']: '16px',
      top: `calc(64px + max(0px, 100dvh - ${64 + 48 + props.bottom}px - env(safe-area-inset-bottom, 0px)) * ${position.value.ratio})`
    })
function dismiss() {
  hintOpen.value = false
  clearTimeout(hintTimer)
}
function place(right, ratio = position.value.ratio) {
  position.value = { right, ratio }
  save(position.value)
}
function start(event) {
  if (pointer || event.button !== 0 || !event.isPrimary) return
  suppressClick = false
  const rect = event.currentTarget.getBoundingClientRect()
  const safeBottom = parseFloat(getComputedStyle(event.currentTarget).scrollMarginBottom) || 0
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, width: rect.width, safeBottom }
  event.currentTarget.setPointerCapture(event.pointerId)
}
function move(event) {
  if (!pointer || pointer.id !== event.pointerId) return
  const dx = event.clientX - pointer.x
  const dy = event.clientY - pointer.y
  if (!point.value && Math.hypot(dx, dy) < 7) return
  point.value = {
    x: Math.max(16, Math.min(innerWidth - 16 - pointer.width, pointer.left + dx)),
    y: Math.max(64, Math.min(innerHeight - props.bottom - pointer.safeBottom - 48, pointer.top + dy))
  }
}
function end(event) {
  if (!pointer || pointer.id !== event.pointerId) return
  if (point.value) {
    const travel = Math.max(1, innerHeight - props.bottom - pointer.safeBottom - 112)
    const right = point.value.x + pointer.width / 2 >= innerWidth / 2
    const ratio = (point.value.y - 64) / travel
    compact.value = !(right && ratio >= 0.9)
    place(right, compact.value ? ratio : 1)
    save({ compact: compact.value })
    suppressClick = true
    dismiss()
  }
  cancel()
}
function cancel() {
  pointer = null
  point.value = null
}
function activate(event) {
  if (suppressClick && event.detail !== 0) { suppressClick = false; return }
  dismiss()
  emit('open')
}
</script>

<style scoped>
.assistant-launcher { position: fixed; z-index: 90; width: max-content; height: 48px; }
.assistant-ball {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 48px;
  margin: 0;
  padding: 0 14px;
  border: 1px solid #a8d8c5;
  border-radius: 999px;
  background: #146658;
  color: #fff;
  box-shadow: 0 4px 12px #123e3538;
  font: 650 14px/1 system-ui, sans-serif;
  white-space: nowrap;
  cursor: pointer;
  scroll-margin-bottom: env(safe-area-inset-bottom, 0px);
  touch-action: none;
  user-select: none;
}
.assistant-symbol { flex: none; width: 21px; height: 21px; }
.assistant-ball::after { border: 0; }
.assistant-ball.is-compact { width: 48px; padding: 0; }
@media (hover: hover) {
  .assistant-ball:hover { background: #1b7564; }
}
.assistant-ball:active { cursor: grabbing; background: #105347; }
.assistant-ball:focus-visible { outline: 2px solid #176b5b; outline-offset: 3px; }
.assistant-tip { position: absolute; bottom: calc(100% + 8px); right: 0; width: max-content; max-width: min(260px, calc(100vw - 32px)); box-sizing: border-box; display: flex; gap: 8px; align-items: center; padding: 8px 10px; background: white; border: 1px solid #c5ddcf; border-radius: 10px; box-shadow: 0 4px 14px #123e3526; color: #203b32; font: 14px/1.5 system-ui,sans-serif; }
.assistant-tip.on-left { right: auto; left: 0; }
.assistant-tip button { flex: none; border: 0; background: transparent; color: #435850; width: 28px; height: 28px; padding: 0; font: 21px/1 system-ui,sans-serif; cursor: pointer; }
</style>
