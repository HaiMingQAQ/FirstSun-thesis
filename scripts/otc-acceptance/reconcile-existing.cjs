'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { EXIT, reporter, loadConfig, executeGate } = require('./runner.cjs');
const { reconcileExisting } = require('./order-flow.cjs');

const ROOT = path.resolve(__dirname, '..', '..', '.local', 'otc-acceptance');
const FIXED = Object.freeze({
  runId: 'otc-20261105-01', permitId: 'otc-user-20261105-01',
  orderId: 163685, tenantId: 901, storeId: 9101,
  memberId: 163621, drugId: 9303
});

function stop(code, stage) {
  const error = new Error(stage);
  error.exitCode = code;
  error.stage = stage;
  throw error;
}

function readEvidence(file) {
  try {
    const info = fs.lstatSync(file);
    if (!info.isFile() || info.isSymbolicLink()) stop(EXIT.REPLAY, 'reconcile.evidence-invalid');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    stop(EXIT.REPLAY, 'reconcile.evidence-missing-or-invalid');
  }
}

function parseArgs(argv) {
  if (argv.includes('--help') || argv.includes('-h')) return { help: true };
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const value = argv[i];
    if (value === '--replay-once') { args.replay = true; continue; }
    if (!['--run-id', '--permit', '--config'].includes(value) ||
        !argv[i + 1] || argv[i + 1].startsWith('--')) stop(EXIT.CONFIG, 'reconcile.arguments');
    args[value.slice(2)] = argv[++i];
  }
  if (args['run-id'] !== FIXED.runId || args.permit !== FIXED.permitId) {
    stop(EXIT.CONFIG, 'reconcile.fixed-run-and-permit-required');
  }
  return args;
}

function validateReconcileProof(root, cfg) {
  if (cfg.runId !== FIXED.runId || cfg.executionPermit?.id !== FIXED.permitId ||
      cfg.fixture.tenantId !== FIXED.tenantId || cfg.fixture.storeId !== FIXED.storeId ||
      cfg.fixture.drugId !== FIXED.drugId) stop(EXIT.REPLAY, 'reconcile.fixture-mismatch');
  const lock = readEvidence(path.join(root, 'execution.lock'));
  const attempt = readEvidence(path.join(root, 'attempt-' + FIXED.runId + '.json'));
  const ledger = readEvidence(path.join(root, 'permits.json'));
  const dispatch = readEvidence(path.join(root, 'dispatch-' + FIXED.runId + '.started'));
  const create = readEvidence(path.join(root, FIXED.runId + '.order-create.started'));
  const original = readEvidence(path.join(root, FIXED.runId + '.summary.json'));
  if (!lock || !attempt || !ledger || !dispatch || !create || !original ||
      lock.runId !== FIXED.runId || lock.permitId !== FIXED.permitId ||
      lock.status !== 'RESERVED' || attempt.runId !== FIXED.runId ||
      attempt.permitId !== FIXED.permitId || attempt.status !== 'UNKNOWN' ||
      ledger.version !== 1 || ledger.permits?.[FIXED.permitId]?.runId !== FIXED.runId ||
      ledger.permits[FIXED.permitId].status !== 'UNKNOWN' ||
      dispatch.runId !== FIXED.runId || dispatch.permitId !== FIXED.permitId ||
      create.runId !== FIXED.runId) stop(EXIT.REPLAY, 'reconcile.original-attempt-mismatch');
  if (original.runId !== FIXED.runId || original.orderId !== FIXED.orderId ||
      original.memberId !== FIXED.memberId || original.tenantId !== FIXED.tenantId ||
      original.storeId !== FIXED.storeId || original.drugId !== FIXED.drugId ||
      original.schema !== cfg.mysql.schema ||
      original.expectedMysqlUuid !== cfg.mysql.expectedUuid ||
      !/^[A-Za-z0-9_-]{1,32}$/.test(original.orderNo || '') ||
      original.failure?.step !== 'staff.verify' ||
      original.phases?.paymentRepeat !== 'one-call-state-stable' ||
      !Array.isArray(original.baseline?.stock) ||
      original.baseline.stock.length === 0 ||
      !/^(0|[1-9]\d*)$/.test(String(original.baseline.memberPoint)) ||
      !Number.isSafeInteger(Number(original.baseline.memberPoint)) ||
      Number(original.pointRule?.memberPoint) !== Number(original.baseline.memberPoint) ||
      original.paymentSnapshot?.order?.length !== 1 ||
      String(original.paymentSnapshot.order[0][0]) !== String(FIXED.orderId) ||
      String(original.paymentSnapshot.order[0][2]) !== String(FIXED.memberId) ||
      String(original.paymentSnapshot.order[0][3]) !== String(FIXED.storeId) ||
      !/^[1-9]\d*$/.test(String(original.paymentSnapshot.order[0][13])) ||
      !original.pointRule || !Number.isSafeInteger(original.expectedEarn?.points) ||
      original.expectedEarn.points < 0 ||
      Number(original.amount) !== 9 || original.expectedEarn.payableAmount == null ||
      original.failure?.message !== 'final audit: expected one successful, non-refunded mock member pay order' ||
      original.result === 'PASS_SCOPED_LOCAL_HTTP_AND_MYSQL') {
    stop(EXIT.REPLAY, 'reconcile.original-summary-mismatch');
  }
  return original;
}

