const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../category.vue'), 'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^\s*import .*;\s*$/gm, '');
const products = (count, overrides = {}) => Array.from({ length: count }, (_, i) => ({ id: i + 1, active: true, stock: 10, rx: false, ...overrides }));
function page(fetch) {
  const calls = [], hooks = {};
  const sandbox = { ref: value => ({ value }), computed: get => ({ get value() { return get(); } }),
    onLoad: fn => { hooks.load = fn; }, onShow: fn => { hooks.show = fn; }, onReachBottom: fn => { hooks.bottom = fn; }, onUnload: fn => { hooks.unload = fn; },
    toast() {}, requireLogin: () => false, useAction: () => ({ busy: { value: false }, act() {} }),
    api: { categories: [{ id: 'all', name: '全部药品' }], history: () => [], remember() {}, clearHistory() {},
      productPage: args => { calls.push({ ...args }); return fetch(args, calls.length); } } };
  vm.createContext(sandbox);
  vm.runInContext(source + ';globalThis.page={load,loadMore,search,select,toggleFilter,retry,keyword,category,list,visibleList,inStock,otcOnly,total,hasMore,loading,error};', sandbox);
  return { ...sandbox.page, calls, hooks };
}
const sliced = list => async ({ pageNo, pageSize }) => ({ total: list.length, list: list.slice((pageNo - 1) * pageSize, pageNo * pageSize) });
test('uses backend total and loads the full catalogue beyond 100 without duplicate concurrent requests', async () => {
  const rows = products(105), p = page(sliced(rows));
  await p.load(); assert.equal(p.list.value.length, 20); assert.equal(p.total.value, 105); assert.equal(p.hasMore.value, true);
  await Promise.all([p.loadMore(), p.loadMore()]); assert.equal(p.calls.length, 2);
  while (p.hasMore.value) await p.loadMore();
  assert.deepEqual(Array.from(p.list.value, p => p.id), rows.map(p => p.id)); assert.equal(p.calls.length, 6);
  await p.loadMore(); assert.equal(p.calls.length, 6);
});
test('overlapping server pages are deduplicated while page offsets still advance', async () => {
  const p = page(async ({ pageNo }) => ({ total: 21, list: pageNo === 1 ? products(20) : [{ id: 20 }, { id: 21 }] }));
  await p.load(); await p.loadMore(); assert.equal(p.list.value.length, 21); assert.equal(p.hasMore.value, false);
});
test('local stock and OTC filters traverse hidden pages before declaring empty', async () => {
  for (const filter of ['stock', 'otc']) {
    const rows = products(45, filter === 'stock' ? { stock: 0 } : { rx: true });
    rows[44] = { id: 45, active: true, stock: 3, rx: false };
    let resolveLast;
    const p = page(args => args.pageNo === 3 ? new Promise(resolve => { resolveLast = () => resolve({ total: 45, list: rows.slice(40) }); }) : sliced(rows)(args));
    const pending = p.toggleFilter(filter);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(p.calls.length, 3); assert.equal(p.loading.value, true); assert.equal(p.visibleList.value.length, 0); assert.equal(p.hasMore.value, true);
    resolveLast(); await pending;
    assert.deepEqual(Array.from(p.visibleList.value, p => p.id), [45]); assert.equal(p.hasMore.value, false);
  }
  const empty = page(sliced(products(45, { stock: 0 })));
  await empty.toggleFilter('stock'); assert.equal(empty.calls.length, 3); assert.equal(empty.visibleList.value.length, 0); assert.equal(empty.hasMore.value, false);
});
test('append failure retains results and retries the same page; empty unexpected pages cannot declare completion', async () => {
  let fail = true;
  const p = page(async args => { if (args.pageNo === 2 && fail) throw new Error('网络失败'); return sliced(products(25))(args); });
  await p.load(); await p.loadMore(); assert.equal(p.list.value.length, 20); assert.equal(p.error.value, '网络失败'); assert.equal(p.hasMore.value, true);
  fail = false; await p.retry(); assert.equal(p.calls[2].pageNo, 2); assert.equal(p.list.value.length, 25); assert.equal(p.error.value, '');
  const inconsistent = page(async () => ({ total: 100, list: [] }));
  await inconsistent.load(); assert.match(inconsistent.error.value, /重新搜索/); assert.equal(inconsistent.loading.value, false);
});
test('search, category and filters reset page 1; typing alone does not mix queries', async () => {
  const p = page(sliced(products(45)));
  await p.load(); await p.loadMore(); p.keyword.value = '板蓝根'; await p.search();
  assert.equal(p.calls.at(-1).pageNo, 1); assert.equal(p.calls.at(-1).keyword, '板蓝根'); assert.equal(p.category.value, 'all');
  p.keyword.value = '未提交的新输入'; await p.loadMore(); assert.equal(p.calls.at(-1).keyword, '板蓝根');
  await p.select(123); assert.equal(p.calls.at(-1).pageNo, 1); assert.equal(p.calls.at(-1).category, 123); assert.equal(p.list.value.length, 20);
  await p.toggleFilter('otc'); assert.equal(p.calls.at(-1).pageNo, 1); assert.equal(p.list.value.length, 20);
});
test('late responses from a previous category or disposed page do not overwrite current results', async () => {
  let resolveOld;
  const p = page(args => args.category === 'all' ? new Promise(resolve => { resolveOld = resolve; }) : Promise.resolve({ total: 1, list: [{ id: 99 }] }));
  const old = p.load(); await p.select(9); resolveOld({ total: 100, list: products(20) }); await old;
  assert.deepEqual(Array.from(p.list.value, p => p.id), [99]); assert.equal(p.total.value, 1); assert.equal(p.loading.value, false);
  const disposed = page(() => new Promise(resolve => { resolveOld = resolve; }));
  const pending = disposed.load(); disposed.hooks.unload(); resolveOld({ total: 1, list: [{ id: 1 }] }); await pending;
  assert.equal(disposed.list.value.length, 0);
});
test('total shrink ends pagination and initial network failure can retry page 1', async () => {
  const p = page(async ({ pageNo }) => ({ total: pageNo === 1 ? 100 : 21, list: pageNo === 1 ? products(20) : [{ id: 21 }] }));
  await p.load(); await p.loadMore(); assert.equal(p.total.value, 21); assert.equal(p.hasMore.value, false);
  let fail = true;
  const first = page(async () => { if (fail) throw new Error('断网'); return { total: 0, list: [] }; });
  await first.load(); fail = false; await first.retry(); assert.equal(first.calls[1].pageNo, 1); assert.equal(first.error.value, '');
});
