<template>
  <s-pharmacy-page>
    <view class="login-head">
      <view class="login-brand">FirstSun</view>
      <view class="fs-heading fs-gap">欢迎来到 FirstSun 药店</view>
      <view class="fs-muted fs-gap">登录后选购药品、查看订单与会员积分</view>
    </view>
    <view v-if="useTestLogin" class="fs-section">
      <view v-if="PHARMACY_H5_TEST_LOGIN" class="fs-notice">
        独立本机验收入口，登录由所连接的本机后端校验测试验证码。请仅使用合成账号与材料，后端数据须按验收环境单独配置。
      </view>
      <view v-else class="fs-notice">
        当前为测试环境，不发送短信、不产生真实消费。请使用测试手机号，避免填写个人敏感信息。
      </view>
      <view class="fs-field">
        <text class="fs-label">手机号</text>
        <input
          v-model="mobile"
          class="fs-input"
          type="number"
          maxlength="11"
          :disabled="busy"
          placeholder="请输入 11 位测试手机号"
        />
        <text v-if="mobile && !validMobile" class="fs-small fs-danger">请输入正确的手机号格式</text>
      </view>
      <view class="fs-field">
        <text class="fs-label">测试验证码</text>
        <view class="fs-row">
          <input
            v-model="code"
            class="fs-input fs-grow"
            type="number"
            maxlength="6"
            :disabled="busy"
            placeholder="6 位测试验证码"
          />
          <button v-if="PHARMACY_DEMO" class="fs-secondary" :disabled="busy || !validMobile" @tap="fillCode">获取测试码</button>
        </view>
        <view v-if="PHARMACY_DEMO" class="fs-muted fs-gap">点击“获取测试码”填入本地 Mock 演示验证码</view>
        <view v-else class="fs-muted fs-gap">请手工填写本次后端运行时配置的测试验证码；此入口不发送真实短信。</view>
      </view>
      <view class="agreement fs-gap">
        <button class="fs-check" :disabled="busy" aria-label="同意测试用户协议" @tap="agreed = !agreed">
          <uni-icons :type="agreed ? 'checkbox-filled' : 'circle'" size="22" color="#176b5b" />
        </button>
        <view class="fs-small">
          我已阅读并同意
          <text class="link" @tap="showTerms">《测试用户协议与隐私说明》</text>
        </view>
      </view>
      <button
        class="fs-primary fs-gap"
        :disabled="busy || !agreed || !validMobile || code.length !== 6"
        :loading="busy"
        @tap="login"
      >
        {{ busy ? '登录中…' : '登录 / 注册测试账户' }}
      </button>
    </view>
    <!-- #ifdef MP-WEIXIN -->
    <view v-else class="fs-section">
      <view class="fs-notice">
        微信登录由服务端验证本次小程序登录凭证，不需要填写手机号，也不会读取或提交 openid。
      </view>
      <view class="agreement fs-gap">
        <button class="fs-check" aria-label="同意用户协议" @tap="agreed = !agreed">
          <uni-icons :type="agreed ? 'checkbox-filled' : 'circle'" size="22" color="#176b5b" />
        </button>
        <view class="fs-small">
          我已阅读并同意
          <text class="link" @tap="showTerms">《用户协议与隐私说明》</text>
        </view>
      </view>
      <button
        class="fs-primary fs-gap"
        :disabled="busy || !agreed"
        :loading="busy"
        @tap="login"
      >
        {{ busy ? '登录中…' : '微信登录' }}
      </button>
    </view>
    <!-- #endif -->
    <!-- #ifndef MP-WEIXIN -->
    <view v-if="!useTestLogin" class="fs-section">
      <view class="fs-notice">真实微信登录仅支持微信小程序，请在微信开发者工具中打开。</view>
    </view>
    <!-- #endif -->
    <view v-if="PHARMACY_DEMO" class="fs-footer">无需微信授权 · 不接入真实短信服务</view>
    <view v-else-if="PHARMACY_H5_TEST_LOGIN" class="fs-footer">独立本机测试登录 · 使用真实后端校验</view>
    <view v-else class="fs-footer">仅支持微信小程序登录 · 不接入手机号授权</view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import api from '@/sheep/api/pharmacy/client';
  import { PHARMACY_DEMO, PHARMACY_H5_TEST_LOGIN, TEST_CODE } from '@/sheep/api/pharmacy/config';
  import { toast, useAction } from './usePharmacy';
  const mobile = ref(''),
    code = ref(''),
    agreed = ref(false);
  const { busy, act } = useAction();
  const useTestLogin = PHARMACY_DEMO || PHARMACY_H5_TEST_LOGIN;
  const validMobile = computed(() => /^1[3-9]\d{9}$/.test(mobile.value));
  const fillCode = () => {
    if (!PHARMACY_DEMO) return;
    code.value = TEST_CODE;
    toast('测试验证码已填入');
  };
  const showTerms = () =>
    uni.showModal({
      title: useTestLogin ? '测试用户协议与隐私说明' : '用户协议与隐私说明',
      content: PHARMACY_H5_TEST_LOGIN
        ? '本入口仅供独立本机验收，账号与手工输入的测试验证码由所连接的真实后端校验，后端须配置本次运行时验证码。请仅使用合成账号与处方材料；处方材料通过私有上传提交，须经药师审核后按核准明细购买。本入口不代表正式顾客网页登录或真实购药服务。'
        : PHARMACY_DEMO
        ? '本地 Mock 演示仅用于功能体验，请仅使用合成账号与材料，不提供真实购药服务。'
        : '本版本仅用于受限演示。微信登录凭证由服务端实时校验；处方材料使用私有上传并经药师审核，请仅使用合成材料与个人资料。正式服务协议将在上线前提供。',
      showCancel: false,
      confirmColor: '#176b5b',
    });
  const login = () =>
    act(async () => {
      if (!agreed.value) return;
      if (useTestLogin && (!validMobile.value || !/^\d{6}$/.test(code.value))) {
        toast('请填写正确的测试手机号与 6 位验证码');
        return;
      }
      if (useTestLogin) await api.login(mobile.value, code.value);
      else await api.wechatLogin();
      toast('登录成功');
      if (getCurrentPages().length > 1) uni.navigateBack();
      else uni.reLaunch({ url: '/pages/pharmacy/user' });
    });
</script>
<style scoped>
  .login-head {
    padding: 40px 24px 16px;
  }
  .agreement {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .link {
    color: #176b5b;
  }
  .login-brand { font-family: Georgia, serif; font-size: 32px; color: #164d43; font-weight: 700; }
</style>
