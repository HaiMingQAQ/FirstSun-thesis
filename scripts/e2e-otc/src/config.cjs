const fs = require('node:fs')
const path = require('node:path')
const net = require('node:net')
const crypto = require('node:crypto')

const REPO_ROOT = path.resolve(__dirname, '../../..')
const REQUIRED = [
  'E2E_ENVIRONMENT_ID', 'E2E_ISOLATED_CONFIRM', 'E2E_CUSTOMER_BASE_URL',
  'E2E_ADMIN_BASE_URL', 'E2E_API_BASE_URL', 'E2E_TENANT_ID', 'E2E_STORE_ID',
  'E2E_RUN_ID', 'E2E_ARTIFACT_ROOT', 'E2E_FIXTURE_DRUG_ID', 'E2E_FIXTURE_DRUG_NAME',
  'E2E_RECONCILER_MODULE', 'E2E_DB_HOST', 'E2E_DB_PORT', 'E2E_DB_NAME',
  'E2E_DB_USER', 'E2E_DB_PASSWORD', 'E2E_MEMBER_ID', 'E2E_ADMIN_USER_ID'
]
const SECRET_KEYS = new Set([
  'E2E_CUSTOMER_PASSWORD', 'E2E_CUSTOMER_LOCAL_PASSWORD', 'E2E_CUSTOMER_LOCAL_CODE',
  'E2E_ADMIN_PASSWORD', 'E2E_DB_PASSWORD'
])

function parseEnvFile(source) {
  const result = {}
  for (const [index, raw] of source.split(/\r?\n/).entries()) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (!match) throw new Error(`Invalid env syntax at line ${index + 1}`)
    let value = match[2].trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    } else {
      value = value.replace(/\s+#.*$/, '').trim()
    }
    result[match[1]] = value
  }
  return result
}

