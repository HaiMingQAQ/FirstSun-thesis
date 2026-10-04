const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../ai.vue'), 'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^\s*import .*;\s*$/gm, '');

function page(consultStream) {
  const timers = new Set(), session = { owner: 'A', messages: [], topicId: 1, storeId: 407 };
  const sandbox = { ref: value => ({ value }), aiSession: session, accountKey: () => 'A', confirm() {}, toast() {}, go() {},
    formatDate: () => '', normalizeDrug: x => x, onShow() {}, onHide() {}, onUnload() {},
    setTimeout(fn) { timers.add(fn); return fn; }, clearTimeout(id) { timers.delete(id); },
    uni: { getStorageSync: () => 'token-A', pageScrollTo() {} },
    createStreamController() { const signal = { aborted: false }; return { signal, abort() { signal.aborted = true; } }; },
    api: { session: () => ({ id: 'A' }), consultStream } };
  vm.createContext(sandbox);
  vm.runInContext(source + ';loggedIn.value=true;globalThis.actions={query,stop,restoreTurn,busy};', sandbox);
  return { actions: sandbox.actions, timers };
}
const message = () => ({ id: 'message-1', content: '查询药品', context: [], answer: '', products: [] });

test('FAILED completion retains all received answer text, stops typing and enables retry', async () => {
  const text = '已核对的药品资料已逐步显示，但后续模型回复中断。';
  const p = page(async (_, onEvent) => { onEvent('delta', { text }); return { status: 'FAILED', answer: 'AI 回复未完成，请重试', products: [{ id: 1, name: '药品资料' }] }; });
  const turn = message(); await p.actions.query(turn);
  assert.equal(turn.answer, text); assert.equal(turn.status, 'FAILED'); assert.match(turn.error, /未完成/);
  assert.equal(turn.products.length, 1); assert.equal(turn.loading, false); assert.equal(p.actions.busy.value, false); assert.equal(p.timers.size, 0);
});

test('failure before any delta displays the safe backend explanation', async () => {
  const p = page(async () => ({ status: 'FAILED', answer: '咨询暂不可用', products: [] }));
  const turn = message(); await p.actions.query(turn);
  assert.equal(turn.answer, '咨询暂不可用'); assert.equal(turn.loading, false); assert.equal(p.actions.busy.value, false);
});

test('network interruption preserves received characters still waiting to be typed', async () => {
  const text = '已经收到的有效文字不应在网络中断时丢失';
  const p = page(async (_, onEvent) => { onEvent('delta', { text }); throw new Error('回复中断，请重试'); });
  const turn = message(); await p.actions.query(turn);
  assert.equal(turn.answer, text); assert.equal(turn.status, 'NETWORK_ERROR'); assert.match(turn.error, /中断/);
  assert.equal(turn.loading, false); assert.equal(p.actions.busy.value, false); assert.equal(p.timers.size, 0);
});

test('successful completion uses the persisted final answer and removes the loading state', async () => {
  const p = page(async (_, onEvent) => { onEvent('delta', { text: '答复' }); return { status: 'SUCCESS', answer: '答复全文', products: [] }; });
  const turn = message(); await p.actions.query(turn);
  assert.equal(turn.answer, '答复全文'); assert.equal(turn.error, ''); assert.equal(turn.loading, false); assert.equal(p.actions.busy.value, false);
});

test('stop rejects late deltas and FAILED completion without reviving the previous request', async () => {
  let receive, finish;
  const p = page((_, onEvent) => { receive = onEvent; return new Promise(resolve => { finish = resolve; }); });
  const turn = message(), pending = p.actions.query(turn);
  receive('delta', { text: '部分' }); p.actions.stop();
  receive('delta', { text: '迟到文字' }); finish({ status: 'FAILED', answer: '失败', products: [] }); await pending;
  assert.equal(turn.answer, '部分'); assert.equal(turn.status, 'STOPPED'); assert.equal(turn.loading, false); assert.equal(p.actions.busy.value, false);
});

test('restored failed history remains explicitly incomplete', () => {
  const p = page(() => {});
  const turn = p.actions.restoreTurn({ id: 1, content: '查询', response: { status: 'FAILED', answer: '保留的部分回复', products: [] } });
  assert.equal(turn.answer, '保留的部分回复'); assert.match(turn.error, /未完成/); assert.equal(turn.loading, false);
});
