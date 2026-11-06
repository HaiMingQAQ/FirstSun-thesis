const { expect } = require('@playwright/test')

class CustomerPage {
  constructor(page, config) { this.page = page; this.config = config }

  async login() {
    await this.page.goto(this.config.customerUrl, { waitUntil: 'domcontentloaded' })
    if (this.config.customerLoginMode === 'email-password') {
      await this.page.getByRole('button', { name: '登录', exact: true }).click()
      const form = this.page.locator('.email-login-form')
      await expect(form).toBeVisible()
      const switchMode = form.getByRole('button', { name: '密码登录', exact: true })
      if (await switchMode.count()) await switchMode.click()
      await form.getByLabel('邮箱').fill(this.config.customerEmail)
      await form.getByLabel('密码').fill(this.config.customerPassword)
      await form.getByRole('button', { name: '登录', exact: true }).click()
    } else {
      await this.page.getByRole('button', { name: '打开本机手机号验收' }).click()
      await expect(this.page.getByRole('dialog')).toBeVisible()
      if (this.config.customerLoginMode === 'local-password') {
        await this.page.getByRole('button', { name: '密码登录', exact: true }).click()
        const form = this.page.locator('form').filter({ has: this.page.getByLabel('手机号') })
        await form.getByLabel('手机号').fill(this.config.localMobile)
        await form.getByLabel('密码').fill(this.config.localPassword)
        await form.getByRole('button', { name: '使用密码登录' }).click()
      } else {
        await this.page.getByLabel('测试手机号').fill(this.config.localMobile)
        await this.page.getByRole('checkbox').check()
        await this.page.getByRole('button', { name: '获取本机验证码' }).click()
        await expect(this.page.getByText('已请求本机验证码，请读取受限运行说明。更换手机号后须重新请求。')).toBeVisible()
        await this.page.getByLabel('运行时验证码').fill(this.config.localCode)
        await this.page.getByRole('button', { name: '登录 / 注册验收会员' }).click()
      }
    }
    await expect(this.page.getByRole('dialog', { name: '会员登录' })).toBeHidden({ timeout: 30_000 })
    await expect(this.page.getByRole('button', { name: '个人中心' })).toBeVisible()
  }

  async addFixture() {
    const search = this.page.getByRole('combobox', { name: '搜索药品' })
    await search.fill(this.config.fixtureDrugName)
    await this.page.getByRole('button', { name: '搜索药品', exact: true }).click()
    const card = this.page.locator(`.product-card[data-product="${this.config.fixtureDrugId}"]`)
    await expect(card).toBeVisible()
    await expect(card.getByText('OTC 非处方', { exact: true })).toBeVisible()
    const added = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/app-api/member/wx-cart/add'))
    await card.getByRole('button', { name: `加入购物车：${this.config.fixtureDrugName}` }).click()
    const addResponse = await added
    if (!addResponse.ok() || (await addResponse.json())?.code !== 0) throw new Error('UI add-to-cart request failed')
    await this.page.getByRole('button', { name: /购物车/ }).click()
    await expect(this.page.getByRole('heading', { name: '购物车', exact: true })).toBeVisible()
  }

  async createPickupOrder() {
    const checkout = this.page.getByRole('button', { name: /去结算/ })
    await expect(checkout).toBeEnabled()
    await checkout.click()
    await expect(this.page.getByRole('heading', { name: '确认订单', exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: /到店自提/ }).click()
    await this.page.getByLabel('订单备注').fill(this.config.runId)
    const createResponse = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/app-api/member/wx-order/create'), { timeout: 30_000 })
    await this.page.getByRole('button', { name: '创建订单', exact: true }).click()
    const response = await createResponse
    let payload
    try { payload = await response.json() } catch { throw new Error('Order creation UI request returned non-JSON') }
    if (!response.ok() || payload?.code !== 0 || !Number.isSafeInteger(Number(payload.data)) || Number(payload.data) <= 0) throw new Error('Single UI order creation request did not return a valid order id')
    return Number(payload.data)
  }

  async pay() {
    const payResponse = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/app-api/member/wx-order/simulate-pay'), { timeout: 30_000 })
    await this.page.getByRole('button', { name: '模拟支付（测试）', exact: true }).click()
    await expect(this.page.getByRole('dialog', { name: '测试模拟支付' })).toBeVisible()
    await this.page.getByRole('dialog', { name: '测试模拟支付' }).getByRole('button', { name: '确认', exact: true }).click()
    const response = await payResponse
    const payload = await response.json()
    if (!response.ok() || payload?.code !== 0 || payload.data !== true) throw new Error('Mock payment UI request did not return success')
    return payload.data
  }

  async reloadOrder() {
    const readback = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/app-api/member/wx-order/get'))
    await this.page.getByRole('button', { name: '刷新订单' }).click()
    const response = await readback
    const payload = await response.json()
    if (!response.ok() || payload?.code !== 0) throw new Error('Customer UI order refresh failed')
    await expect(this.page.locator('.order-status')).toBeVisible()
  }
}

class AdminPage {
  constructor(page, config) { this.page = page; this.config = config }

