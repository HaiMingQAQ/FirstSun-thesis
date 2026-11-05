'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { spawnSync } = require('node:child_process');
const { mysqlDiagnostics } = require('./diagnostics.cjs');

const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const REPORT_DIR = path.join(PROJECT_ROOT, '.local', 'otc-acceptance');
const LEGACY_RUN_ID = 'e2e12-20261030-42eba49acb3f41c485aafcdf6e19a2b7';
const EXIT = Object.freeze({ OK: 0, CONFIG: 2, UNAVAILABLE: 3, PREFLIGHT: 4, REPLAY: 5, ASSERTION: 6, UNKNOWN: 7 });

function invalid(code, stage) {
  const error = new Error(stage);
  error.exitCode = code;
  error.stage = stage;
  return error;
}

function atomicJson(file, value) {
  const temporary = file + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(temporary, file);
}

function xml(value) {
  return String(value).replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
  })[character]);
}

function junit(summary) {
  const reconcilePass = summary.status === 'RECONCILE_PASS' &&
    summary.finalAuditPassed === true && summary.completionRepeatPassed === true &&
    summary.businessExecuted === true;
  const auditOnly = summary.status === 'AUDIT_ONLY_PASS' &&
    summary.finalAuditPassed === true && summary.completionRepeatPassed === false &&
    summary.businessExecuted === false;
  const pass = summary.status === 'BUSINESS_PASS' || reconcilePass;
  const preflight = summary.status === 'PREFLIGHT_READY' || auditOnly;
  const failure = !pass && !preflight;
  const detail = xml(summary.status + ' at ' +
    (summary.failedStage || summary.lastKnownStage || summary.stage));
  return '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<testsuite name="otc-acceptance" tests="1" failures="' + (failure ? 1 : 0) +
    '" skipped="' + (preflight ? 1 : 0) + '">\n' +
    '  <testcase name="isolated OTC business flow">' +
    (preflight ? '<skipped message="' +
      (summary.status === 'AUDIT_ONLY_PASS'
        ? 'Audit only; repeat verification not executed'
        : 'Preflight only; business flow not executed') + '"/>' :
      failure ? '<failure message="' + detail + '">' + detail + '</failure>' : '') +
    '</testcase>\n</testsuite>\n';
}

function reporter(root, mode) {
  fs.mkdirSync(root, { recursive: true });
  let summary = {
    mode, status: 'UNKNOWN', stage: 'startup', startedAt: new Date().toISOString(),
    businessExecuted: false
  };
  const save = () => {
    atomicJson(path.join(root, 'summary.json'), summary);
    fs.writeFileSync(path.join(root, 'junit.xml'), junit(summary), { mode: 0o600 });
  };
  save();
  const event = (stage, result, extra = {}) => {
    fs.appendFileSync(path.join(root, 'events.jsonl'),
      JSON.stringify({ at: new Date().toISOString(), stage, result, ...extra }) + '\n',
      { mode: 0o600 });
    summary = { ...summary, stage };
    save();
  };
  event('startup', 'UNKNOWN');
  return {
    event,
    finish(status, stage, extra = {}) {
      summary = { ...summary, ...extra, status, stage, finishedAt: new Date().toISOString() };
      event(stage, status);
      return summary;
    },
    get summary() { return summary; }
  };
}

function parseArgs(argv) {
  if (argv.includes('--help') || argv.includes('-h')) return { mode: 'help' };
  const mode = argv.includes('--execute') ? 'execute' : 'preflight';
  const accepted = new Set(['--preflight', '--execute', '--config', '--run-id', '--permit']);
  const values = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!accepted.has(arg)) throw invalid(EXIT.CONFIG, 'arguments.invalid');
    if (['--config', '--run-id', '--permit'].includes(arg)) {
      if (!argv[i + 1] || argv[i + 1].startsWith('--')) throw invalid(EXIT.CONFIG, 'arguments.missing-value');
      values[arg.slice(2)] = argv[++i];
    }
  }
  if (argv.includes('--execute') && argv.includes('--preflight')) throw invalid(EXIT.CONFIG, 'arguments.conflicting-mode');
  return { mode, ...values };
}

