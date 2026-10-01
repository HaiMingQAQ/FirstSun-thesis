<template>
  <s-pharmacy-page dock>
    <s-pharmacy-state v-if="!loggedIn" title="请先登录" action="去登录" @retry="go('login')" />
    <s-pharmacy-state
      v-else-if="error || !data"
      :loading="loading"
      :error="error"
      @retry="load"
    />
    <template v-else>
      <view class="fs-section">
        <view class="fs-choice">
          <button :class="{ active: mode === 'pickup' }" @tap="mode = 'pickup'">到店自提</button>
          <button :class="{ active: mode === 'delivery' }" @tap="mode = 'delivery'">
            门店配送
          </button>
        </view>
        <view v-if="mode === 'delivery'" class="fs-menu" @tap="go('address?select=1')">
          <view class="fs-grow">
            <view class="fs-title">
              {{ data.address ? data.address.name + '  ' + data.address.mobile : '请选择收货地址' }}
            </view>
            <view class="fs-muted fs-gap">
              {{ data.address ? data.address.detail : '添加地址，方便门店配送' }}
            </view>
          </view>
          <uni-icons type="right" size="18" />
        </view>
        <view v-else class="fs-gap">
          <view class="fs-title">{{ api.store.name }}</view>
          <view class="fs-muted fs-gap">
            {{ api.store.address }}
            <br />
            营业时间 {{ api.store.hours }}
          </view>
          <view class="fs-muted">请等待门店备货通知后到店取药</view>
        </view>
      </view>
      <view class="fs-section">
        <view class="fs-title">商品清单</view>
        <s-pharmacy-product
          v-for="row in data.items"
          :key="row.drugId"
          :product="row.product"
          compact
          hide-stock
          :qty="row.qty"
        />
        <view class="fs-muted fs-gap">履约门店：{{ api.store.name }}</view>
      </view>
      <view v-if="hasRx" class="fs-section">
        <view class="fs-between">
          <view class="fs-title">
            处方资料
            <text class="fs-tag fs-tag-rx">必填</text>
          </view>
          <text class="fs-muted">{{ data.prescriptions.length }}/3 张</text>
        </view>
        <view class="fs-notice fs-gap">
          本单使用药师核准明细与固定数量；提交时由服务器再次核验处方状态、归属及使用资格。
        </view>
        <view class="fs-muted fs-gap">有效期：{{ formatDate(data.prescription?.approvedUntil) }}</view>
      </view>
      <view class="fs-section">
        <view class="fs-between fs-field">
          <view class="fs-grow">
            <view>积分抵扣</view>
            <view class="fs-muted">可用 {{ data.points }} 积分 · {{ data.pointsRule }}</view>
            <view class="fs-muted">{{ data.maxDeductNote }}，配送费不参与</view>
          </view>
          <switch
            :checked="usePoints"
            :disabled="!data.points"
            color="#176b5b"
            @change="usePoints = $event.detail.value"
          />
        </view>
        <view class="fs-between fs-field">
          <text>支付方式</text>
          <text class="fs-tag">{{ mockPaymentAvailable ? '测试模拟支付（不扣款）' : '在线支付未开放' }}</text>
        </view>
        <view class="fs-field">
          <text class="fs-label">订单备注（选填）</text>
          <textarea v-model="remark" maxlength="200" placeholder="如配送前请先电话联系" />
          <view class="fs-muted">{{ remark.length }}/200</view>
        </view>
      </view>
      <view class="fs-section">
        <view class="fs-between">
          <text>商品金额</text>
          <text>¥{{ money(subtotal) }}</text>
        </view>
        <view class="fs-between fs-gap">
          <text>配送费</text>
          <text>{{ shipping ? '¥' + money(shipping) : '免配送费' }}</text>
        </view>
        <view class="fs-between fs-gap">
          <text>积分抵扣</text>
          <text>− ¥{{ money(discount) }}</text>
        </view>
        <view class="fs-between fs-gap">
          <text class="fs-title">应付合计</text>
          <text class="fs-price">¥{{ money(total) }}</text>
        </view>
      </view>
      <view class="fs-footer">{{ mockPaymentAvailable ? '独立测试环境演示：不会真实扣款或配送' : '提交后请联系门店确认支付及履约' }}</view>
      <view class="fs-dock">
        <view class="fs-grow">
          <text class="fs-small">合计</text>
          <text class="fs-price">¥{{ money(total) }}</text>
        </view>
        <button class="fs-primary" :disabled="loading || busy || submitted || (hasRx && !data.prescription)" :loading="busy" @tap="submit">
          {{ submitted ? '已提交' : busy ? '提交中…' : '提交订单' }}
        </button>
      </view>
    </template>
  </s-pharmacy-page>
