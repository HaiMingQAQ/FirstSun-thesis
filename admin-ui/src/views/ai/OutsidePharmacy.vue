<template>
  <ContentWrap>
    <el-result icon="info" :title="`${feature}已停用`">
      <template #sub-title>
        <p>这个页面不属于药店日常业务，不会发起绘画、音乐、写作或思维导图请求。</p>
      </template>
      <template #extra>
        <div class="scope-note">
          <p>下面这些入口的页面已经停用。数据库里的菜单和授权没有改，菜单仍可能显示，需要协调方停用。</p>
          <ul>
            <li v-for="entry in CLOSED_AI_ENTRIES" :key="entry.component">
              {{ entry.name }}<span v-if="entry.id">（菜单 {{ entry.id }}）</span> · {{ entry.component }}
            </li>
          </ul>
          <p>仍然保留：药店 AI、AI 对话、模型配置、API 密钥、聊天角色、聊天管理、知识库和工具管理。</p>
        </div>
      </template>
    </el-result>
  </ContentWrap>
</template>
<script lang="ts" setup>
import { CLOSED_AI_ENTRIES } from './ai-scope'

defineProps<{ feature: string }>()
</script>
<style scoped>
.scope-note { max-width: 720px; text-align: left; line-height: 1.6; }
.scope-note ul { padding-left: 18px; }
</style>