  async login() {
    await this.page.goto(new URL('/login', `${this.config.adminUrl}/`).href, { waitUntil: 'domcontentloaded' })
    const form = this.page.locator('form.login-form')
    await expect(form).toBeVisible()
    const remember = this.page.locator('form.login-form .el-checkbox')
    if (await remember.count() && await remember.getByRole('checkbox').isChecked()) {
      await remember.click()
      await expect(remember.getByRole('checkbox')).not.toBeChecked()
    }
    await form.locator('input').nth(0).fill(this.config.adminUsername)
    await form.locator('input[type="password"]').fill(this.config.adminPassword)
    const tenantLookup = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/admin-api/system/tenant/get-id-by-name'), { timeout: 10_000 }).catch(() => null)
    const loginResponse = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/admin-api/system/auth/login'), { timeout: 30_000 }).catch(() => null)
    await form.getByRole('button', { name: '登录', exact: true }).click()
    const tenantResponse = await tenantLookup
    if (!tenantResponse) throw new Error('Admin LoginForm did not resolve its build-time tenant name before login')
    const actualTenantName = new URL(tenantResponse.url()).searchParams.get('name')
    const tenantPayload = await tenantResponse.json()
    if (actualTenantName !== this.config.adminTenantName || tenantPayload?.code !== 0 || Number(tenantPayload.data) !== this.config.tenantId) throw new Error('Admin UI build tenant does not match configured tenant 901')
    if (await this.page.locator('[class*="verify"], [class*="captcha"], iframe').count()) throw new Error('Admin interactive captcha appeared; automated bypass is prohibited')
    const response = await loginResponse
    if (!response || !response.ok() || (await response.json())?.code !== 0) throw new Error('Admin UI password authentication failed')
    await expect(this.page).not.toHaveURL(/\/login(?:\?|$)/, { timeout: 30_000 })
  }

  async openOrderList() {
    const url = new URL('/pharmacy-base/member/order', `${this.config.adminUrl}/`)
    await this.page.goto(url.href, { waitUntil: 'domcontentloaded' })
    await expect(this.page.getByPlaceholder('请输入订单号')).toBeVisible({ timeout: 30_000 })
  }

  async findOrder(orderNo) {
    await this.page.getByPlaceholder('请输入订单号').fill(orderNo)
    const searchResponse = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/admin-api/pharmacy/member/order/page') && new URL(response.url()).searchParams.get('orderNo') === orderNo)
    await this.page.getByRole('button', { name: '搜索', exact: true }).click()
    const response = await searchResponse
    const payload = await response.json()
    if (!response.ok() || payload?.code !== 0 || !Array.isArray(payload.data?.list)) throw new Error('Admin UI order search failed')
    const row = this.page.getByRole('row').filter({ has: this.page.getByText(orderNo, { exact: true }) })
    await expect(row).toHaveCount(1)
    return row
  }

  async confirmPicking(row, action, orderNo) {
    const endpoint = action === '开始拣货' ? '/admin-api/pharmacy/member/order/start-picking' : '/admin-api/pharmacy/member/order/finish-picking'
    const actionResponse = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith(endpoint), { timeout: 30_000 })
    await row.getByRole('button', { name: action, exact: true }).click()
    const dialog = this.page.getByRole('dialog').filter({ hasText: orderNo })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '确定', exact: true }).click()
    const response = await actionResponse
    const payload = await response.json()
    if (!response.ok() || payload?.code !== 0 || payload.data !== true) throw new Error(`${action} UI request did not return success`)
    return true
  }

  async verify(row, pickupCode) {
    const actionResponse = this.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/admin-api/pharmacy/member/order/verify'), { timeout: 30_000 })
    await row.getByRole('button', { name: '核销', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '订单核销' })
    await expect(dialog).toBeVisible()
    await dialog.getByPlaceholder('请输入提货码').fill(pickupCode)
    await dialog.getByRole('button', { name: '确认核销', exact: true }).click()
    const response = await actionResponse
    const payload = await response.json()
    if (!response.ok() || payload?.code !== 0 || payload.data !== true) throw new Error('Pickup verification UI request did not return success')
    await expect(dialog).toBeHidden()
    return true
  }
}

module.exports = { CustomerPage, AdminPage }
