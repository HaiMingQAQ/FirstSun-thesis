const path = require('node:path')
const { pathToFileURL } = require('node:url')
const crypto = require('node:crypto')
const { chromium } = require('playwright')
const { ensureRunDirectory, machineResult, writeJson, safeError } = require('./runtime.cjs')
const { createSqlAccess } = require('./sql.cjs')

const REQUIRED_ADAPTER_CHECKS = ['isolation', 'databaseReadOnly', 'fixtures', 'permissions', 'mockCapability', 'replayApproval']

async function fetchCheck(url, label, timeoutMs) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url, { signal: controller.signal, redirect: 'manual' })
    if (response.status < 200 || response.status >= 300) throw new Error(`${label} returned HTTP ${response.status}`)
    return { response, body: await response.text() }
  } finally { clearTimeout(timer) }
}

async function checkFrontend(url, label, timeoutMs) {
  const { response, body } = await fetchCheck(url, `${label} entry`, timeoutMs)
  if (!/<html[\s>]/i.test(body) || !/<script\b[^>]+src=/i.test(body)) throw new Error(`${label} did not serve a versioned frontend entry`)
  const scripts = [...body.matchAll(/<script\b[^>]*src=["']([^"']+)["']/gi)].map(match => match[1])
  const scriptUrl = new URL(scripts[0], response.url || url)
  const asset = await fetchCheck(scriptUrl, `${label} static asset`, timeoutMs)
  if (!asset.body.trim()) throw new Error(`${label} static asset was empty`)
  return { status: response.status, entryAsset: scriptUrl.pathname }
}

async function loadAdapter(config) {
  if (crypto.createHash('sha256').update(require('node:fs').readFileSync(config.adapterPath)).digest('hex') !== config.adapterSha256) throw new Error('Reviewed SQL module changed since configuration validation')
  const module = await import(pathToFileURL(config.adapterPath).href)
  for (const method of ['preflight', 'reconcileStage', 'snapshotAll']) {
    if (typeof module[method] !== 'function') throw new Error(`Adapter is missing ${method}()`) 
  }
  if (!module.sqlQueries || typeof module.sqlQueries !== 'object') throw new Error('Adapter is missing its fixed SQL allowlist')
  const requiredStages = ['afterCreate', 'afterMockPay', 'afterStartPicking', 'afterFinishPicking', 'afterVerify']
  if (module.contractVersion !== 1 || requiredStages.some(stage => !module.supportedStages?.includes(stage))) throw new Error('Adapter does not implement the full staged SQL contract')
  const families = new Set(Object.values(module.sqlQueries).map(query => query.family))
  if (['order', 'orderLines', 'cartCleared', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'saleAbsent', 'sale', 'salePayment', 'points', 'notification'].some(family => !families.has(family))) throw new Error('Reviewed SQL catalog is missing required stage ledger families')
  return module
}

function validateChecks(checks, expectedIds) {
  if (!Array.isArray(checks)) throw new Error('Readiness check result must be an array')
  const byId = new Map(checks.map(check => [check?.id, check]))
  return expectedIds.map(id => {
    const check = byId.get(id)
    if (!check || check.pass !== true) throw new Error(`Readiness check failed: ${id}`)
    return { id, pass: true }
  })
}

