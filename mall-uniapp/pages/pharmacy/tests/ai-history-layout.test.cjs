// Independent browser fixture: no real health questions, model calls or database writes.
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.PHARMACY_UI_URL || 'http://127.0.0.1:4189';
const out = path.resolve(__dirname, '../../../../.local/ai-history-layout-20261003');

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH });
  try {
    const context = await browser.newContext({ viewport: { width: 375, height: 844 } });
    await context.addInitScript(() => {
      localStorage.setItem('token', 'Bearer isolated-ui-fixture');
      localStorage.setItem('tenant-id', '163');
      localStorage.setItem('firstsun-session', JSON.stringify({ type: 'object', data: { id: 1, name: '独立 UI 测试' } }));
    });
    let failHistory = false;
    await context.route('**/app-api/**', async route => {
      const url = new URL(route.request().url());
      let data = [];
      if (url.pathname.endsWith('/ai/topics')) {
        if (failHistory) return route.fulfill({ json: { code: 500, msg: '独立测试：历史加载失败' } });
        data = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, title: '历史话题长名称'.repeat(8), createTime: '2026-10-03T01:00:00' }));
      } else if (url.pathname.endsWith('/ai/topics/1')) {
        data = { topic: { id: 1, storeId: 407 }, hasMore: false, turns: [{ id: 1, clientMessageId: 'fixture-1', content: '给我看看有哪些感冒药',
          response: { status: 'SUCCESS', answer: '独立测试长回复。'.repeat(150), products: [{ id: 11, name: '测试商品', availableQty: 3, price: 19.5 }] } }] };
      }
      await route.fulfill({ json: { code: 0, data } });
    });
    const page = await context.newPage(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/pages/pharmacy/ai');
    await page.getByText('历史记录', { exact: true }).click();
    await page.locator('.history-title').first().click();
    await page.locator('.ai-turn').waitFor();
    assert.equal(await page.locator('.fs-product').count(), 1);
    for (const width of [320, 375, 430]) {
      await page.setViewportSize({ width, height: 844 });
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      await page.waitForTimeout(350);
      const button = page.getByText('历史记录', { exact: true });
      assert(await button.isVisible());
      const box = await button.boundingBox(); assert(box.y >= 0 && box.y + box.height < 120);
      await button.click(); await page.locator('.history-row').nth(11).waitFor();
      const layout = await page.locator('.history-panel').evaluate(element => {
        const box = element.getBoundingClientRect();
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, overflow: document.documentElement.scrollWidth > innerWidth + 1 };
      });
      assert(layout.left >= 0 && layout.right <= width && layout.top >= 0 && layout.bottom <= 844 && !layout.overflow, JSON.stringify(layout));
      await page.screenshot({ path: path.join(out, `history-${width}.png`) });
      await page.locator('.history-backdrop').click({ position: { x: 4, y: 4 } });
      await page.locator('.history-overlay').waitFor({ state: 'hidden' });
    }
    failHistory = true;
    await page.getByText('历史记录', { exact: true }).click();
    await page.getByText('独立测试：历史加载失败', { exact: true }).waitFor();
    failHistory = false;
    await page.getByText('重新加载', { exact: true }).click();
    await page.getByText('独立测试：历史加载失败', { exact: true }).waitFor({ state: 'hidden' });
    assert.deepEqual(errors, []);
    console.log('PASS: isolated history popup at 320/375/430, fixed entry after long chat, restored card, close, failure/retry; no backend writes');
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
