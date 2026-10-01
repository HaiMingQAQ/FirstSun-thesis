<template>
  <s-pharmacy-page dock>
    <view class="fs-notice">AI 仅查询药品档案，不诊断、不开方、不代下单。用药问题请咨询药师。</view>
    <s-pharmacy-state v-if="!loggedIn" title="登录后使用购药助手" action="去登录" @retry="go('login')" />
    <template v-else>
      <view v-if="!messages.length" class="fs-section">
        <view class="fs-title">查找您需要的药品</view>
        <view class="fs-muted fs-gap">请输入药品名称，查询所选门店普通非处方商品与可售库存。</view>
        <button v-for="text in prompts" :key="text" class="fs-outline fs-gap" :disabled="busy" @tap="send(text)">{{ text }}</button>
      </view>
      <view v-for="message in messages" :key="message.id" class="fs-section">
        <view class="question">{{ message.content }}</view>
        <view class="assistant-label fs-gap">AI · 只读查询</view>
        <view v-if="message.loading" class="fs-muted fs-gap">正在查询…</view>
        <template v-else>
          <view class="fs-gap">{{ message.answer }}</view>
          <button v-if="message.error || message.status === 'PENDING'" class="fs-outline fs-gap" :disabled="busy" @tap="retry(message)">{{ message.status === 'PENDING' ? '查看查询结果' : '重新查询' }}</button>
          <s-pharmacy-product v-for="product in message.products" :key="product.id" :product="product" />
          <view v-if="message.source" class="fs-muted fs-gap">{{ message.source }}<view>{{ message.queriedAt }}</view></view>
        </template>
      </view>
    </template>
    <view v-if="loggedIn" class="fs-dock ai-compose">
      <textarea v-model="input" maxlength="500" :disabled="busy" placeholder="输入药品名称或查询问题" />
      <button class="fs-primary" :disabled="busy || !input.trim()" :loading="busy" @tap="send(input)">{{ busy ? '查询中' : '发送' }}</button>
    </view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { go, accountKey } from './usePharmacy';
  import { normalizeDrug, formatDate } from '@/sheep/api/pharmacy/common';
  const input = ref(''), messages = ref([]), busy = ref(false), loggedIn = ref(false);
  let owner = '', generation = 0;
  const prompts = ['查询感冒灵的商品档案', '查询板蓝根是否有货'];
  const newId = () => `consult_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  async function query(message) {
    if (busy.value || !api.session()) return;
    const requestOwner = accountKey(), requestGeneration = generation;
    busy.value = true; message.loading = true; message.error = false;
    try {
      const result = await api.consult({ clientMessageId: message.id, content: message.content });
      if (requestOwner !== accountKey() || requestGeneration !== generation) return;
      message.status = result.status; message.answer = result.answer;
      message.error = result.status === 'FAILED'; message.source = result.source; message.queriedAt = formatDate(result.queriedAt);
      message.products = (result.products || []).map(p => normalizeDrug({ id: p.id, tradeName: p.name,
        genericName: p.genericName, specification: p.specification, manufacturer: p.manufacturer,
        isRx: 0, memberPrice: p.price }, { stock: p.availableQty, image: p.imageUrl, approval: p.approvalNo }));
    } catch (error) {
      if (requestOwner !== accountKey() || requestGeneration !== generation) return;
      message.error = true; message.status = 'NETWORK_ERROR'; message.answer = error?.message || '查询失败，请稍后重试';
    } finally { message.loading = false; if (requestGeneration === generation) busy.value = false; }
  }
  function send(text) {
    if (busy.value || !text.trim()) return;
    messages.value.push({ id: newId(), content: text.trim(), products: [], loading: true }); input.value = '';
    query(messages.value[messages.value.length - 1]);
  }
  function retry(message) {
    // PENDING/network errors reuse IDs; explicit failed results start a new attempt.
    if (message.status === 'FAILED') message.id = newId();
    query(message);
  }
  onShow(() => {
    const account = accountKey();
    loggedIn.value = !!api.session();
    if (account !== owner || !loggedIn.value) { generation++; messages.value = []; input.value = ''; busy.value = false; }
    owner = account;
  });
</script>
<style scoped>
  .question { margin-left: 24px; background: #edf5f0; border-radius: 10px; padding: 12px 16px; }
  .assistant-label { color: #176b5b; font-size: 12px; }
  .ai-compose { align-items: flex-end; }
  .ai-compose textarea { flex: 1; min-width: 0; height: 48px; padding: 12px; font-size: 15px; }
</style>
