import assert from 'node:assert/strict'
import { test } from 'node:test'
import { beginSale, finishSale } from './settlement.ts'

test('结算进行中或结果未知时不能再开一单', () => {
  assert.equal(beginSale('idle'), 'submitting')
  assert.equal(beginSale('confirmed'), 'submitting')
  assert.equal(beginSale('submitting'), null)
  assert.equal(beginSale('unknown'), null)
})

test('小票没读到就不能当成入账完成', () => {
  assert.equal(finishSale(true), 'confirmed')
  assert.equal(finishSale(false), 'unknown')
})
