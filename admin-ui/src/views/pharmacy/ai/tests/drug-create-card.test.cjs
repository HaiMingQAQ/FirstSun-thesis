const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { stripTypeScriptTypes } = require('node:module');
const vue = require('vue');
const text = fs.readFileSync(path.join(__dirname, '../DrugCreateCard.vue'), 'utf8');
const source = stripTypeScriptTypes(text.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, ''));

async function card(overrides = {}, allowed = true) {
  const calls = { preview: [], confirm: [], cancel: [] }, events = [];
  const props = vue.reactive({ draft: { genericName: '合成演示药', drugCode: 'ISOLATED-ONLY', specification: '1盒', unit: '盒', categoryId: 1, retailPrice: 10, drugType: 1, isRx: 0, isSpecial: 0, isPseudoephedrine: 0, isColdChain: 0, storageCond: 0, insuranceType: 0, needExpiry: 1, saleableOnline: 0, status: 1, taxRate: 0, minStock: 0, maxStock: 0, approveStatus: 1, tenantId: 999 } });
  const api = {
    async drugPreview(payload) { calls.preview.push(payload); return overrides.preview ? overrides.preview(payload) : { commandId: 91, tool: 'CREATE_DRUG', status: 'PENDING', confirmationToken: 'isolated-only' }; },
    async confirm(id, token) { calls.confirm.push({ id, token }); return overrides.confirm ? overrides.confirm(id) : { drugId: 101, status: 'EXECUTED' }; },
    async recentCommands() { return overrides.recent ? overrides.recent() : [{ commandId: 91, tool: 'CREATE_DRUG', status: 'EXECUTED' }]; },
    async cancel(id) { calls.cancel.push(id); }
  };
  const sandbox = {
    ...vue, defineProps: () => props,
    defineEmits: () => (event, value) => { events.push([event, value]); if(event === 'update:submission') props.submission = value; if(event === 'update:draft') props.draft = value; },
    DICT_TYPE: new Proxy({}, { get: (_, key) => key }), getIntDictOptions: () => Array.from({ length: 9 }, (_, value) => ({ value, label: String(value) })),
    CategoryApi: { getSimpleCategoryList: async () => [{ id: 1, catName: '常用药品' }] }, handleTree: value => value,
    hasPermission: () => allowed, useRouter: () => ({ push() {} }), onMounted() {},
    PharmacyAiApi: api, window: { dispatchEvent() {} }, Event: class {},
  };
  vm.runInNewContext(source + '\nglobalThis.view = { form, formRef, submit, busy, loadCategories, checkSubmission, confirmPending, resetSubmission };', sandbox);
  sandbox.view.formRef.value = { validate: async () => overrides.valid !== false };
  await sandbox.view.loadCategories();
  return { ...sandbox.view, props, calls, events };
}

test('manual submit only confirms the whitelisted draft and locks repeated clicks after success', async () => {
  const page = await card();
  await page.submit(); await page.submit();
  assert.equal(page.calls.preview.length, 1);
  assert.equal(page.calls.preview[0].approveStatus, undefined);
  assert.equal(page.calls.preview[0].tenantId, undefined);
  assert.equal(page.calls.confirm.length, 1);
  assert.equal(page.props.submission.status, 'EXECUTED');
  assert.equal(page.props.submission.drugId, 101);
});

test('validation and permission failures never prepare or execute a command', async () => {
  for (const page of [await card({ valid: false }), await card({}, false)]) {
    await page.submit();
    assert.equal(page.calls.preview.length, 0);
    assert.equal(page.calls.confirm.length, 0);
  }
});

test('concurrent submit is blocked while the first request is pending', async () => {
  let release;
  const page = await card({ preview: () => new Promise(resolve => { release = resolve; }) });
  const first = page.submit();
  await new Promise(resolve => setImmediate(resolve));
  await page.submit();
  assert.equal(page.calls.preview.length, 1);
  release({ commandId: 91, tool: 'CREATE_DRUG', status: 'PENDING', confirmationToken: 'isolated-only' });
  await first;
  assert.equal(page.calls.confirm.length, 1);
});

test('lost confirmation response cannot create another draft; checking resolves an executed command', async () => {
  const page = await card({ confirm: async () => { throw Error('simulated lost response'); } });
  await page.submit(); await page.submit();
  assert.equal(page.calls.preview.length, 1);
  assert.equal(page.props.submission.commandId, 91);
  await page.checkSubmission();
  assert.equal(page.props.submission.status, 'EXECUTED');
  await page.submit();
  assert.equal(page.calls.confirm.length, 1);
});

test('unknown submission record keeps the form locked instead of allowing duplicates', async () => {
  const page = await card({ confirm: async () => { throw Error('simulated lost response'); }, recent: async () => [] });
  await page.submit(); await page.checkSubmission(); await page.submit();
  assert.equal(page.calls.preview.length, 1);
  assert.equal(page.props.submission.commandId, 91);
});
