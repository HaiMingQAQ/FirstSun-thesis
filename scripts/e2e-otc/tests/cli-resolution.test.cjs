const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

test('setup resolves the Playwright CLI through the exported test package path', () => {
  const cliSource = fs.readFileSync(path.resolve(__dirname, '../src/cli.cjs'), 'utf8')
  assert.match(cliSource, /function setup\(\)\s*\{\s*const cliPath = require\.resolve\('@playwright\/test\/cli'\)/)
  assert.doesNotThrow(() => require.resolve('@playwright/test/cli'))
})

const { hasExplicitLoginBuildFlags } = require('../src/readiness.cjs')

test('readiness accepts Vite static login flags with any normal JavaScript string quotes', () => {
  for (const quote of ['"', "'", '`']) {
    assert.equal(hasExplicitLoginBuildFlags(`captchaEnable:${quote}false${quote},tenantEnable:${quote}true${quote}`), true)
  }
})

test('readiness still rejects missing or non-literal login build flags', () => {
  assert.equal(hasExplicitLoginBuildFlags('captchaEnable:void 0,tenantEnable:void 0'), false)
  assert.equal(hasExplicitLoginBuildFlags('captchaEnable:`true`,tenantEnable:`true`'), false)
  assert.equal(hasExplicitLoginBuildFlags('captchaEnable:`false`,tenantEnable:`false`'), false)
})
