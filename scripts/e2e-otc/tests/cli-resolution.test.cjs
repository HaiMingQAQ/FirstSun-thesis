const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

test('setup resolves the Playwright CLI through the exported test package path', () => {
  const cliSource = fs.readFileSync(path.resolve(__dirname, '../src/cli.cjs'), 'utf8')
  assert.match(cliSource, /function setup\(\)\s*\{\s*const cliPath = require\.resolve\('@playwright\/test\/cli'\)/)
  assert.doesNotThrow(() => require.resolve('@playwright/test/cli'))
})
