// 通用创作菜单仍写在数据库里。这些页面属于 yudao 官方 AI 大模型模块，未接入（表结构未导入、
// 无 API key/模型配置）。前端在 generateRoutes 里统一把这些入口从菜单栏/路由/面包屑中隐藏。
// 「药店 AI」（pharmacy/ai/index）是本项目自建的 AI 问药能力，不属于官方模块，始终保留。

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
  { id: 832, name: '导图管理', path: '/ai/mind-map', component: 'ai/mindmap/manager/index.vue' },
  { id: 850, name: 'AI 知识库', path: '/ai/knowledge', component: 'ai/knowledge/knowledge/index' },
  { id: 911, name: 'AI 工作流', path: '/ai/workflow', component: 'ai/workflow/index.vue' }
]

export const KEPT_AI_ENTRIES: AiEntry[] = [
  { id: 792, name: 'AI 对话', path: '/ai/chat', component: 'ai/chat/index/index.vue' },
  { id: 794, name: 'API 密钥', path: '/ai/api-key', component: 'ai/model/apiKey/index.vue' },
  { id: 800, name: '模型配置', path: '/ai/model', component: 'ai/model/model/index.vue' },
  { id: 806, name: '聊天角色', path: '/ai/chat-role', component: 'ai/model/chatRole/index.vue' },
  { id: 811, name: '聊天管理', path: '/ai/chat-conversation', component: 'ai/chat/manager/index.vue' },
  { id: 855, name: '工具管理', path: '/ai/tool', component: 'ai/model/tool/index.vue' },
  { name: '药店 AI', path: '/pharmacy/ai', component: 'pharmacy/ai/index' }
]

// 归一化组件路径：兼容数据库里 component 有无 .vue 后缀（如 ai/mindmap/manager/index 与
// ai/mindmap/manager/index.vue 应视为同一入口）。
function normalizeComponent(component?: string): string {
  return (component || '').replace(/\.vue$/, '')
}

export function isClosedAiComponent(component: string) {
  const target = normalizeComponent(component)
  return CLOSED_AI_ENTRIES.some((entry) => normalizeComponent(entry.component) === target)
}

// 判断某个后端菜单节点是否属于「已停用」的 AI 入口。
// 后端菜单树节点字段是 component（如 ai/image/index/index.vue）、id（菜单 id）。
export function isClosedAiMenu(node: { component?: string; id?: number | string }): boolean {
  if (!node) {
    return false
  }
  if (node.component && isClosedAiComponent(node.component)) {
    return true
  }
  if (node.id != null && CLOSED_AI_ENTRIES.some((entry) => entry.id != null && entry.id === Number(node.id))) {
    return true
  }
  return false
}

// 递归过滤掉已停用的 AI 菜单节点（菜单栏 / 路由 / 面包屑统一不再出现）。
export function filterClosedAiMenus<T extends { component?: string; id?: number | string; children?: T[] }>(
  nodes: T[]
): T[] {
  return nodes
    .filter((node) => !isClosedAiMenu(node))
    .map((node) => ({
      ...node,
      children: node.children?.length ? filterClosedAiMenus(node.children) : node.children
    }))
}
