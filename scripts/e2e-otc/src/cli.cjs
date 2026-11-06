const fs = require('node:fs')
const path = require('node:path')
const { spawnSync, spawn } = require('node:child_process')
const { loadConfig } = require('./config.cjs')
const { ensureRunDirectory, machineResult, writeJson, safeError, redact } = require('./runtime.cjs')
process.umask(0o077)

function parseArgs(args) {
  let envFile
  for (let index = 0; index < args.length; index++) {
    if (args[index] !== '--env-file' || !args[index + 1]) throw new Error('Only --env-file PATH is accepted')
    if (envFile) throw new Error('--env-file may be provided once')
    envFile = args[++index]
  }
  return envFile
}

function summarizeReadiness(config, runDir, ready) {
  const result = machineResult(config, runDir, ready.ok ? 'READY' : 'PRECHECK_BLOCKED', ready.checks, { error: ready.error || null })
  writeJson(path.join(runDir, 'readiness.json'), result)
  return result
}

async function oneclick(config) {
  let runDir
  try { runDir = ensureRunDirectory(config) } catch (error) {
    const result = { result: 'EVIDENCE_ERROR', runId: config.runId, error: safeError(error), evidencePath: config.artifactRoot }
    process.stdout.write(`${JSON.stringify(result)}\n`)
    return 3
  }
  const checks = []
  try {
    const { runReadiness } = require('./readiness.cjs')
    const ready = await runReadiness(config, { runDir })
    const readiness = summarizeReadiness(config, runDir, ready)
    checks.push({ id: 'readiness', pass: ready.ok, checks: readiness.checks.length })
    if (!ready.ok) {
      const result = machineResult(config, runDir, 'PRECHECK_BLOCKED', checks, { error: ready.error, orderId: null, orderNo: null })
      writeJson(path.join(runDir, 'summary.json'), result)
      process.stdout.write(`${JSON.stringify(result)}\n`)
      return 2
    }
    if (!config.replayConfirmed) throw Object.assign(new Error('Explicit same-order replay approval is missing'), { code: 'PRECHECK_BLOCKED' })
    checks.push({ id: 'explicit-same-order-replay-approval', pass: true })

    const logRoot = '/workspace/codex-logs'
    fs.mkdirSync(logRoot, { recursive: true, mode: 0o700 })
    const logPath = path.join(logRoot, `${config.runId}.playwright.log`)
    const reportDir = path.join(runDir, 'playwright')
    const testOutputDir = path.join(runDir, 'test-results')
    fs.mkdirSync(path.join(reportDir, 'html'), { recursive: true, mode: 0o700 })
    const env = {
      ...process.env,
      E2E_ENV_FILE: config.envPath,
      E2E_RUN_DIR: runDir,
      E2E_PLAYWRIGHT_REPORT_DIR: path.join(reportDir, 'html'),
      E2E_PLAYWRIGHT_OUTPUT_DIR: testOutputDir
    }
    const cliPath = require.resolve('@playwright/test/cli')
    const child = spawn(process.execPath, [cliPath, 'test', '--config', path.resolve(__dirname, '../playwright.config.cjs')], {
      cwd: path.resolve(__dirname, '..'), env, stdio: ['ignore', 'pipe', 'pipe']
    })
    // Browser diagnostics can contain entered values or authentication headers.
    // Drain them without copying raw Playwright output into the operation log.
    child.stdout.on('data', () => {})
    child.stderr.on('data', () => {})
    const exitCode = await new Promise((resolve, reject) => {
      child.once('error', reject)
      child.once('close', resolve)
    })
    fs.writeFileSync(logPath, JSON.stringify({ runId: config.runId, playwrightExitCode: exitCode }) + '\n', { mode: 0o600, flag: 'wx' })
    checks.push({ id: 'playwright-flow', pass: exitCode === 0, exitCode, logPath })
    const flowPath = path.join(runDir, 'flow.json')
    if (exitCode !== 0 || !fs.existsSync(flowPath)) {
      const flow = fs.existsSync(flowPath) ? JSON.parse(fs.readFileSync(flowPath, 'utf8')) : null
      const evidenceFailure = flow?.result === 'EVIDENCE_ERROR'
      const result = machineResult(config, runDir, evidenceFailure ? 'EVIDENCE_ERROR' : 'FAIL', checks, {
        orderId: flow?.orderId || null, orderNo: flow?.orderNo || null,
        failedStage: flow?.stages?.at(-1)?.name || 'playwright-flow', error: flow?.error || `Playwright exited ${exitCode}`
      })
      writeJson(path.join(runDir, 'summary.json'), result)
      process.stdout.write(`${JSON.stringify(result)}\n`)
      return evidenceFailure ? 3 : 1
    }
    const flow = JSON.parse(fs.readFileSync(flowPath, 'utf8'))
    const expectedStages = [
      'customer-login-and-safe-preconditions', 'add-to-cart-and-store-pickup-checkout',
      'created-once-and-server-amount-readback', 'mock-paid-and-ledger-reconciled', 'admin-start-picking-and-ledger-reconciled',
      'admin-finish-picking-and-customer-readback', 'admin-verify-and-both-ui-terminal-readback',
      'full-ledger-snapshot-before-replay', 'same-order-http-replay-stable-ledgers-and-refreshed-uis',
      'sql-afterCreate', 'sql-afterMockPay', 'sql-afterStartPicking', 'sql-afterFinishPicking', 'sql-afterVerify'
    ]
    if (flow.result !== 'FLOW_PASS_RECONCILED' || !flow.orderId || !flow.orderNo || expectedStages.some(name => !flow.stages?.some(stage => stage.name === name && stage.pass === true))) {
      throw Object.assign(new Error('Evidence flow is incomplete; success result is forbidden'), { code: 'EVIDENCE_ERROR' })
    }
    const requiredFiles = [
      path.join(reportDir, 'html', 'index.html'), path.join(testOutputDir, 'junit.xml'),
      path.join(testOutputDir, 'playwright-report.json'), path.join(runDir, 'screenshots', 'customer-completed.png'),
      path.join(runDir, 'screenshots', 'admin-completed.png'), logPath, path.join(runDir, 'readiness.json'), flowPath
      , path.join(runDir, 'screenshots', 'customer-created.png'), path.join(runDir, 'screenshots', 'customer-paid.png'),
      path.join(runDir, 'screenshots', 'admin-picking-started.png'), path.join(runDir, 'screenshots', 'admin-picking-finished.png'),
      path.join(runDir, 'screenshots', 'customer-completed-after-replay.png'), path.join(runDir, 'screenshots', 'admin-completed-after-replay.png'),
      path.join(runDir, 'customer-trace.zip'), path.join(runDir, 'admin-trace.zip')
    ]
    const missing = requiredFiles.filter(file => !fs.existsSync(file) || fs.statSync(file).size === 0)
    if (missing.length) throw Object.assign(new Error('Required evidence material is missing'), { code: 'EVIDENCE_ERROR' })
    checks.push({ id: 'complete-external-evidence', pass: true, materialCount: requiredFiles.length })
    const result = machineResult(config, runDir, 'PASS_OTC_UI', checks, {
      orderId: flow.orderId, orderNo: flow.orderNo, payableAmountYuan: flow.payableAmountYuan,
      stages: flow.stages.map(({ name, pass }) => ({ name, pass })), failedStage: null
    })
    writeJson(path.join(runDir, 'summary.json'), result)
    process.stdout.write(`${JSON.stringify(result)}\n`)
    return 0
  } catch (error) {
    const blocked = error.code === 'PRECHECK_BLOCKED'
    const evidenceError = error.code === 'EVIDENCE_ERROR' || ['EACCES', 'EPERM', 'EEXIST', 'ENOSPC', 'EROFS'].includes(error.code)
    const result = machineResult(config, runDir, blocked ? 'PRECHECK_BLOCKED' : evidenceError ? 'EVIDENCE_ERROR' : 'FAIL', checks, { error: safeError(error) })
    try { writeJson(path.join(runDir, 'summary.json'), result) } catch { result.result = 'EVIDENCE_ERROR'; process.stdout.write(`${JSON.stringify(result)}\n`); return 3 }
    process.stdout.write(`${JSON.stringify(result)}\n`)
    return blocked ? 2 : evidenceError ? 3 : 1
  }
}

