// Compile guards and login page source with isolated real-adapter contract stubs; no HTTP calls.
const { test } = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const config = fs.readFileSync(path.join(root, 'sheep/api/pharmacy/config.js'), 'utf8');
const login = fs.readFileSync(path.join(__dirname, '../login.vue'), 'utf8');
const utility = fs.readFileSync(path.join(__dirname, '../usePharmacy.js'), 'utf8');
const server = fs.readFileSync(path.join(root, 'sheep/api/pharmacy/server.js'), 'utf8');
const env = { SHOPRO_H5_TEST_LOGIN: 'true', MODE: 'local-acceptance', SHOPRO_H5_TEST_BUILD: 'local-acceptance',
  SHOPRO_H5_TEST_BACKEND_URL: 'http://127.0.0.1:58090', SHOPRO_BASE_URL: 'http://127.0.0.1:58090', SHOPRO_DEV_BASE_URL: 'http://127.0.0.1:58090/' };
function enabled(platform, values) {
  let source = config;
  if (platform !== 'H5') source = source.replace(/\/\/ #ifdef H5[\s\S]*?\/\/ #endif/, '');
  const sandbox = { URL, env: values }; vm.createContext(sandbox);
  vm.runInContext(source.replace(/export /g, '').replace(/import\.meta\.env/g, 'env') + ';globalThis.result={PHARMACY_DEMO,PHARMACY_H5_TEST_LOGIN};', sandbox);
  assert.equal(sandbox.result.PHARMACY_DEMO, false);
  return sandbox.result.PHARMACY_H5_TEST_LOGIN;
}
function page(isEnabled, overrides = {}) {
  const requests = [], toasts = [], navigation = [];
  const sandbox = { ref: value => ({ value }), computed: get => ({ get value() { return get(); } }),
    PHARMACY_DEMO: false, PHARMACY_H5_TEST_LOGIN: isEnabled, TEST_CODE: 'mock-only',
    getCurrentPages: () => [{}], SESSION_KEY: 'firstsun-session',
    uni: { showToast: value => toasts.push(value.title), reLaunch: value => navigation.push(value.url), showModal() {}, setStorageSync() {} },
    call: async config => { requests.push(config); return overrides.request ? overrides.request(config) : {}; },
    api: { profile: async () => ({ userId: 91 }), wechatLogin: overrides.wechat || (async () => { throw new Error('仅支持微信小程序'); }) } };
  vm.createContext(sandbox);
  const method = server.slice(server.indexOf('  async sendLoginCode('), server.indexOf('  async wechatLogin(')).trim().replace(/,$/, '');
  Object.assign(sandbox.api, vm.runInContext(`({${method}})`, sandbox));
  vm.runInContext(utility.replace(/^import .*;$/gm, '').replace(/export /g, '')
    + login.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^\s*import .*;$/gm, '')
    + ';globalThis.page={mobile,code,agreed,login,fillCode,busy};', sandbox);
  return { ...sandbox.page, api: sandbox.api, requests, toasts, navigation };
}
const fill = p => { p.mobile.value = '13900001611'; p.code.value = '987654'; p.agreed.value = true; };

test('explicit send and consume adapter preserves header contract without another send', async () => {
  const p = page(true);
  await p.api.sendLoginCode('13900001611');
  assert.equal(p.requests.length, 1);
  await p.api.loginWithCode('13900001611', '987654');
  assert.equal(p.requests.length, 2);
  assert.equal(p.requests[0].url, '/member/auth/send-sms-code');
  assert.equal(p.requests[1].url, '/member/auth/login-or-register');
  assert.equal(p.requests[1].header['X-Sms-Code'], '987654');
});

test('runtime requires H5, acceptance mode, compiler marker and consistent local backend; MP never enables it', () => {
  assert.equal(enabled('H5', {}), false); assert.equal(enabled('H5', env), true); assert.equal(enabled('MP-WEIXIN', env), false);
  for (const patch of [{ MODE: 'production' }, { MODE: 'development' }, { SHOPRO_H5_TEST_BUILD: '' },
    { SHOPRO_H5_TEST_LOGIN: 'false' }, { SHOPRO_BASE_URL: 'http://example.org' },
    { SHOPRO_DEV_BASE_URL: 'http://127.0.0.1:58091' }, { SHOPRO_H5_TEST_BACKEND_URL: 'http://private@127.0.0.1:58090' }])
    assert.equal(enabled('H5', { ...env, ...patch }), false);
});

test('enabled page uses the existing real login adapter with the manually entered code and no auto-fill', async () => {
  const p = page(enabled('H5', env)); assert.equal(p.code.value, ''); p.fillCode(); assert.equal(p.code.value, '');
  fill(p); await p.login();
  assert.deepEqual(p.requests.map(request => request.url), ['/member/auth/send-sms-code', '/member/auth/login-or-register']);
  assert.equal(p.requests[1].header['X-Sms-Code'], '987654'); assert.equal(p.requests[1].custom.skipUserInit, true);
  assert.deepEqual(p.toasts, ['登录成功']); assert.deepEqual(p.navigation, ['/pages/pharmacy/user']);
  assert.match(login, /v-if="PHARMACY_DEMO"[^>]*@tap="fillCode"/);
  assert.doesNotMatch(login, /在线处方上传尚未开放/);
});

test('required agreement and valid inputs gate login; duplicate submissions and backend failures cannot fake success', async () => {
  const missing = page(true); fill(missing); missing.agreed.value = false; await missing.login(); assert.equal(missing.requests.length, 0);
  missing.agreed.value = true; missing.code.value = 'abcdef'; await missing.login(); assert.equal(missing.requests.length, 0);
  let resolve;
  const pending = page(true, { request: config => config.url.endsWith('send-sms-code') ? new Promise(done => { resolve = done; }) : Promise.resolve({}) });
  fill(pending); const result = pending.login(); await pending.login(); assert.equal(pending.requests.length, 1);
  resolve({}); await result; assert.equal(pending.requests.length, 2); assert.equal(pending.navigation.length, 1);
  const failed = page(true, { request: async () => { throw new Error('后端未配置测试验证码'); } }); fill(failed); await failed.login();
  assert.equal(failed.navigation.length, 0); assert.doesNotMatch(failed.toasts.join(''), /登录成功/);
});

test('default H5 does not fall back to test phone login, and WeChat still uses its real login method', async () => {
  const h5 = page(enabled('H5', {})); fill(h5); await h5.login(); assert.equal(h5.requests.length, 0); assert.equal(h5.navigation.length, 0);
  let wechatCalls = 0;
  const mp = page(enabled('MP-WEIXIN', env), { wechat: async () => { wechatCalls++; } }); fill(mp); await mp.login();
  assert.equal(wechatCalls, 1); assert.equal(mp.requests.length, 0);
});
