// Page scripts with isolated API contract stubs; no backend, database or private materials.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const read = name => fs.readFileSync(path.join(__dirname, '..', name + '.vue'), 'utf8');
const script = name => read(name).match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^\s*import .*;$/gm, '');
const approved = () => ({ id: 91, status: 0, reviewStatus: 1, approvedUntil: 20000,
  approvedItems: [{ drugId: 101, qty: 2 }, { drugId: 102, qty: 1 }], uses: [] });

function page(name, api = {}) {
  const hooks = {}, routes = [], toasts = [];
  let account = 'tenant:member-a', now = 10000;
  const ref = value => ({ value });
  const sandbox = { ref, reactive: value => value, computed: get => ({ get value() { return get(); } }),
    onLoad: fn => { hooks.load = fn; }, onShow: fn => { hooks.show = fn; },
    accountKey: () => account, go: route => routes.push(route), toast: text => toasts.push(text),
    Date: class extends Date { static now() { return now; } },
    useRequest() {
      const loading = ref(false), error = ref('');
      return { loading, error, async run(fn) {
        loading.value = true; error.value = '';
        try { return await fn(); } catch (e) { error.value = e.message; } finally { loading.value = false; }
      } };
    },
    useAction() {
      const busy = ref(false);
      return { busy, async act(fn) {
        if (busy.value) return;
        busy.value = true;
        try { return await fn(); } catch (e) { toasts.push(e.message); } finally { busy.value = false; }
      } };
    },
    api: { session: () => ({ userId: account }), ...api } };
  vm.createContext(sandbox);
  const state = name === 'cart' ? 'items,selected,total,allSelected,invalid,checkout' : 'selected,canBuy,checkout';
  vm.runInContext(script(name) + `;globalThis.state={${state}};`, sandbox);
  return { ...sandbox.state, sandbox, hooks, routes, toasts,
    switchAccount: value => { account = value; }, setTime: value => { now = value; } };
}

test('cart and Rx detail expose the existing application and record routes', () => {
  const cart = read('cart'), detail = read('detail');
  assert.doesNotMatch(cart, /在线处方购药暂未开放/);
  assert.match(cart, /药师审核通过后从处方详情按核准明细结算/);
  const context = vm.createContext({ row: { drugId: 101 }, product: { id: 101 }, requireLogin: () => true,
    routes: [] });
  context.go = route => context.routes.push(route);
  for (const [source, expected] of [
    [cart, ['prescription-upload', 'prescription-upload?drugId=101']],
    [detail, ['prescription-upload', 'prescription-upload?drugId=101']],
  ]) {
    context.routes.length = 0;
    for (const match of source.matchAll(/@tap="([^"]*go\('prescription-upload[^\"]*)"/g)) vm.runInContext(match[1], context);
    assert.deepEqual(Array.from(context.routes), expected);
  }
});

test('historic checked Rx rows stay excluded from cart selection and ordinary checkout', async () => {
  const updated = [], starts = [];
  const p = page('cart', { updateCart: async (id, patch) => updated.push([id, patch.checked]), beginCheckout: () => starts.push('ordinary') });
  const row = (id, rx) => ({ drugId: id, checked: true, qty: 2, product: { rx, active: true, stock: 10, price: 300 } });
  p.items.value = [row(101, true), row(102, false)];
  assert.equal(p.invalid(p.items.value[0]), true);
  assert.deepEqual(Array.from(p.selected.value, r => r.drugId), [102]);
  assert.equal(p.total.value, 600);
  assert.equal(p.allSelected.value, true);
  await p.checkout();
  assert.deepEqual(updated, [[101, false]]);
  assert.deepEqual(starts, ['ordinary']);
  assert.deepEqual(p.routes, ['checkout']);
  p.items.value = [row(101, true)];
  assert.equal(p.selected.value.length, 0);
  assert.equal(p.total.value, 0);
  assert.match(read('cart'), /:disabled="busy \|\| !selected.length"/);
});

test('checkout refresh blocks pending, rejected, voided, expired and already-used prescriptions', async () => {
  const cases = [
    ['pending', { reviewStatus: 0 }], ['rejected', { reviewStatus: 2 }], ['voided', { status: 2 }],
    ['expired', { approvedUntil: 10000 }], ['invalid expiry', { approvedUntil: 'invalid' }],
    ['missing approval items', { approvedItems: [] }], ['held', { uses: [{ status: 'HELD' }] }],
    ['paid', { uses: [{ status: 'PAID' }] }],
  ];
  for (const [name, patch] of cases) {
    let begins = 0;
    const latest = { ...approved(), ...patch };
    const p = page('prescription-upload', { prescription: async () => latest, beginPrescriptionCheckout: async () => { begins++; } });
    p.selected.value = approved();
    assert.equal(Boolean(p.canBuy.value), true, name + ': initially available');
    await p.checkout();
    assert.equal(Boolean(p.canBuy.value), false, name);
    assert.equal(begins, 0, name);
    assert.deepEqual(p.routes, [], name);
    assert.match(p.toasts[0], /暂不可结算/, name);
  }
});

test('valid approval with released unpaid use reaches checkout with its fixed approved quantities', async () => {
  const record = { ...approved(), uses: [{ status: 'RELEASED' }] }, starts = [];
  const p = page('prescription-upload', { prescription: async () => record, beginPrescriptionCheckout: async id => starts.push(id) });
  p.selected.value = approved();
  await p.checkout();
  assert.deepEqual(starts, [91]);
  assert.deepEqual(p.routes, ['checkout']);
  assert.deepEqual(Array.from(p.selected.value.approvedItems, i => [i.drugId, i.qty]), [[101, 2], [102, 1]]);
  assert.deepEqual(p.toasts, []);
});

test('approval expiring while the detail is open and request failures never navigate', async () => {
  let begins = 0;
  const p = page('prescription-upload', { prescription: async () => approved(), beginPrescriptionCheckout: async () => { begins++; } });
  p.selected.value = approved();
  assert.equal(Boolean(p.canBuy.value), true);
  p.setTime(20001);
  await p.checkout();
  assert.equal(begins, 0); assert.deepEqual(p.routes, []);
  for (const stage of ['read', 'begin']) {
    const failed = page('prescription-upload', {
      prescription: async () => { if (stage === 'read') throw new Error('读取失败'); return approved(); },
      beginPrescriptionCheckout: async () => { throw new Error('结算校验失败'); },
    });
    failed.selected.value = approved();
    await failed.checkout();
    assert.deepEqual(failed.routes, []); assert.match(failed.toasts[0], /失败/);
  }
});

test('switching account during either approval request cannot continue checkout', async () => {
  for (const stage of ['read', 'begin']) {
    let resolve, begins = 0;
    const deferred = () => new Promise(done => { resolve = done; });
    const p = page('prescription-upload', { prescription: () => stage === 'read' ? deferred() : Promise.resolve(approved()),
      beginPrescriptionCheckout: () => { begins++; return deferred(); } });
    p.selected.value = approved();
    const pending = p.checkout();
    await new Promise(done => setImmediate(done));
    p.switchAccount('tenant:member-b'); resolve(approved()); await pending;
    assert.equal(begins, stage === 'read' ? 0 : 1);
    assert.deepEqual(p.routes, []); assert.match(p.toasts[0], /账号已变化/);
  }
});
