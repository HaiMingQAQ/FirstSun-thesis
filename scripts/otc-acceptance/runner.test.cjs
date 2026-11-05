'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { run, reserve, executeGate, workerResult, junit, EXIT, LEGACY_RUN_ID } = require('./runner.cjs');
const { assertCommonResult, assertRunnerDispatch, mysqlCell,
  canonicalPaymentId, isSaleStockOut, markReplayOnce } = require('./order-flow.cjs');
const { mysqlDiagnostics } = require('./diagnostics.cjs');
const { FIXED, parseArgs: parseReconcileArgs, validateReconcileProof } = require('./reconcile-existing.cjs');

function sandbox() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'otc-runner-'));
  const cli = path.join(root, 'mysql.exe');
  const defaults = path.join(root, 'readonly.cnf');
  fs.writeFileSync(cli, '');
  fs.writeFileSync(defaults, '');
  const cfg = {
    environment: 'isolated-local-test', dailyDatabase: false,
    mockPaymentOnly: true, externalProvidersDisabled: true,
    appBaseUrl: 'http://127.0.0.1:18080/app-api',
    adminBaseUrl: 'http://127.0.0.1:18080/admin-api',
    mysql: { host: '127.0.0.1', port: 13306, schema: 'isolated_test',
      expectedUuid: '11111111-1111-1111-1111-111111111111',
      cli, defaultsExtraFile: defaults },
    fixture: { tenantId: 9, storeId: 10, drugId: 11,
      memberMobile: '13900000000', memberDevSmsCode: '654321',
      adminUsername: 'tester', adminPassword: 'TopSecret42' },
    runId: 'fresh_run_001',
    executionPermit: { id: 'permit_001', runId: 'fresh_run_001',
      maxNewOrders: 1, confirmed: true }
  };
  fs.writeFileSync(path.join(root, 'config.json'), JSON.stringify(cfg));
  return { root, cfg, cleanup: () => fs.rmSync(root, { recursive: true, force: true }) };
}

function reports(root) {
  return fs.readdirSync(path.join(root, 'invocations'))
    .map(name => path.join(root, 'invocations', name));
}

const health = async (url, options) => {
  assert.equal(options.method, 'GET');
  assert.equal(url.pathname, '/actuator/health');
  return { ok: true, json: async () => ({ status: 'UP' }) };
};

const select = (cfg, sql) => {
  assert.match(sql, /^SELECT DATABASE\(\),@@server_uuid;/);
  assert.doesNotMatch(sql, /\b(?:INSERT|UPDATE|DELETE|DROP|ALTER)\b/i);
  return [cfg.mysql.schema + '\t' + cfg.mysql.expectedUuid, '0', '1', '1', '2'];
};

test('missing config is separate from unavailable service and writes initial reports', async () => {
  const s = sandbox();
  try {
    fs.unlinkSync(path.join(s.root, 'config.json'));
    assert.equal(await run([], { root: s.root, quiet: true }), EXIT.CONFIG);
    const dir = reports(s.root)[0];
    const summary = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json')));
    assert.equal(summary.status, 'CONFIG_ERROR');
    assert.equal(summary.businessExecuted, false);
    assert.ok(fs.existsSync(path.join(dir, 'events.jsonl')));
    assert.ok(fs.existsSync(path.join(dir, 'junit.xml')));
    assert.equal(fs.existsSync(path.join(s.root, 'execution.lock')), false);
  } finally { s.cleanup(); }
});

test('checked-in daily Compose endpoints are rejected before any connection', async () => {
  const s = sandbox();
  try {
    s.cfg.appBaseUrl = 'http://127.0.0.1:28080/app-api';
    s.cfg.adminBaseUrl = 'http://127.0.0.1:28080/admin-api';
    fs.writeFileSync(path.join(s.root, 'config.json'), JSON.stringify(s.cfg));
    let connected = false;
    assert.equal(await run([], { root: s.root, quiet: true,
      fetchImpl: async () => { connected = true; throw new Error('unexpected'); }
    }), EXIT.CONFIG);
    assert.equal(connected, false);
  } finally { s.cleanup(); }
});

