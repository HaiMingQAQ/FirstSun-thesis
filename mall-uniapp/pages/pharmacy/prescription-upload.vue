<template>
  <s-pharmacy-page>
    <s-pharmacy-state
      v-if="!loggedIn"
      title="登录后查看处方"
      action="去登录"
      @retry="go('login')"
    />
    <template v-else-if="checkout">
      <s-pharmacy-state title="在线处方提交暂未开放" description="私有上传与审核购药能力尚未接通，请联系门店办理。此页不会上传或生成处方。" action="返回购物车" @retry="go('cart')" />
    </template>
    <template v-else>
      <view class="fs-pad fs-white">
        <view class="fs-title">我的处方</view>
        <view class="fs-muted">查看随订单提交的处方及审核状态</view>
      </view>
      <s-pharmacy-state
        v-if="loading || error || !prescriptions.length"
        :loading="loading"
        :error="error"
        title="暂无处方记录"
        description="仅展示已有订单关联记录；在线处方提交暂未开放"
        @retry="load"
      />
      <view
        v-else
        v-for="order in prescriptions"
        :key="order.id"
        class="fs-section"
        @tap="go('order-detail?id=' + order.id)"
      >
        <view class="fs-between">
          <text class="fs-title">{{ order.prescriptions.length }} 张处方</text>
          <text class="fs-tag fs-tag-rx">
            {{ order.status === 'cancelled' ? '审核已终止' : order.review }}
          </text>
        </view>
        <view class="fs-muted fs-gap">{{ order.id }}</view>
        <view class="fs-muted">{{ order.createdAt }}</view>
        <button class="fs-text-btn fs-gap">
          查看关联订单
          <uni-icons type="right" size="14" color="#176b5b" />
        </button>
      </view>
    </template>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onLoad, onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { go, useRequest } from './usePharmacy';
  const checkout = ref(false),
    loggedIn = ref(false),
    prescriptions = ref([]);
  const { loading, error, run } = useRequest();
  const load = () =>
    run(async () => {
      prescriptions.value = (await api.orders()).filter((o) => o.prescriptions.length);
    });
  onLoad((q) => {
    checkout.value = q.checkout === '1';
  });
  onShow(() => {
    loggedIn.value = !!api.session();
    if (loggedIn.value && !checkout.value) load();
  });
</script>
