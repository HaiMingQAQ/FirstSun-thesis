const path = require('node:path')
const { pathToFileURL } = require('node:url')
const { test, expect } = require('@playwright/test')
const { loadConfig } = require('../src/config.cjs')
const { CustomerPage, AdminPage } = require('../src/pages.cjs')
const { captureSession, apiCall, assertOrder } = require('../src/api.cjs')
const { createSqlAccess } = require('../src/sql.cjs')
const { writeJson, safeError } = require('../src/runtime.cjs')
const { runReadiness, loadAdapter } = require('../src/readiness.cjs')

const config = loadConfig(process.env.E2E_ENV_FILE)
const runDir = process.env.E2E_RUN_DIR
const relativeRunDir = runDir && path.relative(config.artifactRoot, runDir)
if (!runDir || !path.isAbsolute(runDir) || !relativeRunDir || relativeRunDir.startsWith('..') || path.isAbsolute(relativeRunDir)) {
  throw new Error('E2E_RUN_DIR must be under the configured external evidence root')
}

const stages = []
let flow = { result: 'FAIL', orderId: null, orderNo: null, error: null, stages }
let baselinePayableFen
function checkpoint(name, detail = {}) { stages.push({ name, pass: true, ...detail }) }
function formattedYuan(value) { return Number(value).toFixed(2) }
function assertStage(result, stage, requiredChecks) {
  if (result?.stage !== stage || result.pass !== true || !Array.isArray(result.checks)) throw new Error(`SQL reconciliation failed at ${stage}`)
  const ids = new Set(result.checks.filter(check => check?.pass === true).map(check => check.id))
  if (requiredChecks.some(id => !ids.has(id))) throw new Error(`SQL reconciliation at ${stage} omitted required ledger checks`)
}

