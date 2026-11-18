<template>
  <s-pharmacy-page>
    <view class="login-head">
      <view class="login-brand">FirstSun</view>
      <view class="fs-heading fs-gap">欢迎来到 FirstSun 药店</view>
      <view class="fs-muted fs-gap">登录后选购药品、查看订单与会员积分</view>
    </view>
    <view v-if="usePhoneLogin" class="fs-section">
      <view class="fs-field">
        <text class="fs-label">手机号</text>
        <input
          v-model="mobile"
          class="fs-input"
          type="number"
          maxlength="11"
          :disabled="busy"
          placeholder="请输入 11 位手机号"
        />
        <text v-if="mobile && !validMobile" class="fs-small fs-danger">请输入正确的手机号格式</text>
      </view>
      <view class="fs-field">
        <text class="fs-label">验证码</text>
        <view class="fs-row">
          <input
            v-model="code"
            class="fs-input fs-grow"
            type="number"
            maxlength="6"
            :disabled="busy"
            placeholder="6 位验证码"
          />
          <button v-if="PHARMACY_DEMO" class="fs-secondary" :disabled="busy || !validMobile" @tap="fillCode">填入验证码</button>
        </view>
        <view v-if="!PHARMACY_DEMO" class="fs-muted fs-gap">请输入已有验证码，此入口不会发送短信。</view>
      </view>
      <view class="agreement fs-gap">
        <button class="fs-check" :disabled="busy" aria-label="同意用户协议" @tap="agreed = !agreed">
          <uni-icons :type="agreed ? 'checkbox-filled' : 'circle'" size="22" color="#176b5b" />
        </button>
        <view class="fs-small">
          我已阅读并同意
          <text class="link" @tap="showTerms">《用户协议与隐私说明》</text>
        </view>
      </view>
      <button
        class="fs-primary fs-gap"
        :disabled="busy || !agreed || !validMobile || code.length !== 6"
        :loading="busy"
        @tap="login"
      >
        {{ busy ? '登录中…' : '登录 / 注册' }}
      </button>
    </view>
    <view v-if="loginMode !== 'phone'" class="fs-section">
      <view v-if="loginMode !== 'reset'" class="fs-row">
        <button :class="loginMode === 'register' ? 'fs-outline' : 'fs-primary'" :disabled="busy || sending" @tap="setMode('password')">邮箱登录</button>
        <button :class="loginMode === 'register' ? 'fs-primary' : 'fs-outline'" :disabled="busy || sending" @tap="setMode('register')">邮箱注册</button>
      </view>
      <view v-if="loginMode === 'reset'" class="fs-title">找回密码</view>
      <view v-if="loginMode === 'reset'" class="fs-muted fs-gap">请使用已验证的登录邮箱，联系邮箱不能用于找回密码。</view>
      <view v-if="emailNotice" class="fs-notice" role="status">{{ emailNotice }}</view>
      <view class="fs-field fs-gap"><text class="fs-label">邮箱</text><input v-model.trim="email" class="fs-input" maxlength="254" :disabled="busy || sending" placeholder="请输入邮箱" /></view>
      <view v-if="loginMode !== 'password'" class="fs-field"><text class="fs-label">邮箱验证码</text><view class="fs-row"><input v-model="emailCode" class="fs-input fs-grow" type="number" maxlength="6" :disabled="busy || sending" placeholder="6 位验证码" /><button class="fs-outline" :disabled="busy || sending || remaining > 0 || !validEmail" @tap="requestCode">{{ sending ? '发送中…' : remaining ? remaining + ' 秒后重发' : '获取验证码' }}</button></view><view v-if="sentEmail === normalizedEmail && sentEmail" class="fs-muted fs-gap">{{ loginMode === 'reset' ? '如该邮箱可用于找回密码，请查收邮箱及垃圾邮件。' : '请查收邮箱及垃圾邮件。' }}</view></view>
      <view v-if="loginMode !== 'code'" class="fs-field"><text class="fs-label">{{ loginMode === 'reset' ? '新密码' : '密码' }}</text><view class="password-field"><input v-model="password" class="fs-input fs-grow" :password="!passwordVisible" :disabled="busy || sending" @blur="password = $event.detail.value" :placeholder="loginMode === 'register' ? '设置密码（可选）' : loginMode === 'reset' ? '请输入新密码' : '请输入密码'" /><button class="password-eye" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" :disabled="busy || sending" @tap="passwordVisible = !passwordVisible"><uni-icons :type="passwordVisible ? 'eye-slash' : 'eye'" size="22" color="#62756c" /></button></view><view v-if="['register', 'reset'].includes(loginMode)" class="fs-muted fs-gap">8–64 个字符，可使用中文、字母、数字或符号，请勿只输入空格。</view><view v-if="['register', 'reset'].includes(loginMode) && newPasswordHint" class="fs-small fs-danger fs-gap" role="alert">{{ newPasswordHint }}</view></view>
      <view v-if="loginMode === 'reset'" class="fs-field">
        <text class="fs-label">确认新密码</text>
        <view class="password-field"><input v-model="confirmPassword" class="fs-input" :password="!confirmVisible" :disabled="busy || sending" @blur="confirmPassword = $event.detail.value" placeholder="再次输入新密码" /><button class="password-eye" :aria-label="confirmVisible ? '隐藏确认密码' : '显示确认密码'" :aria-pressed="confirmVisible" :disabled="busy || sending" @tap="confirmVisible = !confirmVisible"><uni-icons :type="confirmVisible ? 'eye-slash' : 'eye'" size="22" color="#62756c" /></button></view>
        <text v-if="confirmPassword && confirmPassword !== password" class="fs-small fs-danger">两次密码不一致</text>
      </view>
      <view class="agreement fs-gap"><button class="fs-check" aria-label="同意用户协议" :disabled="busy" @tap="agreed = !agreed"><uni-icons :type="agreed ? 'checkbox-filled' : 'circle'" size="22" color="#176b5b" /></button><view class="fs-small">我已阅读并同意 <text class="link" @tap="showTerms">《用户协议与隐私说明》</text></view></view>
      <view v-if="emailError" class="fs-danger fs-gap" role="alert">{{ emailError }}</view>
      <button class="fs-primary fs-gap" :disabled="!canSubmitEmail" @tap="submitEmail">{{ busy ? '处理中…' : loginMode === 'register' ? '注册并登录' : loginMode === 'reset' ? '重设密码' : '登录' }}</button>
      <button v-if="['password', 'code'].includes(loginMode)" class="fs-text-btn fs-gap" :disabled="busy || sending" @tap="setMode(loginMode === 'code' ? 'password' : 'code')">{{ loginMode === 'code' ? '使用密码登录' : '使用邮箱验证码登录' }}</button>
      <button v-if="loginMode === 'password'" class="fs-text-btn fs-gap" :disabled="busy || sending" @tap="setMode('reset')">忘记密码</button>
      <button v-if="loginMode === 'reset'" class="fs-text-btn fs-gap" :disabled="busy || sending" @tap="setMode('password')">返回邮箱登录</button>
    </view>
    <!-- #ifdef MP-WEIXIN -->
    <view v-if="!PHARMACY_DEMO" class="fs-section">
      <view class="fs-notice">
        也可使用当前微信账户登录。
      </view>
      <button
        class="fs-primary fs-gap"
        :disabled="busy || !agreed"
        :loading="busy"
        @tap="wechatLogin"
      >
        {{ busy ? '登录中…' : '微信登录' }}
      </button>
    </view>
    <!-- #endif -->
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed, watch, onUnmounted } from 'vue';
  import { onLoad, onUnload } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { PHARMACY_DEMO, PHARMACY_H5_TEST_LOGIN, TEST_CODE } from '@/sheep/api/pharmacy/config';
  import { toast, useAction } from './usePharmacy';
  const mobile = ref(''),
    code = ref(''),
    agreed = ref(false);
  const { busy, act } = useAction();
  const loginMode = ref('password');
  onLoad((query) => {
    // #ifdef H5
    if (query?.channel === 'phone' && PHARMACY_H5_TEST_LOGIN
      && ['127.0.0.1', 'localhost', '[::1]'].includes(window.location.hostname)) loginMode.value = 'phone';
    // #endif
  });
  const usePhoneLogin = computed(() => loginMode.value === 'phone');
  const passwordVisible = ref(false), confirmVisible = ref(false), confirmPassword = ref(''), emailNotice = ref('');
  let active = true;
  onUnload(() => { active = false; });
  const email = ref(''), password = ref(''), emailCode = ref(''), sentEmail = ref(''), sending = ref(false), emailError = ref(''), remaining = ref(0);
  const normalizedEmail = computed(() => email.value.trim().toLowerCase());
  const validEmail = computed(() => {
    const value = normalizedEmail.value, [local, domain, ...rest] = value.split('@');
    return !!(value.length <= 254 && local && local.length <= 64 && !local.startsWith('.') && !local.endsWith('.') && !local.includes('..') && /^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local) && domain && !rest.length && domain.includes('.') && domain.split('.').every(label => label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)));
  });
  const validNewPassword = () => {
    const chars = [...password.value];
    return !!(password.value.trim() && chars.length >= 8 && chars.length <= 64 &&
      chars.reduce((size, ch) => { const n = ch.codePointAt(0); return size + (n < 128 ? 1 : n < 2048 ? 2 : n < 65536 ? 3 : 4); }, 0) <= 72);
  };
  const newPasswordHint = computed(() => {
    if (!password.value || validNewPassword()) return '';
    const chars = [...password.value];
    const size = chars.reduce((total, ch) => {
      const n = ch.codePointAt(0);
      return total + (n < 128 ? 1 : n < 2048 ? 2 : n < 65536 ? 3 : 4);
    }, 0);
    if (chars.length > 64 || size > 72) return '密码过长，请减少字符';
    if (!password.value.trim()) return '密码不能只包含空格';
    return '密码至少需要 8 个字符';
  });
  const canSubmitEmail = computed(() => agreed.value && !busy.value && !sending.value && validEmail.value &&
    (loginMode.value === 'password' ? !!password.value.trim() :
      sentEmail.value === normalizedEmail.value && /^\d{6}$/.test(emailCode.value) &&
      (loginMode.value === 'reset' ? validNewPassword() && password.value === confirmPassword.value : loginMode.value !== 'register' || !password.value || validNewPassword())));
  let timer;
  watch(normalizedEmail, () => { clearInterval(timer); remaining.value = 0; sentEmail.value = ''; emailCode.value = ''; emailError.value = ''; }, { flush: 'sync' });
  onUnmounted(() => { active = false; clearInterval(timer); });
  function setMode(mode) {
    if (busy.value || sending.value) return;
    clearInterval(timer); remaining.value = 0;
    loginMode.value = mode; passwordVisible.value = confirmVisible.value = false; confirmPassword.value = emailNotice.value = ''; password.value = ''; emailCode.value = ''; sentEmail.value = ''; emailError.value = '';
  }
  async function requestCode() {
    if (!validEmail.value || busy.value || sending.value || remaining.value || !['register', 'code', 'reset'].includes(loginMode.value)) return;
    sending.value = true; emailError.value = '';
    try {
      const target = normalizedEmail.value;
      if (loginMode.value === 'reset') await api.sendResetEmailCode(target);
      else await api.sendEmailCode(target, loginMode.value === 'register' ? 'register' : 'login');
      if (!active || target !== normalizedEmail.value) return;
      sentEmail.value = target;
      const until = Date.now() + 60000;
      remaining.value = 60;
      timer = setInterval(() => { remaining.value = Math.max(0, Math.ceil((until - Date.now()) / 1000)); if (!remaining.value) clearInterval(timer); }, 1000);
    } catch (error) { emailError.value = error.message || '暂时无法获取验证码，请重试'; }
    finally { sending.value = false; }
  }
  async function submitEmail() {
    if (!canSubmitEmail.value) return;
    await act(async () => {
      emailError.value = '';
      try {
        if (loginMode.value === 'reset') {
          await api.resetEmailPassword(normalizedEmail.value, password.value, emailCode.value, () => active);
          if (!active) return;
          loginMode.value = 'password';
          password.value = confirmPassword.value = emailCode.value = sentEmail.value = '';
          passwordVisible.value = confirmVisible.value = false;
          emailNotice.value = '密码已更新，请重新登录';
          return;
        }
        await api.authenticateEmail(loginMode.value, normalizedEmail.value, password.value, emailCode.value);
        password.value = ''; emailCode.value = '';
        returnAfterLogin();
      } catch (error) { emailError.value = error.message || '登录未完成，请重试'; }
    });
  }
  const validMobile = computed(() => /^1[3-9]\d{9}$/.test(mobile.value));
  const fillCode = () => {
    if (!PHARMACY_DEMO) return;
    code.value = TEST_CODE;
    toast('验证码已填入');
  };
  const showTerms = () =>
    uni.showModal({
      title: '用户协议与隐私说明',
      content: '登录凭据用于验证会员身份。手机号或邮箱用于登录和账号核验；请妥善保管密码与验证码。订单和处方信息仅通过相应服务入口提交，请勿在普通会话中发送敏感资料。',
      showCancel: false,
      confirmColor: '#176b5b',
    });
  const returnAfterLogin = () => {
    toast('登录成功');
    if (getCurrentPages().length > 1) uni.navigateBack();
    else uni.reLaunch({ url: '/pages/pharmacy/user' });
  };
  const login = () => act(async () => {
    if (!usePhoneLogin.value || !agreed.value || !validMobile.value || !/^\d{6}$/.test(code.value)) return;
    await api.login(mobile.value, code.value);
    code.value = '';
    returnAfterLogin();
  });
  const wechatLogin = () => act(async () => {
    if (!agreed.value) return;
    await api.wechatLogin();
    returnAfterLogin();
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
  .password-field { position: relative; width: 100%; }
  .password-field .fs-input { padding-right: 52px; }
  .password-eye { position: absolute; right: 2px; top: 50%; transform: translateY(-50%); width: 44px; height: 44px; padding: 0; display: flex; align-items: center; justify-content: center; background: transparent; }
  .login-brand { font-family: Georgia, serif; font-size: 32px; color: #164d43; font-weight: 700; }
</style>
