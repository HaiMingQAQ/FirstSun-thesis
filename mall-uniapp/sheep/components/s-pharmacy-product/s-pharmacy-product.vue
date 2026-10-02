<template>
  <view class="fs-product" :class="{ compact }">
    <view v-if="product.rx" class="product-image rx-image" @tap="open">
      <uni-icons type="locked" size="24" color="#62756c" />
      <text>审核前不展示</text>
    </view>
    <image v-else
      class="product-image"
      :src="failed || !product.image ? '/static/pharmacy/medicine-placeholder.png' : product.image"
      mode="aspectFit"
      @error="failed = true"
      @tap="open"
    />
    <view class="product-content">
      <view class="product-name" @tap="open">
        <text class="fs-tag" :class="{ 'fs-tag-rx': product.rx }">
          {{ product.device ? '器械' : product.rx ? '处方药' : 'OTC' }}
        </text>
        {{ product.name }}
      </view>
      <view class="fs-muted specification" @tap="open">{{ product.specification }}</view>
      <view v-if="!hideStock" class="stock" :class="{ low: product.stock < 5 }">
        {{
          !product.active
            ? '商品已失效'
            : product.stock === 0
            ? '暂时缺货'
            : product.stock < 5
            ? `仅剩 ${product.stock} 件`
            : `有货 · 库存 ${product.stock}`
        }}
      </view>
      <view class="fs-between price-line">
        <text class="fs-price">¥{{ money(product.price) }}</text>
        <button
          v-if="add"
          class="add-button"
          :disabled="busy || product.rx || !product.stock || !product.active"
          aria-label="加入购物车"
          @tap.stop="$emit('add', product)"
        >
          <uni-icons type="plus" size="20" color="#176b5b" />
        </button>
        <text v-if="qty" class="fs-muted">× {{ qty }}</text>
      </view>
      <view v-if="qty" class="fs-muted">小计 ¥{{ money(product.price * qty) }}</view>
      <slot />
    </view>
  </view>
</template>
<script setup>
  defineOptions({ options: { styleIsolation: 'apply-shared' } });
  import { ref, watch } from 'vue';
  import { money } from '@/sheep/api/pharmacy/client';
  const props = defineProps({
    product: Object,
    add: Boolean,
    busy: Boolean,
    compact: Boolean,
    hideStock: Boolean,
    qty: Number,
  });
  defineEmits(['add']);
  const failed = ref(false);
  watch(() => props.product.image, () => (failed.value = false));
  const open = () => uni.navigateTo({ url: `/pages/pharmacy/detail?id=${props.product.id}` });
</script>
<style scoped>
  .fs-product {
    display: flex;
    gap: 13px;
    padding: 18px 0;
    border-bottom: 1px solid #e2e9e4;
  }
  .product-image {
    width: 90px;
    height: 99px;
    flex-shrink: 0;
    background: #fafbf9;
    border-radius: 8px;
    border: 1px solid #e2e9e4;
  }
  .product-content {
    flex: 1;
    min-width: 0;
  }
  .product-name {
    font-size: 16px;
    font-weight: 550;
    line-height: 1.55;
    overflow-wrap: anywhere;
  }
  .specification {
    margin-top: 5px;
  }
  .stock {
    color: #64796e;
    font-size: 13px;
    margin-top: 5px;
  }
  .low {
    color: #a55e22;
  }
  .price-line {
    margin-top: 5px;
    min-height: 44px;
  }
  .add-button {
    width: 44px;
    min-height: 44px;
    padding: 0;
    border-radius: 50%;
    background: #edf5f0;
    border: 1px solid #d7e6dd;
  }
  .compact {
    gap: 10px;
  }
  .compact .product-image {
    width: 62px;
    height: 68px;
  }
  .compact .fs-price {
    font-size: 19px;
  }
  .rx-image { display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 8px; color: #62756c; font-size: 11px; text-align: center; }
  .specification { font-size: 14px; }
  .price-line { flex-wrap: wrap; }
  @media (max-width: 340px) {
    .product-image { width: 76px; height: 88px; }
    .compact .product-image { width: 62px; height: 68px; }
  }
</style>
