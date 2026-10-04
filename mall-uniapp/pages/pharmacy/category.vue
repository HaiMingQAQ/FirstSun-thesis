<template>
  <s-pharmacy-page tab="category">
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
        <button :class="{ active: inStock }" @tap="toggleFilter('stock')"><uni-icons :type="inStock ? 'checkbox-filled' : 'circle'" color="#176b5b" size="18" />仅看有货</button>
        <button :class="{ active: otcOnly }" @tap="toggleFilter('otc')"><uni-icons :type="otcOnly ? 'checkbox-filled' : 'circle'" color="#176b5b" size="18" />非处方商品</button>
      </view>
      <view class="category-results">
        <view class="fs-between result-title">
          <text class="fs-small">{{ keyword ? '搜索结果' : categoryName }}</text>
          <text class="fs-muted">{{ inStock || otcOnly ? '已加载匹配 ' + visibleList.length + ' 件' : '已加载 ' + list.length + ' / ' + total + ' 件' }}</text>
        </view>
        <s-pharmacy-state
          v-if="!visibleList.length && (loading || error || !hasMore)"
          :loading="loading"
          :error="error"
          title="没有找到相关药品"
          description="试试通用名，或切换其他分类"
          :action="error ? '重试' : '查看全部'"
          @retry="retry"
        />
        <s-pharmacy-product
          v-for="product in visibleList"
          :key="product.id"
          :product="product"
          add
          :busy="busy"
          @add="add"
        />
        <view v-if="visibleList.length && error" class="fs-footer"><text>{{ error }}</text><button class="fs-text-btn" @tap="loadMore">重试加载</button></view>
        <button v-if="hasMore && !error" class="fs-outline fs-gap" :disabled="loading" @tap="loadMore">{{ loading ? '加载中…' : '继续加载' }}</button>
        <view v-if="visibleList.length && !hasMore && !loading && !error" class="fs-footer">{{ inStock || otcOnly ? '已显示全部符合筛选条件的药品' : '已显示全部药品' }}</view>
      </view>
    </view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { onLoad, onShow, onReachBottom, onUnload } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { toast, requireLogin, useAction } from './usePharmacy';
  const keyword = ref(''),
    category = ref('all'),
    list = ref([]),
    history = ref(api.history());
  const inStock = ref(false), otcOnly = ref(false);
  const visibleList = computed(() => list.value.filter(p => (!inStock.value || p.active && p.stock > 0) && (!otcOnly.value || !p.rx)));
  const categories = ref(api.categories);
  const loading = ref(false), error = ref(''), total = ref(0), hasMore = ref(false);
  const pageSize = 20;
  let pageNo = 0, generation = 0, query = { keyword: '', category: 'all' };
  const { busy, act } = useAction();
  const categoryName = computed(
    () => categories.value.find((c) => String(c.id) === String(category.value))?.name || '全部药品',
  );
  async function load(reset = true) {
    if (!reset && (loading.value || !hasMore.value && pageNo > 0)) return;
    if (reset) {
      generation++; pageNo = 0; list.value = []; total.value = 0; hasMore.value = false;
      query = { keyword: keyword.value.trim(), category: category.value };
    }
    const g = generation, filters = { ...query }, previousVisible = visibleList.value.length;
    loading.value = true; error.value = '';
    try {
      do {
        const nextPage = pageNo + 1;
        const page = await api.productPage({ ...filters, pageNo: nextPage, pageSize });
        if (g !== generation) return;
        const more = nextPage * pageSize < page.total;
        if (!page.list.length && more) throw new Error('药品列表发生变化，请重新搜索');
        const ids = new Set(list.value.map(p => String(p.id)));
        for (const product of page.list) {
          if (!ids.has(String(product.id))) { list.value.push(product); ids.add(String(product.id)); }
        }
        pageNo = nextPage; total.value = page.total; hasMore.value = more;
        categories.value = api.categories;
        // Local stock/OTC filters can hide a whole page; inspect subsequent pages before declaring empty.
      } while (hasMore.value && visibleList.value.length === previousVisible);
    } catch (e) { if (g === generation) error.value = e.message || '药品加载失败，请重试'; }
    finally { if (g === generation) loading.value = false; }
  }
  const loadMore = () => load(false);
  function toggleFilter(type) {
    if (type === 'stock') inStock.value = !inStock.value; else otcOnly.value = !otcOnly.value;
    return load();
  }
  function search() {
    api.remember(keyword.value);
    history.value = api.history();
    category.value = 'all';
    return load();
  }
  function select(id) {
    category.value = id;
    return load();
  }
  function clearHistory() {
    api.clearHistory();
    history.value = [];
  }
  function retry() {
    if (error.value) return loadMore();
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
  onShow(() => load());
  onReachBottom(() => { if (hasMore.value && !error.value) loadMore(); });
  onUnload(() => { generation++; });
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