</template>
<script setup>
  import { formatDate } from '@/sheep/api/pharmacy/common';
  import { ref, computed } from 'vue';
  import { onShow } from '@dcloudio/uni-app';
  import api, { money } from '@/sheep/api/pharmacy/client';
  import { go, toast, accountKey } from './usePharmacy';
  const data = ref(null),
    mode = ref('pickup'),
    usePoints = ref(false),
    remark = ref(''),
    loggedIn = ref(false),
    submitted = ref(false),
    mockPaymentAvailable = ref(false);
  const loading = ref(false), error = ref(''), busy = ref(false);
  let owner = '', generation = 0;
  const hasRx = computed(() => data.value?.items.some((r) => r.product.rx));
  const subtotal = computed(
    () => data.value?.items.reduce((s, r) => s + r.product.price * r.qty, 0) || 0,
  );
  const shipping = computed(() => data.value?.shipping?.[mode.value] ?? 0);
  const discount = computed(() => (usePoints.value ? data.value?.maxDeductFen || 0 : 0));
  const total = computed(() => subtotal.value + shipping.value - discount.value);
  const load = async () => {
      if (loading.value) return;
      loading.value = true; error.value = '';
      mockPaymentAvailable.value = false;
      const requestOwner = accountKey(), requestGeneration = generation;
      try {
      const paymentAvailable = await api.mockPaymentAvailable();
      if (requestOwner !== accountKey() || requestGeneration !== generation) return;
      // 返回地址/处方页后保留已挂载的表单，避免 H5 ResizeSensor 激活时引用已卸载节点。
      const result = await api.checkout();
      if (requestOwner !== accountKey() || requestGeneration !== generation) return;
      mockPaymentAvailable.value = paymentAvailable;
      data.value = result;
      } catch (e) {
        if (requestOwner === accountKey() && requestGeneration === generation) error.value = e.message || '结算加载失败';
      } finally {
        if (requestOwner === accountKey() && requestGeneration === generation) loading.value = false;
      }
  };
  const submit = () => {
    if (busy.value || submitted.value || !data.value || (hasRx.value && !data.value.prescription)) return;
    (async () => {
      const requestOwner = accountKey(), requestGeneration = generation;
      busy.value = true;
      try {
      const order = await api.createOrder({
        mode: mode.value,
        address: data.value.address,
        usePoints: usePoints.value,
        remark: remark.value,
      });
      if (requestOwner !== accountKey() || requestGeneration !== generation) return;
      submitted.value = true;
      toast('订单已提交');
      uni.redirectTo({ url: `/pages/pharmacy/order-detail?id=${order.id}` });
      } catch (e) {
        if (requestOwner === accountKey() && requestGeneration === generation) toast(e.message || '提交失败');
      } finally {
        if (requestOwner === accountKey() && requestGeneration === generation) busy.value = false;
      }
    })();
  };
  onShow(() => {
    const account = accountKey();
    if (account !== owner) {
      generation++; owner = account; data.value = null; remark.value = ''; usePoints.value = false;
      submitted.value = false; mockPaymentAvailable.value = false; loading.value = false; busy.value = false; error.value = '';
    }
    loggedIn.value = !!api.session();
    if (loggedIn.value && !submitted.value) load();
  });
</script>
