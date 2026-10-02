const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../sheep/api/pharmacy/server.js'), 'utf8');
const profile = source.slice(source.indexOf('  async profile('), source.indexOf('  async updateProfile(')).trim().replace(/,$/, '');
const update = source.slice(source.indexOf('  async updateProfile('), source.indexOf('  // 页面契约：api.categories')).trim().replace(/,$/, '');
test('profile preserves server avatar and refuses stale-token results', async () => {
  let token = 'A', written, resolve;
  const uni = { getStorageSync: () => token, setStorageSync: (_, data) => { written = data; } };
  const raw = { id: 9, nickname: '昵称', avatar: 'https://example.invalid/avatar.png' };
  const load = new Function('accountCall', 'uni', 'SESSION_KEY', `return ({${profile}}).profile;`);
  const data = await load(async () => raw, uni, 'session')();
  assert.equal(data.avatar, raw.avatar); assert.equal(data.name, '昵称');
  const pending = load(() => new Promise(r => { resolve = r; }), uni, 'session')();
  token = 'B'; resolve({ ...raw, nickname: '旧账号' });
  await assert.rejects(pending, /账号已变化/); assert.equal(written.name, '昵称');
});
test('avatar upload failures and account changes cannot update another profile', async () => {
  for (const scenario of ['failure', 'accountChange', 'success']) {
    let owner = 'A', options; const calls = [];
    const uni = { getStorageSync: key => key === 'token' ? 'Bearer test' : 163, uploadFile: o => { options = o; } };
    const run = new Function('draftOwner', 'requireOwner', 'accountCall', 'uni', 'api', 'baseUrl', 'apiPath', 'tenantId', `return ({${update}}).updateProfile;`)(
      () => owner, wanted => { if (wanted !== owner) throw new Error('账号已变化'); }, async r => { calls.push(r); }, uni, { profile: async () => ({ name: '已保存' }) }, '', '', 163);
    const result = run({ nickname: ' 新昵称 ', avatarFile: '/local/avatar.png' });
    assert.equal(options.header.Authorization, 'Bearer test');
    if (scenario === 'accountChange') owner = 'B';
    if (scenario === 'failure') options.success({ statusCode: 200, data: JSON.stringify({ code: 403 }) });
    else options.success({ statusCode: 200, data: JSON.stringify({ code: 0, data: 'https://example.invalid/avatar.png' }) });
    if (scenario !== 'success') { await assert.rejects(result); assert.equal(calls.length, 0); }
    else { assert.equal((await result).name, '已保存'); assert.deepEqual(calls[0].data, { nickname: '新昵称', avatar: 'https://example.invalid/avatar.png' }); }
  }
});

test('private account requests snapshot auth headers and ignore old-account 401', async () => {
  const method = source.slice(source.indexOf('async function accountCall('), source.indexOf('function requireOwner('));
  let owner = 'A', fail, captured, cleared = false;
  const run = new Function('draftOwner', 'uni', 'requireOwner', 'call', 'clearAuthSession', 'tenantId', method + ';return accountCall;')(
    () => owner, { getStorageSync: key => key === 'token' ? 'Bearer-' + owner : 163 }, () => {},
    config => { captured = config; return new Promise((_, reject) => { fail = reject; }); }, () => { cleared = true; }, 163);
  const pending = run({ url: '/pharmacy/ai/topics', method: 'DELETE' });
  owner = 'B'; fail(Object.assign(new Error('expired'), { code: 401 })); await assert.rejects(pending);
  assert.equal(captured.header.Authorization, 'Bearer-A'); assert.equal(captured.custom.isToken, false); assert.equal(captured.custom.skipTenant, true); assert.equal(cleared, false);
});
