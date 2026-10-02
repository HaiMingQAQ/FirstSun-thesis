<template>
  <s-pharmacy-page dock>
    <s-pharmacy-state
      v-if="loading || error || !product"
      :loading="loading"
      :error="error"
      @retry="load"
    />
    <template v-else>
      <view v-if="product.rx" class="detail-restricted">
        <uni-icons type="locked" size="36" color="#62756c" />
        <view>处方审核前暂不展示包装和详细资料</view>
      </view>
      <view v-else class="detail-hero">
        <image
          :src="
            failed || !product.image ? '/static/pharmacy/medicine-placeholder.png' : product.image
          "
          mode="aspectFit"
          @error="failed = true"
          @tap="preview"
        />
        <text class="image-caption">包装图仅供识别，请以实物为准</text>
      </view>
      <view class="fs-section">
        <view class="fs-between">
          <text v-if="!product.rx" class="fs-price detail-price">¥{{ money(product.price) }}</text>
          <text class="fs-muted">
            {{ product.stock ? `库存 ${product.stock} 件` : '暂时缺货' }}
          </text>
        </view>
        <view class="detail-title fs-gap">
          <text class="fs-tag" :class="{ 'fs-tag-rx': product.rx }">
            {{ product.device ? '器械' : product.rx ? '处方药' : 'OTC' }}
          </text>
          {{ product.name }}
        </view>
        <view class="fs-muted">通用名：{{ product.genericName }}</view>
        <view class="fs-gap fs-small">{{ product.specification }}</view>
      </view>
      <view v-if="product.rx" class="fs-notice">
        处方药须经药师审核后购买。请先提交私有处方材料，审核通过后按核准明细结算。
      </view>
      <view v-if="!product.rx" class="fs-section">
        <view class="fs-title">药品信息</view>
        <view class="detail-field">
          <text>规格</text>
          <text>{{ product.specification }}</text>
        </view>
        <view class="detail-field">
          <text>生产厂家</text>
          <text>{{ product.manufacturer }}</text>
        </view>
        <view class="detail-field">
          <text>{{ product.device ? '备案信息' : '批准文号' }}</text>
          <text>{{ product.approval }}</text>
        </view>
        <view class="detail-field">
          <text>履约门店</text>
          <text>{{ api.store.name }}</text>
        </view>
      </view>
      <view class="fs-section">
        <view class="fs-title">用药提示</view>
        <view class="fs-muted fs-gap">
          请仔细阅读药品说明书，按说明书或在药师指导下使用。处方药请遵医嘱；本页面不提供诊断或个体化用药建议。
        </view>
      </view>
      <view v-if="!product.rx" class="fs-section fs-between">
        <text>购买数量</text>
        <s-pharmacy-stepper v-model="qty" :max="product.stock" :disabled="!product.stock || busy" />
      </view>
      <view v-if="product.rx" class="fs-dock">
        <button class="fs-primary fs-grow" @tap="requireLogin() && go('prescription-upload?drugId=' + product.id)">提交处方申请</button>
      </view>
      <view v-else class="fs-dock">
        <button class="fs-text-btn" aria-label="查看购物车" @tap="go('cart')">
          <uni-icons type="cart" size="24" color="#176b5b" />
        </button>
        <button class="fs-secondary fs-grow" :disabled="busy || !product.active || !product.stock" @tap="add">
          加入购物车
        </button>
        <button class="fs-primary fs-grow" :disabled="busy || !product.active || !product.stock" @tap="buy">
          {{ !product.active ? '商品已失效' : product.stock ? '立即购买' : '暂时缺货' }}
        </button>
      </view>
    </template>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onLoad, onShow } from '@dcloudio/uni-app';
  import api, { money } from '@/sheep/api/pharmacy/client';
  import { go, toast, requireLogin, useRequest, useAction } from './usePharmacy';
  const id = ref(),
    product = ref(null),
    qty = ref(1),
    failed = ref(false);
  const { loading, error, run } = useRequest();
  const { busy, act } = useAction();
  const load = () =>
    run(async () => {
      failed.value = false;
      product.value = await api.product(id.value);
      qty.value = Math.max(1, Math.min(qty.value, product.value.stock));
    });
  const add = () => {
    if (!product.value?.active || product.value.rx || !product.value.stock) return;
    if (requireLogin())
      act(async () => {
        await api.add(product.value.id, qty.value);
        toast('已加入购物车');
      });
  };
  const buy = () => {
    if (!product.value?.active || product.value.rx || !product.value.stock) return;
    if (requireLogin()) {
      act(async () => {
        api.beginCheckout({ id: product.value.id, qty: qty.value });
        await go('checkout');
      });
    }
  };
  const preview = () =>
    uni.previewImage({ urls: [product.value.image || '/static/pharmacy/medicine-placeholder.png'] });
  onLoad((q) => {
    id.value = q.id;
  });
  onShow(load);
</script>
<style scoped>
  .detail-hero {
    background: #fff;
    padding: 22px;
    text-align: center;
  }
  .detail-hero image {
    width: 100%;
    height: 225px;
  }
  .image-caption {
    display: block;
    color: #829086;
    font-size: 11px;
    margin-top: 8px;
  }
  .detail-field {
    display: flex;
    gap: 20px;
    padding-top: 16px;
    font-size: 14px;
  }
  .detail-field text:first-child {
    color: #64716c;
    width: 64px;
    flex-shrink: 0;
  }
  .detail-field text:last-child {
    min-width: 0;
    flex: 1;
  }
  .detail-title { font-size: 22px; line-height: 1.5; font-weight: 600; }
  .detail-price { font-size: 28px; }
  .detail-restricted { height: 225px; background: #f3f6f3; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; padding: 24px; text-align: center; color: #62756c; font-size: 14px; }
  @media (max-width: 340px) { .detail-title { font-size: 20px; } }
</style>
