const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../ai.vue'), 'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^\s*import .*;\s*$/gm, '');
function page() {
  let owner = 'A', confirmResolve, resolveTopic;
  const calls = [];
  const session = { owner, messages: [], topicId: 1, storeId: 407 };
  const sandbox = { ref: value => ({ value }), aiSession: session, accountKey: () => owner,
    confirm: () => new Promise(resolve => { confirmResolve = resolve; }), toast() {}, go() {}, formatDate: () => '', normalizeDrug: x => x,
    onShow() {}, onHide() {}, onUnload() {}, clearTimeout, setTimeout,
    uni: { getStorageSync: () => 'token-' + owner, pageScrollTo() {} },
    createStreamController: () => ({ signal: { aborted: false }, abort() {} }),
    api: { session: () => ({ id: owner }), clearAiTopics: async () => calls.push('clear-' + owner), deleteAiTopic: async id => calls.push('delete-' + owner + '-' + id),
      aiTopic: () => new Promise(resolve => { resolveTopic = resolve; }), consultStream: request => { calls.push(request.topicId); return new Promise(() => {}); } } };
  vm.createContext(sandbox); vm.runInContext(source + ';loggedIn.value=true;globalThis.actions={clearHistory,deleteTopic,openTopic,send};', sandbox);
  return { session, calls, actions: sandbox.actions, setOwner: value => { owner = value; }, confirm: value => confirmResolve(value), topic: value => resolveTopic(value) };
}
test('confirmation for account A cannot clear or delete account B after a switch', async () => {
  for (const action of ['clearHistory', 'deleteTopic']) {
    const p = page(); const pending = p.actions[action]({ id: 1 });
    p.setOwner('B'); p.confirm(true); await pending; assert.deepEqual(p.calls, []);
  }
  const p = page(); const pending = p.actions.clearHistory(); p.confirm(true); await pending; assert.deepEqual(p.calls, ['clear-A']);
});
test('switching history blocks sending until the selected topic has loaded', async () => {
  const p = page(); const opening = p.actions.openTopic({ id: 2 });
  p.actions.send('adult followup'); assert.equal(p.session.messages.length, 0); assert.deepEqual(p.calls, []);
  p.topic({ topic: { id: 2, storeId: 407 }, turns: [], hasMore: false }); await opening;
  p.actions.send('adult followup'); assert.equal(p.session.messages.length, 1); assert.deepEqual(p.calls, [2]);
});
