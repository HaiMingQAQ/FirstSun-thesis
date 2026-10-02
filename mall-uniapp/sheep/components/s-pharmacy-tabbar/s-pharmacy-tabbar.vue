<template>
  <view class="pharmacy-tabbar">
    <button
      v-for="item in tabs"
      :key="item.path"
      class="tab"
      :aria-label="item.text"
      :class="{ active: item.path === current, central: item.path === 'ai' }"
      @tap="switchTab(item)"
    >
      <view class="tab-icon"><uni-icons :type="item.icon" size="22" :color="item.path === 'ai' ? '#fff' : item.path === current ? '#176b5b' : '#62756c'" /></view>
      <text>{{ item.text }}</text>
    </button>
  </view>
</template>
<script setup>
  defineOptions({ options: { styleIsolation: 'isolated' } });
  const props = defineProps({ current: String });
  const tabs = [
    { text: '首页', icon: 'home', path: 'index' },
    { text: '分类', icon: 'list', path: 'category' },
    { text: 'AI 助手', icon: 'chatbubble-filled', path: 'ai' },
    { text: '购物车', icon: 'cart', path: 'cart' },
    { text: '我的', icon: 'person', path: 'user' },
  ];
  function switchTab(item) {
    if (item.path === props.current) return;
    uni.showLoading({ title: '加载页面…', mask: true });
    uni.reLaunch({ url: `/pages/pharmacy/${item.path}`, complete: () => uni.hideLoading(), fail: () => uni.showToast({ title: '页面加载失败，请重试', icon: 'none' }) });
  }
</script>
<style scoped>
  .pharmacy-tabbar { position: fixed; bottom: 0; left: 0; right: 0; z-index: 40; display: flex; align-items: flex-end; background: #fff; border-top: 1px solid #e2e9e4; padding-bottom: env(safe-area-inset-bottom); box-sizing: border-box; }
  .pharmacy-tabbar .tab { flex: 1; min-width: 0; width: 20%; margin: 0; height: 64px; padding: 5px 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; background: transparent; border: 0; border-radius: 0; color: #62756c; font-size: 11px; line-height: 16px; font-weight: 400; box-sizing: border-box; }
  .tab::after { border: 0; }
  .tab-icon { height: 28px; display: flex; align-items: center; justify-content: center; }
  .pharmacy-tabbar .active { color: #176b5b; font-weight: 600; }
  .pharmacy-tabbar .central .tab-icon { height: 40px; width: 40px; margin-top: -9px; border-radius: 50%; background: #176b5b; box-shadow: 0 3px 8px rgba(23,107,91,.18); }
  .pharmacy-tabbar .central { color: #62756c; font-weight: 400; }
  .pharmacy-tabbar .central.active { color: #176b5b; font-weight: 600; }
  .pharmacy-tabbar .central.active .tab-icon { box-shadow: 0 0 0 3px #d9eae1; }
</style>
