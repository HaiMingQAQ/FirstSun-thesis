<template>
  <s-pharmacy-page :tab="1">
    <view class="fs-pad fs-white">
      <view class="fs-search">
        <uni-icons type="search" size="20" color="#64716c" />
        <input
          v-model="keyword"
          placeholder="药品名称、通用名、条码"
          confirm-type="search"
          @confirm="search"
        />
        <button class="fs-text-btn" @tap="search">搜索</button>
      </view>
      <view v-if="history.length" class="history">
        <text class="fs-muted">最近</text>
        <text
          v-for="word in history.slice(0, 3)"
          :key="word"
          @tap="
            keyword = word;
            search();
          "
        >
          {{ word }}
        </text>
        <button class="fs-text-btn" aria-label="清空搜索历史" @tap="clearHistory">
          <uni-icons type="trash" size="16" color="#758078" />
        </button>
      </view>
    </view>
    <view class="category-layout">
      <view class="category-nav">
        <button
          v-for="cat in categories"
          :key="cat.id"
          :class="{ active: String(category) === String(cat.id) }"
          @tap="select(cat.id)"
        >
          {{ cat.name }}
        </button>
      </view>
      <view class="category-filters">
        <button :class="{ active: inStock }" @tap="inStock = !inStock"><uni-icons :type="inStock ? 'checkbox-filled' : 'circle'" color="#176b5b" size="18" />仅看有货</button>
        <button :class="{ active: otcOnly }" @tap="otcOnly = !otcOnly"><uni-icons :type="otcOnly ? 'checkbox-filled' : 'circle'" color="#176b5b" size="18" />非处方商品</button>
      </view>
      <view class="category-results">
        <view class="fs-between result-title">
          <text class="fs-small">{{ keyword ? '搜索结果' : categoryName }}</text>
          <text class="fs-muted">{{ visibleList.length }} 件</text>
        </view>
        <s-pharmacy-state
          v-if="loading || error || !visibleList.length"
          :loading="loading"
          :error="error"
          title="没有找到相关药品"
          description="试试通用名，或切换其他分类"
          action="查看全部"
          @retry="retry"
        />
        <s-pharmacy-product
          v-else
          v-for="product in visibleList"
          :key="product.id"
          :product="product"
          add
          :busy="busy"
          @add="add"
        />
        <view v-if="list.length && !loading && !error" class="fs-footer">已显示全部药品</view>
      </view>
    </view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { onLoad, onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { toast, requireLogin, useRequest, useAction } from './usePharmacy';
  const keyword = ref(''),
    category = ref('all'),
    list = ref([]),
    history = ref(api.history());
  const inStock = ref(false), otcOnly = ref(false);
  const visibleList = computed(() => list.value.filter(p => (!inStock.value || p.active && p.stock > 0) && (!otcOnly.value || !p.rx)));
  const categories = ref(api.categories);
  const { loading, error, run } = useRequest();
  const { busy, act } = useAction();
  const categoryName = computed(
    () => categories.value.find((c) => String(c.id) === String(category.value))?.name || '全部药品',
  );
  const load = () =>
    run(async () => {
      list.value = await api.products({ keyword: keyword.value, category: category.value });
      categories.value = api.categories;
    });
  function search() {
    if (loading.value) return;
    api.remember(keyword.value);
    history.value = api.history();
    category.value = 'all';
    load();
  }
  function select(id) {
    if (loading.value) return;
    category.value = id;
    load();
  }
  function clearHistory() {
    api.clearHistory();
    history.value = [];
  }
  function retry() {
    if (!error.value) {
      inStock.value = false;
      otcOnly.value = false;
      keyword.value = '';
      category.value = 'all';
    }
    load();
  }
  const add = (p) => {
    if (p.rx) return toast('处方购买暂未开放，请联系门店');
    if (requireLogin())
      act(async () => {
        await api.add(p.id);
        toast('已加入购物车');
      });
  };
  onLoad((q) => {
    category.value = q.category || 'all';
  });
  onShow(load);
</script>
<style scoped>
  .history { display: flex; gap: 12px; align-items: center; font-size: 12px; margin-top: 6px; }
  .history > text:not(:first-child) { max-width: 76px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .history button { margin-left: auto; }
  .category-layout { background: #fff; min-height: 70vh; }
  .category-nav { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 16px 12px; }
  .category-nav button { min-width: 0; max-width: 100%; padding: 8px 12px; font-size: 14px; background: #f4f6f3; color: #62756c; border: 1px solid #e2e9e4; border-radius: 8px; }
  .category-nav button.active { color: #176b5b; border-color: #176b5b; background: #edf5f0; font-weight: 600; }
  .category-filters { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 16px 12px; border-bottom: 7px solid #f4f6f3; }
  .category-filters button { background: transparent; color: #62756c; padding: 0; gap: 6px; font-size: 13px; }
  .category-results { padding: 0 16px; }
  .result-title { padding-top: 18px; }
  @media (max-width: 340px) { .category-nav, .category-filters, .category-results { padding-left: 14px; padding-right: 14px; } }
</style>