test('one ordinary OTC pickup order crosses customer/admin UI and same-order idempotency replay', async ({ browser }) => {
  const ready = await runReadiness(config, { runDir, createEvidence: false })
  if (!ready.ok) throw new Error('PRECHECK_BLOCKED: repeated runner readiness failed before business writes')
  const customerContext = await browser.newContext()
  const adminContext = await browser.newContext()
  const customerPage = await customerContext.newPage()
  const adminPage = await adminContext.newPage()
  customerPage.setDefaultTimeout(config.timeoutMs)
  adminPage.setDefaultTimeout(config.timeoutMs)
  customerPage.setDefaultNavigationTimeout(30_000)
  adminPage.setDefaultNavigationTimeout(30_000)
  let createRequests = 0
  customerPage.on('request', request => {
    if (new URL(request.url()).pathname.endsWith('/app-api/member/wx-order/create') && request.method() === 'POST') createRequests++
  })
  const customerSession = captureSession(customerPage, '/app-api/', config.customerUrl)
  const adminSession = captureSession(adminPage, '/admin-api/', config.adminUrl)
  const Customer = new CustomerPage(customerPage, config)
  const Admin = new AdminPage(adminPage, config)
  let sql
  let order
  let orderId
  let orderNo
  let pickupCode
  let failed
  let tracing = false

  try {
    await Customer.login()
    const existingCart = await apiCall(config, customerSession, 'GET', '/app-api/member/wx-cart/list')
    if (!Array.isArray(existingCart) || existingCart.length !== 0) throw new Error('Customer cart is not empty; refusing to touch pre-existing cart rows')
    const capability = await apiCall(config, customerSession, 'GET', '/app-api/member/wx-order/mock-payment-available')
    if (capability !== true) throw new Error('Backend mock payment capability is not enabled')
    await Admin.login()
    await Admin.openOrderList()
    const adapter = await loadAdapter(config)
    sql = await createSqlAccess(config, adapter)
    await customerContext.tracing.start({ screenshots: true, snapshots: true, sources: false })
    await adminContext.tracing.start({ screenshots: true, snapshots: true, sources: false })
    tracing = true
    checkpoint('customer-login-and-safe-preconditions', { mockPaymentAvailable: true, emptyCart: true })

    await Customer.addFixture()
    const cartRow = customerPage.locator(`.purchase-item[data-drug-id="${config.fixtureDrugId}"]`)
    await expect(customerPage.locator('.purchase-item')).toHaveCount(1)
    await expect(cartRow).toHaveCount(1)
    await expect(cartRow).toHaveAttribute('data-store-id', String(config.storeId))
    await expect(cartRow.getByRole('checkbox')).toBeChecked()
    const initialQty = Number(await cartRow.locator('output').innerText())
    if (initialQty !== 1) throw new Error('New fixture cart row did not start with quantity one')
    for (let n = initialQty; n < config.quantity; n++) {
      await cartRow.getByRole('button', { name: `增加${config.fixtureDrugName}数量` }).click()
      await expect(cartRow.locator('output')).toHaveText(String(n + 1))
    }
    await expect(cartRow.locator('output')).toHaveText(String(config.quantity))
    checkpoint('add-to-cart-and-store-pickup-checkout', { drugId: config.fixtureDrugId, quantity: config.quantity })

    orderId = await Customer.createPickupOrder()
    if (createRequests !== 1) throw new Error('Exactly one UI create request is required; no automatic create retry is permitted')
    await expect(customerPage.locator('.order-status')).toBeVisible({ timeout: 30_000 })
    const routedId = Number(new URLSearchParams((new URL(customerPage.url()).hash.split('?')[1] || '')).get('id'))
    if (routedId !== orderId) throw new Error('Customer detail route does not match the one create response')
    order = await apiCall(config, customerSession, 'GET', `/app-api/member/wx-order/get?id=${orderId}`)
    order = assertOrder(order, { orderId, tenantId: config.tenantId, storeId: config.storeId, status: 0, payStatus: 0, orderType: 0 })
    orderNo = order.orderNo
    baselinePayableFen = toFen(order.payableAmount)
    if (typeof orderNo !== 'string' || !orderNo.trim()) throw new Error('Created order number is missing')
    if (!Array.isArray(order.lines) || order.lines.length !== 1 || Number(order.lines[0].drugId) !== config.fixtureDrugId || Number(order.lines[0].qty) !== config.quantity) throw new Error('Created order lines do not match the one configured OTC fixture')
    await expect(customerPage.locator('.order-status')).toContainText(orderNo)
    await expect(customerPage.locator('.actual-totals')).toContainText(`¥${formattedYuan(order.payableAmount)}`)
    await capture(customerPage, 'customer-created')
    await reconcile(sql, adapter, 'afterCreate', order, config, ['order', 'orderLines', 'cartCleared'])
    checkpoint('created-once-and-server-amount-readback', { orderId, orderNo, payableAmountYuan: formattedYuan(order.payableAmount), status: 0, payStatus: 0, sql: true })

    await Customer.pay()
    order = await waitForCustomerOrder(config, customerSession, orderId, orderNo, 1, 1)
    await Customer.reloadOrder()
    await expect(customerPage.locator('.order-status')).toContainText('待拣货')
    await capture(customerPage, 'customer-paid')
    await reconcile(sql, adapter, 'afterMockPay', order, config, ['order', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'saleAbsent'])
    checkpoint('mock-paid-and-ledger-reconciled', { status: 1, payStatus: 1, sql: true })

    let row = await Admin.findOrder(orderNo)
    let adminOrder = await readAdminOrder(config, adminSession, orderId)
    assertSameOrder(adminOrder, order, config, 1, 1)
    await Admin.confirmPicking(row, '开始拣货', orderNo)
    adminOrder = await waitForAdminOrder(config, adminSession, orderId, orderNo, config.storeId, 2, 1)
    order = await waitForCustomerOrder(config, customerSession, orderId, orderNo, 2, 1)
    await capture(adminPage, 'admin-picking-started')
    await reconcile(sql, adapter, 'afterStartPicking', order, config, ['order', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'saleAbsent'])
    checkpoint('admin-start-picking-and-ledger-reconciled', { status: 2, payStatus: 1, sql: true })

    row = await Admin.findOrder(orderNo)
    await Admin.confirmPicking(row, '完成拣货', orderNo)
    adminOrder = await waitForAdminOrder(config, adminSession, orderId, orderNo, config.storeId, 3, 1)
    order = await waitForCustomerOrder(config, customerSession, orderId, orderNo, 3, 1)
    await Customer.reloadOrder()
    await expect(customerPage.locator('.order-status')).toContainText('待自提')
    pickupCode = (await customerPage.locator('.pickup-code').innerText()).trim()
    if (!pickupCode || pickupCode === '服务端未提供') throw new Error('Customer UI did not expose the server pickup code')
    await capture(customerPage, 'customer-ready-for-pickup')
    await capture(adminPage, 'admin-picking-finished')
    await reconcile(sql, adapter, 'afterFinishPicking', order, config, ['order', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'saleAbsent'])
    checkpoint('admin-finish-picking-and-customer-readback', { status: 3, payStatus: 1, pickupCodePresent: true, sql: true })

    row = await Admin.findOrder(orderNo)
    await Admin.verify(row, pickupCode)
    adminOrder = await waitForAdminOrder(config, adminSession, orderId, orderNo, config.storeId, 4, 1)
    order = await waitForCustomerOrder(config, customerSession, orderId, orderNo, 4, 1)
    await Customer.reloadOrder()
    await expect(customerPage.locator('.order-status')).toContainText('已完成')
    if (!order.finishAt) throw new Error('Completed order has no server finishAt')
    await capture(customerPage, 'customer-completed')
    await capture(adminPage, 'admin-completed')
    const identity = { tenantId: config.tenantId, storeId: config.storeId, orderId, orderNo }
    await reconcile(sql, adapter, 'afterVerify', order, config, ['order', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'sale', 'salePayment', 'points', 'notification'])
    checkpoint('admin-verify-and-both-ui-terminal-readback', { status: 4, payStatus: 1, finishAtPresent: true, sql: true })

    const before = await snapshotAll(sql, adapter, identity)
    validateSnapshot(before)
    checkpoint('full-ledger-snapshot-before-replay')

    customerSession.replayOrderId = orderId
    adminSession.replayOrderId = orderId
    const payReplay = await apiCall(config, customerSession, 'POST', `/app-api/member/wx-order/simulate-pay?id=${orderId}`)
    if (payReplay !== true) throw new Error('Same-order mock payment replay did not follow the current service no-op success contract')
    const verifyReplay = await apiCall(config, adminSession, 'PUT', `/admin-api/pharmacy/member/order/verify?id=${orderId}&pickupCode=${encodeURIComponent(pickupCode)}`)
    if (verifyReplay !== true) throw new Error('Same-order completed pickup verification replay did not follow the current service no-op success contract')
    const after = await snapshotAll(sql, adapter, identity)
    validateSnapshot(after)
    if (canonical(before) !== canonical(after)) throw new Error('Full ledger snapshot changed after same-order payment/verification replay')
    await reconcile(sql, adapter, 'afterVerify', order, config, ['order', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'sale', 'salePayment', 'points', 'notification'])
    await Customer.reloadOrder()
    await expect(customerPage.locator('.order-status')).toContainText('已完成')
    await Admin.openOrderList()
    row = await Admin.findOrder(orderNo)
    adminOrder = await readAdminOrder(config, adminSession, orderId)
    assertSameOrder(adminOrder, order, config, 4, 1)
    await capture(customerPage, 'customer-completed-after-replay')
    await capture(adminPage, 'admin-completed-after-replay')
    checkpoint('same-order-http-replay-stable-ledgers-and-refreshed-uis')
    if (createRequests !== 1) throw new Error('Unexpected second create request was observed')
    flow = { result: 'FLOW_PASS_RECONCILED', orderId, orderNo, payableAmountYuan: formattedYuan(order.payableAmount), stages }
  } catch (error) {
    failed = error
    flow = { result: error.code === 'EVIDENCE_ERROR' ? 'EVIDENCE_ERROR' : 'FAIL', orderId: orderId || null, orderNo: orderNo || null, stages, error: 'OTC assertion failed; inspect last confirmed checkpoint in restricted evidence' }
    throw new Error('OTC flow stopped; subsequent business writes were not executed')
  } finally {
    try { writeJson(path.join(runDir, 'flow.json'), flow) } catch (error) { if (!failed) throw error }
    if (tracing) {
      await customerContext.tracing.stop({ path: path.join(runDir, 'customer-trace.zip') })
      await adminContext.tracing.stop({ path: path.join(runDir, 'admin-trace.zip') })
    }
    if (sql) await sql.close()
    await customerContext.close()
    await adminContext.close()
  }
})

