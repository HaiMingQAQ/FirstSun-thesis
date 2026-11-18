<template>
  <s-pharmacy-page assistant>
    <scroll-view scroll-x class="order-tabs fs-white">
      <view class="tabs-inner">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          :class="{ active: filter === tab.value }"
          @tap="filter = tab.value"
        >
          {{ tab.label }}
        </button>
      </view>
    </scroll-view>
    <view class="status-scroll-hint">左右滑动查看全部状态</view>
    <s-pharmacy-state
      v-if="!loggedIn"
      title="登录后查看订单"
      action="去登录"
      @retry="go('login')"
    />
    <s-pharmacy-state
      v-else-if="loading || error || !filtered.length"
      :loading="loading"
      :error="error"
      title="暂无相关订单"
      description="购买后的订单会显示在这里"
      action="去选购药品"
      @retry="error ? load() : go('category')"
    />
    <view v-else v-for="order in filtered" :key="order.id" class="fs-section">
      <view class="fs-between">
        <text class="fs-small">{{ order.store?.name || api.store.name }}</text>
        <text class="order-status">{{ orderStatusName(order) }}</text>
      </view>
      <view class="fs-muted fs-gap">订单号 {{ order.orderNo || order.id }}</view>
      <view v-for="row in (expanded[order.id] ? order.items : order.items.slice(0, 1))" :key="row.drugId" class="order-summary">
        <view class="fs-grow"><view class="summary-name">{{ row.product.genericName || row.product.name }}</view><view class="fs-muted">{{ row.product.specification || '规格未提供' }}</view></view>
        <text>× {{ row.qty }}</text>
      </view>
      <view v-if="!order.items.length" class="fs-muted fs-gap">商品摘要暂未提供，可查看订单详情</view>
      <view class="fs-between summary-count">
        <text class="fs-muted">{{ order.items.length ? itemKinds(order) + ' 款 · 共 ' + itemCount(order) + ' 件' : '' }}</text>
        <button v-if="order.items.length > 1" class="fs-text-btn" @tap="expanded[order.id] = !expanded[order.id]">{{ expanded[order.id] ? '收起商品' : '展开全部商品' }}</button>
      </view>
      <view class="order-bottom">
        <view class="fs-grow"><view class="fs-muted">{{ orderPaymentLabel(order) }}</view><text>{{ order.paid ? '实付合计' : '应付合计' }} </text><text class="fs-price">¥{{ money(order.total) }}</text></view>
        <s-pharmacy-order-actions :order="order" :mock-payment-available="mockPaymentAvailable" detail @change="load" />
      </view>
    </view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { onLoad, onShow, onPullDownRefresh } from '@dcloudio/uni-app';
  import api, { money, orderStatusName, orderPaymentLabel, statusNames } from '@/sheep/api/pharmacy/client';
  import { go, useRequest } from './usePharmacy';
  const orders = ref([]),
    filter = ref('all'),
    loggedIn = ref(false),
    mockPaymentAvailable = ref(false);
  const expanded = ref({});
  const itemCount = order => order.items.reduce((sum, row) => sum + Number(row.qty || 0), 0);
  const itemKinds = order => order.items.length;
  const { loading, error, run } = useRequest();
  const tabs = [
    { value: 'all', label: '全部' },
    ...Object.entries(statusNames).map(([value, label]) => ({ value, label })),
  ];
  const filtered = computed(() =>
    orders.value.filter((o) => filter.value === 'all' || o.status === filter.value),
  );
  const load = () =>
    run(async () => {
      mockPaymentAvailable.value = false;
      mockPaymentAvailable.value = await api.mockPaymentAvailable();
      orders.value = await api.orders();
    });
  onLoad((q) => (filter.value = q.status || 'all'));
  onShow(() => {
    loggedIn.value = !!api.session();
    if (loggedIn.value) load();
    else {
      orders.value = [];
      mockPaymentAvailable.value = false;
    }
  });
  onPullDownRefresh(async () => {
    if (loggedIn.value) await load();
    uni.stopPullDownRefresh();
  });
</script>
<style scoped>
  .order-tabs {
    width: 100%;
    white-space: nowrap;
  }
  .tabs-inner {
    display: inline-flex;
    min-width: 100%;
  }
  .tabs-inner button {
    display: inline-flex;
    padding: 14px 16px;
    background: #fff;
    flex-shrink: 0;
    border-radius: 0;
    font-size: 14px;
    color: #62756c;
  }
  .tabs-inner .active {
    color: #176b5b;
    border-bottom: 2px solid #176b5b;
    font-weight: 600;
  }
  .order-status {
    color: #176b5b;
    font-size: 14px;
  }
  .status-scroll-hint { font-size: 12px; color: #62756c; text-align: right; padding: 4px 16px 8px; background: #fff; }
  .order-summary { display: flex; align-items: flex-start; gap: 12px; padding-top: 12px; }
  .summary-name { font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }
  .summary-count { min-height: 32px; font-size: 13px; margin-top: 4px; }
  .order-bottom { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; border-top: 1px solid #e2e9e4; padding-top: 12px; margin-top: 4px; }
  .order-bottom :deep(.order-actions) { margin-top: 0; }
  .order-bottom :deep(.order-actions button) { padding: 8px 10px; }
  .order-tabs { border-bottom: 1px solid #e2e9e4; }
</style>
