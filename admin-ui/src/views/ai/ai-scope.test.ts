import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CLOSED_AI_ENTRIES, KEPT_AI_ENTRIES, isClosedAiComponent } from './ai-scope.ts'

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
