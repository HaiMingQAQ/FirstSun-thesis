// 通用创作菜单仍写在数据库里。页面停用后不再调用对应接口；菜单记录留给协调方停用。

export interface AiEntry {
  id?: number
  name: string
  path: string
  component: string
}

export const CLOSED_AI_ENTRIES: AiEntry[] = [
  { id: 816, name: 'AI 绘画', path: '/ai/image', component: 'ai/image/index/index.vue' },
  { id: 817, name: '绘画管理', path: '/ai/image', component: 'ai/image/manager/index.vue' },
  { name: '绘图作品', path: '/ai/image/square', component: 'ai/image/square/index.vue' },
  { id: 829, name: 'AI 音乐', path: '/ai/music', component: 'ai/music/index/index.vue' },
  { id: 821, name: '音乐管理', path: '/ai/music', component: 'ai/music/manager/index.vue' },
  { id: 825, name: 'AI 写作', path: '/ai/write', component: 'ai/write/index/index.vue' },
  { id: 826, name: '写作管理', path: '/ai/write', component: 'ai/write/manager/index.vue' },
  { id: 831, name: 'AI 思维导图', path: '/ai/mind-map', component: 'ai/mindmap/index/index.vue' },
  { id: 832, name: '导图管理', path: '/ai/mind-map', component: 'ai/mindmap/manager/index.vue' }
]

export const KEPT_AI_ENTRIES: AiEntry[] = [
  { id: 792, name: 'AI 对话', path: '/ai/chat', component: 'ai/chat/index/index.vue' },
  { id: 794, name: 'API 密钥', path: '/ai/api-key', component: 'ai/model/apiKey/index.vue' },
  { id: 800, name: '模型配置', path: '/ai/model', component: 'ai/model/model/index.vue' },
  { id: 806, name: '聊天角色', path: '/ai/chat-role', component: 'ai/model/chatRole/index.vue' },
  { id: 811, name: '聊天管理', path: '/ai/chat-conversation', component: 'ai/chat/manager/index.vue' },
  { id: 850, name: 'AI 知识库', path: '/ai/knowledge', component: 'ai/knowledge/knowledge/index' },
  { id: 855, name: '工具管理', path: '/ai/tool', component: 'ai/model/tool/index.vue' },
  { name: '药店 AI', path: '/pharmacy/ai', component: 'pharmacy/ai/index' }
]

export function isClosedAiComponent(component: string) {
  return CLOSED_AI_ENTRIES.some((entry) => entry.component === component)
}
