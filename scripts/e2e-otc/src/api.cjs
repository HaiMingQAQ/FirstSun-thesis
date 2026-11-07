function captureSession(page, apiPrefix, origin) {
  const session = { headers: null }
  page.on('request', request => {
    if (new URL(request.url()).origin !== origin || !new URL(request.url()).pathname.startsWith(apiPrefix)) return
    const source = request.headers()
    const headers = {}
    for (const key of ['authorization', 'cookie', 'tenant-id', 'tenantid', 'accept-language']) {
      if (source[key]) headers[key] = source[key]
    }
    if (headers.authorization || headers.cookie) session.headers = headers
  })
  return session
}

async function apiCall(config, session, method, endpoint, body) {
  if (!session?.headers || (!session.headers.authorization && !session.headers.cookie)) throw new Error('Authenticated UI session was not observed')
  const url = new URL(endpoint, `${config.apiUrl}/`)
  if (url.origin !== config.apiUrl || !['GET', 'POST', 'PUT'].includes(method)) throw new Error('API target is outside the approved isolated endpoint')
  if (String(session.headers['tenant-id']) !== String(config.tenantId)) throw new Error('UI authenticated request did not supply the configured tenant-id header')
  if (method !== 'GET' && !/^\/(?:app-api\/member\/wx-order\/simulate-pay|admin-api\/pharmacy\/member\/order\/verify)$/.test(url.pathname)) throw new Error('HTTP writes are restricted to same-order payment and verification replay')
  if (method !== 'GET' && (!session.replayOrderId || Number(url.searchParams.get('id')) !== session.replayOrderId)) throw new Error('Replay must target the order created by this UI run')
  const response = await fetch(url, {
    method,
    headers: { ...session.headers, ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    redirect: 'manual', signal: AbortSignal.timeout(config.timeoutMs)
  })
  let payload
  try { payload = await response.json() } catch { throw new Error(`API ${method} ${url.pathname} returned non-JSON HTTP ${response.status}`) }
  if (!response.ok || payload?.code !== 0) throw new Error(`API ${method} ${url.pathname} failed (HTTP ${response.status}, code ${String(payload?.code ?? 'missing')})`)
  return payload.data
}

function assertOrder(order, expected) {
  if (!order || Number(order.id) !== Number(expected.orderId) || (order.tenantId !== undefined && Number(order.tenantId) !== expected.tenantId) || Number(order.storeId) !== expected.storeId) {
    throw new Error('Order identity, tenant, or store does not match the active run')
  }
  if (expected.orderNo && order.orderNo !== expected.orderNo) throw new Error('Order number changed during the flow')
  if (expected.status !== undefined && Number(order.status) !== expected.status) throw new Error(`Unexpected order status at checkpoint ${expected.status}`)
  if (expected.payStatus !== undefined && Number(order.payStatus) !== expected.payStatus) throw new Error(`Unexpected payment status at checkpoint ${expected.payStatus}`)
  if (expected.orderType !== undefined && Number(order.orderType) !== expected.orderType) throw new Error('Order is not ordinary pickup orderType=0')
  const payable = Number(order.payableAmount)
  if (!Number.isFinite(payable) || payable <= 0) throw new Error('Server payable amount is missing or invalid')
  return order
}

module.exports = { captureSession, apiCall, assertOrder }
