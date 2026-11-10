import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// 顾客端 AI 侧栏删除对话：验证只删自己名下话题、当前话题删除后清空视图、失败不破坏列表。
const app = fs.readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8');
const css = fs.readFileSync(new URL('../src/live.css', import.meta.url), 'utf8');

function appFunction(name, context) {
  const line = app.split(/\r?\n/).find((row) => new RegExp('^(async )?function ' + name + '\\(').test(row));
  assert(line, 'App.vue must define ' + name);
  return vm.runInNewContext('(' + line + ')', context);
}

function harness(options = {}) {
  const calls = [];
  let owner = 'A';
  const topics = options.topics || [{ id: 11, title: '板蓝根颗粒有什么禁忌？' }, { id: 12, title: '查询板蓝根颗粒的价格和库存' }];
  const context = {
    user: { value: options.loggedOut ? null : { userId: 1 } },
    topics: { value: topics.map((row) => ({ ...row })) },
    topicId: { value: options.activeId === undefined ? 11 : options.activeId },
    topicStore: { value: 9101 },
    messages: { value: [{ id: 'm1', content: '板蓝根颗粒有什么禁忌' }] },
    moreTurns: { value: true },
    moreTopics: { value: true },
    historyLoading: { value: false },
    deleting: { value: false },
    pendingDeleteId: { value: null },
    clearPending: { value: false },
    historyError: { value: '' },
    generation: 1,
    currentOwner: () => owner,
    api: {
      deleteAiTopic: async (id) => {
        calls.push(['delete', id]);
        if (options.deleteError) throw new Error(options.deleteError);
      },
      clearAiTopics: async () => {
        calls.push(['clear']);
        if (options.clearError) throw new Error(options.clearError);
      }
    },
    stop: () => calls.push(['stop']),
    notify: (text) => calls.push(['notify', text]),
    history: { replaceState: (_state, _title, url) => calls.push(['url', url]) }
  };
  return {
    calls,
    context,
    switchOwner: () => {
      owner = 'B';
    },
    deleteTopic: appFunction('deleteTopic', context),
    clearTopics: appFunction('clearTopics', context),
    askDelete: appFunction('askDeleteTopic', context),
    askClear: appFunction('askClearTopics', context)
  };
}

test('deleting the open conversation clears the view and returns to a blank topic', async () => {
  const h = harness({ activeId: 11 });
  await h.deleteTopic({ id: 11, title: '板蓝根颗粒有什么禁忌？' });
  assert.deepEqual(h.calls.find((row) => row[0] === 'delete'), ['delete', 11]);
  assert.equal(h.context.topics.value.length, 1);
  assert.equal(h.context.topics.value[0].id, 12);
  assert.equal(h.context.topicId.value, null);
  assert.equal(h.context.topicStore.value, null);
  assert.equal(h.context.messages.value.length, 0);
  assert.equal(h.context.moreTurns.value, false);
  assert.ok(h.calls.some((row) => row[0] === 'stop'), 'an in-flight answer must be stopped');
  assert.deepEqual(h.calls.find((row) => row[0] === 'url'), ['url', '#ai']);
  assert.equal(h.context.pendingDeleteId.value, null);
  assert.equal(h.context.deleting.value, false);
});

test('deleting another conversation leaves the open one untouched', async () => {
  const h = harness({ activeId: 12 });
  await h.deleteTopic({ id: 11, title: '板蓝根颗粒有什么禁忌？' });
  assert.equal(h.context.topics.value.length, 1);
  assert.equal(h.context.topicId.value, 12);
  assert.equal(h.context.messages.value.length, 1);
  assert.ok(!h.calls.some((row) => row[0] === 'stop'), 'must not stop the answer of an unrelated topic');
  assert.ok(!h.calls.some((row) => row[0] === 'url'));
});

test('a failed delete keeps the row, surfaces the error and releases the button', async () => {
  const h = harness({ deleteError: '话题不存在或无权访问' });
  await h.deleteTopic({ id: 11, title: '板蓝根颗粒有什么禁忌？' });
  assert.equal(h.context.topics.value.length, 2);
  assert.equal(h.context.historyError.value, '话题不存在或无权访问');
  assert.equal(h.context.deleting.value, false);
  assert.ok(!h.calls.some((row) => row[0] === 'notify'));
});

test('a response arriving after the account switched cannot rewrite the list', async () => {
  const h = harness();
  const pending = h.deleteTopic({ id: 11, title: '板蓝根颗粒有什么禁忌？' });
  h.switchOwner();
  await pending;
  assert.equal(h.context.topics.value.length, 2);
  assert.equal(h.context.topicId.value, 11);
  assert.ok(!h.calls.some((row) => row[0] === 'url'));
});

test('deleting requires a signed-in member and ignores double clicks', async () => {
  const signedOut = harness({ loggedOut: true });
  await signedOut.deleteTopic({ id: 11, title: '板蓝根颗粒有什么禁忌？' });
  assert.equal(signedOut.calls.length, 0);
  const busy = harness();
  busy.context.deleting.value = true;
  await busy.deleteTopic({ id: 11, title: '板蓝根颗粒有什么禁忌？' });
  assert.equal(busy.calls.length, 0);
});

test('clearing history removes every topic and resets the conversation view', async () => {
  const h = harness();
  await h.clearTopics();
  assert.deepEqual(h.calls.find((row) => row[0] === 'clear'), ['clear']);
  assert.equal(h.context.topics.value.length, 0);
  assert.equal(h.context.topicId.value, null);
  assert.equal(h.context.messages.value.length, 0);
  assert.equal(h.context.moreTopics.value, false);
  assert.equal(h.context.clearPending.value, false);
  assert.deepEqual(h.calls.find((row) => row[0] === 'notify'), ['notify', '对话已清空']);
});

test('the confirmation step must be opened before the destructive call runs', () => {
  const h = harness();
  h.askDelete(12);
  assert.equal(h.context.pendingDeleteId.value, 12);
  h.askDelete(12);
  assert.equal(h.context.pendingDeleteId.value, null, 'clicking the same row again cancels');
  h.askClear();
  assert.equal(h.context.clearPending.value, true);
  assert.equal(h.context.pendingDeleteId.value, null, 'opening clear closes any pending row');
  assert.equal(h.calls.length, 0, 'confirmation alone must not call the API');
});

test('the sidebar exposes a per-row delete button and a clear action', () => {
  assert.match(app, /class="topic-del"/);
  assert.match(app, /@click\.stop="askDeleteTopic\(t\.id\)"/);
  assert.match(app, /aria-label="清空全部对话"/);
  assert.match(app, /确认删除/);
  assert.match(app, /pendingDeleteId===t\.id/);
  assert.match(css, /\.history-row \.topic-del\{/);
  assert.match(css, /@media\(hover:none\)\{\.history-row \.topic-del\{opacity:1\}\}/);
});
