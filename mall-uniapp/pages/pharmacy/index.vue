<template>
  <s-pharmacy-page tab="index">
    <view class="home-head fs-pad fs-white">
      <view class="fs-search" @tap="go('category')">
        <uni-icons type="search" size="21" color="#64716c" />
        <text class="fs-muted">搜索药品名称、通用名、条码</text>
      </view>
      <view class="store-line fs-between" @tap="showStore">
        <view class="fs-row fs-grow"><uni-icons type="location" size="17" color="#176b5b" /><text>{{ storeInfo.name }}</text></view>
        <uni-icons type="right" size="16" color="#758078" />
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
        <text>{{ categoryLabel(cat.name) }}</text>
      </view>
      <view class="shortcut-item" @tap="go('category')">
        <view class="shortcut-icon"><uni-icons type="list" size="24" color="#176b5b" /></view>
        <text>全部分类</text>
      </view>
    </view>
    <view class="fs-section home-products">
      <view class="fs-between">
        <view>
          <view class="fs-title">常备好药</view>
        </view>
        <button class="fs-text-btn" @tap="go('category')">
          查看全部
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
    <view class="fs-footer">处方药需药师审核。请按说明书或药师指导使用</view>
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
  const categoryLabel = name => {
    if (/消化|肠胃/.test(name)) return '消化用药';
    if (/维生素/.test(name)) return '维生素';
    if (/处方/.test(name)) return '处方药';
    if (/感冒/.test(name)) return '感冒用药';
    return name;
  };
  const { loading, error, run } = useRequest();
  const { busy, act } = useAction();
  const load = () =>
    run(async () => {
      list.value = (await api.products({ pageSize: 5 })).slice(0, 5);
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
  .home-head { padding-top: 12px; padding-bottom: 8px; }
  .store-line { padding: 10px 0 0; font-size: 14px; }
  .shortcuts {
    display: flex;
    padding: 8px 16px 12px;
    gap: 8px;
    box-sizing: border-box;
  }
  .shortcut-item {
    flex: 1 1 0;
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
    word-break: keep-all;
    overflow-wrap: break-word;
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
  .home-products {
    margin-top: 0;
  }
</style>
