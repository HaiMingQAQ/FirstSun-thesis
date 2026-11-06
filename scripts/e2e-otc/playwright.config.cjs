const path = require('node:path')
const fs = require('node:fs')
const { loadConfig, outsideRepo } = require('./src/config.cjs')
const config = loadConfig()
const runDir = outsideRepo(process.env.E2E_RUN_DIR || '', 'Playwright run directory')
const relative = path.relative(fs.realpathSync(config.artifactRoot), fs.realpathSync(runDir))
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Playwright requires a dedicated oneclick run directory')
const outputRoot = path.join(runDir, 'test-results')

module.exports = {
  testDir: './tests',
  testMatch: '**/*.spec.cjs',
  timeout: 600_000,
  globalTimeout: 900_000,
  expect: { timeout: 12_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['junit', { outputFile: path.join(outputRoot, 'junit.xml') }],
    ['json', { outputFile: path.join(outputRoot, 'playwright-report.json') }],
    ['html', { outputFolder: path.join(runDir, 'playwright', 'html'), open: 'never' }]
  ],
  outputDir: path.join(outputRoot, 'artifacts'),
  use: {
    browserName: 'chromium',
    headless: true,
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off'
  }
}