function localUrl(raw, expectedPath) {
  let url;
  try { url = new URL(raw); } catch { throw invalid(EXIT.CONFIG, 'config.api-url'); }
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.pathname !== expectedPath || url.search || url.hash || url.username || url.password) {
    throw invalid(EXIT.CONFIG, 'config.api-url');
  }
  return url;
}

function positiveInt(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function loadConfig(root, args) {
  const file = path.resolve(args.config || path.join(root, 'config.json'));
  const relative = path.relative(root, file);
  if (relative.startsWith('..') || path.isAbsolute(relative)) throw invalid(EXIT.CONFIG, 'config.outside-private-dir');
  let content;
  try {
    if (fs.lstatSync(file).isSymbolicLink()) throw new Error('symlink');
    content = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    throw invalid(EXIT.CONFIG, 'config.missing-or-invalid');
  }
  const cfg = { ...content, runId: args['run-id'] || content.runId };
  const api = localUrl(cfg.appBaseUrl, '/app-api');
  const admin = localUrl(cfg.adminBaseUrl, '/admin-api');
  if (api.origin !== admin.origin || cfg.environment !== 'isolated-local-test' ||
      cfg.dailyDatabase !== false || cfg.mockPaymentOnly !== true ||
      cfg.externalProvidersDisabled !== true) throw invalid(EXIT.CONFIG, 'config.isolation');
  const db = cfg.mysql || {};
  const fixture = cfg.fixture || {};
  // These are the checked-in daily Compose defaults, which are never OTC acceptance targets.
  if (api.port === '28080' || db.port === 23327 || db.schema === 'firstsun_pharmacy') {
    throw invalid(EXIT.CONFIG, 'config.daily-endpoint');
  }
  if (db.host !== '127.0.0.1' || !positiveInt(db.port) || db.port > 65535 ||
      !/^[A-Za-z0-9_]{1,64}$/.test(db.schema || '') ||
      !/^[0-9a-f-]{36}$/i.test(db.expectedUuid || '') ||
      !path.isAbsolute(db.cli || '') || !path.isAbsolute(db.defaultsExtraFile || '') ||
      !fs.existsSync(db.cli) || !fs.existsSync(db.defaultsExtraFile)) {
    throw invalid(EXIT.CONFIG, 'config.mysql');
  }
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(cfg.runId || '') ||
      !positiveInt(fixture.tenantId) || !positiveInt(fixture.storeId) ||
      !positiveInt(fixture.drugId) || !/^\d{11}$/.test(fixture.memberMobile || '')) {
    throw invalid(EXIT.CONFIG, 'config.fixture');
  }
  return cfg;
}

function executeGate(cfg, args) {
  const fixture = cfg.fixture;
  const permit = cfg.executionPermit || {};
  if (cfg.runId === LEGACY_RUN_ID) throw invalid(EXIT.REPLAY, 'run.legacy-id-reserved');
  if (!args.permit || args.permit !== permit.id || permit.confirmed !== true ||
      permit.maxNewOrders !== 1 || permit.runId !== cfg.runId ||
      !/^[A-Za-z0-9_-]{8,64}$/.test(permit.id || '')) {
    throw invalid(EXIT.CONFIG, 'execute.permit');
  }
  if (!/^\d{1,32}$/.test(fixture.memberDevSmsCode || '') ||
      !/^[A-Za-z0-9]{4,30}$/.test(fixture.adminUsername || '') ||
      typeof fixture.adminPassword !== 'string' || fixture.adminPassword.length < 4 ||
      fixture.adminPassword.length > 16) throw invalid(EXIT.CONFIG, 'execute.credentials');
}

