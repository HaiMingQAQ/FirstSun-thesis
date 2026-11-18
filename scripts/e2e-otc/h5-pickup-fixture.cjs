// Run against an existing local H5 dev server. All order and store responses are synthetic.
// NODE_PATH can point to an existing installation's node_modules.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const origin = 'http://127.0.0.1:4188';
const pickup = { id: 901, orderNo: 'WX-FIXTURE-20261115-001', orderType: 0, pickupCode: '583297', status: 2 };
const delivery = { id: 902, orderNo: 'WX-FIXTURE-20261115-002', orderType: 1, pickupCode: '681354', status: 3 };
const legacy = { id: 903, orderType: 0, status: 4 };
const orders = [pickup, delivery, legacy].map(order => ({
  payStatus: 1, storeId: 407, goodsAmount: 10, payableAmount: 10, lines: [], ...order,
}));

async function main() {
  const browser = await chromium.launch(process.env.H5_FIXTURE_BROWSER ? { executablePath: process.env.H5_FIXTURE_BROWSER } : {});
  try {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      serviceWorkers: 'block',
      permissions: ['clipboard-read', 'clipboard-write'],
    });
    await context.addInitScript(() => localStorage.setItem('token', 'synthetic-h5-order-fixture'));
    const receiptCalls = [];
    await context.route('**/*', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const method = request.method();
      const id = Number(url.searchParams.get('id'));
      const respond = data => route.fulfill({ json: { code: 0, data, msg: '' } });
      const endpoint = url.pathname;
      if (method === 'GET' && endpoint.endsWith('/pharmacy/store/list')) {
        return respond([{ id: 407, storeName: 'FirstSun 合成测试门店', address: '合成测试路 1 号', businessHours: '09:00–21:00' }]);
      }
      if (method === 'GET' && endpoint.endsWith('/member/wx-order/mock-payment-available')) return respond(false);
      if (method === 'GET' && endpoint.endsWith('/member/wx-order/page')) return respond({ list: orders, total: orders.length });
      if (method === 'GET' && endpoint.endsWith('/member/wx-order/get')) {
        const order = orders.find(item => item.id === id);
        return order ? respond(order) : route.abort();
      }
      if (method === 'POST' && endpoint.endsWith('/member/wx-order/confirm-receive')) {
        receiptCalls.push(id);
        if (id !== delivery.id) return route.abort();
        orders[1].status = 4;
        return respond(true);
      }
      // Allow only local dev assets. Never forward real API requests, even to a local proxy.
      if (url.origin === origin && ['GET', 'HEAD'].includes(method)
        && !['xhr', 'fetch', 'eventsource'].includes(request.resourceType())
        && !endpoint.startsWith('/app-api/') && !endpoint.startsWith('/member/')) return route.continue();
      return route.abort();
    });
    const page = await context.newPage();
    page.setDefaultTimeout(25_000);
    page.on('pageerror', error => console.error('H5 page error:', error.message));
    page.on('response', response => {
      if (response.status() >= 400) console.error('H5 response:', response.status(), new URL(response.url()).pathname);
    });
    page.on('requestfailed', request => {
      const url = new URL(request.url());
      if (url.origin === origin) console.error('H5 request failed:', request.resourceType(), url.pathname);
    });
    const text = value => page.getByText(value, { exact: true });
    const button = name => page.locator('uni-button, button').filter({ hasText: new RegExp('^\\s*' + name + '\\s*$') });
    const checkWidth = async () =>
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390, 'page must not overflow horizontally');
    const visit = async (path, label) => {
      await page.goto(origin + '/pages/pharmacy/' + path);
      await text(label).waitFor().catch(async error => {
        console.error('H5 page content:', (await page.locator('body').innerText()).slice(0, 400));
        throw error;
      });
      await checkWidth();
    };
    const detail = id => 'order-detail?id=' + id;
    await visit(detail(pickup.id), '备货中');
    await text(pickup.orderNo).waitFor();
    assert.equal(await text(pickup.pickupCode).count(), 0);
    assert.equal(await button('复制提货码').count(), 0);
    assert.equal(await button('确认已取药').count(), 0);

    orders[0].status = 3;
    await page.reload();
    await text('可到店自提').waitFor();
    await text(pickup.pickupCode).waitFor();
    await text('合成测试路 1 号').waitFor();
    await text(/09:00–21:00/).waitFor();
    await checkWidth();
    await button('复制提货码').click();
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), pickup.pickupCode);
    await button('复制订单号').click();
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), pickup.orderNo);
    assert.equal(await button('确认已取药').count(), 0);

    await visit('order', '订单号 ' + pickup.orderNo);
    await text('订单号 ' + delivery.orderNo).waitFor();
    await text('订单号 ' + legacy.id).waitFor();

    await visit(detail(legacy.id), '已完成');
    await text(String(legacy.id)).waitFor();
    assert.equal(await button('复制订单号').count(), 0);

    await visit(detail(delivery.id), '待收货');
    assert.equal(await button('复制提货码').count(), 0);
    assert.equal(await text(delivery.pickupCode).count(), 0);
    await button('确认收货').click();
    await page.locator('uni-modal').getByText('确定', { exact: true }).click();
    await text('已完成').waitFor();
    assert.deepEqual(receiptCalls, [delivery.id]);
    console.log('PASS: H5 390px, preparing → ready pickup, real clipboard, order numbers and fallback, delivery receipt. No live API calls.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
