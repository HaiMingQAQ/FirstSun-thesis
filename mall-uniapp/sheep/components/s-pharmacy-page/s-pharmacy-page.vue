<template>
  <view class="fs-page" :class="{ 'has-tabs': !!tab, 'has-dock': dock }">
    <slot />
    <view class="fs-footer fs-demo">
      {{
        PHARMACY_DEMO
          ? '测试体验 · 商品价格及库存为模拟数据'
          : '受限演示 · 商品价格及库存来自演示环境'
      }}
    </view>
    <s-pharmacy-tabbar v-if="!!tab" :current="tab" />
  </view>
</template>
<script setup>
  import { PHARMACY_DEMO } from '@/sheep/api/pharmacy/config';
  defineOptions({ options: { styleIsolation: 'apply-shared' } });
  defineProps({ tab: { type: String, default: '' }, dock: Boolean });
</script>