async function reconcile(sql, adapter, stage, order, config, requiredChecks) {
  const readStart = sql.reads.length
  const result = await adapter.reconcileStage({
    stage,
    tenantId: config.tenantId,
    storeId: config.storeId,
    orderId: Number(order.id),
    orderNo: order.orderNo,
    expected: {
      memberId: config.memberId,
      status: Number(order.status), payStatus: Number(order.payStatus), orderType: Number(order.orderType),
      payableAmountYuan: formattedYuan(order.payableAmount), quantity: config.quantity,
      payableAmountFen: toFen(order.payableAmount),
      drugId: config.fixtureDrugId, finishAt: order.finishAt || null
    },
    select: scopedSelect(sql, config.tenantId, Number(order.id))
  })
  assertStage(result, stage, requiredChecks)
  const observed = result.observedOrder
  if (!observed || Number(observed.tenantId) !== config.tenantId || Number(observed.memberId) !== config.memberId || Number(observed.storeId) !== config.storeId || Number(observed.id) !== Number(order.id) || observed.orderNo !== order.orderNo || Number(observed.payableAmountFen) !== toFen(order.payableAmount) || Number(observed.status) !== Number(order.status) || Number(observed.payStatus) !== Number(order.payStatus)) throw new Error('SQL order identity/status/amount does not match the UI/API checkpoint')
  const readFamilies = new Set(sql.reads.slice(readStart).map(read => read.family))
  if (requiredChecks.some(family => !readFamilies.has(family))) throw new Error(`SQL reconciliation at ${stage} did not execute each required ledger SELECT`)
  stages.push({ name: `sql-${stage}`, pass: true, checks: requiredChecks })
}

