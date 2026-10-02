<template>
  <s-pharmacy-page tab="ai" dock>
    <view class="ai-intro">AI 问药参考 · 不替代诊疗，处方药须药师审核</view>
    <view v-if="loggedIn" class="ai-toolbar">
      <text>{{ aiSession.topicId ? '当前话题' : '新话题' }}</text>
      <button class="fs-text-btn" :disabled="historyBusy" @tap="newTopic">新话题</button>
      <button class="fs-text-btn" :disabled="busy || historyBusy" @tap="showHistory">历史记录</button>
    </view>
    <view v-if="historyOpen" class="fs-section history-panel">
      <view class="fs-between"><text class="fs-title">我的询问记录</text><button class="fs-text-btn" @tap="historyOpen = false">关闭</button></view>
      <view class="fs-muted">健康问答保存在当前会员账户，可删除；不会用于自动诊断。</view>
      <s-pharmacy-state v-if="historyBusy || historyError || !topics.length" :loading="historyBusy" :error="historyError" title="还没有询问记录" action="重新加载" @retry="showHistory" />
      <view v-for="topic in topics" :key="topic.id" class="history-row">
        <button class="history-title" :disabled="historyBusy" @tap="openTopic(topic)">{{ topic.title }}<text>{{ formatDate(topic.createTime) }}</text></button>
        <button class="fs-text-btn" :disabled="historyBusy" @tap="deleteTopic(topic)">删除</button>
      </view>
      <button v-if="moreTopics" class="fs-outline fs-gap" :disabled="historyBusy" @tap="showHistory(true)">更多历史话题</button>
      <button v-if="topics.length" class="fs-text-btn fs-gap" :disabled="historyBusy" @tap="clearHistory">清空历史记录</button>
    </view>
    <s-pharmacy-state v-if="!loggedIn" title="登录后使用 AI 助手" action="去登录" @retry="go('login')" />
    <view v-else class="chat-content" :style="keyboardHeight ? { paddingBottom: keyboardHeight + 'px' } : {}">
      <button v-if="moreTurns" class="fs-outline fs-gap" :disabled="historyBusy || busy" @tap="earlierTurns">查看更早记录</button>
      <view v-if="!messages.length" class="ai-welcome fs-section">
        <view class="assistant-mark"><uni-icons type="chatbubble-filled" color="#fff" size="28" /></view>
        <view class="fs-heading fs-gap">有什么可以帮您？</view>
        <view class="fs-muted fs-gap">可以聊聊您的不舒服，也可以查询本店药品。需要人工帮助时，我会引导您联系药师。</view>
        <view class="fs-muted fs-gap history-notice">问答会发送给配置的模型服务商，并保存在当前会员账户；可在历史记录中删除。</view>
        <button v-for="text in prompts" :key="text" class="fs-outline fs-gap" :disabled="busy" @tap="send(text)">{{ text }}</button>
      </view>
      <view v-for="message in messages" :key="message.id" class="ai-turn fs-section">
        <view class="question">{{ message.content }}</view>
        <view class="assistant-label fs-gap"><uni-icons type="chatbubble-filled" size="15" color="#176b5b" /> FirstSun AI 助手</view>
        <view v-if="message.loading && !message.answer" class="thinking fs-gap"><text>正在思考</text><view class="thinking-dot" /><view class="thinking-dot" /><view class="thinking-dot" /></view>
        <view class="answer fs-gap">{{ displayAnswer(message.answer) }}<text v-if="message.loading && message.answer" class="cursor">▍</text></view>
        <view v-if="message.error || ['PENDING', 'STOPPED'].includes(message.status)" class="fs-muted fs-gap">{{ message.error || (message.status === 'STOPPED' ? '已停止显示，本条回复可能不完整' : '请求处理中，请稍后查看结果') }}</view>
        <button v-if="message.error || ['PENDING', 'STOPPED', 'FAILED'].includes(message.status)" class="fs-outline fs-gap" :disabled="busy" @tap="retry(message)">重试 / 查看结果</button>
        <s-pharmacy-product v-for="product in message.products" :key="product.id" :product="product" />
        <view v-if="message.source && message.products.length" class="fs-muted fs-gap">{{ message.source }}<view>{{ message.queriedAt }}</view></view>
        <view v-if="message.status === 'SUCCESS'" class="support-actions fs-gap">
          <button class="fs-text-btn" @tap="go('consultation')">联系门店药师</button>
          <button class="fs-text-btn" @tap="doctorDemo">医生咨询（模拟）</button>
        </view>
      </view>
    </view>
    <view v-if="loggedIn" class="fs-dock above-tabs ai-compose" :style="keyboardHeight ? { bottom: keyboardHeight + 'px' } : {}">
      <textarea v-model="input" maxlength="500" :disabled="busy || historyBusy" :adjust-position="false" placeholder="描述症状，或输入药品名称" @keyboardheightchange="keyboardHeight = $event.detail.height" />
      <button v-if="busy" class="fs-secondary" @tap="stop">停止</button>
      <button v-else class="fs-primary" :disabled="historyBusy || !input.trim()" @tap="send(input)">发送</button>
    </view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onShow, onHide, onUnload } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { createStreamController } from '@/sheep/api/pharmacy/sse';
  import { go, accountKey, confirm, toast } from './usePharmacy';
  import { aiSession } from './ai-session';
  import { normalizeDrug, formatDate } from '@/sheep/api/pharmacy/common';
  const messages = aiSession.messages;
  const input = ref(''), busy = ref(false), loggedIn = ref(false), keyboardHeight = ref(0);
  const historyOpen = ref(false), historyBusy = ref(false), historyError = ref(''), topics = ref([]), moreTopics = ref(false), moreTurns = ref(false);
  const displayAnswer = text => (text || '').replace(/\*\*([^*]+)\*\*/g, '$1');
  const prompts = ['我感冒了，有什么用药建议？', '我有点肚子疼，应该怎么办？', '查询板蓝根是否有货'];
  let generation = 0, controller, active, typingTimer, scrollAt = 0;
  const newId = () => `consult_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  function stop() {
    generation++; controller?.abort(); clearTimeout(typingTimer); typingTimer = null; keyboardHeight.value = 0;
    if (active?.loading) { active.loading = false; active.status = 'STOPPED'; }
    busy.value = false;
  }
  function newTopic() {
    stop(); messages.splice(0); aiSession.topicId = null; aiSession.storeId = null;
    input.value = ''; moreTurns.value = false; historyOpen.value = false; uni.pageScrollTo({ scrollTop: 0, duration: 0 });
  }
  async function showHistory(append = false) {
    if (historyBusy.value) return;
    const owner = accountKey(), g = generation; historyOpen.value = true; historyBusy.value = true; historyError.value = '';
    try {
      if (!api.aiTopics) throw new Error('历史记录需要连接真实后端');
      const rows = await api.aiTopics(append === true ? topics.value[topics.value.length - 1]?.id : undefined);
      if (owner !== accountKey() || g !== generation) return;
      topics.value = append === true ? [...topics.value, ...rows] : rows; moreTopics.value = rows.length === 30;
    } catch (e) { if (owner === accountKey() && g === generation) historyError.value = e.message || '历史记录加载失败'; }
    finally { historyBusy.value = false; }
  }
  function restoreTurn(turn) {
    const result = turn.response;
    return { id: turn.clientMessageId, recordId: turn.id, content: turn.content, context: [], answer: result?.answer || '', products: productCards(result?.products),
      status: result?.status || 'PENDING', source: result?.source, queriedAt: formatDate(result?.queriedAt), loading: false };
  }
  async function openTopic(topic, beforeId) {
    if (historyBusy.value) return;
    stop(); const owner = accountKey(), g = generation; historyBusy.value = true; historyError.value = '';
    try {
      const detail = await api.aiTopic(topic.id, beforeId);
      if (owner !== accountKey() || g !== generation) return;
      const restored = detail.turns.map(restoreTurn);
      if (beforeId) messages.unshift(...restored); else messages.splice(0, messages.length, ...restored);
      aiSession.topicId = detail.topic.id; aiSession.storeId = detail.topic.storeId; moreTurns.value = detail.hasMore; historyOpen.value = false;
    } catch (e) { if (owner === accountKey() && g === generation) { historyError.value = e.message || '加载话题失败'; toast(historyError.value); } }
    finally { historyBusy.value = false; }
  }
  function earlierTurns() { return openTopic({ id: aiSession.topicId }, messages[0]?.recordId); }
  async function deleteTopic(topic) {
    if (historyBusy.value || busy.value) return;
    const owner = accountKey(), token = uni.getStorageSync('token'), g = generation; historyBusy.value = true;
    const current = () => owner === accountKey() && token === uni.getStorageSync('token') && g === generation;
    try { if (!await confirm('删除话题', '删除该话题的询问和回复？此操作不可恢复。') || !current()) return; await api.deleteAiTopic(topic.id); if (!current()) return; if (aiSession.topicId === topic.id) newTopic(); topics.value = topics.value.filter(t => t.id !== topic.id); }
    catch (e) { if (owner === accountKey()) toast(e.message || '删除失败'); }
    finally { historyBusy.value = false; }
  }
  async function clearHistory() {
    if (historyBusy.value || busy.value) return;
    const owner = accountKey(), token = uni.getStorageSync('token'), g = generation; historyBusy.value = true;
    const current = () => owner === accountKey() && token === uni.getStorageSync('token') && g === generation;
    try { if (!await confirm('清空历史', '删除本账户的所有 AI 话题和已保存问答？此操作不可恢复。') || !current()) return; await api.clearAiTopics(); if (!current()) return; newTopic(); topics.value = []; moreTopics.value = false; }
    catch (e) { if (owner === accountKey()) toast(e.message || '清空失败'); }
    finally { historyBusy.value = false; }
  }
  function productCards(products = []) {
    return products.map(p => normalizeDrug({ id: p.id, tradeName: p.name, genericName: p.genericName, specification: p.specification, manufacturer: p.manufacturer,
      isRx: 0, memberPrice: p.price }, { stock: p.availableQty, image: p.imageUrl, approval: p.approvalNo }));
  }
  function doctorDemo() {
    uni.showModal({ title: '医生咨询（模拟）', content: '此入口仅展示毕设咨询流程，尚未接入真实医生。您可以联系本店药师；紧急不适请及时就医。', confirmText: '联系药师', cancelText: '关闭', success: result => { if (result.confirm) go('consultation'); } });
  }
  async function query(message) {
    if (busy.value || historyBusy.value || !api.session()) return;
    const owner = accountKey(), token = uni.getStorageSync('token'), g = generation;
    const current = () => g === generation && owner === accountKey() && loggedIn.value;
    clearTimeout(typingTimer); typingTimer = null;
    controller = createStreamController(); const signal = controller.signal;
    busy.value = true; active = message; message.loading = true; message.error = ''; message.answer = ''; message.products = [];
    const queue = [];
    const type = () => {
      if (!current() || signal.aborted) return;
      message.answer += queue.splice(0, 4).join('');
      if (Date.now() - scrollAt > 250) { scrollAt = Date.now(); uni.pageScrollTo({ scrollTop: 100000, duration: 0 }); }
      typingTimer = queue.length ? setTimeout(type, 20) : null;
    };
    try {
      if (!api.consultStream) throw new Error('演示适配器不提供真实 AI 流式咨询，请使用真实后端');
      if (!aiSession.topicId) {
        const topic = await api.createAiTopic(); if (!current() || signal.aborted) return;
        aiSession.topicId = topic.id; aiSession.storeId = topic.storeId;
      }
      const result = await api.consultStream({ clientMessageId: message.id, content: message.content, context: message.context, topicId: aiSession.topicId, storeId: aiSession.storeId }, (event, data) => {
        if (!current()) return;
        if (event === 'delta') { queue.push(...Array.from(data.text || '')); if (!typingTimer) type(); }
      }, signal);
      while (queue.length && current() && !signal.aborted) await new Promise(resolve => setTimeout(resolve, 20));
      if (!current() || signal.aborted) return;
      message.status = result.status; message.answer = result.answer;
      message.error = result.status === 'FAILED' ? '本次回复未完成，请重试' : '';
      message.source = result.source; message.queriedAt = formatDate(result.queriedAt);
      message.products = productCards(result.products);
    } catch (error) {
      if (error.code === 401 && g === generation && (!api.session() || (owner === accountKey() && token === uni.getStorageSync('token')))) { stop(); messages.splice(0); loggedIn.value = false; return; }
      if (!current()) return;
      message.status = 'NETWORK_ERROR'; message.error = error?.message || '回复中断，请重试';
    } finally {
      if (current()) { clearTimeout(typingTimer); typingTimer = null; message.loading = false; busy.value = false; }
    }
  }
  function send(text) {
    if (busy.value || historyBusy.value || !text.trim()) return;
    const context = messages.filter(m => m.status === 'SUCCESS').flatMap(m => [{ role: 'user', content: m.content }, { role: 'assistant', content: m.answer.slice(0, 1000) }]).slice(-6);
    messages.push({ id: newId(), content: text.trim(), context, answer: '', products: [], loading: true }); input.value = '';
    query(messages[messages.length - 1]);
  }
  function retry(message) { if (message.status === 'FAILED') message.id = newId(); query(message); }
  onShow(() => {
    const owner = accountKey(); loggedIn.value = !!api.session();
    if (owner !== aiSession.owner || !loggedIn.value) { newTopic(); topics.value = []; historyError.value = ''; }
    aiSession.owner = owner;
  });
  onHide(stop); onUnload(stop);
</script>
<style scoped>
  .ai-intro { padding: 12px 16px; font-size: 12px; color: #62756c; background: #eef3f0; }
  .ai-toolbar { position: sticky; top: var(--window-top, 0px); z-index: 20; padding: 4px 16px; display: flex; align-items: center; background: #fff; border-bottom: 1px solid #e2e9e4; }
  .ai-toolbar > text { flex: 1; color: #62756c; font-size: 12px; }
  .ai-toolbar .fs-text-btn { font-size: 13px; }
  .history-row { display: flex; align-items: center; gap: 8px; border-bottom: 1px solid #e2e9e4; }
  .history-title { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-start; text-align: left; background: #fff; }
  .history-title text { font-size: 11px; color: #62756c; }
  .thinking { display: flex; align-items: center; gap: 5px; color: #62756c; font-size: 13px; }
  .thinking-dot { width: 5px; height: 5px; background: #176b5b; border-radius: 50%; animation: thinking 1.2s infinite ease-in-out; }
  .thinking-dot:nth-child(3) { animation-delay: 0.2s; }
  .thinking-dot:nth-child(4) { animation-delay: 0.4s; }
  @keyframes thinking { 0%, 80%, 100% { opacity: 0.25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }
  .ai-welcome { padding-top: 24px; }
  .assistant-mark { width: 52px; height: 52px; display: flex; align-items: center; justify-content: center; border-radius: 16px; background: #176b5b; }
  .question { margin-left: 24px; background: #e5f0e9; border-radius: 14px 14px 4px 14px; padding: 12px 16px; white-space: pre-wrap; }
  .assistant-label { color: #176b5b; font-size: 12px; display: flex; gap: 6px; align-items: center; }
  .answer { white-space: pre-wrap; line-height: 1.85; font-size: 15px; overflow-wrap: break-word; }
  .cursor { color: #176b5b; }
  .support-actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .support-actions .fs-text-btn { font-size: 12px; min-height: 36px; border: 1px solid #e2e9e4; }
  .ai-compose { align-items: flex-end; }
  .ai-compose textarea { flex: 1; min-width: 0; height: 48px; padding: 10px; font-size: 14px; }
</style>