async function run(argv = process.argv.slice(2)) {
  let args;
  try { args = parseArgs(argv); }
  catch (error) { args = { parseError: error }; }
  if (args.help) {
    process.stdout.write('Read-only: node scripts/otc-acceptance/reconcile-existing.cjs --run-id otc-20261105-01 --permit otc-user-20261105-01\n' +
      'One verified repeat: add --replay-once after the same arguments. No order or payment creation.\n');
    return EXIT.OK;
  }
  const root = ROOT;
  const reportDir = path.join(root, 'reconciliations', randomUUID());
  const report = reporter(reportDir, 'reconcile-existing');
  const replayMarker = path.join(root, 'reconcile-replay-' + FIXED.runId + '.started');
  const replayMarkerPresentBefore = fs.existsSync(replayMarker);
  const done = code => {
    process.stdout.write(JSON.stringify({
      status: report.summary.status, reportDir, exitCode: code
    }) + '\n');
    return code;
  };
  try {
    if (args.parseError) throw args.parseError;
    const cfg = loadConfig(root, args);
    executeGate(cfg, args);
    report.event('reconcile.original-evidence', 'STARTED');
    const original = validateReconcileProof(root, cfg);
    if (args.replay && fs.existsSync(replayMarker)) {
      stop(EXIT.REPLAY, 'reconcile.repeat-already-attempted');
    }
    report.event('reconcile.original-evidence', 'READY');
    report.event('reconcile.final-audit', 'UNKNOWN');
    const assertions = await reconcileExisting(cfg, original, reportDir, root, !!args.replay);
    report.finish(args.replay ? 'RECONCILE_PASS' : 'AUDIT_ONLY_PASS',
      args.replay ? 'reconcile.verify-repeat' : 'reconcile.final-audit', {
        sourceRunId: FIXED.runId, orderId: FIXED.orderId,
        businessExecuted: !!args.replay,
        finalAuditPassed: true, completionRepeatPassed: !!args.replay,
        inventoryOutQty: assertions.inventoryOutQty,
        pointDelta: assertions.pointRecordsNet
      });
    return done(EXIT.OK);
  } catch (error) {
    const code = error.exitCode || (
      error.safe && !/unknown|transport|read-only SQL failed/i.test(error.message)
        ? EXIT.ASSERTION : EXIT.UNKNOWN);
    const status = code === EXIT.CONFIG ? 'CONFIG_ERROR' :
      code === EXIT.REPLAY ? 'REPLAY_REFUSED' :
      code === EXIT.ASSERTION ? 'ASSERTION_FAILED' : 'UNKNOWN';
    const stage = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(error.stage || '')
      ? error.stage : 'reconcile.unknown';
    report.finish(status, stage, {
      sourceRunId: FIXED.runId, orderId: FIXED.orderId,
      businessExecuted: !!args.replay && !replayMarkerPresentBefore &&
        fs.existsSync(replayMarker)
    });
    return done(code);
  }
}

if (require.main === module) {
  run().then(code => { process.exitCode = code; })
    .catch(() => { process.exitCode = EXIT.UNKNOWN; });
}

module.exports = { FIXED, parseArgs, validateReconcileProof, run };