function reserve(root, cfg) {
  const lockPath = path.join(root, 'execution.lock');
  const attemptPath = path.join(root, 'attempt-' + cfg.runId + '.json');
  const ledgerPath = path.join(root, 'permits.json');
  let lock;
  try { lock = fs.openSync(lockPath, 'wx', 0o600); }
  catch { throw invalid(EXIT.REPLAY, 'execute.concurrent-or-unreconciled'); }
  try {
    fs.writeFileSync(lock, JSON.stringify({
      runId: cfg.runId, permitId: cfg.executionPermit.id,
      status: 'RESERVED', at: new Date().toISOString()
    }) + '\n');
    fs.closeSync(lock);
    lock = null;
    if (fs.existsSync(path.join(root, cfg.runId + '.summary.json')) ||
        fs.existsSync(path.join(root, cfg.runId + '.order-create.started'))) {
      throw invalid(EXIT.REPLAY, 'execute.prior-worker-evidence');
    }
    let ledger = { version: 1, reservedHistoricalRunId: LEGACY_RUN_ID, permits: {} };
    if (fs.existsSync(ledgerPath)) {
      try { ledger = JSON.parse(fs.readFileSync(ledgerPath, 'utf8')); }
      catch { throw invalid(EXIT.REPLAY, 'execute.ledger-invalid'); }
      if (ledger.version !== 1 || ledger.reservedHistoricalRunId !== LEGACY_RUN_ID ||
          !ledger.permits || typeof ledger.permits !== 'object') {
        throw invalid(EXIT.REPLAY, 'execute.ledger-invalid');
      }
    }
    if (Object.hasOwn(ledger.permits, cfg.executionPermit.id)) {
      throw invalid(EXIT.REPLAY, 'execute.permit-already-used');
    }
    let attempt;
    try { attempt = fs.openSync(attemptPath, 'wx', 0o600); }
    catch { throw invalid(EXIT.REPLAY, 'execute.run-id-already-attempted'); }
    fs.writeFileSync(attempt, JSON.stringify({
      runId: cfg.runId, permitId: cfg.executionPermit.id,
      status: 'UNKNOWN', at: new Date().toISOString()
    }) + '\n');
    fs.closeSync(attempt);
    ledger.permits[cfg.executionPermit.id] = { runId: cfg.runId, status: 'UNKNOWN' };
    atomicJson(ledgerPath, ledger);
    return { lockPath, attemptPath, ledgerPath };
  } catch (error) {
    if (lock !== null) fs.closeSync(lock);
    fs.unlinkSync(lockPath);
    throw error;
  }
}

function mysqlSelect(cfg, query, report) {
  const db = cfg.mysql;
  const args = [
    '--defaults-extra-file=' + db.defaultsExtraFile,
    '--protocol=tcp', '-h', db.host, '-P', String(db.port),
    '--database=' + db.schema, '--batch', '--raw', '--skip-column-names',
    '--default-character-set=utf8mb4'
  ];
  const result = spawnSync(db.cli, args, {
    input: query, encoding: 'utf8', timeout: 15000,
    windowsHide: true, shell: false, maxBuffer: 1024 * 1024
  });
  if (result.error || result.status !== 0) {
    const details = mysqlDiagnostics(result);
    report.event('preflight.mysql-client', 'FAILED', details);
    const schemaError = [1049, 1054, 1146].includes(details.mysqlErrorCode);
    throw invalid(schemaError ? EXIT.PREFLIGHT : EXIT.UNAVAILABLE,
      schemaError ? 'preflight.mysql-schema' : 'preflight.mysql-unavailable');
  }
  return result.stdout.replace(/\r/g, '').trim().split('\n');
}

async function preflight(cfg, report, fetchImpl = fetch, selectImpl = mysqlSelect) {
  report.event('preflight.health', 'STARTED');
  let response;
  try {
    response = await fetchImpl(new URL('/actuator/health', cfg.appBaseUrl), {
      method: 'GET', redirect: 'error', signal: AbortSignal.timeout(5000)
    });
    if (!response.ok || (await response.json()).status !== 'UP') throw new Error('health');
  } catch {
    throw invalid(EXIT.UNAVAILABLE, 'preflight.backend-unavailable');
  }
  report.event('preflight.health', 'READY');

  report.event('preflight.mysql', 'STARTED');
  const { tenantId, storeId, drugId, memberMobile } = cfg.fixture;
  const lines = selectImpl(cfg,
    'SELECT DATABASE(),@@server_uuid;\n' +
    'SELECT COUNT(*) FROM ph_wx_order WHERE tenant_id=' + tenantId + " AND remark='" + cfg.runId + "';\n" +
    'SELECT COUNT(*) FROM member_user WHERE tenant_id=' + tenantId + " AND mobile='" + memberMobile + "' AND deleted=0;\n" +
    'SELECT COUNT(*) FROM ph_drug WHERE tenant_id=' + tenantId + ' AND id=' + drugId +
    ' AND deleted=0 AND status=1 AND approve_status=1 AND saleable_online=1 AND is_rx=0 AND drug_type IN (1,2);\n' +
    'SELECT COALESCE(SUM(s.qty-s.qty_frozen),0) FROM ph_inv_batch b ' +
    'JOIN ph_inv_location_stock s ON s.batch_id=b.id AND s.tenant_id=b.tenant_id ' +
    'WHERE b.tenant_id=' + tenantId + ' AND b.store_id=' + storeId +
    ' AND b.drug_id=' + drugId + ' AND b.deleted=0 AND s.deleted=0 ' +
    'AND b.quality_status=0 AND b.expiry_date>=CURRENT_DATE;\n', report);
  const identity = (lines[0] || '').split('\t');
  if (identity[0] !== cfg.mysql.schema || identity[1] !== cfg.mysql.expectedUuid ||
      lines.length !== 5 || lines[1] !== '0' || lines[2] !== '1' ||
      lines[3] !== '1' || !Number.isFinite(Number(lines[4])) || Number(lines[4]) < 1) {
    throw invalid(EXIT.PREFLIGHT, 'preflight.fixture-or-identity');
  }
  report.event('preflight.mysql', 'READY');
}

