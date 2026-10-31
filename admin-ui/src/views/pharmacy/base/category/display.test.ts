import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parentCategoryLabel } from './display.ts'

test('顶级分类和缺失名称分别展示', () => {
  assert.equal(parentCategoryLabel(0, '不该使用'), '顶级分类')
  assert.equal(parentCategoryLabel(null), '顶级分类')
  assert.equal(parentCategoryLabel(undefined), '顶级分类')
  assert.equal(parentCategoryLabel(8, '西药'), '西药')
  assert.equal(parentCategoryLabel(8), '上级编号 8')
})
