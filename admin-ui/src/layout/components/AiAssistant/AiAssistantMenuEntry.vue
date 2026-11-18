<template>
  <el-tooltip
    content="查库存、找功能、填药品档案"
    placement="top-start"
    :visible="introOpen && !collapse"
    :enterable="false"
  >
    <div class="assistant-entry-wrap">
      <button
        type="button"
        class="ai-assistant-entry"
        :class="{ 'is-active': assistant.opened, 'is-collapsed': collapse }"
        :aria-expanded="assistant.opened"
        aria-controls="ai-assistant-drawer"
        aria-label="AI 助手"
        :title="collapse ? 'AI 助手' : undefined"
        @click="toggle"
      >
        <svg class="assistant-symbol" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6 17.4 3 20l.9-4A8.2 8.2 0 0 1 2.5 11c0-4.5 4.1-8 9.5-8s9.5 3.5 9.5 8-4.1 8-9.5 8a11.2 11.2 0 0 1-6-1.6Z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M12 6.7c.5 2.3 1.4 3.3 3.7 3.8-2.3.5-3.2 1.4-3.7 3.7-.5-2.3-1.5-3.2-3.8-3.7 2.3-.5 3.3-1.5 3.8-3.8Z" fill="currentColor" />
        </svg>
        <span v-if="!collapse" class="assistant-label">AI 助手</span>
        <Icon v-if="!collapse" icon="ep:arrow-right" class="assistant-arrow" aria-hidden="true" />
      </button>
    </div>
  </el-tooltip>
</template>

<script setup lang="ts">
import { useAiAssistantStore } from '@/store/modules/aiAssistant'
import { useUserStore } from '@/store/modules/user'
import { useAppStore } from '@/store/modules/app'
import { getTenantId, getVisitTenantId } from '@/utils/auth'

const props = defineProps<{ collapse: boolean }>()
const assistant = useAiAssistantStore()
const user = useUserStore()
const app = useAppStore()
const introOpen = ref(false)
let hintTimer: ReturnType<typeof setTimeout> | undefined

function dismiss() {
  introOpen.value = false
  clearTimeout(hintTimer)
}

watch(() => [props.collapse, app.getMobile, user.getUser.id] as const, ([collapsed, mobile, id]) => {
  dismiss()
  if (collapsed || mobile || !id) return
  const key = `firstsun-ai-launcher:staff:${getTenantId()}:${getVisitTenantId()}:${id}`
  try {
    const saved = JSON.parse(localStorage.getItem(key) || '{}')
    if (saved.seenLauncherIntro) return
    localStorage.setItem(key, JSON.stringify({ ...saved, seenLauncherIntro: true }))
  } catch { /* The button remains usable without browser storage. */ }
  introOpen.value = true
  hintTimer = setTimeout(dismiss, 7500)
}, { immediate: true })

onUnmounted(dismiss)

function toggle() {
  dismiss()
  assistant.toggle()
}
</script>

<style scoped lang="scss">
.ai-assistant-entry {
  box-sizing: border-box;
  display: flex;
  width: calc(100% - 16px);
  height: 44px;
  padding: 0 12px;
  margin: 6px 8px 8px;
  color: #fff;
  background: #19695b;
  border: 1px solid #8fc7b68c;
  border-radius: 9px;
  box-shadow: 0 2px 8px #082a2340;
  cursor: pointer;
  align-items: center;
  gap: 8px;
  font-weight: 650;

  &:hover { background: #217b6a; }
  &:active,
  &.is-active { background: #105347; }
  &:focus-visible { outline: 2px solid #b7e6d1; outline-offset: 2px; }

  &.is-collapsed {
    justify-content: center;
    padding: 0;
  }
}
.assistant-symbol { width: 21px; height: 21px; flex: none; }
.assistant-label { flex: 1; text-align: left; white-space: nowrap; }
.assistant-arrow { flex: none; opacity: .88; }
</style>