test('service unavailable consumes no permit; same config can later reach its first worker dispatch', async () => {
  const s = sandbox();
  try {
    assert.equal(await run(['--execute', '--permit', 'permit_001'], {
      root: s.root, quiet: true, fetchImpl: async () => { throw new Error('offline'); }
    }), EXIT.UNAVAILABLE);
    assert.equal(fs.existsSync(path.join(s.root, 'attempt-fresh_run_001.json')), false);
    assert.equal(fs.existsSync(path.join(s.root, 'permits.json')), false);
    assert.equal(fs.existsSync(path.join(s.root, 'execution.lock')), false);
    assert.equal(await run(['--execute', '--permit', 'permit_001'], {
      root: s.root, quiet: true, fetchImpl: health, mysqlSelectImpl: select,
      spawnWorker: () => ({ status: 1, signal: null, error: null })
    }), EXIT.UNKNOWN);
    assert.ok(fs.existsSync(path.join(s.root, 'attempt-fresh_run_001.json')));
    assert.ok(fs.existsSync(path.join(s.root, 'execution.lock')));
    const dirs = reports(s.root);
    assert.equal(dirs.length, 2);
    assert.notEqual(dirs[0], dirs[1]);
    assert.ok(dirs.some(dir => JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'))).status === 'SERVICE_UNAVAILABLE'));
    assert.ok(dirs.some(dir => JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'))).status === 'UNKNOWN'));
  } finally { s.cleanup(); }
});

test('preflight is GET and SELECT only and never marks business PASS', async () => {
  const s = sandbox();
  try {
    let sawStartup = false;
    const fakeFetch = async (url, options) => {
      const dirs = reports(s.root);
      assert.equal(dirs.length, 1);
      const started = JSON.parse(fs.readFileSync(path.join(dirs[0], 'summary.json')));
      sawStartup = started.status === 'UNKNOWN';
      return health(url, options);
    };
    assert.equal(await run([], { root: s.root, quiet: true,
      fetchImpl: fakeFetch, mysqlSelectImpl: select }), EXIT.OK);
    assert.equal(sawStartup, true);
    const dir = reports(s.root)[0];
    const summary = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json')));
    assert.equal(summary.status, 'PREFLIGHT_READY');
    assert.equal(summary.businessExecuted, false);
    assert.match(fs.readFileSync(path.join(dir, 'junit.xml'), 'utf8'), /<skipped /);
    assert.equal(fs.existsSync(path.join(s.root, 'attempt-fresh_run_001.json')), false);
  } finally { s.cleanup(); }
});

test('concurrent or repeated run and permit cannot reserve another order', () => {
  const s = sandbox();
  try {
    const first = reserve(s.root, s.cfg);
    assert.ok(fs.existsSync(first.attemptPath));
    assert.throws(() => reserve(s.root, s.cfg), error => error.exitCode === EXIT.REPLAY);
    fs.unlinkSync(first.lockPath);
    assert.throws(() => reserve(s.root, s.cfg), error => error.exitCode === EXIT.REPLAY);
    const changed = { ...s.cfg, runId: 'fresh_run_002',
      executionPermit: { ...s.cfg.executionPermit, runId: 'fresh_run_002' } };
    assert.throws(() => reserve(s.root, changed), error => error.exitCode === EXIT.REPLAY);
    assert.equal(fs.existsSync(first.attemptPath), true);
  } finally { s.cleanup(); }
});

test('historical run ID cannot be dispatched by a new permit', () => {
  const s = sandbox();
  try {
    s.cfg.runId = LEGACY_RUN_ID;
    s.cfg.executionPermit.runId = LEGACY_RUN_ID;
    assert.throws(() => executeGate(s.cfg, { permit: 'permit_001' }),
      error => error.exitCode === EXIT.REPLAY);
  } finally { s.cleanup(); }
});

test('worker boolean gate alone cannot dispatch without matching persistent reservation', () => {
  const s = sandbox();
  try {
    const f = s.cfg.fixture;
    const db = s.cfg.mysql;
    const child = spawnSync(process.execPath, [path.join(__dirname, 'order-flow.cjs'), '--worker'], {
      encoding: 'utf8', timeout: 5000,
      env: {
        ...process.env, OTC_RUNNER_LOCKED: '1', OTC_PERMIT_ID: 'permit_001',
        E2E_RUN_ID: s.cfg.runId, E2E_PRIVATE_DIR: s.root,
        E2E_APP_BASE_URL: s.cfg.appBaseUrl, E2E_ADMIN_BASE_URL: s.cfg.adminBaseUrl,
        E2E_MYSQL_HOST: db.host, E2E_MYSQL_PORT: String(db.port),
        E2E_MYSQL_SCHEMA: db.schema, E2E_MYSQL_EXPECTED_UUID: db.expectedUuid,
        E2E_MYSQL_CLI: db.cli, E2E_MYSQL_DEFAULTS_EXTRA_FILE: db.defaultsExtraFile,
        E2E_TENANT_ID: String(f.tenantId), E2E_STORE_ID: String(f.storeId),
        E2E_DRUG_ID: String(f.drugId), E2E_MEMBER_MOBILE: f.memberMobile,
        E2E_MEMBER_DEV_SMS_CODE: f.memberDevSmsCode,
        E2E_ADMIN_USERNAME: f.adminUsername, E2E_ADMIN_PASSWORD: f.adminPassword
      }
    });
    assert.equal(child.status, 1);
    assert.match(child.stderr, /reservation evidence/);
    assert.equal(fs.existsSync(path.join(s.root, 'dispatch-' + s.cfg.runId + '.started')), false);
    assert.equal(fs.existsSync(path.join(s.root, s.cfg.runId + '.summary.json')), false);
    const reserved = reserve(s.root, s.cfg);
    assertRunnerDispatch(s.root, s.cfg.runId, s.cfg.executionPermit.id);
    assert.ok(fs.existsSync(path.join(s.root, 'dispatch-' + s.cfg.runId + '.started')));
    assert.throws(() => assertRunnerDispatch(s.root, s.cfg.runId, s.cfg.executionPermit.id),
      /already attempted/);
    assert.ok(fs.existsSync(reserved.lockPath));
  } finally { s.cleanup(); }
});

test('timed-out worker retains last safe stage in JSON and JUnit without passing', async () => {
  const s = sandbox();
  try {
    const code = await run(['--execute', '--permit', 'permit_001'], {
      root: s.root, quiet: true, fetchImpl: health, mysqlSelectImpl: select,
      spawnWorker: () => {
        fs.writeFileSync(path.join(s.root, s.cfg.runId + '.summary.json'),
          JSON.stringify({ currentStep: 'payment.first' }));
        return { status: null, signal: 'SIGTERM', error: { code: 'ETIMEDOUT' } };
      }
    });
    assert.equal(code, EXIT.UNKNOWN);
    const dir = reports(s.root)[0];
    const summary = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json')));
    assert.equal(summary.status, 'UNKNOWN');
    assert.equal(summary.lastKnownStage, 'payment.first');
    assert.match(fs.readFileSync(path.join(dir, 'junit.xml'), 'utf8'), /UNKNOWN at payment.first/);
    assert.ok(fs.existsSync(path.join(s.root, 'execution.lock')));
    assert.ok(fs.existsSync(path.join(s.root, 'attempt-' + s.cfg.runId + '.json')));
    fs.writeFileSync(path.join(s.root, s.cfg.runId + '.summary.json'),
      JSON.stringify({ currentStep: 'payment.first/secret' }));
    assert.equal(workerResult(s.root, s.cfg, { status: null, signal: 'SIGTERM' }).lastKnownStage, null);
  } finally { s.cleanup(); }
});

test('worker assertion failure and unknown outcome stay distinct; HTTP 500 never passes', () => {
  const s = sandbox();
  try {
    const auditPath = path.join(s.root, s.cfg.runId + '.summary.json');
    fs.writeFileSync(auditPath, JSON.stringify({
      failure: { step: 'final audit', message: 'final audit: payment association mismatch' }
    }));
    assert.equal(workerResult(s.root, s.cfg, { status: 1 }).code, EXIT.ASSERTION);
    fs.writeFileSync(auditPath, JSON.stringify({
      failure: { step: 'payment.first', message: 'transport outcome unknown' }
    }));
    assert.equal(workerResult(s.root, s.cfg, { status: 1 }).code, EXIT.UNKNOWN);
    assert.equal(workerResult(s.root, s.cfg, { status: null, signal: 'SIGTERM' }).code, EXIT.UNKNOWN);
    assert.throws(() => assertCommonResult('payment.repeat', 500, 0));
    assert.doesNotThrow(() => assertCommonResult('payment.repeat', 200, 0));
  } finally { s.cleanup(); }
});

test('JUnit names the failed business stage and MySQL diagnostics omit raw stderr', () => {
  const report = junit({ status: 'ASSERTION_FAILED', stage: 'execute.worker-result',
    failedStage: 'payment.repeat-once' });
  assert.match(report, /ASSERTION_FAILED at payment.repeat-once/);
  assert.doesNotMatch(report, /execute.worker-result/);
  const diagnostics = mysqlDiagnostics({
    status: 1, signal: 'SIGTERM', error: { code: 'ETIMEDOUT' },
    stderr: 'ERROR 1045 (28000): Access denied for user secret-user password secret-value'
  });
  assert.deepEqual(diagnostics, {
    exitCode: 1, processErrorCode: 'ETIMEDOUT', signal: 'SIGTERM',
    mysqlErrorCode: 1045, sqlState: '28000'
  });
  assert.doesNotMatch(JSON.stringify(diagnostics), /secret-user|secret-value/);
});

test('reports remain redacted and independent across concurrent preflights', async () => {
  const s = sandbox();
  try {
    const results = await Promise.all([run([], { root: s.root, quiet: true,
      fetchImpl: health, mysqlSelectImpl: select }), run([], { root: s.root, quiet: true,
      fetchImpl: health, mysqlSelectImpl: select })]);
    assert.deepEqual(results, [EXIT.OK, EXIT.OK]);
    const dirs = reports(s.root);
    assert.equal(dirs.length, 2);
    for (const dir of dirs) {
      const report = ['summary.json', 'events.jsonl', 'junit.xml']
        .map(file => fs.readFileSync(path.join(dir, file), 'utf8')).join('\n');
      for (const secret of ['TopSecret42', '654321', '13900000000']) {
        assert.equal(report.includes(secret), false);
      }
    }
  } finally { s.cleanup(); }
});

test('canonical payment ID uses pay_no, normalizes MySQL NULL, and rejects mismatched compatibility ID', () => {
  assert.equal(mysqlCell('NULL'), null);
  assert.equal(mysqlCell('\\N'), null);
  assert.equal(mysqlCell('19'), '19');
  const row = Array(18).fill(null);
  row[13] = '19';
  assert.equal(canonicalPaymentId(row), '19');
  assert.equal(row[12], null);
  row[12] = '19';
  assert.equal(canonicalPaymentId(row), '19');
  row[12] = '18';
  assert.throws(() => canonicalPaymentId(row), /differs/);
  row[12] = null;
  for (const invalid of [null, 'NULL', '0', '0019', '19x']) {
    row[13] = invalid;
    assert.throws(() => canonicalPaymentId(row), /positive pay_no/);
  }
});

test('stock out accepts only the two source mapper pairs with zero in-quantity', () => {
  const row = Array(15).fill('0');
  row[5] = '20'; row[9] = '2';
  assert.equal(isSaleStockOut(row), true);
  row[5] = '82'; row[9] = '6';
  assert.equal(isSaleStockOut(row), true);
  row[5] = '20'; row[9] = '6';
  assert.equal(isSaleStockOut(row), false);
  row[5] = '82'; row[9] = '2';
  assert.equal(isSaleStockOut(row), false);
  row[5] = '20'; row[9] = '2'; row[6] = '1';
  assert.equal(isSaleStockOut(row), false);
});

test('reconciliation requires original run evidence and one-time replay marker', () => {
  const s = sandbox();
  try {
    s.cfg.runId = FIXED.runId;
    s.cfg.executionPermit = { id: FIXED.permitId, runId: FIXED.runId };
    s.cfg.fixture.tenantId = FIXED.tenantId;
    s.cfg.fixture.storeId = FIXED.storeId;
    s.cfg.fixture.drugId = FIXED.drugId;
    assert.throws(() => validateReconcileProof(s.root, s.cfg), /evidence/);
    reserve(s.root, s.cfg);
    fs.writeFileSync(path.join(s.root, 'dispatch-' + FIXED.runId + '.started'),
      JSON.stringify({ runId: FIXED.runId, permitId: FIXED.permitId }));
    fs.writeFileSync(path.join(s.root, FIXED.runId + '.order-create.started'),
      JSON.stringify({ runId: FIXED.runId }));
    const paymentOrder = Array(16).fill(null);
    paymentOrder[0] = String(FIXED.orderId);
    paymentOrder[2] = String(FIXED.memberId);
    paymentOrder[3] = String(FIXED.storeId);
    paymentOrder[13] = '19';
    const original = {
      runId: FIXED.runId, orderId: FIXED.orderId,
      memberId: FIXED.memberId, tenantId: FIXED.tenantId,
      storeId: FIXED.storeId, drugId: FIXED.drugId,
      schema: s.cfg.mysql.schema, expectedMysqlUuid: s.cfg.mysql.expectedUuid,
      orderNo: 'WXTEST163685', amount: 9,
      failure: { step: 'staff.verify',
        message: 'final audit: expected one successful, non-refunded mock member pay order' },
      phases: { paymentRepeat: 'one-call-state-stable' },
      baseline: { stock: [['batch']], memberPoint: '27' },
      paymentSnapshot: { order: [paymentOrder] },
      pointRule: { memberPoint: 27, earnEnabled: true, earnPerYuan: 1, levelMultiplier: 1 },
      expectedEarn: { points: 9, payableAmount: 9 }
    };
    fs.writeFileSync(path.join(s.root, FIXED.runId + '.summary.json'), JSON.stringify(original));
    assert.equal(validateReconcileProof(s.root, s.cfg).orderId, FIXED.orderId);
    markReplayOnce(s.root, FIXED.runId, FIXED.permitId);
    assert.throws(() => markReplayOnce(s.root, FIXED.runId, FIXED.permitId),
      /already attempted/);
    original.phases.paymentRepeat = 'not-proven';
    fs.writeFileSync(path.join(s.root, FIXED.runId + '.summary.json'), JSON.stringify(original));
    assert.throws(() => validateReconcileProof(s.root, s.cfg), /summary-mismatch/);
    original.phases.paymentRepeat = 'one-call-state-stable';
    original.failure.message = 'transport outcome unknown';
    fs.writeFileSync(path.join(s.root, FIXED.runId + '.summary.json'), JSON.stringify(original));
    assert.throws(() => validateReconcileProof(s.root, s.cfg), /summary-mismatch/);
  } finally { s.cleanup(); }
});

test('reconciliation CLI is fixed to the failed order and audit-only JUnit stays skipped', () => {
  assert.throws(() => parseReconcileArgs([
    '--run-id', 'different-run', '--permit', FIXED.permitId
  ]), /fixed-run-and-permit/);
  assert.equal(parseReconcileArgs([
    '--run-id', FIXED.runId, '--permit', FIXED.permitId, '--replay-once'
  ]).replay, true);
  const xml = junit({ status: 'AUDIT_ONLY_PASS', stage: 'reconcile.final-audit',
    finalAuditPassed: true, completionRepeatPassed: false, businessExecuted: false });
  assert.match(xml, /<skipped message="Audit only; repeat verification not executed"/);
  assert.match(junit({ status: 'RECONCILE_PASS', stage: 'reconcile.verify-repeat',
    finalAuditPassed: true, completionRepeatPassed: true, businessExecuted: true }),
  /failures="0" skipped="0"/);
  assert.match(junit({ status: 'RECONCILE_PASS', stage: 'reconcile.verify-repeat',
    finalAuditPassed: true, completionRepeatPassed: false, businessExecuted: true }),
  /failures="1"/);
});