function required(source, name) {
  const value = source[name]
  if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing required setting: ${name}`)
  return value.trim()
}

function numberSetting(source, name, fallback, min, max) {
  const raw = source[name] || String(fallback)
  if (!/^\d+$/.test(raw)) throw new Error(`Invalid numeric setting: ${name}`)
  const value = Number(raw)
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`Out-of-range setting: ${name}`)
  return value
}

function safeUrl(source, name) {
  let url
  try { url = new URL(required(source, name)) } catch { throw new Error(`Invalid URL setting: ${name}`) }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error(`Unsafe URL setting: ${name}`)
  }
  const loopback = url.hostname === 'localhost' || url.hostname === '[::1]' || (net.isIP(url.hostname) === 4 && url.hostname.startsWith('127.'))
  if (!loopback) throw new Error(`${name} must target an explicit loopback isolated endpoint`)
  const port = Number(url.port || (url.protocol === 'https:' ? 443 : 80))
  const sharedPorts = new Set([48080, 5173, 4190, 3307])
  const recordedIsolatedPorts = new Set([18482, 23347, 26381])
  if (sharedPorts.has(port)) throw new Error(`${name} targets a deny-by-default shared demo port`)
  if (recordedIsolatedPorts.has(port) && source.E2E_ALLOW_ISOLATED_RECORDED_PORTS !== 'YES') {
    throw new Error(`${name} uses a recorded isolated port; set E2E_ALLOW_ISOLATED_RECORDED_PORTS=YES after manual stack review`)
  }
  return url.origin
}

function outsideRepo(target, label) {
  if (!path.isAbsolute(target)) throw new Error(`${label} must be an absolute path`)
  const resolved = path.resolve(target)
  let ancestor = resolved
  while (!fs.existsSync(ancestor)) ancestor = path.dirname(ancestor)
  const realTarget = fs.realpathSync(ancestor)
  const rel = path.relative(REPO_ROOT, realTarget)
  if (!rel.startsWith('..') && !path.isAbsolute(rel)) throw new Error(`${label} must be outside the repository`)
  return resolved
}

function loadConfig(envFileArg) {
  const envFile = envFileArg || process.env.E2E_ENV_FILE
  if (!envFile) throw new Error('Provide --env-file PATH or E2E_ENV_FILE')
  const envPath = outsideRepo(envFile, 'Environment file')
  const stat = fs.statSync(envPath)
  if (!stat.isFile()) throw new Error('Environment path must be a regular file')
  if (process.platform !== 'win32' && (stat.mode & 0o077) !== 0) throw new Error('Environment file permissions must be owner-only (chmod 600)')
  const source = parseEnvFile(fs.readFileSync(envPath, 'utf8'))
  for (const key of REQUIRED) required(source, key)
  if (source.E2E_ISOLATED_CONFIRM !== 'I_CONFIRM_ISOLATED_OTC_WRITES') throw new Error('Explicit isolated write confirmation is missing')
  if (source.E2E_ALLOW_ISOLATED_RECORDED_PORTS && source.E2E_ALLOW_ISOLATED_RECORDED_PORTS !== 'YES') throw new Error('E2E_ALLOW_ISOLATED_RECORDED_PORTS must be YES or unset')
  if (source.E2E_ALLOW_ISOLATED_RECORDED_PORTS === 'YES' && source.E2E_ISOLATED_CONFIRM !== 'I_CONFIRM_ISOLATED_OTC_WRITES') throw new Error('Recorded port opt-in requires isolated write confirmation')
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$/.test(source.E2E_ENVIRONMENT_ID)) throw new Error('Invalid isolated environment identity')
  if (!/^otc-[A-Za-z0-9][A-Za-z0-9._-]{2,79}$/.test(source.E2E_RUN_ID)) throw new Error('E2E_RUN_ID must be a unique otc- prefixed run id')

  const tenantId = numberSetting(source, 'E2E_TENANT_ID', 0, 1, Number.MAX_SAFE_INTEGER)
  const storeId = numberSetting(source, 'E2E_STORE_ID', 0, 1, Number.MAX_SAFE_INTEGER)
  if (tenantId !== 901 || storeId !== 9101) throw new Error('This harness is scoped to tenant 901 and store 9101')
  const customerLoginMode = source.E2E_CUSTOMER_LOGIN_MODE || 'email-password'
  if (!['email-password', 'local-password', 'local-code'].includes(customerLoginMode)) throw new Error('Unsupported E2E_CUSTOMER_LOGIN_MODE')
  if (customerLoginMode === 'email-password') {
    required(source, 'E2E_CUSTOMER_EMAIL'); required(source, 'E2E_CUSTOMER_PASSWORD')
  } else {
    required(source, 'E2E_CUSTOMER_LOCAL_MOBILE')
    if (customerLoginMode === 'local-password') required(source, 'E2E_CUSTOMER_LOCAL_PASSWORD')
    if (customerLoginMode === 'local-code' && !/^\d{6}$/.test(required(source, 'E2E_CUSTOMER_LOCAL_CODE'))) throw new Error('Local test code must contain exactly six digits')
  }
  required(source, 'E2E_ADMIN_USERNAME'); required(source, 'E2E_ADMIN_PASSWORD'); required(source, 'E2E_ADMIN_TENANT_NAME')

  const customerUrl = safeUrl(source, 'E2E_CUSTOMER_BASE_URL')
  const adminUrl = safeUrl(source, 'E2E_ADMIN_BASE_URL')
  const apiUrl = safeUrl(source, 'E2E_API_BASE_URL')
  const dbPort = numberSetting(source, 'E2E_DB_PORT', 0, 1, 65535)
  if (dbPort === 3307) throw new Error('E2E_DB_PORT targets a deny-by-default shared demo port')
  if (new Set([18482, 23347, 26381]).has(dbPort) && source.E2E_ALLOW_ISOLATED_RECORDED_PORTS !== 'YES') throw new Error('Recorded isolated DB port requires E2E_ALLOW_ISOLATED_RECORDED_PORTS=YES and manual review')
  const adapterPath = outsideRepo(required(source, 'E2E_RECONCILER_MODULE'), 'Reconciliation adapter')
  if (!fs.statSync(adapterPath).isFile()) throw new Error('Reconciliation adapter must be a regular file')
  const adapterSha256 = required(source, 'E2E_RECONCILER_SHA256')
  if (!/^[a-f0-9]{64}$/.test(adapterSha256) || crypto.createHash('sha256').update(fs.readFileSync(adapterPath)).digest('hex') !== adapterSha256) throw new Error('Reconciliation adapter differs from its independently reviewed SHA256')
  const reviewPath = outsideRepo(required(source, 'E2E_STACK_REVIEW_FILE'), 'Stack review file')
  let review
  try { review = JSON.parse(fs.readFileSync(reviewPath, 'utf8')) } catch { throw new Error('Stack review file must contain valid JSON') }
  if (!review || review.environmentId !== source.E2E_ENVIRONMENT_ID || !review.reviewId || !review.reviewedBy || review.isolated !== true || !review.mysqlServerUuid || !review.loginFormAsset?.path || !/^[a-f0-9]{64}$/.test(review.loginFormAsset.sha256 || '')) throw new Error('Incomplete independently reviewed stack identity manifest')
  if (review.customerUrl !== customerUrl || review.adminUrl !== adminUrl || review.apiUrl !== apiUrl || review.database !== source.E2E_DB_NAME || Number(review.mysqlHostPort) !== dbPort || review.customerProxyTarget !== apiUrl || review.adminProxyTarget !== apiUrl) throw new Error('Configured endpoints do not match reviewed isolated stack/proxy identity')
  const artifactRoot = outsideRepo(required(source, 'E2E_ARTIFACT_ROOT'), 'Evidence root')
  const fixtureDrugId = numberSetting(source, 'E2E_FIXTURE_DRUG_ID', 0, 1, Number.MAX_SAFE_INTEGER)
  const quantity = numberSetting(source, 'E2E_FIXTURE_QUANTITY', 1, 1, 50)
  const dbHost = required(source, 'E2E_DB_HOST')
  if (!(dbHost === 'localhost' || dbHost === '::1' || (net.isIP(dbHost) === 4 && dbHost.startsWith('127.')))) throw new Error('Database host must be loopback')
  const apiHealthPath = source.E2E_API_HEALTH_PATH || '/actuator/health'
  if (!/^\/[A-Za-z0-9/_-]+$/.test(apiHealthPath)) throw new Error('Invalid E2E_API_HEALTH_PATH')

  return {
    environmentId: source.E2E_ENVIRONMENT_ID,
    allowRecordedPorts: source.E2E_ALLOW_ISOLATED_RECORDED_PORTS === 'YES',
    envPath,
    customerUrl, adminUrl, apiUrl,
    tenantId, storeId, runId: source.E2E_RUN_ID,
    artifactRoot,
    fixtureDrugId, fixtureDrugName: required(source, 'E2E_FIXTURE_DRUG_NAME'), quantity,
    memberId: numberSetting(source, 'E2E_MEMBER_ID', 0, 1, Number.MAX_SAFE_INTEGER),
    adminUserId: numberSetting(source, 'E2E_ADMIN_USER_ID', 0, 1, Number.MAX_SAFE_INTEGER),
    customerLoginMode,
    customerEmail: source.E2E_CUSTOMER_EMAIL,
    customerPassword: source.E2E_CUSTOMER_PASSWORD,
    localMobile: source.E2E_CUSTOMER_LOCAL_MOBILE,
    localPassword: source.E2E_CUSTOMER_LOCAL_PASSWORD,
    localCode: source.E2E_CUSTOMER_LOCAL_CODE,
    adminUsername: source.E2E_ADMIN_USERNAME,
    adminPassword: source.E2E_ADMIN_PASSWORD,
    adminTenantName: source.E2E_ADMIN_TENANT_NAME,
    adapterPath,
    adapterSha256, review,
    db: { host: dbHost, port: dbPort, database: required(source, 'E2E_DB_NAME'), user: required(source, 'E2E_DB_USER'), password: source.E2E_DB_PASSWORD },
    apiHealthPath,
    timeoutMs: numberSetting(source, 'E2E_TIMEOUT_MS', 15000, 1000, 120000),
    pollIntervalMs: numberSetting(source, 'E2E_POLL_INTERVAL_MS', 1000, 100, 10000),
    replayConfirmed: source.E2E_REPLAY_CONFIRM === 'I_APPROVE_SAME_ORDER_MOCK_PAY_AND_VERIFY_REPLAY',
    secretKeys: [...SECRET_KEYS]
  }
}

module.exports = { loadConfig, parseEnvFile, REPO_ROOT, outsideRepo }
