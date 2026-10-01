<template>
  <s-pharmacy-page :tab="0">
    <view class="home-head fs-pad fs-white">
      <view class="fs-between">
        <view>
          <view class="brand">
            FirstSun
          </view>
          <view class="fs-muted">您的身边药店</view>
        </view>
        <uni-icons type="shop" size="30" color="#176b5b" />
      </view>
      <view class="store-line fs-between fs-gap" @tap="showStore">
        <view class="fs-grow">
          <view class="store-title">
            <uni-icons type="location" size="17" color="#176b5b" />
            {{ storeInfo.name }}
            <text class="fs-tag">演示门店</text>
          </view>
          <view class="fs-muted">到店自提 / 门店配送 · {{ storeInfo.hours }}</view>
        </view>
        <uni-icons type="right" size="16" color="#758078" />
      </view>
      <view class="fs-search fs-gap" @tap="go('category')">
        <uni-icons type="search" size="21" color="#64716c" />
        <text class="fs-muted">搜索药品名称、通用名、条码</text>
      </view>
    </view>
    <view class="assistant-entry fs-pad fs-white">
      <view class="assistant-panel" @tap="go('ai')">
        <uni-icons type="chatbubble" size="28" color="#176b5b" />
        <view class="fs-grow"><view class="fs-title">购药智能助手</view><view class="fs-muted">查询药品档案与门店有货商品</view></view>
        <uni-icons type="right" size="18" color="#176b5b" />
      </view>
    </view>
    <view class="shortcuts fs-white">
      <view
        v-for="cat in categories.slice(1, 5)"
        :key="cat.id"
        class="shortcut-item"
        @tap="go('category?category=' + cat.id)"
      >
        <view class="shortcut-icon"><uni-icons :type="cat.icon" size="24" color="#176b5b" /></view>
        <text>{{ cat.name }}</text>
      </view>
      <view class="shortcut-item" @tap="go('category')">
        <view class="shortcut-icon"><uni-icons type="list" size="24" color="#176b5b" /></view>
        <text>全部</text>
      </view>
    </view>
    <view class="service-strip">
      <uni-icons type="checkmarkempty" size="16" color="#176b5b" />
      <text>门店履约</text>
      <text class="dot">·</text>
      <text>处方药需药师审核</text>
    </view>
    <view class="fs-section home-products">
      <view class="fs-between">
        <view>
          <view class="fs-title">常备好药</view>
          <view class="fs-muted">按需选购，遵循用药指导</view>
        </view>
        <button class="fs-text-btn" @tap="go('category')">
          全部
          <uni-icons type="right" size="14" color="#176b5b" />
        </button>
      </view>
      <s-pharmacy-state
        v-if="loading || error || !list.length"
        :loading="loading"
        :error="error"
        title="药品正在整理中"
        description="稍后再来看看，或尝试刷新"
        @retry="load"
      />
      <template v-else>
        <s-pharmacy-product
          v-for="product in list"
          :key="product.id"
          :product="product"
          add
          :busy="busy"
          @add="add"
        />
      </template>
    </view>
    <view class="fs-footer">请仔细阅读说明书，按说明书或药师指导使用</view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { go, toast, requireLogin, useRequest, useAction } from './usePharmacy';
  const list = ref([]);
  const storeInfo = ref(api.store);
  const categories = ref(api.categories);
  const { loading, error, run } = useRequest();
  const { busy, act } = useAction();
  const load = () =>
    run(async () => {
      list.value = (await api.products()).slice(0, 5);
      categories.value = api.categories;
      storeInfo.value = api.store;
    });
  const add = (p) => {
    if (p.rx) { if (requireLogin()) go('prescription-upload?drugId=' + p.id); return; }
    if (requireLogin())
      act(async () => {
        await api.add(p.id);
        toast('已加入购物车');
      });
  };
  const showStore = () =>
    uni.showModal({
      title: api.store.name,
      content: `${api.store.address}\n营业时间 ${api.store.hours}\n支持门店配送与到店自提，结算时选择。`,
      showCancel: false,
      confirmColor: '#176b5b',
    });
  onShow(load);
  onPullDownRefresh(async () => {
    await load();
    uni.stopPullDownRefresh();
  });
</script>
<style scoped>
  .brand {
    color: #176b5b;
    font-size: 25px;
    font-family: Georgia, serif;
    font-weight: 700;
    letter-spacing: -0.7px;
  }
  .brand text {
    font-size: 20px;
    margin-left: 6px;
    letter-spacing: 0;
  }
  .store-line {
    padding-top: 14px;
    border-top: 1px solid #edf0ee;
  }
  .store-title {
    font-size: 15px;
    font-weight: 600;
    margin-bottom: 4px;
  }
  .shortcuts {
    display: flex;
    padding: 2px 8px 16px;
    box-sizing: border-box;
  }
  .shortcut-item {
    flex: 1 1 20%;
    min-width: 0;
    box-sizing: border-box;
    background: #fff;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 0;
    gap: 8px;
    font-size: 12px;
  }
  .shortcut-item > text {
    display: block;
    width: 100%;
    min-height: 30px;
    line-height: 15px;
    text-align: center;
    white-space: normal;
    word-break: break-all;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
  .shortcut-icon {
    width: 44px;
    height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #f0f5f1;
    border-radius: 8px;
  }
  .service-strip {
    display: flex;
    gap: 8px;
    justify-content: center;
    align-items: center;
    padding: 10px 8px;
    color: #63766a;
    font-size: 12px;
  }
  .dot {
    color: #bcc8c0;
  }
  .home-products {
    margin-top: 0;
  }
  .assistant-entry { padding-top: 0; }
  .assistant-panel { display: flex; align-items: center; gap: 12px; padding: 16px 12px; background: #edf5f0; border: 1px solid #dce9df; border-radius: 10px; }
  .assistant-panel .fs-title { font-size: 17px; }
  .assistant-panel .fs-muted { font-size: 12px; }
</style>
