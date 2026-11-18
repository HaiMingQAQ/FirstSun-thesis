<template>
  <s-pharmacy-page :dock="showActions">
    <s-pharmacy-state
      v-if="!loggedIn"
      title="登录后查看订单"
      action="去登录"
      @retry="go('login')"
    />
    <s-pharmacy-state
      v-else-if="loading || error || !order"
      :loading="loading"
      :error="error"
      @retry="load"
    />
    <template v-else>
      <view class="status-head">
        <view class="fs-row">
          <uni-icons
            :type="order.status === 'completed' ? 'checkbox-filled' : 'info'"
            size="30"
            color="#176b5b"
          />
          <view>
            <view class="fs-heading">{{ orderStatusName(order) }}</view>
            <view class="fs-muted fs-gap">{{ statusDescription }}</view>
          </view>
        </view>
      </view>
      <view class="fs-section">
        <view class="fs-between">
          <text class="fs-title">{{ order.mode === 'pickup' ? '到店自提' : '门店配送' }}</text>
          <text class="fs-tag">{{ orderPaymentLabel(order) }}</text>
        </view>
        <view class="fs-gap">
          {{
            order.mode === 'pickup'
              ? order.store.name
              : [order.address?.name, order.address?.mobile].filter(Boolean).join(' ')
          }}
        </view>
        <view class="fs-muted fs-gap">
          {{ order.mode === 'pickup' ? order.store.address : order.address?.detail }}
        </view>
        <view v-if="order.mode === 'pickup' && order.store.hours" class="fs-muted">
          营业时间 {{ order.store.hours }}
        </view>
        <view v-if="order.pickupCode" class="fs-between fs-gap">
          <view>
            <view class="fs-muted">提货码</view>
            <text class="pickup-code">{{ order.pickupCode }}</text>
          </view>
          <button class="fs-outline" @tap="copyPickupCode">复制提货码</button>
        </view>
      </view>
      <view class="fs-section">
        <view class="fs-title">{{ order.store.name }}</view>
        <s-pharmacy-product
          v-for="row in order.items"
          :key="row.drugId"
          :product="row.product"
          compact
          hide-stock
          :qty="row.qty"
        />
        <view class="fs-between fs-gap">
          <text>商品金额</text>
          <text>¥{{ money(order.subtotal) }}</text>
        </view>
        <view class="fs-between fs-gap">
          <text>配送费</text>
          <text>¥{{ money(order.shipping) }}</text>
        </view>
        <view class="fs-between fs-gap">
          <text>积分抵扣</text>
          <text>− ¥{{ money(order.points) }}</text>
        </view>
        <view class="fs-between fs-gap">
          <text>{{ order.paid ? '实付金额' : '应付金额' }}</text>
          <text class="fs-price">¥{{ money(order.total) }}</text>
        </view>
      </view>
      <view class="fs-section">
        <view class="fs-title">处方审核</view>
        <view class="fs-gap">
          <text class="fs-tag" :class="{ 'fs-tag-rx': order.prescriptions.length }">
            {{
              order.status === 'cancelled' && order.prescriptions.length
                ? '订单已取消，审核已终止'
                : order.review
            }}
          </text>
        </view>
        <view v-if="order.prescriptions.length" class="fs-muted fs-gap">
          已提交
          {{ order.prescriptions.length }}
          张处方。需经药师审核，不承诺审核结果。
        </view>
      </view>
      <view class="fs-section"><button class="fs-outline" @tap="go('consultation?kind=SERVICE&draft=' + encodeURIComponent('咨询订单：' + (order.orderNo || order.id)))">问订单与取货</button><button class="fs-text-btn" @tap="go('consultation?kind=PHARMACIST&draft=' + encodeURIComponent('咨询订单用药：' + (order.orderNo || order.id)))">问药师</button></view>
      <view class="fs-section">
        <view class="fs-title">订单信息</view>
        <view class="meta-row">
          <text>订单编号</text>
          <text>{{ order.orderNo || order.id }}</text>
        </view>
        <button v-if="order.orderNo" class="fs-outline copy-order-no" @tap="copyOrderNo">复制订单号</button>
        <view class="meta-row">
          <text>创建时间</text>
          <text>{{ order.createdAt }}</text>
        </view>
        <view class="meta-row">
          <text>付款状态</text>
          <text>{{ orderPaymentLabel(order) }}</text>
        </view>
        <view class="meta-row">
          <text>订单备注</text>
          <text>{{ order.remark || '无' }}</text>
        </view>
      </view>
      <view v-if="showActions" class="fs-dock order-dock">
        <s-pharmacy-order-actions :order="order" :mock-payment-available="mockPaymentAvailable" @change="load" />
      </view>
    </template>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { onLoad, onShow } from '@dcloudio/uni-app';
  import api, { money, orderStatusName, orderPaymentLabel } from '@/sheep/api/pharmacy/client';
  import { go, toast, useRequest } from './usePharmacy';
  const id = ref(''),
    order = ref(null),
    loggedIn = ref(false),
    mockPaymentAvailable = ref(false);
  const { loading, error, run } = useRequest();
  const showActions = computed(() => {
    const current = order.value;
    return !!current && (
      ['unpaid', 'review'].includes(current.status) ||
      (current.status === 'ready' && current.canReceive !== false)
    );
  });
  const readyDescription = (current) => {
    if (!current || current.readyForHandoff === false) return '门店正在备货，请等待履约状态更新';
    if (current.readyForHandoff === undefined) {
      return current.mode === 'pickup' ? '取药后可确认已取药' : '收到商品后可确认收货';
    }
    if (current.mode === 'delivery') return '请在收到商品后确认收货';
    return current.pickupCode ? '商品已备好，到店后请向店员出示提货码' : '商品已备好，请联系门店核实提货码';
  };
  const statusDescription = computed(
    () =>
      ({
        unpaid: '订单尚未支付，请核对金额后处理',
        review: '处方已提交，请等待药师审核',
        ready: readyDescription(order.value),
        completed: '感谢您的信任，请按说明书使用药品',
        cancelled: '订单已取消，积分返还以门店记录为准',
      }[order.value?.status]),
  );
  const copyPickupCode = () => {
    const code = order.value?.pickupCode;
    if (code) uni.setClipboardData({ data: code, fail: () => toast('复制失败，请向店员出示提货码') });
  };
  const copyOrderNo = () => {
    const orderNo = order.value?.orderNo;
    if (orderNo) uni.setClipboardData({ data: String(orderNo), fail: () => toast('复制失败，请查看页面上的订单编号') });
  };
  const load = () =>
    run(async () => {
      mockPaymentAvailable.value = false;
      mockPaymentAvailable.value = await api.mockPaymentAvailable();
      order.value = await api.order(id.value);
    });
  onLoad((q) => (id.value = q.id));
  onShow(() => {
    loggedIn.value = !!api.session();
    if (loggedIn.value) load();
    else {
      order.value = null;
      mockPaymentAvailable.value = false;
    }
  });
</script>
<style scoped>
  .status-head {
    padding: 24px 16px;
    background: #edf5f0;
  }
  .pickup-code {
    font-size: 22px;
    font-weight: 600;
    letter-spacing: 2px;
  }
  .meta-row {
    display: flex;
    justify-content: space-between;
    gap: 18px;
    font-size: 13px;
    padding-top: 16px;
  }
  .meta-row text:first-child {
    width: 60px;
    flex-shrink: 0;
    color: #64716c;
  }
  .meta-row text:last-child {
    min-width: 0;
    text-align: right;
  }
  .meta-row text:last-child { overflow-wrap: anywhere; }
  .copy-order-no { margin: 10px 0 0 auto; padding: 6px 12px; font-size: 13px; }
  .order-dock :deep(.order-actions) { width: 100%; margin-top: 0; }
</style>
