'use strict';

// Adapted from the frozen 12/order-flow-readiness.cjs. Business assertions are retained.
// This worker is launched only after runner.cjs has recorded and locked the attempt.

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { mysqlDiagnostics } = require('./diagnostics.cjs');

const ENV = process.env;
const events = [];
let currentStep = 'configuration';
let cfg;
let summary;
let memberToken;
let staffToken;
let orderId;
let pickupCode;

function fail(message) {
  const error = new Error(message);
  error.safe = true;
  throw error;
}

function need(name) {
  const value = ENV[name];
  if (typeof value !== 'string' || value.length === 0) fail('missing required setting: ' + name);
  return value;
}

function positiveInt(value, name) {
  if (!/^\d+$/.test(String(value))) fail('invalid integer setting: ' + name);
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n <= 0) fail('invalid integer setting: ' + name);
  return n;
}

function localBase(name, expectedPath) {
  const value = need(name);
  let url;
  try { url = new URL(value); } catch { fail('invalid local API base: ' + name); }
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.pathname !== expectedPath || url.username || url.password || url.search || url.hash) {
    fail('API base must be explicit loopback port with path ' + expectedPath);
  }
  return url.origin + url.pathname;
}

function loadConfig() {
  if (process.argv[2] !== '--worker' || ENV.OTC_RUNNER_LOCKED !== '1') {
    fail('worker gate missing');
  }
  const runId = need('E2E_RUN_ID');
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(runId)) fail('invalid run id');
  if (runId === 'e2e12-20261030-42eba49acb3f41c485aafcdf6e19a2b7') fail('historical run id is reserved');
  const schema = need('E2E_MYSQL_SCHEMA');
  if (!/^[A-Za-z0-9_]{1,64}$/.test(schema)) fail('invalid isolated schema');
  const expectedUuid = need('E2E_MYSQL_EXPECTED_UUID');
  if (!/^[0-9a-f-]{36}$/i.test(expectedUuid)) fail('invalid expected MySQL UUID');

  const privateDir = path.resolve(need('E2E_PRIVATE_DIR'));
  const dirInfo = fs.lstatSync(privateDir);
  if (!dirInfo.isDirectory() || dirInfo.isSymbolicLink()) fail('private runtime directory is not a real directory');

  const mysqlCli = path.resolve(need('E2E_MYSQL_CLI'));
  const mysqlDefaults = path.resolve(need('E2E_MYSQL_DEFAULTS_EXTRA_FILE'));
  if (!fs.statSync(mysqlCli).isFile() || !fs.statSync(mysqlDefaults).isFile()) {
    fail('MySQL client or private defaults file is unavailable');
  }

  const memberMobile = need('E2E_MEMBER_MOBILE');
  const devCode = need('E2E_MEMBER_DEV_SMS_CODE');
  if (!/^\d{11}$/.test(memberMobile) || devCode.length > 32 || /[\r\n]/.test(devCode)) {
    fail('invalid synthetic member login input');
  }
  const adminUsername = need('E2E_ADMIN_USERNAME');
  const adminPassword = need('E2E_ADMIN_PASSWORD');
  if (!/^[A-Za-z0-9]{4,30}$/.test(adminUsername) || adminPassword.length < 4 || adminPassword.length > 16) {
    fail('invalid staff login input');
  }

  const mysqlHost = need('E2E_MYSQL_HOST');
  const mysqlPort = positiveInt(need('E2E_MYSQL_PORT'), 'E2E_MYSQL_PORT');
  if (mysqlHost !== '127.0.0.1') fail('MySQL host must be the verified local clone endpoint');

  return {
    appBase: localBase('E2E_APP_BASE_URL', '/app-api'),
    adminBase: localBase('E2E_ADMIN_BASE_URL', '/admin-api'),
    tenantId: positiveInt(need('E2E_TENANT_ID'), 'E2E_TENANT_ID'),
    storeId: positiveInt(need('E2E_STORE_ID'), 'E2E_STORE_ID'),
    drugId: positiveInt(need('E2E_DRUG_ID'), 'E2E_DRUG_ID'),
    memberMobile,
    devCode,
    adminUsername,
    adminPassword,
    runId,
    privateDir,
    mysqlCli,
    mysqlDefaults,
    mysqlHost,
    mysqlPort,
    schema,
    expectedUuid,
    authHeader: ENV.E2E_AUTH_HEADER || 'Authorization',
    tenantHeader: ENV.E2E_TENANT_HEADER || 'tenant-id',
    smsHeader: 'X-Sms-Code'
  };
}

function assertRunnerDispatch(privateDir, runId, permitId) {
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(permitId || '')) fail('worker permit missing');
  const readEvidence = file => {
    try {
      const info = fs.lstatSync(file);
      if (!info.isFile() || info.isSymbolicLink()) fail('worker reservation evidence invalid');
      return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      fail('worker reservation evidence missing or invalid');
    }
  };
  const lock = readEvidence(path.join(privateDir, 'execution.lock'));
  const attempt = readEvidence(path.join(privateDir, 'attempt-' + runId + '.json'));
  const ledger = readEvidence(path.join(privateDir, 'permits.json'));
  const permit = ledger?.permits?.[permitId];
  if (!lock || !attempt || !ledger ||
      lock.runId !== runId || lock.permitId !== permitId || lock.status !== 'RESERVED' ||
      attempt.runId !== runId || attempt.permitId !== permitId || attempt.status !== 'UNKNOWN' ||
      ledger.version !== 1 || permit?.runId !== runId || permit?.status !== 'UNKNOWN') {
    fail('worker reservation evidence does not match run');
  }
  const marker = path.join(privateDir, 'dispatch-' + runId + '.started');
  let handle;
  try { handle = fs.openSync(marker, 'wx', 0o600); }
  catch { fail('worker dispatch already attempted'); }
  try {
    fs.writeFileSync(handle, JSON.stringify({ runId, permitId, at: new Date().toISOString() }) + '\n');
  } finally {
    fs.closeSync(handle);
  }
}

