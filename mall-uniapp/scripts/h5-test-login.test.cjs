const { test } = require('node:test'), assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process'), path = require('node:path');
const { validateH5TestLogin } = require('./h5-test-login.cjs');
const args = ['build', '-p', 'h5', '--mode', 'local-acceptance'];
const env = { SHOPRO_H5_TEST_LOGIN: 'true', SHOPRO_H5_TEST_BACKEND_URL: 'http://127.0.0.1:58090',
  SHOPRO_BASE_URL: 'http://127.0.0.1:58090', SHOPRO_DEV_BASE_URL: 'http://127.0.0.1:58090/' };

test('explicit H5 acceptance mode requires the same credential-free HTTP loopback backend', () => {
  assert.equal(validateH5TestLogin(args, env), true);
  assert.equal(validateH5TestLogin(['build', '-p', 'h5'], {}), false);
  for (const patch of [
    { SHOPRO_H5_TEST_BACKEND_URL: undefined }, { SHOPRO_H5_TEST_BACKEND_URL: 'http://example.org' },
    { SHOPRO_H5_TEST_BACKEND_URL: 'http://user:private@127.0.0.1:58090' },
    { SHOPRO_BASE_URL: 'http://127.0.0.1:58091' }, { SHOPRO_DEV_BASE_URL: 'https://127.0.0.1:58090' },
    { SHOPRO_DEV_BASE_URL: 'http://127.0.0.1:58090/app-api' }, { SHOPRO_BASE_URL: 'http://127.0.0.1:58090/?token=private' }
  ]) assert.throws(() => validateH5TestLogin(args, { ...env, ...patch }), /回环后端/);
});

test('normal H5, release and every WeChat build reject the test switch before the compiler starts', () => {
  for (const forbidden of [ ['build', '-p', 'h5'], ['build', '-p', 'h5', '--mode', 'production'],
    ['-p', 'mp-weixin', '--mode', 'local-acceptance'], ['build', '-p', 'mp-weixin'],
    [...args, '--mode', 'production'], [...args, '-p', 'mp-weixin'] ]) {
    assert.throws(() => validateH5TestLogin(forbidden, env), /测试登录/);
    const result = spawnSync(process.execPath, ['scripts/uni.cjs', ...forbidden], {
      cwd: path.resolve(__dirname, '..'), env: { ...process.env, ...env }, encoding: 'utf8'
    });
    assert.equal(result.status, 1); assert.match(result.stderr, /测试登录/);
    assert.doesNotMatch(result.stderr, /private|Cannot find module|编译/);
  }
});
