<template>
  <AiLauncher
    v-if="canChat && useFloatingLauncher && !assistant.opened && !isWorkspace"
    :identity="launcherIdentity"
    label="AI 助手"
    hint="查库存、找功能、填药品档案"
    :bottom="88"
    @open="assistant.open"
  />
  <div
    class="ai-assistant-host"
    :class="{ 'is-open': assistant.opened }"
    :style="{ ...hostStyle, zIndex: drawerZIndex }"
    aria-live="polite"
  >
    <div class="ai-assistant-mask" aria-label="关闭 AI 助手" @click="assistant.close"></div>
    <aside
      id="ai-assistant-drawer"
      class="ai-assistant-drawer"
      role="dialog"
      aria-modal="true"
      :inert="!assistant.opened"
      aria-label="AI助手"
    >
      <header class="ai-assistant-header">
        <div class="title"><Icon icon="ep:chat-dot-round" /> AI 助手</div>
        <el-button text circle aria-label="关闭 AI 助手" @click="assistant.close">
          <Icon icon="ep:close" />
        </el-button>
      </header>
      <div class="ai-assistant-workspace">
        <PharmacyAiWorkspace v-if="assistant.initialized" embedded :visible="assistant.opened" :key="launcherIdentity" />
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { useZIndex } from 'element-plus'
import AiLauncher from './AiLauncher.vue'
import { useUserStore } from '@/store/modules/user'
import { getTenantId, getVisitTenantId } from '@/utils/auth'
import { useAiAssistantStore } from '@/store/modules/aiAssistant'
import { useAppStore } from '@/store/modules/app'
import { getLayoutRenderMode } from '@/utils/layout'
import PharmacyAiWorkspace from '@/views/pharmacy/ai/index.vue'

const assistant = useAiAssistantStore()
const { nextZIndex } = useZIndex()
const drawerZIndex = ref(2000)
watch(() => assistant.opened, (open) => {
  if (open) drawerZIndex.value = nextZIndex()
}, { flush: 'sync' })
const app = useAppStore()
const useFloatingLauncher = computed(() => {
  const mode = getLayoutRenderMode(app.getLayout)
  return app.getMobile || mode === 'top'
})
const user = useUserStore()
const route = useRoute()
const canChat = computed(() => user.getPermissions.has('pharmacy:ai:chat') || user.getPermissions.has('*:*:*'))
const isWorkspace = computed(() => route.matched.some(record => /pharmacy[/-]ai(?:\/index)?$/.test(record.path)))
const launcherIdentity = computed(() => `staff:${getTenantId()}:${getVisitTenantId()}:${user.getUser.id}`)
watch(isWorkspace, (active) => { if (active) assistant.close() })

const hostStyle = computed(() => {
  if (app.getMobile || getLayoutRenderMode(app.getLayout) === 'top') return { left: '0' }
  if (getLayoutRenderMode(app.getLayout) === 'cutMenu') {
    return { left: app.getCollapse ? 'var(--tab-menu-min-width)' : 'var(--tab-menu-max-width)' }
  }
  return {
    left: app.getCollapse ? 'var(--left-menu-min-width)' : 'var(--left-menu-max-width)'
  }
})

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && assistant.opened) assistant.close()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.ai-assistant-host {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 2000;
  pointer-events: none;
  transition: left 0.2s ease;
}

.ai-assistant-mask {
  position: absolute;
  background: rgb(0 0 0 / 38%);
  opacity: 0;
  transition: opacity 280ms ease;
  inset: 0;
}

.ai-assistant-drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  width: 66.667vw;
  min-width: min(760px, 100%);
  max-width: 100%;
  overflow: hidden;
  background: var(--el-bg-color);
  transform: translateX(102%);
  box-shadow: -8px 0 28px rgb(0 0 0 / 18%);
  transition: transform 280ms ease;
  flex-direction: column;
}

.is-open {
  pointer-events: auto;
}

.is-open .ai-assistant-mask {
  opacity: 1;
}

.is-open .ai-assistant-drawer {
  transform: translateX(0);
}

.ai-assistant-header {
  display: flex;
  height: 56px;
  padding: 0 16px 0 20px;
  border-bottom: 1px solid var(--el-border-color-light);
  align-items: center;
  justify-content: space-between;
  flex: none;
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
}

.ai-assistant-workspace {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

@media (width <= 768px) {
  .ai-assistant-drawer {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-assistant-host,
  .ai-assistant-mask,
  .ai-assistant-drawer {
    transition: none;
  }
}
</style>