function appendEvent(event) {
  const safeEvent = Object.assign({ at: new Date().toISOString() }, event);
  events.push(safeEvent);
  if (cfg) {
    fs.appendFileSync(path.join(cfg.privateDir, cfg.runId + '.http-sql-events.jsonl'),
      JSON.stringify(safeEvent) + '\n', { encoding: 'utf8', mode: 0o600 });
  }
}

function saveSummary() {
  if (!cfg || !summary) return;
  summary.events = events;
  const target = path.join(cfg.privateDir, cfg.runId + '.summary.json');
  const temporary = target + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(summary, null, 2), { encoding: 'utf8', mode: 0o600 });
  fs.renameSync(temporary, target);
}

function markStep(step) {
  currentStep = step;
  if (summary) {
    summary.currentStep = step;
    appendEvent({ kind: 'phase', step, result: 'STARTED' });
    saveSummary();
  }
}

function buildUrl(base, endpoint, params) {
  const url = new URL(base + endpoint);
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  }
  return url;
}

function assertCommonResult(step, httpStatus, code) {
  if (httpStatus < 200 || httpStatus >= 300 || code !== 0) {
    fail(step + ': API did not return CommonResult code 0');
  }
}

async function callApi(step, base, endpoint, options) {
  const opts = options || {};
  const method = opts.method || 'GET';
  const headers = { accept: 'application/json', [cfg.tenantHeader]: String(cfg.tenantId) };
  if (opts.token) headers[cfg.authHeader] = 'Bearer ' + opts.token;
  if (opts.headers) Object.assign(headers, opts.headers);
  let body;
  if (opts.body !== undefined) {
    headers['content-type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  let response;
  let payload;
  try {
    response = await fetch(buildUrl(base, endpoint, opts.params), {
      method,
      headers,
      body,
      signal: controller.signal,
      redirect: 'error'
    });
    const raw = await response.text();
    try { payload = JSON.parse(raw); } catch { fail(step + ': response was not JSON'); }
  } catch (error) {
    appendEvent({ kind: 'http', step, method, route: endpoint, transportError: error.name || 'Error' });
    fail(step + ': transport outcome unknown; no retry will be attempted');
  } finally {
    clearTimeout(timer);
  }

  const code = Number.isInteger(payload && payload.code) ? payload.code : null;
  appendEvent({
    kind: 'http', step, method, route: endpoint,
    httpStatus: response.status, commonCode: code,
    dataType: payload && payload.data === null ? 'null' : typeof (payload && payload.data)
  });
  assertCommonResult(step, response.status, code);
  return { data: payload.data, code, httpStatus: response.status, ok: response.ok };
}

function mysqlRows(label, selectSql) {
  if (!/^\s*SELECT\b/i.test(selectSql) || selectSql.includes(';')) fail(label + ': only one SELECT is allowed');
  const sql = 'SELECT DATABASE(), @@server_uuid;\n' + selectSql + '\n';
  const args = [
    '--defaults-extra-file=' + cfg.mysqlDefaults,
    '--protocol=tcp', '-h', cfg.mysqlHost, '-P', String(cfg.mysqlPort),
    '--database=' + cfg.schema,
    '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4'
  ];
  const result = spawnSync(cfg.mysqlCli, args, {
    input: sql, encoding: 'utf8', windowsHide: true, shell: false,
    timeout: 15000, maxBuffer: 8 * 1024 * 1024
  });
  if (result.error || result.status !== 0) {
    appendEvent({ kind: 'sql', label, failed: true, ...mysqlDiagnostics(result) });
    fail(label + ': read-only SQL failed');
  }
  const lines = String(result.stdout || '').replace(/\r/g, '').split('\n').filter(Boolean);
  const identity = (lines.shift() || '').split('\t');
  if (identity[0] !== cfg.schema || identity[1] !== cfg.expectedUuid) {
    appendEvent({ kind: 'sql', label, failed: true, identityMatched: false });
    fail(label + ': selected schema or MySQL UUID mismatch');
  }
  const rows = lines.map(line => line.split('\t').map(mysqlCell));
  appendEvent({ kind: 'sql', label, exitCode: 0, rowCount: rows.length, identityMatched: true });
  return rows;
}

function mysqlCell(value) {
  return value === '\\N' || value === 'NULL' ? null : value;
}

function canonicalPaymentId(orderRow) {
  const payNo = orderRow[13];
  if (typeof payNo !== 'string' || !/^[1-9]\d*$/.test(payNo)) {
    fail('audit: missing positive pay_no payment association');
  }
  const compatibilityId = orderRow[12];
  if (compatibilityId !== null && compatibilityId !== payNo) {
    fail('audit: pay_order_id differs from pay_no payment association');
  }
  return payNo;
}

function intCell(row, index) {
  const value = Number(row[index]);
  if (!Number.isFinite(value)) fail('invalid numeric SQL result');
  return value;
}

function moneyMinor(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) fail('invalid money SQL/API value');
  return Math.round(n * 100);
}

function costTenThousandths(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) fail('invalid inventory cost value');
  return Math.round(n * 10000);
}