async function runReadiness(config, options = {}) {
  const checks = []
  let runDir = options.runDir || null
  if (options.createEvidence !== false && !runDir) runDir = ensureRunDirectory(config)
  try {
    if (!config.replayConfirmed) throw new Error('Explicit same-order replay approval is missing')
    const assetUrl = new URL(config.review.loginFormAsset.path, `${config.adminUrl}/`)
    if (assetUrl.origin !== config.adminUrl) throw new Error('Reviewed LoginForm asset must be served by the admin origin')
    const loginAsset = await fetchCheck(assetUrl, 'Reviewed admin LoginForm asset', config.timeoutMs)
    if (crypto.createHash('sha256').update(loginAsset.body).digest('hex') !== config.review.loginFormAsset.sha256) throw new Error('Served admin LoginForm asset differs from independent review')
    if (!/captchaEnable\s*:\s*["']false["']/.test(loginAsset.body) || !/tenantEnable\s*:\s*["']true["']/.test(loginAsset.body)) throw new Error('Reviewed LoginForm asset must contain explicit captchaEnable:false and tenantEnable:true build values')
    checks.push({ id: 'reviewed-admin-login-build', pass: true, sha256: config.review.loginFormAsset.sha256, captchaDisabled: true, tenantEnabled: true })
    const health = await fetchCheck(new URL(config.apiHealthPath, `${config.apiUrl}/`), 'Backend health', config.timeoutMs)
    let parsed
    try { parsed = JSON.parse(health.body) } catch { throw new Error('Backend health response was not JSON') }
    if (parsed.status !== 'UP') throw new Error('Backend health is not UP')
    checks.push({ id: 'backend-health', pass: true, status: health.response.status, state: 'UP' })

    const customer = await checkFrontend(config.customerUrl, 'Customer frontend', config.timeoutMs)
    checks.push({ id: 'customer-static-entry', pass: true, asset: customer.entryAsset })
    const admin = await checkFrontend(new URL('/login', `${config.adminUrl}/`), 'Admin frontend', config.timeoutMs)
    checks.push({ id: 'admin-static-entry', pass: true, asset: admin.entryAsset })

    const tenantResponse = await fetch(new URL(`/admin-api/system/tenant/get-id-by-name?name=${encodeURIComponent(config.adminTenantName)}`, `${config.adminUrl}/`), { redirect: 'manual', signal: AbortSignal.timeout(config.timeoutMs) })
    let tenantPayload
    try { tenantPayload = await tenantResponse.json() } catch { throw new Error('Admin API proxy did not return the tenant lookup envelope') }
    if (!tenantResponse.ok || tenantPayload?.code !== 0 || Number(tenantPayload.data) !== config.tenantId) throw new Error('Admin proxy tenant lookup did not resolve the configured tenant 901')
    checks.push({ id: 'admin-api-proxy-and-tenant', pass: true, tenantId: config.tenantId })

    const customerProbe = await fetch(new URL('/app-api/member/wx-order/mock-payment-available', `${config.customerUrl}/`), { redirect: 'manual', signal: AbortSignal.timeout(config.timeoutMs) })
    let customerPayload
    try { customerPayload = await customerProbe.json() } catch { throw new Error('Customer API proxy did not return an API envelope') }
    if (!customerProbe.ok || !Number.isInteger(customerPayload?.code)) throw new Error('Customer API proxy target could not be verified')
    checks.push({ id: 'customer-api-proxy', pass: true, unauthenticatedCode: customerPayload.code })

    const adapter = await loadAdapter(config)
    const sql = await createSqlAccess(config, adapter)
    try {
      if (sql.identity.serverUuid !== config.review.mysqlServerUuid) throw new Error('Connected database server UUID differs from reviewed isolated stack')
      checks.push({ id: 'database-read-only-connection', pass: true, database: sql.identity.databaseName, account: sql.identity.account, serverPort: Number(sql.identity.serverPort), serverUuid: sql.identity.serverUuid, grants: sql.grants })
      const adapterResult = await adapter.preflight({
        tenantId: config.tenantId, storeId: config.storeId, fixtureDrugId: config.fixtureDrugId,
        fixtureDrugName: config.fixtureDrugName, adminTenantName: config.adminTenantName, memberId: config.memberId, adminUserId: config.adminUserId,
        database: { host: config.db.host, port: config.db.port, database: config.db.database, user: config.db.user, identity: sql.identity, grants: sql.grants },
        environmentId: config.environmentId, runId: config.runId, review: config.review,
        endpoints: { customer: config.customerUrl, admin: config.adminUrl, api: config.apiUrl }, select: sql.select
      })
      const readFamilies = new Set(sql.reads.map(read => read.family))
      if (['tenant', 'store', 'member', 'employee', 'permissions', 'drug', 'stock', 'mock'].some(family => !readFamilies.has(family))) throw new Error('Preflight adapter did not actually query every required fixture/permission/mock SQL family')
      checks.push({ id: 'readonly-adapter-db-probes', pass: true })
      const verifiedAdapterChecks = validateChecks(adapterResult?.checks, REQUIRED_ADAPTER_CHECKS)
      checks.push(...verifiedAdapterChecks.map(check => ({ ...check, source: 'reviewed-adapter' })))
      const fixture = adapterResult.fixture
      if (!fixture || Number(fixture.drugId) !== config.fixtureDrugId || Number(fixture.storeId) !== config.storeId || fixture.name !== config.fixtureDrugName || fixture.rx !== false || fixture.device !== false || Number(fixture.availableQty) < config.quantity) {
        throw new Error('Adapter fixture identity or available quantity does not match configured ordinary OTC fixture')
      }
      if (Number(fixture.memberId) !== config.memberId || Number(fixture.adminUserId) !== config.adminUserId) throw new Error('SQL preflight member/staff identity differs from the configured dedicated accounts')
      checks.push({ id: 'fixture-identity', pass: true, drugId: config.fixtureDrugId, storeId: config.storeId, ordinaryOtc: true, availableQty: Number(fixture.availableQty) })
    } finally { await sql.close() }

    const browser = await chromium.launch({ headless: true })
    try {
      const customerContext = await browser.newContext()
      const adminContext = await browser.newContext()
      try {
        const customerPage = await customerContext.newPage()
        const adminPage = await adminContext.newPage()
        customerPage.setDefaultTimeout(config.timeoutMs)
        adminPage.setDefaultTimeout(config.timeoutMs)
        await customerPage.goto(config.customerUrl, { waitUntil: 'domcontentloaded' })
        if (!(await customerPage.getByRole('button', { name: '登录', exact: true }).count())) throw new Error('Customer login entry was not rendered')
        await adminPage.goto(new URL('/login', `${config.adminUrl}/`).href, { waitUntil: 'networkidle' })
        if (!(await adminPage.locator('input[type="password"]').count()) || !(await adminPage.locator('input').count())) throw new Error('Admin username/password login form was not rendered')
        checks.push({ id: 'customer-login-ui', pass: true })
        checks.push({ id: 'admin-login-ui', pass: true, captchaDisabledInLoadedBuild: true })
      } finally { await customerContext.close(); await adminContext.close() }
    } finally { await browser.close() }

    return { ok: true, runDir, checks }
  } catch (error) {
    return { ok: false, runDir, checks, error: safeError(error) }
  }
}

async function readinessCommand(config) {
  const runDir = ensureRunDirectory(config, `-precheck-${require('./runtime.cjs').runIdSuffix()}`)
  const result = await runReadiness(config, { runDir })
  const machine = machineResult(config, runDir, result.ok ? 'READY' : 'PRECHECK_BLOCKED', result.checks, { error: result.error || null })
  writeJson(path.join(runDir, 'readiness.json'), machine)
  process.stdout.write(`${JSON.stringify(machine)}\n`)
  return result.ok ? 0 : 2
}

module.exports = { runReadiness, readinessCommand, REQUIRED_ADAPTER_CHECKS, loadAdapter }