async function readAdminOrder(config, session, orderId) {
  return apiCall(config, session, 'GET', `/admin-api/pharmacy/member/order/get?id=${orderId}`)
}

async function waitForAdminOrder(config, session, orderId, orderNo, storeId, status, payStatus) {
  const deadline = Date.now() + config.timeoutMs
  while (Date.now() < deadline) {
    const order = await readAdminOrder(config, session, orderId)
    if (order.orderNo !== orderNo || Number(order.storeId) !== storeId || Number(order.orderType) !== 0) throw new Error('Admin order readback identity changed')
    if (Number(order.status) === status && Number(order.payStatus) === payStatus) return order
    await new Promise(resolve => setTimeout(resolve, config.pollIntervalMs))
  }
  throw new Error(`Admin order did not reach expected status ${status}/${payStatus}`)
}

async function waitForCustomerOrder(config, session, orderId, orderNo, status, payStatus) {
  const deadline = Date.now() + config.timeoutMs
  while (Date.now() < deadline) {
    const order = await apiCall(config, session, 'GET', `/app-api/member/wx-order/get?id=${orderId}`)
    if (toFen(order.payableAmount) !== baselinePayableFen) throw new Error('Server payable amount changed after creation')
    if (order.orderNo !== orderNo || Number(order.storeId) !== config.storeId || Number(order.orderType) !== 0) throw new Error('Customer order readback identity changed')
    if (Number(order.status) === status && Number(order.payStatus) === payStatus) return assertOrder(order, { orderId, tenantId: config.tenantId, storeId: config.storeId, orderNo, status, payStatus, orderType: 0 })
    await new Promise(resolve => setTimeout(resolve, config.pollIntervalMs))
  }
  throw new Error(`Customer order did not reach expected status ${status}/${payStatus}`)
}

function assertSameOrder(actual, expected, config, status, payStatus) {
  assertOrder(actual, { orderId: Number(expected.id), tenantId: config.tenantId, storeId: config.storeId, orderNo: expected.orderNo, status, payStatus, orderType: 0 })
  if (formattedYuan(actual.payableAmount) !== formattedYuan(expected.payableAmount)) throw new Error('Customer/admin API payable amounts differ')
  if (Number(actual.memberId) !== config.memberId) throw new Error('Admin order belongs to a different member')
}

async function capture(page, name) {
  try {
    await page.screenshot({ path: path.join(runDir, 'screenshots', `${name}.png`), fullPage: true, mask: [page.locator('.pickup-code'), page.locator('input[type="password"]')] })
  } catch { throw Object.assign(new Error('Required stage screenshot could not be archived'), { code: 'EVIDENCE_ERROR' }) }
}

function scopedSelect(sql, tenantId, orderId) {
  return (name, params) => {
    if (!Array.isArray(params) || params[0] !== tenantId || params[1] !== orderId) throw new Error('Stage SQL requires tenantId and this new orderId as its first two bound parameters')
    return sql.select(name, params)
  }
}

async function snapshotAll(sql, adapter, identity) {
  const start = sql.reads.length
  const snapshot = await adapter.snapshotAll({ ...identity, select: scopedSelect(sql, identity.tenantId, identity.orderId) })
  validateSnapshot(snapshot)
  const readFamilies = new Set(sql.reads.slice(start).map(read => read.family))
  if (Object.keys(snapshot).some(family => !readFamilies.has(family))) throw new Error('Full snapshot included a ledger family without executing its SQL read')
  return snapshot
}

function validateSnapshot(snapshot) {
  const required = ['order', 'payment', 'paymentExtension', 'inventoryAllocations', 'inventoryFlows', 'sale', 'salePayment', 'points', 'notification']
  if (!snapshot || typeof snapshot !== 'object' || required.some(key => !Object.hasOwn(snapshot, key))) throw new Error('Full SQL snapshot omitted a required ledger family')
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`
  return JSON.stringify(value)
}

function toFen(value) {
  const match = String(value).match(/^(\d+)(?:\.(\d{1,2}))?$/)
  if (!match) throw new Error('Money must be an exact nonnegative decimal with at most two fractional digits')
  const result = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'))
  if (!Number.isSafeInteger(result)) throw new Error('Money is outside the exact supported range')
  return result
}