function decimalParts(value) {
  const match = String(value).match(/^([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/);
  if (!match) fail('invalid point-rule decimal value');
  const sign = match[1] === '-' ? -1n : 1n;
  const fraction = match[3] || '';
  let coefficient = sign * BigInt(match[2] + fraction);
  let scale = fraction.length - Number(match[4] || 0);
  if (scale < 0) {
    coefficient *= 10n ** BigInt(-scale);
    scale = 0;
  }
  return { coefficient, scale };
}

function expectedEarnPoints(amount, rule) {
  if (rule.earnEnabled !== true) return { points: 0, reason: 'earning-disabled' };
  const amountParts = decimalParts(amount);
  if (amountParts.coefficient <= 0n) return { points: 0, reason: 'non-positive-payable-amount' };
  const rate = decimalParts(rule.earnPerYuan);
  const multiplier = decimalParts(rule.levelMultiplier);
  const product = amountParts.coefficient * rate.coefficient * multiplier.coefficient;
  if (product <= 0n) return { points: 0, reason: 'non-positive-effective-rate' };
  const scale = amountParts.scale + rate.scale + multiplier.scale;
  const points = product / (10n ** BigInt(scale)); // BigDecimal RoundingMode.DOWN for positive values.
  if (points === 0n) return { points: 0, reason: 'round-down-to-zero' };
  const javaIntPoints = BigInt.asIntN(32, points); // Java BigDecimal.intValue() keeps the low 32 bits.
  return javaIntPoints <= 0n
    ? { points: 0, reason: 'int-overflow-clamped-to-zero' }
    : { points: Number(javaIntPoints), reason: 'enabled-rule-formula' };
}

function inventoryTotals(rows) {
  const batches = new Map();
  let locationQty = 0;
  let locationAvailable = 0;
  for (const row of rows) {
    const batchId = row[0];
    if (!batches.has(batchId)) batches.set(batchId, row);
    locationQty += intCell(row, 10);
    locationAvailable += intCell(row, 10) - intCell(row, 11);
  }
  let totalQty = 0;
  let qtySold = 0;
  for (const row of batches.values()) {
    totalQty += intCell(row, 3);
    qtySold += intCell(row, 6);
  }
  return { batchCount: batches.size, totalQty, qtySold, locationQty, locationAvailable };
}

function stockRows() {
  const sql = 'SELECT b.id,b.batch_no,DATE_FORMAT(b.expiry_date,\'%Y-%m-%d\'),b.qty_total,b.qty_avail,' +
    'b.qty_frozen,b.qty_sold,b.cost_price,s.id,s.location_id,s.qty,s.qty_frozen ' +
    'FROM ph_inv_batch b JOIN ph_inv_location_stock s ON s.batch_id=b.id AND s.tenant_id=b.tenant_id ' +
    'JOIN ph_location l ON l.id=s.location_id AND l.tenant_id=s.tenant_id AND l.warehouse_id=b.warehouse_id ' +
    'JOIN ph_warehouse w ON w.id=b.warehouse_id AND w.tenant_id=b.tenant_id AND w.store_id=b.store_id ' +
    'JOIN ph_store st ON st.id=b.store_id AND st.tenant_id=b.tenant_id ' +
    'JOIN ph_drug d ON d.id=b.drug_id AND d.tenant_id=b.tenant_id ' +
    'WHERE b.tenant_id=' + cfg.tenantId + ' AND b.store_id=' + cfg.storeId + ' AND b.drug_id=' + cfg.drugId +
    ' AND s.deleted=0 AND b.deleted=0 AND l.deleted=0 AND w.deleted=0 AND st.deleted=0 AND d.deleted=0' +
    ' AND st.status=1 AND w.status=1 AND l.status=1 AND d.status=1 AND d.approve_status=1' +
    ' AND d.saleable_online=1 AND d.is_rx=0 AND d.drug_type IN (1,2)' +
    ' AND b.quality_status=0 AND b.expiry_date>=CURRENT_DATE ORDER BY b.id,s.id';
  return mysqlRows('inventory', sql);
}

function readBusinessAudit(memberId, id) {
  const order = mysqlRows('wx-order',
    'SELECT id,order_no,member_id,store_id,order_type,status,pay_status,goods_amount,point_deduct,' +
    'point_deduct_amount,point_earned,payable_amount,pay_order_id,pay_no,paid_at,finish_at,remark,pickup_code FROM ph_wx_order WHERE id=' + id +
    ' AND tenant_id=' + cfg.tenantId);
  if (order.length !== 1) fail('audit: expected one pharmacy order row');
  const orderNo = order[0][1];
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(orderNo)) fail('audit: order number format unexpected');
  const lines = mysqlRows('wx-order-lines',
    'SELECT id,wx_order_id,drug_id,qty,price,line_amount,batch_id,batch_no,picked_qty,location_id ' +
    'FROM ph_wx_order_line WHERE wx_order_id=' + id + ' AND tenant_id=' + cfg.tenantId + ' ORDER BY id');
  const paymentId = canonicalPaymentId(order[0]);
  const payOrder = mysqlRows('pay-order',
    'SELECT id,channel_code,user_id,user_type,merchant_order_id,price,status,refund_price,success_time,channel_order_no FROM pay_order WHERE id=' +
    paymentId);
  const sales = mysqlRows('sale-order',
    'SELECT id,order_no,store_id,member_id,wx_order_id,total_qty,subtotal,payable_amount,paid_amount,cost_amount,' +
    'points_earned,status,source FROM ph_sale_order WHERE wx_order_id=' + id + ' AND tenant_id=' + cfg.tenantId + ' ORDER BY id');
  const saleLines = mysqlRows('sale-order-lines',
    'SELECT sl.id,sl.order_id,sl.drug_id,sl.batch_id,sl.batch_no,sl.qty,sl.price,sl.line_amount,sl.cost_price,sl.location_id ' +
    'FROM ph_sale_order_line sl JOIN ph_sale_order so ON so.id=sl.order_id AND so.tenant_id=sl.tenant_id ' +
    'WHERE so.wx_order_id=' + id + ' AND so.tenant_id=' + cfg.tenantId + ' ORDER BY sl.id');
  const salePayments = mysqlRows('sale-payments',
    'SELECT sp.id,sp.order_id,sp.pay_no,sp.pay_method,sp.pay_amount,sp.channel,sp.status,sp.payment_no,sp.pay_order_id,' +
    'sp.paid_at,(sp.paid_at=p.success_time) AS paid_time_matches,p.channel_order_no ' +
    'FROM ph_sale_payment sp JOIN ph_sale_order so ON so.id=sp.order_id ' +
    'LEFT JOIN pay_order p ON p.id=sp.pay_order_id ' +
    'WHERE so.wx_order_id=' + id + ' AND so.tenant_id=' + cfg.tenantId + ' ORDER BY sp.id');
  const flows = mysqlRows('inventory-flows',
    'SELECT id,store_id,batch_id,drug_id,batch_no,flow_type,in_qty,out_qty,balance_qty,biz_type,biz_no,' +
    'biz_line_id,location_id,frozen_delta,unit_cost FROM ph_inv_flow WHERE tenant_id=' + cfg.tenantId +
    ' AND store_id=' + cfg.storeId + ' AND drug_id=' + cfg.drugId + ' AND biz_no=\'' + orderNo +
    '\' AND deleted=0 ORDER BY id');
  const pointRecords = mysqlRows('point-records',
    'SELECT id,user_id,biz_id,biz_type,point,total_point FROM member_point_record WHERE user_id=' + memberId +
    ' AND biz_id=\'' + orderNo + '\' ORDER BY id');
  const member = mysqlRows('member-points',
    'SELECT id,point FROM member_user WHERE id=' + memberId + ' AND tenant_id=' + cfg.tenantId + ' AND deleted=0');
  return { order, lines, payOrder, sales, saleLines, salePayments, flows, pointRecords, member, stock: stockRows() };
}

function assertStable(before, after, label) {
  if (JSON.stringify(before) !== JSON.stringify(after)) fail(label + ': duplicate call changed audited business rows');
}

function isSaleStockOut(row) {
  const flowType = intCell(row, 5);
  const bizType = intCell(row, 9);
  return intCell(row, 6) === 0 &&
    ((flowType === 20 && bizType === 2) || (flowType === 82 && bizType === 6));
}

function validateFinalAudit(before, finalAudit) {
  const order = finalAudit.order[0];
  const paymentId = canonicalPaymentId(order);
  if (finalAudit.lines.length !== 1 || intCell(finalAudit.lines[0], 2) !== cfg.drugId || intCell(finalAudit.lines[0], 3) !== 1) {
    fail('final audit: expected exactly one OTC order line with quantity one');
  }
  if (intCell(order, 3) !== cfg.storeId || intCell(order, 4) !== 0 || intCell(order, 5) !== 4 || intCell(order, 6) !== 1) {
    fail('final audit: order is not completed paid pickup');
  }
  if (intCell(order, 2) !== summary.memberId || intCell(order, 8) !== 0 || Number(order[9]) !== 0 ||
      !order[14] || !order[15]) fail('final audit: order owner, zero deduction, or paid/finish timestamps mismatch');
  if (finalAudit.payOrder.length !== 1 || finalAudit.payOrder[0][1] !== 'mock' ||
      intCell(finalAudit.payOrder[0], 6) !== 10 || finalAudit.payOrder[0][2] !== String(summary.memberId) ||
      intCell(finalAudit.payOrder[0], 3) !== 1 || intCell(finalAudit.payOrder[0], 7) !== 0 ||
      !finalAudit.payOrder[0][8] || !finalAudit.payOrder[0][9]) {
    fail('final audit: expected one successful, non-refunded mock member pay order');
  }
  if (moneyMinor(order[11]) !== intCell(finalAudit.payOrder[0], 5) ||
      finalAudit.payOrder[0][0] !== paymentId ||
      finalAudit.payOrder[0][4] !== order[1]) fail('final audit: pay amount or order association mismatch');
  if (finalAudit.sales.length !== 1 || intCell(finalAudit.sales[0], 4) !== orderId ||
      intCell(finalAudit.sales[0], 2) !== cfg.storeId || intCell(finalAudit.sales[0], 3) !== summary.memberId ||
      finalAudit.sales[0][1] !== 'WXSO-' + orderId ||
      intCell(finalAudit.sales[0], 5) !== 1 || intCell(finalAudit.sales[0], 11) !== 1 ||
      intCell(finalAudit.sales[0], 12) !== 1 || finalAudit.saleLines.length !== 1 ||
      finalAudit.saleLines[0][1] !== String(finalAudit.sales[0][0]) ||
      intCell(finalAudit.saleLines[0], 2) !== cfg.drugId || intCell(finalAudit.saleLines[0], 5) !== 1) {
    fail('final audit: expected one completed online sale header and one matching sale line');
  }
  if (finalAudit.salePayments.length !== 1 || finalAudit.salePayments[0][1] !== String(finalAudit.sales[0][0]) ||
      finalAudit.salePayments[0][8] !== paymentId || finalAudit.salePayments[0][5] !== 'mock' ||
      intCell(finalAudit.salePayments[0], 3) !== 2 || intCell(finalAudit.salePayments[0], 6) !== 1 ||
      finalAudit.salePayments[0][2] !== finalAudit.payOrder[0][9] ||
      finalAudit.salePayments[0][7] !== 'WXSO-PAY-' + orderId ||
      intCell(finalAudit.salePayments[0], 10) !== 1 || finalAudit.salePayments[0][11] !== finalAudit.payOrder[0][9] ||
      moneyMinor(finalAudit.salePayments[0][4]) !== intCell(finalAudit.payOrder[0], 5)) {
    fail('final audit: expected one successful mock sale payment linked by order and amount');
  }
  const stockOut = finalAudit.flows.filter(isSaleStockOut);
  const outQty = stockOut.reduce((sum, row) => sum + intCell(row, 7), 0);
  if (outQty !== 1 || stockOut.length !== 1) fail('final audit: expected one unit in the online-order sale stock flow');
  if (finalAudit.payOrder[0][0] !== paymentId ||
      finalAudit.payOrder[0][4] !== order[1]) fail('final audit: pay order merchant and pharmacy-order links mismatch');
  if (moneyMinor(finalAudit.sales[0][8]) !== intCell(finalAudit.payOrder[0], 5)) {
    fail('final audit: sale paid amount differs from mock pay order');
  }
  if (moneyMinor(finalAudit.sales[0][7]) !== moneyMinor(order[11])) {
    fail('final audit: sale payable amount differs from pharmacy order');
  }
  if (finalAudit.saleLines[0][3] !== stockOut[0][2] ||
      costTenThousandths(finalAudit.saleLines[0][8]) !== costTenThousandths(stockOut[0][14])) {
    fail('final audit: sale batch or cost snapshot differs from stock outflow');
  }
  if (finalAudit.member.length !== 1 || before.member.length !== 1) fail('final audit: member point row missing');
  const pointDelta = intCell(finalAudit.member[0], 1) - intCell(before.member[0], 1);
  const recordedPoints = finalAudit.pointRecords.reduce((sum, row) => sum + intCell(row, 4), 0);
  if (pointDelta !== recordedPoints) fail('final audit: point balance delta does not match order point records');
  const earnRecords = finalAudit.pointRecords.filter(row => intCell(row, 3) === 2);
  const deductRecords = finalAudit.pointRecords.filter(row => intCell(row, 3) === 3);
  const expectedEarn = summary.expectedEarn.points;
  if (Number(order[10] || 0) !== expectedEarn || Number(finalAudit.sales[0][10] || 0) !== expectedEarn ||
      deductRecords.length !== 0 ||
      (expectedEarn === 0 && earnRecords.length !== 0) ||
      (expectedEarn > 0 && (earnRecords.length !== 1 || intCell(earnRecords[0], 1) !== summary.memberId ||
        earnRecords[0][2] !== order[1] || intCell(earnRecords[0], 4) !== expectedEarn ||
        intCell(earnRecords[0], 5) !== intCell(finalAudit.member[0], 1)))) {
    fail('final audit: expected earn points, order/sale totals, and CONSUME_EARN record mismatch');
  }
  const beforeStock = inventoryTotals(before.stock);
  const afterStock = inventoryTotals(finalAudit.stock);
  if (beforeStock.totalQty - afterStock.totalQty !== 1 || beforeStock.locationQty - afterStock.locationQty !== 1 ||
      afterStock.qtySold - beforeStock.qtySold !== 1) {
    fail('final audit: inventory batch/location totals do not show exactly one sale');
  }
  summary.finalAssertions = {
    completedPaidPickup: true,
    oneOrderLine: true,
    oneMockPayOrder: true,
    oneSaleHeaderLineAndPayment: true,
    inventoryOutQty: outQty,
    inventoryBefore: beforeStock,
    inventoryAfter: afterStock,
    pointsBefore: intCell(before.member[0], 1),
    pointsAfter: intCell(finalAudit.member[0], 1),
    pointRecordsNet: recordedPoints,
    expectedEarnPoints: expectedEarn,
    expectedEarnReason: summary.expectedEarn.reason,
    earnRecordCount: earnRecords.length,
    saleLineBatchId: finalAudit.saleLines[0][3],
    saleLineBatchNo: finalAudit.saleLines[0][4],
    saleLineCostPrice: finalAudit.saleLines[0][8],
    stockFlowUnitCost: stockOut[0][14]
  };
}

function markReplayOnce(root, runId, permitId) {
  const marker = path.join(root, 'reconcile-replay-' + runId + '.started');
  let handle;
  try { handle = fs.openSync(marker, 'wx', 0o600); }
  catch { fail('reconciliation repeat verify already attempted'); }
  try {
    fs.writeFileSync(handle, JSON.stringify({ runId, permitId, at: new Date().toISOString() }) + '\n');
  } finally {
    fs.closeSync(handle);
  }
}

async function reconcileExisting(runConfig, original, reportDir, root, replay) {
  cfg = {
    runId: original.runId,
    appBase: runConfig.appBaseUrl, adminBase: runConfig.adminBaseUrl,
    tenantId: runConfig.fixture.tenantId, storeId: runConfig.fixture.storeId,
    drugId: runConfig.fixture.drugId, privateDir: reportDir,
    mysqlCli: runConfig.mysql.cli, mysqlDefaults: runConfig.mysql.defaultsExtraFile,
    mysqlHost: runConfig.mysql.host, mysqlPort: runConfig.mysql.port,
    schema: runConfig.mysql.schema, expectedUuid: runConfig.mysql.expectedUuid,
    authHeader: 'Authorization', tenantHeader: 'tenant-id'
  };
  orderId = original.orderId;
  summary = {
    runId: original.runId, source: 'existing-order-reconciliation',
    orderId, memberId: original.memberId, expectedEarn: original.expectedEarn,
    startedAt: new Date().toISOString(), phases: {}
  };
  try {
    markStep('reconcile.final-audit');
    const before = readBusinessAudit(original.memberId, orderId);
    const order = before.order[0];
    if (Number(order[0]) !== orderId || Number(order[2]) !== original.memberId ||
        Number(order[3]) !== cfg.storeId || Number(order[4]) !== 0 ||
        Number(order[5]) !== 4 || Number(order[6]) !== 1 ||
        order[1] !== original.orderNo || order[16] !== original.runId ||
        typeof order[17] !== 'string' || !order[17].trim() ||
        moneyMinor(order[11]) !== moneyMinor(original.amount) ||
        moneyMinor(order[11]) !== moneyMinor(original.expectedEarn.payableAmount)) {
      fail('reconciliation: completed order identity or amount mismatch');
    }
    const recalculated = expectedEarnPoints(order[11], original.pointRule);
    if (recalculated.points !== original.expectedEarn.points ||
        recalculated.reason !== original.expectedEarn.reason) {
      fail('reconciliation: original point-rule calculation mismatch');
    }
    validateFinalAudit({
      stock: original.baseline.stock,
      member: [[String(original.memberId), String(original.baseline.memberPoint)]]
    }, before);
    summary.phases.finalAudit = 'passed';
    saveSummary();
    if (!replay) return summary.finalAssertions;

    markStep('reconcile.staff-login');
    const login = await callApi('reconcile.staff.login', cfg.adminBase, '/system/auth/login', {
      method: 'POST', body: {
        username: runConfig.fixture.adminUsername,
        password: runConfig.fixture.adminPassword
      }
    });
    if (!login.data?.accessToken || Number(login.data.userId) !== 95111) {
      fail('reconciliation: expected synthetic store staff login');
    }
    staffToken = login.data.accessToken;
    markStep('reconcile.verify-repeat');
    markReplayOnce(root, original.runId, runConfig.executionPermit.id);
    const response = await callApi('reconcile.verify.repeat-once',
      cfg.adminBase, '/pharmacy/member/order/verify', {
        method: 'PUT', token: staffToken,
        params: { id: orderId, pickupCode: order[17] }
      });
    if (response.data !== true) fail('reconciliation: repeat verify did not return true');
    assertStable(before, readBusinessAudit(original.memberId, orderId),
      'reconciliation repeat completed-order verify');
    summary.phases.completionRepeat = 'one-call-state-stable';
    summary.finishedAt = new Date().toISOString();
    saveSummary();
    return summary.finalAssertions;
  } catch (error) {
    summary.failure = {
      step: currentStep,
      message: error.safe ? error.message : 'reconciliation unexpected failure'
    };
    saveSummary();
    error.stage = currentStep;
    throw error;
  }
}

async function prepareCart() {
  const initial = await callApi('cart.read.before', cfg.appBase, '/member/wx-cart/list', { token: memberToken });
  if (!Array.isArray(initial.data)) fail('cart read did not return a list');
  const rows = initial.data.filter(row => Number(row.storeId) === cfg.storeId);
  const targetRows = rows.filter(row => Number(row.drugId) === cfg.drugId);
  if (targetRows.length > 1) fail('multiple existing cart rows for selected store and drug');
  for (const row of rows) {
    if (Number(row.drugId) !== cfg.drugId && Number(row.selectedFlag) === 1) {
      await callApi('cart.unselect.other-current-store-row', cfg.appBase, '/member/wx-cart/update-selected', {
        method: 'PUT', token: memberToken,
        params: { id: positiveInt(row.id, 'cart row id'), selectedFlag: 0 }
      });
    }
  }
  if (targetRows.length === 0) {
    await callApi('cart.add.target', cfg.appBase, '/member/wx-cart/add', {
      method: 'POST', token: memberToken,
      body: { drugId: cfg.drugId, qty: 1, storeId: cfg.storeId }
    });
  } else {
    const row = targetRows[0];
    const cartId = positiveInt(row.id, 'target cart row id');
    if (Number(row.qty) !== 1) {
      await callApi('cart.set.target-qty-one', cfg.appBase, '/member/wx-cart/update-qty', {
        method: 'PUT', token: memberToken, body: { id: cartId, qty: 1 }
      });
    }
    if (Number(row.selectedFlag) !== 1) {
      await callApi('cart.select.target', cfg.appBase, '/member/wx-cart/update-selected', {
        method: 'PUT', token: memberToken, params: { id: cartId, selectedFlag: 1 }
      });
    }
  }
  const verified = await callApi('cart.read.after', cfg.appBase, '/member/wx-cart/list', { token: memberToken });
  const selected = verified.data.filter(row => Number(row.storeId) === cfg.storeId && Number(row.selectedFlag) === 1);
  if (selected.length !== 1 || Number(selected[0].drugId) !== cfg.drugId || Number(selected[0].qty) !== 1) {
    fail('cart precondition failed: current store must have only the target quantity-one line selected');
  }
}

async function execute() {
  cfg = loadConfig();
  assertRunnerDispatch(cfg.privateDir, cfg.runId, need('OTC_PERMIT_ID'));
  const markerPath = path.join(cfg.privateDir, cfg.runId + '.order-create.started');
  if (fs.existsSync(markerPath)) fail('run id already has an order-create attempt; refusing replay');
  summary = {
    runId: cfg.runId,
    schema: cfg.schema,
    expectedMysqlUuid: cfg.expectedUuid,
    tenantId: cfg.tenantId,
    storeId: cfg.storeId,
    drugId: cfg.drugId,
    startedAt: new Date().toISOString(),
    phases: {}
  };

  markStep('preflight.member-existing');
  const mobileLiteral = cfg.memberMobile;
  const memberRows = mysqlRows('member-preflight',
    'SELECT id,point FROM member_user WHERE tenant_id=' + cfg.tenantId + ' AND mobile=\'' + mobileLiteral + '\' AND deleted=0');
  if (memberRows.length !== 1) fail('preflight: expected one existing synthetic member in clone');
  const memberId = positiveInt(memberRows[0][0], 'member id');
  summary.memberId = memberId;

  markStep('preflight.synthetic-otc-and-stock');
  const drugRows = mysqlRows('drug-preflight',
    'SELECT id,drug_type,is_rx,status,approve_status,saleable_online FROM ph_drug WHERE tenant_id=' +
    cfg.tenantId + ' AND id=' + cfg.drugId + ' AND deleted=0');
  if (drugRows.length !== 1 || ![1, 2].includes(intCell(drugRows[0], 1)) || intCell(drugRows[0], 2) !== 0 ||
      intCell(drugRows[0], 3) !== 1 || intCell(drugRows[0], 4) !== 1 || intCell(drugRows[0], 5) !== 1) {
    fail('preflight: selected product is not an active approved online OTC non-prescription drug');
  }
  const baselineStock = stockRows();
  if (inventoryTotals(baselineStock).locationAvailable < 1) fail('preflight: selected store has no available unit');
  const baselineMember = mysqlRows('points-before',
    'SELECT id,point FROM member_user WHERE id=' + memberId + ' AND tenant_id=' + cfg.tenantId + ' AND deleted=0');
  if (baselineMember.length !== 1) fail('preflight: member point balance row missing');
  const existingRunOrder = mysqlRows('run-id-order-preflight',
    'SELECT id FROM ph_wx_order WHERE tenant_id=' + cfg.tenantId + ' AND remark=\'' + cfg.runId + '\'');
  if (existingRunOrder.length !== 0) fail('preflight: run id already appears in an order');
  summary.baseline = { stock: baselineStock, stockTotals: inventoryTotals(baselineStock), memberPoint: baselineMember[0][1] };
  summary.phases.preflight = 'passed';
  saveSummary();

  markStep('auth.member');
  await callApi('member.send-local-dev-code', cfg.appBase, '/member/auth/send-sms-code', {
    method: 'POST', params: { mobile: cfg.memberMobile }
  });
  const memberLogin = await callApi('member.login-existing', cfg.appBase, '/member/auth/login-or-register', {
    method: 'POST', params: { mobile: cfg.memberMobile }, headers: { [cfg.smsHeader]: cfg.devCode }
  });
  if (!memberLogin.data || String(memberLogin.data.userId) !== String(memberId) || !memberLogin.data.accessToken) {
    fail('member login did not match the preflighted existing member');
  }
  memberToken = memberLogin.data.accessToken;

  markStep('member.point-rule-summary');
  const pointSummary = await callApi('member.point-rule-summary', cfg.appBase, '/member/point/summary', {
    token: memberToken
  });
  if (!pointSummary.data || typeof pointSummary.data.earnEnabled !== 'boolean' ||
      Number(pointSummary.data.memberPoint) !== intCell(baselineMember[0], 1)) {
    fail('member point summary did not match the preflight balance or runtime rule shape');
  }
  summary.pointRule = {
    memberPoint: Number(pointSummary.data.memberPoint),
    earnEnabled: pointSummary.data.earnEnabled,
    earnPerYuan: pointSummary.data.earnPerYuan,
    levelMultiplier: pointSummary.data.levelMultiplier
  };
  if (summary.pointRule.earnEnabled &&
      (summary.pointRule.earnPerYuan === null || summary.pointRule.earnPerYuan === undefined ||
       summary.pointRule.levelMultiplier === null || summary.pointRule.levelMultiplier === undefined)) {
    fail('member point summary omitted an enabled earning-rule value');
  }
  summary.phases.pointRule = 'captured-from-runtime';
  saveSummary();

  markStep('auth.staff');
  const staffLogin = await callApi('staff.login', cfg.adminBase, '/system/auth/login', {
    method: 'POST', body: { username: cfg.adminUsername, password: cfg.adminPassword }
  });
  if (!staffLogin.data || !staffLogin.data.accessToken) fail('staff login response missing access token');
  staffToken = staffLogin.data.accessToken;

  markStep('payment-capability');
  const capability = await callApi('mock-payment.capability', cfg.appBase, '/member/wx-order/mock-payment-available', {
    token: memberToken
  });
  if (capability.data !== true) fail('mock-payment capability is not strictly true');

  markStep('cart.prepare');
  await prepareCart();
  summary.phases.cart = 'target-only-selected';
  saveSummary();

  markStep('order.create');
  const marker = fs.openSync(markerPath, 'wx', 0o600);
  fs.writeFileSync(marker, JSON.stringify({ runId: cfg.runId, startedAt: new Date().toISOString() }));
  fs.closeSync(marker);
  const createResult = await callApi('order.create.once', cfg.appBase, '/member/wx-order/create', {
    method: 'POST', token: memberToken,
    body: { storeId: cfg.storeId, orderType: 0, remark: cfg.runId, usePoints: 0 }
  });
  orderId = positiveInt(createResult.data, 'created order id');
  summary.orderId = orderId;
  summary.phases.orderCreate = 'confirmed';
  saveSummary();

  markStep('order.inspect-created');
  let order = (await callApi('order.get.created', cfg.appBase, '/member/wx-order/get', {
    token: memberToken, params: { id: orderId }
  })).data;
  if (!order || Number(order.id) !== orderId || Number(order.storeId) !== cfg.storeId ||
      Number(order.orderType) !== 0 || Number(order.status) !== 0 || Number(order.payStatus) !== 0 ||
      order.remark !== cfg.runId || !order.pickupCode || Number(order.pointDeduct || 0) !== 0 ||
      Number(order.pointDeductAmount || 0) !== 0) fail('created order did not match the requested zero-deduction pickup order');
  pickupCode = order.pickupCode;
  summary.orderNo = order.orderNo;
  summary.amount = order.payableAmount;
  summary.expectedEarn = Object.assign({
    payableAmount: order.payableAmount,
    earnEnabled: summary.pointRule.earnEnabled,
    earnPerYuan: summary.pointRule.earnPerYuan,
    levelMultiplier: summary.pointRule.levelMultiplier
  }, expectedEarnPoints(order.payableAmount, summary.pointRule));
  summary.phases.orderCreated = 'wait-pay-pickup-qty-one-requested';
  saveSummary();

  markStep('payment.first');
  const payment = await callApi('payment.simulate.once', cfg.appBase, '/member/wx-order/simulate-pay', {
    method: 'POST', token: memberToken, params: { id: orderId }
  });
  if (payment.data !== true) fail('first mock payment did not return true');
  order = (await callApi('order.get.paid', cfg.appBase, '/member/wx-order/get', {
    token: memberToken, params: { id: orderId }
  })).data;
  if (!order || Number(order.payStatus) !== 1 || Number(order.status) !== 1) fail('order did not reach paid/wait-picking state');
  const paymentSnapshot = readBusinessAudit(memberId, orderId);
  summary.paymentSnapshot = paymentSnapshot;
  saveSummary();

  markStep('payment.repeat-once');
  const beforePayRepeat = readBusinessAudit(memberId, orderId);
  const duplicatePayment = await callApi('payment.simulate.repeat-once', cfg.appBase, '/member/wx-order/simulate-pay', {
    method: 'POST', token: memberToken, params: { id: orderId }
  });
  if (duplicatePayment.data !== true) fail('repeat payment did not return true');
  summary.duplicatePayment = { httpStatus: duplicatePayment.httpStatus, commonCode: duplicatePayment.code };
  assertStable(beforePayRepeat, readBusinessAudit(memberId, orderId), 'repeat payment');
  summary.phases.paymentRepeat = 'one-call-state-stable';
  saveSummary();

  markStep('staff.start-picking');
  const started = await callApi('staff.start-picking', cfg.adminBase, '/pharmacy/member/order/start-picking', {
    method: 'PUT', token: staffToken, params: { id: orderId }
  });
  if (started.data !== true) fail('start-picking did not return true');
  markStep('staff.finish-picking');
  const picked = await callApi('staff.finish-picking', cfg.adminBase, '/pharmacy/member/order/finish-picking', {
    method: 'PUT', token: staffToken, params: { id: orderId }
  });
  if (picked.data !== true) fail('finish-picking did not return true');
  order = (await callApi('order.get.wait-verify', cfg.appBase, '/member/wx-order/get', {
    token: memberToken, params: { id: orderId }
  })).data;
  if (!order || Number(order.status) !== 3 || !order.pickupCode) fail('order did not reach paid/wait-verify state');
  pickupCode = order.pickupCode;

  markStep('staff.verify');
  const verified = await callApi('staff.verify.once', cfg.adminBase, '/pharmacy/member/order/verify', {
    method: 'PUT', token: staffToken, params: { id: orderId, pickupCode }
  });
  if (verified.data !== true) fail('pickup verification did not return true');
  order = (await callApi('order.get.completed', cfg.appBase, '/member/wx-order/get', {
    token: memberToken, params: { id: orderId }
  })).data;
  if (!order || Number(order.status) !== 4 || Number(order.payStatus) !== 1 ||
      Number(order.pointEarned || 0) !== summary.expectedEarn.points) {
    fail('order did not reach completed/paid state with the rule-calculated earned points');
  }
  const finalAudit = readBusinessAudit(memberId, orderId);
  validateFinalAudit({ stock: baselineStock, member: baselineMember }, finalAudit);
  summary.finalAudit = finalAudit;
  summary.phases.fulfillmentAndAccounting = 'passed';
  saveSummary();

  markStep('staff.verify.repeat-once');
  const beforeVerifyRepeat = readBusinessAudit(memberId, orderId);
  const duplicateVerify = await callApi('staff.verify.repeat-once', cfg.adminBase, '/pharmacy/member/order/verify', {
    method: 'PUT', token: staffToken, params: { id: orderId, pickupCode }
  });
  if (duplicateVerify.data !== true) fail('repeat verify did not return true');
  summary.duplicateVerify = { httpStatus: duplicateVerify.httpStatus, commonCode: duplicateVerify.code };
  assertStable(beforeVerifyRepeat, readBusinessAudit(memberId, orderId), 'repeat completed-order verify');
  summary.phases.completionRepeat = 'one-call-state-stable';
  summary.finishedAt = new Date().toISOString();
  summary.result = 'PASS_SCOPED_LOCAL_HTTP_AND_MYSQL';
  saveSummary();
  process.stdout.write(JSON.stringify({
    result: summary.result, runId: cfg.runId, schema: cfg.schema, orderId,
    amount: summary.amount, orderStatus: 4, payStatus: 1,
    inventoryOutQty: summary.finalAssertions.inventoryOutQty,
    pointDelta: summary.finalAssertions.pointRecordsNet,
    duplicatePayment: summary.duplicatePayment,
    duplicateVerify: summary.duplicateVerify,
    summaryFile: path.join(cfg.privateDir, cfg.runId + '.summary.json')
  }) + '\n');
}

if (require.main === module) {
  try {
    execute().catch(error => {
      if (summary) {
        summary.failure = { step: currentStep, message: error.safe ? error.message : currentStep + ': unexpected failure' };
        summary.finishedAt = new Date().toISOString();
        saveSummary();
      }
      process.stderr.write(JSON.stringify({ result: 'STOPPED', step: currentStep,
        reason: error.safe ? error.message : currentStep + ': unexpected failure' }) + '\n');
      process.exitCode = 1;
    });
  } catch (error) {
    process.stderr.write(JSON.stringify({ result: 'STOPPED', step: currentStep,
      reason: error.safe ? error.message : currentStep + ': unexpected failure' }) + '\n');
    process.exitCode = 1;
  }
}

module.exports = {
  assertCommonResult, assertRunnerDispatch, mysqlCell, canonicalPaymentId,
  isSaleStockOut, markReplayOnce, reconcileExisting
};
