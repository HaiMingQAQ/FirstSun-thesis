<template>
  <view class="fs-page" :class="{ 'has-tabs': !!tab, 'has-dock': dock }">
    <slot />
    <!-- #ifdef H5 -->
    <AiLauncher v-if="assistant && !quickOpen" :identity="launcherIdentity" :bottom="tab || dock ? 96 : 24"
      :label="dock ? '查价查库存' : 'AI 找药'"
      :hint="dock ? '想知道这款商品有没有货？' : '可以问我价格、库存和规格'" @open="openQuick" />
    <view v-if="assistant && quickOpen" class="quick-ai-mask">
      <view class="quick-ai-backdrop" @tap="quickOpen = false" />
      <view class="quick-ai-panel" :class="{ 'quick-ai-panel--chat': quickSignedIn }" role="dialog" aria-label="AI 购药助手">
        <view class="quick-ai-header">
          <text>AI 购药助手</text>
          <button v-if="quickSignedIn" class="fs-text-btn" @tap="expand">完整对话</button>
          <button class="fs-text-btn" aria-label="关闭 AI 助手" @tap="quickOpen = false">关闭</button>
        </view>
        <PharmacyAiChat v-if="quickSignedIn" ref="chat" embedded :drug-name="drugName" @login-required="quickSignedIn = false" />
        <view v-else class="quick-ai-guest">
          <text class="quick-ai-guest-title">登录后继续使用 AI 助手</text>
          <text class="quick-ai-guest-copy">登录后会回到这里，你可以继续查询药品和库存。</text>
          <button class="fs-primary" @tap="loginForQuick">登录并继续</button>
        </view>
      </view>
    </view>
    <!-- #endif -->
    <s-pharmacy-tabbar v-if="!!tab" :current="tab" />
  </view>
</template>
<script setup>
  defineOptions({ options: { styleIsolation: 'apply-shared' } });
  defineProps({ tab: { type: String, default: '' }, dock: Boolean, assistant: Boolean, drugName: String });
  // #ifdef H5
  import { ref } from 'vue';
  import { onPageShow, onPageHide } from '@dcloudio/uni-app';
  import AiLauncher from './AiLauncher.vue';
  import PharmacyAiChat from '@/pages/pharmacy/PharmacyAiChat.vue';
  import api from '@/sheep/api/pharmacy/client';
  import { accountKey, go, toast } from '@/pages/pharmacy/usePharmacy';
  const quickOpen = ref(false), quickSignedIn = ref(false), chat = ref(null);
  let reopenAfterLogin = false;
  const launcherIdentity = ref('member-h5:' + accountKey());
  onPageShow(() => {
    launcherIdentity.value = 'member-h5:' + accountKey();
    if (!reopenAfterLogin) return;
    reopenAfterLogin = false;
    if (api.session()) openQuick();
  });
  function openQuick() {
    quickSignedIn.value = !!api.session();
    quickOpen.value = true;
  }
  function loginForQuick() {
    if (reopenAfterLogin) return;
    reopenAfterLogin = true;
    uni.navigateTo({
      url: '/pages/pharmacy/login',
      fail: () => {
        reopenAfterLogin = false;
        toast('无法打开登录页，请重试');
      },
    });
  }
  onPageHide(() => { quickOpen.value = false; });
  function expand() {
    if (chat.value?.isBusy()) { toast('请等待回复完成或先停止生成'); return; }
    quickOpen.value = false;
    go('ai');
  }
  // #endif
</script>

<style scoped>
.quick-ai-mask {
  position: fixed;
  inset: 0;
  z-index: 950;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(16px, env(safe-area-inset-top)) 16px max(16px, env(safe-area-inset-bottom));
}
.quick-ai-backdrop { position: absolute; inset: 0; background: #123e3550; }
.quick-ai-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: min(100%, 480px);
  max-height: min(80vh, 100%);
  max-height: min(80dvh, 100%);
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 12px 36px #123e3540;
}
.quick-ai-panel--chat {
  height: 75vh;
  height: 75dvh;
}
.quick-ai-header { flex: none; display: flex; align-items: center; gap: 4px; padding: 8px 12px; border-bottom: 1px solid #e2e9e4; }
.quick-ai-header > text { margin-right: auto; font-weight: 700; }
.quick-ai-guest { min-height: 0; overflow: auto; display: grid; gap: 12px; padding: 22px 16px 16px; }
.quick-ai-guest-title { font-size: 17px; font-weight: 700; }
.quick-ai-guest-copy { color: #485f55; font-size: 14px; line-height: 1.6; }
</style>
