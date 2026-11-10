import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  CLOSED_AI_ENTRIES,
  KEPT_AI_ENTRIES,
  isClosedAiComponent,
  isClosedAiMenu,
  filterClosedAiMenus
} from './ai-scope.ts'

test('官方 AI 模块入口被收口，仅 API 密钥/模型配置/药店 AI 保留', () => {
  const closed = CLOSED_AI_ENTRIES.map((entry) => entry.component)
  assert.ok(closed.includes('ai/image/index/index.vue'))
  assert.ok(closed.includes('ai/music/index/index.vue'))
  assert.ok(closed.includes('ai/write/index/index.vue'))
  assert.ok(closed.includes('ai/mindmap/index/index.vue'))
  assert.ok(closed.includes('ai/knowledge/knowledge/index'))
  assert.ok(closed.includes('ai/workflow/index.vue'))
  assert.ok(closed.includes('ai/chat/index/index.vue'))
  assert.ok(closed.includes('ai/model/chatRole/index.vue'))
  assert.ok(closed.includes('ai/chat/manager/index.vue'))
  assert.ok(closed.includes('ai/model/tool/index.vue'))
  assert.equal(isClosedAiComponent('ai/model/model/index.vue'), false)
  assert.equal(isClosedAiComponent('ai/model/apiKey/index.vue'), false)
  assert.equal(isClosedAiComponent('pharmacy/ai/index'), false)
  assert.deepEqual(
    KEPT_AI_ENTRIES.map((entry) => entry.name).sort(),
    ['API 密钥', '模型配置', '药店 AI']
  )
})

test('停用菜单通过 component 或 id 都能识别', () => {
  assert.equal(isClosedAiMenu({ component: 'ai/image/index/index.vue' }), true)
  assert.equal(isClosedAiMenu({ id: 816 }), true)
  assert.equal(isClosedAiMenu({ id: 792 }), true)
  assert.equal(isClosedAiMenu({ id: 806 }), true)
  assert.equal(isClosedAiMenu({ id: 811 }), true)
  assert.equal(isClosedAiMenu({ id: 855 }), true)
  assert.equal(isClosedAiMenu({ component: 'ai/model/model/index.vue' }), false)
  assert.equal(isClosedAiMenu(undefined as any), false)
})

test('component 缺省 .vue 后缀也能识别为停用（数据库导图管理实际无 .vue）', () => {
  assert.equal(isClosedAiMenu({ component: 'ai/mindmap/manager/index' }), true)
  assert.equal(isClosedAiComponent('ai/mindmap/manager/index'), true)
  assert.equal(isClosedAiMenu({ component: 'ai/knowledge/knowledge/index' }), true)
  assert.equal(isClosedAiMenu({ id: 911 }), true)
  assert.equal(isClosedAiMenu({ id: 850 }), true)
})

test('filterClosedAiMenus 递归摘除停用菜单及其子节点', () => {
  const tree = [
    { name: 'AI 绘画', component: 'ai/image/index/index.vue', children: [] },
    { name: '模型配置', component: 'ai/model/model/index.vue', children: [] },
    {
      name: '控制台',
      component: 'Layout',
      children: [
        { name: 'AI 音乐', component: 'ai/music/index/index.vue' },
        { name: 'API 密钥', component: 'ai/model/apiKey/index.vue' }
      ]
    }
  ]
  const result = filterClosedAiMenus(tree)
  assert.equal(result.length, 2)
  assert.deepEqual(
    result.map((n: any) => n.name),
    ['模型配置', '控制台']
  )
  const consoleChildren = (result[1] as any).children
  assert.deepEqual(
    consoleChildren.map((n: any) => n.name),
    ['API 密钥']
  )
})
