const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const test = require('node:test');
const { isDeployableHttpsUrl } = require('./production-url.cjs');

test('微信发布地址只接受 HTTPS 域名', () => {
  // example.org 仅作为格式测试样本，不是本项目的部署地址。
  assert.equal(isDeployableHttpsUrl('https://example.org/api'), true);
  assert.equal(isDeployableHttpsUrl('https://example.org.:443/api'), true);
  for (const value of [undefined, '', 'http://example.org', 'https://user@example.org',
    'https://localhost', 'https://LOCALHOST.', 'https://api.localhost.',
    'https://127.0.0.1', 'https://127.0.0.2', 'https://127.255.255.255',
    'https://127.1', 'https://2130706433', 'https://0.0.0.0',
    'https://[::1]', 'https://[::ffff:127.0.0.1]', 'https://192.168.1.1']) {
    assert.equal(isDeployableHttpsUrl(value), false, String(value));
  }
});

test('微信发布构建入口拒绝 127/8 地址并提示具体参数', () => {
  const root = path.resolve(__dirname, '..');
  const env = { ...process.env,
    SHOPRO_BASE_URL: 'https://127.0.0.2',
    SHOPRO_TRIAL_BASE_URL: 'https://example.org',
    SHOPRO_STATIC_URL: 'https://example.org',
    SHOPRO_H5_URL: 'https://example.org',
  };
  const result = spawnSync(process.execPath, ['scripts/uni.cjs', 'build', '-p', 'mp-weixin'], {
    cwd: root, env, encoding: 'utf8',
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /SHOPRO_BASE_URL/);
  assert.doesNotMatch(result.stderr, /SHOPRO_TRIAL_BASE_URL/);
});
