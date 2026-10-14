// Executes the component source with isolated API contracts; no service or database calls.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { stripTypeScriptTypes } = require('node:module');
const vue = fs.readFileSync(path.join(__dirname, '../InventoryPurchaseDialog.vue'), 'utf8');
const source = stripTypeScriptTypes(vue.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, ''));
const readPermissions = ['pharmacy:ai:chat', 'pharmacy:inventory-batch:query'];
const createPermission = 'pharmacy:purchase:order:create';
const warehousePermission = 'pharmacy:inventory-warehouse:query';
const snapshot = { storeId: 407, rule: '可用库存 < 最低库存', hasMore: false, items: [
  { drugId: 101, drugCode: 'D101', drugName: '合成药品甲', availableQty: 2, minStock: 50 },
  { drugId: 102, drugCode: 'D102', drugName: '合成药品乙', availableQty: 0, minStock: 20 }
] };

function page(overrides = {}, permissions = [...readPermissions, createPermission, warehousePermission]) {
  const calls = { alerts: [], supplier: 0, warehouse: [], preview: [] }, emitted = [];
  const allowed = new Set(permissions);
  const sandbox = {
    ref: value => ({ value }), reactive: value => value, computed: get => ({ get value() { return get(); } }),
    checkPermi: required => required.some(permission => allowed.has(permission)),
    defineEmits: () => (...event) => emitted.push(event), defineExpose() {},
    PharmacyAiPurchaseApi: {
      inventoryAlerts: async params => { calls.alerts.push(params); return overrides.alerts ? overrides.alerts(params) : structuredClone(snapshot); },
      preview: async data => { calls.preview.push(data); return overrides.preview ? overrides.preview(data) : { commandId: 61, tool: 'CREATE_PURCHASE_ORDER', status: 'PENDING', confirmationToken: 'isolated-test' }; }
    },
    getSimpleSupplierList: async () => { calls.supplier++; return overrides.supplier ? overrides.supplier() : [{ id: 21, supplierName: '合成供应商', supplierCode: 'S21', defaultDiscount: 0.5 }]; },
    getWarehousePage: async params => { calls.warehouse.push(params); return overrides.warehouse ? overrides.warehouse(params) : { total: 3, list: [
      { id: 31, storeId: 407, status: 1 }, { id: 32, storeId: 408, status: 1 }, { id: 33, storeId: 407, status: 0 }
    ] }; }
  };
  vm.createContext(sandbox);
  vm.runInContext(source + ';globalThis.page={open,close,load,submit,rows,form,loaded,visible,loading,submitting,loadError,previewError,warehouses,keyword};', sandbox);
  return { ...sandbox.page, calls, emitted, allowed };
}
const fill = p => {
  p.form.supplierId = 21; p.form.orderDate = '2026-10-12';
  p.rows.value[0].selected = true; p.rows.value[0].orderQty = 3; p.rows.value[0].unitPrice = 12.5;
};

test('real alert rows and options load without guessing quantities, prices, supplier or date', async () => {
  const p = page(); await p.open();
  assert.equal(p.loaded.value, true); assert.equal(p.rows.value.length, 2);
  assert.equal(p.form.supplierId, undefined); assert.equal(p.form.orderDate, '');
  for (const row of p.rows.value) {
    assert.equal(row.selected, false); assert.equal(row.orderQty, undefined); assert.equal(row.unitPrice, undefined);
  }
  assert.deepEqual(Array.from(p.warehouses.value, row => row.id), [31]);
  assert.equal(p.calls.alerts[0].limit, 20);
});

test('missing, stale and invalid required inputs cannot create a preview', async () => {
  const cases = [
    [p => { p.form.supplierId = undefined; }, /供应商/], [p => { p.form.supplierId = 99; }, /供应商/],
    [p => { p.form.orderDate = ''; }, /下单日期/], [p => { p.rows.value[0].selected = false; }, /勾选/],
    [p => { p.rows.value[0].orderQty = undefined; }, /正整数/], [p => { p.rows.value[0].orderQty = 0; }, /正整数/],
    [p => { p.rows.value[0].orderQty = 1.5; }, /正整数/], [p => { p.rows.value[0].unitPrice = undefined; }, /含税单价/],
    [p => { p.rows.value[0].unitPrice = -1; }, /含税单价/], [p => { p.rows.value[0].unitPrice = NaN; }, /含税单价/],
    [p => { p.form.warehouseId = 32; }, /本店启用仓库/]
  ];
  for (const [change, expected] of cases) {
    const p = page(); await p.open(); fill(p); change(p); await p.submit();
    assert.equal(p.calls.preview.length, 0); assert.equal(p.emitted.length, 0); assert.match(p.previewError.value, expected);
  }
});

