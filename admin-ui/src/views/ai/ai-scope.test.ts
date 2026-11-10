import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  CLOSED_AI_ENTRIES,
  KEPT_AI_ENTRIES,
  isClosedAiComponent,
  isClosedAiMenu,
  filterClosedAiMenus
} from './ai-scope.ts'

test('绘画、音乐、写作和思维导图被收口，模型和药店 AI 保留', () => {
  const closed = CLOSED_AI_ENTRIES.map((entry) => entry.component)
  assert.ok(closed.includes('ai/image/index/index.vue'))
  assert.ok(closed.includes('ai/music/index/index.vue'))
  assert.ok(closed.includes('ai/write/index/index.vue'))
  assert.ok(closed.includes('ai/mindmap/index/index.vue'))
  assert.equal(isClosedAiComponent('ai/model/model/index.vue'), false)
  assert.equal(isClosedAiComponent('pharmacy/ai/index'), false)
  assert.ok(KEPT_AI_ENTRIES.some((entry) => entry.name === '模型配置'))
  assert.ok(KEPT_AI_ENTRIES.some((entry) => entry.name === '药店 AI'))
  assert.ok(KEPT_AI_ENTRIES.some((entry) => entry.name === 'AI 知识库'))
})

test('停用菜单通过 component 或 id 都能识别', () => {
  assert.equal(isClosedAiMenu({ component: 'ai/image/index/index.vue' }), true)
  assert.equal(isClosedAiMenu({ id: 816 }), true)
  assert.equal(isClosedAiMenu({ component: 'ai/model/model/index.vue' }), false)
  assert.equal(isClosedAiMenu({ id: 792 }), false)
  assert.equal(isClosedAiMenu(undefined as any), false)
})

test('component 缺省 .vue 后缀也能识别为停用（数据库导图管理实际无 .vue）', () => {
  assert.equal(isClosedAiMenu({ component: 'ai/mindmap/manager/index' }), true)
  assert.equal(isClosedAiComponent('ai/mindmap/manager/index'), true)
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