async function main() {
  const [command, ...rest] = process.argv.slice(2)
  try {
    const envFile = parseArgs(rest)
    if (command === 'lint') return lint()
    if (command === 'setup') return setup()
    const config = loadConfig(envFile)
    if (command === 'readiness') return require('./readiness.cjs').readinessCommand(config)
    if (command === 'oneclick') return oneclick(config)
    throw new Error('Expected command: setup, readiness, oneclick, or lint')
  } catch (error) {
    const result = { result: 'PRECHECK_BLOCKED', error: safeError(error), orderId: null, orderNo: null }
    process.stdout.write(`${JSON.stringify(result)}\n`)
    return 2
  }
}

function lint() {
  const root = path.resolve(__dirname, '..')
  const files = [
    'src/config.cjs', 'src/runtime.cjs', 'src/readiness.cjs', 'src/api.cjs', 'src/pages.cjs', 'src/cli.cjs',
    'tests/otc-oneclick.spec.cjs', 'playwright.config.cjs', 'src/sql.cjs'
  ]
  for (const file of files) {
    const result = spawnSync(process.execPath, ['--check', path.join(root, file)], { encoding: 'utf8' })
    if (result.status !== 0) {
      process.stderr.write(result.stderr)
      return 1
    }
  }
  process.stdout.write(`Static syntax check passed (${files.length} files).\n`)
  return 0
}

function setup() {
  const cliPath = require.resolve('@playwright/test/cli')
  const result = spawnSync(process.execPath, [cliPath, 'install', 'chromium'], { stdio: 'inherit' })
  return result.status ?? 1
}

main().then(code => { process.exitCode = code }).catch(() => { process.exitCode = 1 })
