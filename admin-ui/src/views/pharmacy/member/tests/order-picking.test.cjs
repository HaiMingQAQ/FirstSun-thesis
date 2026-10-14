// Page source with isolated API contracts; does not call a backend or database.
const { test } = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { stripTypeScriptTypes } = require('node:module');
const read = name => fs.readFileSync(path.join(__dirname, '..', name + '.vue'), 'utf8');
function page(detail, overrides = {}) {
  const source = stripTypeScriptTypes(read(detail ? 'OrderDetail' : 'order').match(/<script lang="ts" setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, ''));
  const calls = [], success = [], emitted = [];
  let order = { id: 51, orderNo: 'SYNTH-51', status: 1, payStatus: 1, orderType: 0 }, allowed = true;
  const sandbox = { ref: value => ({ value }), reactive: value => value, defineOptions() {}, defineExpose() {}, onMounted() {},
    defineEmits: () => event => emitted.push(event), checkPermi: () => allowed,
    useMessage: () => ({ confirm: overrides.confirm || (async () => {}), success: value => success.push(value) }),
    OrderApi: {
      getOrder: overrides.getOrder || (async () => ({ ...order })),
      getOrderLineList: overrides.lines || (async () => [{ drugName: '合成药品', qty: 2, price: 3, lineAmount: 6 }]),
      getOrderPage: overrides.getPage || (async () => ({ list: [{ ...order }], total: 1 })),
      startPicking: async id => { calls.push(['start', id]); if (overrides.action) return overrides.action(); order.status = 2; },
      finishPicking: async id => { calls.push(['finish', id]); if (overrides.action) return overrides.action(); order.status = 3; }
    } };
  vm.createContext(sandbox);
  vm.runInContext(source + ';globalThis.page={' + (detail ? 'open,loaded,orderData,orderItems,error,handleClose,picking,handlePicking' : 'getList,list,loading,pickingId,pickingError,handlePicking,handleVerify,verifyVisible') + '};', sandbox);
  const p = { ...sandbox.page, calls, success, emitted, setPermission: value => { allowed = value; }, setOrder: value => { order = { ...order, ...value }; } };
  p.initialize = () => detail ? p.open(51) : p.getList();
  p.act = finish => detail ? p.handlePicking(finish) : p.handlePicking(p.list.value[0], finish);
  return p;
}

test('list and detail start and finish paid orders through actual endpoints and refresh only after success', async () => {
  for (const detail of [false, true]) {
    const p = page(detail); await p.initialize(); await p.act(false); await p.act(true);
    assert.deepEqual(p.calls, [['start', 51], ['finish', 51]]); assert.equal(p.success.length, 2);
    assert.equal(detail ? p.orderData.value.status : p.list.value[0].status, 3);
    if (detail) { assert.equal(p.orderItems.value[0].lineAmount, 6); assert.deepEqual(p.emitted, ['success', 'success']); }
  }
});

test('unpaid, wrong-status, loading and unauthorized operations do not call picking APIs', async () => {
  for (const detail of [false, true]) {
    for (const values of [{ payStatus: 0 }, { status: 0 }, { status: 3 }, { status: 4 }, { status: -1 }]) {
      const p = page(detail); p.setOrder(values); await p.initialize(); await p.act(false); assert.equal(p.calls.length, 0);
    }
    const p = page(detail); await p.initialize(); p.setPermission(false); await p.act(false); assert.equal(p.calls.length, 0);
    const loading = page(detail, detail ? { getOrder: () => new Promise(() => {}) } : { getPage: () => new Promise(() => {}) });
    loading.initialize(); if (detail) await loading.act(false); else await loading.handlePicking({ id: 51, status: 1, payStatus: 1 }, false);
    assert.equal(loading.calls.length, 0);
  }
});

test('double clicks and confirmation cancellation never repeat or perform a picking write', async () => {
  for (const detail of [false, true]) {
    let resolve;
    const p = page(detail, { confirm: () => new Promise(done => { resolve = done; }) }); await p.initialize();
    const pending = p.act(false); await p.act(false); assert.equal(p.calls.length, 0);
    resolve(); await pending; assert.equal(p.calls.length, 1);
    const cancelled = page(detail, { confirm: async () => { throw 'cancel'; } }); await cancelled.initialize(); await cancelled.act(false);
    assert.equal(cancelled.calls.length, 0); assert.equal(cancelled.success.length, 0);
  }
});

test('business errors leave the order unchanged; refresh errors report the completed write without fake success', async () => {
  for (const detail of [false, true]) {
    const failed = page(detail, { action: async () => { throw new Error('状态已变化'); } }); await failed.initialize(); await failed.act(false);
    assert.equal(failed.success.length, 0); assert.equal(detail ? failed.orderData.value.status : failed.list.value[0].status, 1);
    assert.match(detail ? failed.error.value : failed.pickingError.value, /状态已变化/);
    let reads = 0;
    const refresh = async () => { if (++reads > 1) throw new Error('refresh unavailable'); return detail ? { id: 51, status: 1, payStatus: 1 } : { list: [{ id: 51, status: 1, payStatus: 1 }], total: 1 }; };
    const p = page(detail, detail ? { getOrder: refresh } : { getPage: refresh }); await p.initialize(); await p.act(false);
    assert.equal(p.calls.length, 1); assert.equal(p.success.length, 0); assert.match(detail ? p.error.value : p.pickingError.value, /已完成.*刷新失败/);
  }
});

test('detail load failure and old requests cannot expose stale order actions or fabricated empty lines', async () => {
  const failure = page(true, { lines: async () => { throw new Error('明细读取失败'); } }); await failure.initialize(); await failure.act(false);
  assert.equal(failure.loaded.value, false); assert.equal(failure.calls.length, 0); assert.match(failure.error.value, /读取失败/);
  let resolve;
  const p = page(true, { getOrder: id => id === 51 ? new Promise(done => { resolve = done; }) : Promise.resolve({ id, status: 2, payStatus: 1 }) });
  const old = p.open(51); await p.open(52); resolve({ id: 51, status: 1, payStatus: 1 }); await old;
  assert.equal(p.orderData.value.id, 52); await p.act(true); assert.deepEqual(p.calls, [['finish', 52]]);
});

test('closing the detail during confirmation prevents a stale write; delivery orders cannot use pickup verification', async () => {
  let resolve;
  const p = page(true, { confirm: () => new Promise(done => { resolve = done; }) }); await p.initialize();
  const pending = p.act(false); p.handleClose(() => {}); resolve(); await pending; assert.equal(p.calls.length, 0);
  const list = page(false); await list.initialize(); list.handleVerify({ id: 51, status: 3, payStatus: 1, orderType: 1 });
  assert.equal(list.verifyVisible.value, false);
});