function workerEnv(cfg, root) {
  const f = cfg.fixture;
  const db = cfg.mysql;
  return {
    ...process.env, OTC_RUNNER_LOCKED: '1', OTC_PERMIT_ID: cfg.executionPermit.id,
    E2E_RUN_ID: cfg.runId, E2E_PRIVATE_DIR: root,
    E2E_APP_BASE_URL: cfg.appBaseUrl, E2E_ADMIN_BASE_URL: cfg.adminBaseUrl,
    E2E_MYSQL_HOST: db.host, E2E_MYSQL_PORT: String(db.port),
    E2E_MYSQL_SCHEMA: db.schema, E2E_MYSQL_EXPECTED_UUID: db.expectedUuid,
    E2E_MYSQL_CLI: db.cli, E2E_MYSQL_DEFAULTS_EXTRA_FILE: db.defaultsExtraFile,
    E2E_TENANT_ID: String(f.tenantId), E2E_STORE_ID: String(f.storeId),
    E2E_DRUG_ID: String(f.drugId), E2E_MEMBER_MOBILE: f.memberMobile,
    E2E_MEMBER_DEV_SMS_CODE: f.memberDevSmsCode,
    E2E_ADMIN_USERNAME: f.adminUsername, E2E_ADMIN_PASSWORD: f.adminPassword
  };
}

function workerResult(root, cfg, child) {
  let audit;
  try { audit = JSON.parse(fs.readFileSync(path.join(root, cfg.runId + '.summary.json'), 'utf8')); }
  catch { audit = null; }
  const safeStage = value => typeof value === 'string' &&
    /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(value) ? value : null;
  const lastKnownStage = safeStage(audit?.failure?.step) || safeStage(audit?.currentStep);
  if (child.error || child.signal || child.status === null || !audit) {
    return { status: 'UNKNOWN', code: EXIT.UNKNOWN, lastKnownStage };
  }
  if (child.status === 0 && audit.result === 'PASS_SCOPED_LOCAL_HTTP_AND_MYSQL' &&
      audit.finalAssertions?.completedPaidPickup === true &&
      audit.finalAssertions?.oneMockPayOrder === true &&
      audit.finalAssertions?.oneSaleHeaderLineAndPayment === true &&
      audit.phases?.paymentRepeat === 'one-call-state-stable' &&
      audit.phases?.completionRepeat === 'one-call-state-stable') {
    return {
      status: 'BUSINESS_PASS', code: EXIT.OK,
      orderId: audit.orderId, inventoryOutQty: audit.finalAssertions.inventoryOutQty,
      pointDelta: audit.finalAssertions.pointRecordsNet
    };
  }
  if (child.status !== 0 && audit.failure &&
      !/unknown|transport|read-only SQL failed/i.test(audit.failure.message || '')) {
    return { status: 'ASSERTION_FAILED', code: EXIT.ASSERTION, failedStage: lastKnownStage };
  }
  return { status: 'UNKNOWN', code: EXIT.UNKNOWN, lastKnownStage };
}