test('preview sends only user-selected values and leaves command confirmation to the parent', async () => {
  const p = page(); await p.open(); fill(p);
  p.form.remark = ' 测试备注 '; await p.submit();
  const payload = JSON.parse(JSON.stringify(p.calls.preview[0]));
  assert.deepEqual(payload, { purchase: { supplierId: 21, orderDate: '2026-10-12', remark: '测试备注',
    lines: [{ drugId: 101, orderQty: 3, unitPrice: 12.5 }] } });
  assert.equal(p.emitted[0][0], 'preview'); assert.equal(p.emitted[0][1].status, 'PENDING');
  assert.equal(p.emitted[0][1].confirmationToken, 'isolated-test'); assert.equal(p.visible.value, false);
});

test('permissions require all read/create grants; optional warehouse permission makes no unauthorized request', async () => {
  for (const missing of readPermissions) {
    const p = page({}, [...readPermissions, createPermission].filter(permission => permission !== missing));
    await p.open(); assert.equal(p.loaded.value, false); assert.equal(p.calls.alerts.length, 0); assert.equal(p.calls.supplier, 0);
    assert.match(p.loadError.value, /权限/);
  }
  const readOnly = page({}, readPermissions); await readOnly.open(); fill(readOnly); await readOnly.submit();
  assert.equal(readOnly.calls.preview.length, 0);
  const noWarehouse = page({}, [...readPermissions, createPermission]); await noWarehouse.open(); fill(noWarehouse);
  assert.equal(noWarehouse.calls.warehouse.length, 0); await noWarehouse.submit(); assert.equal(noWarehouse.emitted.length, 1);
  const revoked = page(); await revoked.open(); fill(revoked); revoked.allowed.delete(createPermission); await revoked.submit();
  assert.equal(revoked.calls.preview.length, 0);
});

test('option or alert failures remain errors, and retry replaces them with the actual snapshot', async () => {
  for (const stage of ['alerts', 'supplier', 'warehouse']) {
    let failed = true;
    const p = page({ [stage]: async () => {
      if (failed) throw new Error(stage + ' unavailable');
      if (stage === 'alerts') return structuredClone(snapshot);
      return stage === 'warehouse' ? { list: [], total: 0 } : [];
    } });
    await p.open(); assert.equal(p.loaded.value, false); assert.equal(p.rows.value.length, 0); assert.match(p.loadError.value, /unavailable/);
    await p.submit(); assert.equal(p.calls.preview.length, 0);
    failed = false; await p.load(); assert.equal(p.loaded.value, true); assert.equal(p.loadError.value, '');
  }
});

test('warehouse pagination reads every page, and truncated pages cannot become a fake empty result', async () => {
  const p = page({ warehouse: async ({ pageNo }) => ({ total: 2, list: [{ id: pageNo, storeId: 407, status: 1 }] }) });
  await p.open(); assert.equal(p.calls.warehouse.length, 2); assert.equal(p.warehouses.value.length, 2);
  const broken = page({ warehouse: async () => ({ total: 2, list: [] }) }); await broken.open();
  assert.equal(broken.loaded.value, false); assert.match(broken.loadError.value, /完整/);
});

test('duplicate submission, failed preview and a response after closing cannot emit a successful command', async () => {
  let resolve;
  const p = page({ preview: () => new Promise(done => { resolve = done; }) }); await p.open(); fill(p);
  const pending = p.submit(); await p.submit(); assert.equal(p.calls.preview.length, 1);
  p.visible.value = false; p.close(); resolve({ commandId: 61, status: 'PENDING' }); await pending; assert.equal(p.emitted.length, 0);
  const failed = page({ preview: async () => { throw new Error('供应商证照失效'); } }); await failed.open(); fill(failed); await failed.submit();
  assert.equal(failed.emitted.length, 0); assert.equal(failed.visible.value, true); assert.match(failed.previewError.value, /证照失效/);
});

test('old dialog requests cannot replace a reopened dialog snapshot', async () => {
  let resolve, count = 0;
  const p = page({ alerts: () => ++count === 1 ? new Promise(done => { resolve = done; }) : Promise.resolve({ ...snapshot, storeId: 409 }) });
  const old = p.open(); await p.open(); resolve(structuredClone(snapshot)); await old;
  assert.equal(p.loaded.value, true); assert.equal(p.loading.value, false); assert.equal(p.warehouses.value.length, 0);
  fill(p); await p.submit(); assert.equal(p.emitted.length, 1);
});
