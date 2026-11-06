const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { REPO_ROOT } = require('./config.cjs')

function safeError(error) {
  let message = String(error?.message || 'Unexpected error')
  // Never archive entered credentials from Playwright action annotations.
  message = message.replace(/^(Fill|Fill password) ".*"(?=\s*$)/gm, '$1 "[REDACTED]"')
  return message.replace(/(password|token|authorization|secret|验证码|提货码)\s*[:=]\s*[^\s,;]+/ig, '$1=[REDACTED]').slice(0, 500)
}

function redact(text, config) {
  let value = String(text)
  const secrets = [config.customerPassword, config.localPassword, config.localCode, config.adminPassword, config.db.password, config.localMobile, config.customerEmail]
  for (const secret of secrets) if (secret) value = value.split(secret).join('[REDACTED]')
  return value.replace(/(authorization\s*[:=]\s*)[^\s,;]+/ig, '$1[REDACTED]')
}

function ensureRunDirectory(config, suffix = '') {
  fs.mkdirSync(config.artifactRoot, { recursive: true, mode: 0o700 })
  const realRoot = fs.realpathSync(config.artifactRoot)
  const rel = path.relative(REPO_ROOT, realRoot)
  if (!rel.startsWith('..') && !path.isAbsolute(rel)) throw new Error('Evidence root resolves inside the repository')
  const runDir = path.join(realRoot, config.runId + suffix)
  fs.mkdirSync(runDir, { mode: 0o700 })
  for (const name of ['playwright', 'test-results', 'screenshots']) fs.mkdirSync(path.join(runDir, name), { mode: 0o700 })
  return runDir
}

function machineResult(config, runDir, result, checks, extra = {}) {
  return {
    result,
    runId: config.runId,
    tenantId: config.tenantId,
    storeId: config.storeId,
    environmentId: config.environmentId,
    endpoints: { customer: config.customerUrl, admin: config.adminUrl, api: config.apiUrl, mysql: `${config.db.host}:${config.db.port}/${config.db.database}` },
    evidencePath: runDir || null,
    repositoryCleanliness: 'evidence-outside-repository',
    checks,
    ...extra
  }
}

function writeJson(file, value) {
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600, flag: 'wx' })
}

function runIdSuffix() { return crypto.randomBytes(6).toString('hex') }

module.exports = { safeError, redact, ensureRunDirectory, machineResult, writeJson, runIdSuffix }