async function run(argv = process.argv.slice(2), options = {}) {
  let args;
  try { args = parseArgs(argv); }
  catch (error) { args = { mode: 'preflight', parseError: error }; }
  if (args.mode === 'help') {
    process.stdout.write('Usage: node scripts/otc-acceptance/runner.cjs [--preflight] [--config .local/otc-acceptance/config.json] [--run-id ID]\n' +
      '       node scripts/otc-acceptance/runner.cjs --execute --permit PERMIT_ID [--config ...] [--run-id ID]\n' +
      'Exit: 0 ready/pass, 2 config, 3 unavailable, 4 preflight, 5 replay/lock, 6 assertion, 7 unknown.\n');
    return EXIT.OK;
  }
  const root = options.root || REPORT_DIR;
  const invocationDir = path.join(root, 'invocations', randomUUID());
  const report = reporter(invocationDir, args.mode);
  const done = code => {
    if (!options.quiet) process.stdout.write(JSON.stringify({
      status: report.summary.status, reportDir: invocationDir, exitCode: code
    }) + '\n');
    return code;
  };
  let reserved;
  let workerStarted = false;
  try {
    if (args.parseError) throw args.parseError;
    report.event('configuration', 'STARTED');
    const cfg = loadConfig(root, args);
    report.event('configuration', 'READY');
    if (args.mode === 'execute') executeGate(cfg, args);
    await preflight(cfg, report, options.fetchImpl, options.mysqlSelectImpl);
    if (args.mode === 'preflight') {
      report.finish('PREFLIGHT_READY', 'preflight.complete', { runId: cfg.runId });
      return done(EXIT.OK);
    }
    reserved = reserve(root, cfg);
    report.event('execute.reserved', 'UNKNOWN', { runId: cfg.runId });
    report.event('execute.worker', 'UNKNOWN');
    workerStarted = true;
    const child = (options.spawnWorker || spawnSync)(process.execPath,
      [path.join(__dirname, 'order-flow.cjs'), '--worker'], {
        env: workerEnv(cfg, root), encoding: 'utf8', windowsHide: true,
        shell: false, timeout: 180000, maxBuffer: 1024 * 1024
      });
    const outcome = workerResult(root, cfg, child);
    report.finish(outcome.status, 'execute.worker-result', {
      runId: cfg.runId, businessExecuted: true,
      ...(outcome.status === 'BUSINESS_PASS' ? {
        orderId: outcome.orderId, inventoryOutQty: outcome.inventoryOutQty,
        pointDelta: outcome.pointDelta
      } : {
        failedStage: outcome.failedStage || null,
        lastKnownStage: outcome.lastKnownStage || null
      })
    });
    if (outcome.status === 'BUSINESS_PASS') {
      atomicJson(reserved.attemptPath, { runId: cfg.runId, permitId: cfg.executionPermit.id,
        status: 'BUSINESS_PASS', finishedAt: new Date().toISOString() });
      const ledger = JSON.parse(fs.readFileSync(reserved.ledgerPath, 'utf8'));
      ledger.permits[cfg.executionPermit.id].status = 'BUSINESS_PASS';
      atomicJson(reserved.ledgerPath, ledger);
      fs.unlinkSync(reserved.lockPath);
    }
    return done(outcome.code);
  } catch (error) {
    const code = error.exitCode || EXIT.UNKNOWN;
    const status = code === EXIT.CONFIG ? 'CONFIG_ERROR' :
      code === EXIT.UNAVAILABLE ? 'SERVICE_UNAVAILABLE' :
      code === EXIT.PREFLIGHT ? 'PREFLIGHT_FAILED' :
      code === EXIT.REPLAY ? 'REPLAY_REFUSED' : 'UNKNOWN';
    report.finish(status, error.stage || 'runner.unexpected', { businessExecuted: workerStarted });
    if (reserved && !workerStarted) fs.unlinkSync(reserved.lockPath);
    return done(code);
  }
}

if (require.main === module) {
  run().then(code => { process.exitCode = code; })
    .catch(() => { process.exitCode = EXIT.UNKNOWN; });
}

module.exports = { EXIT, LEGACY_RUN_ID, reporter, loadConfig, executeGate, reserve,
  preflight, workerResult, run, parseArgs, junit };
