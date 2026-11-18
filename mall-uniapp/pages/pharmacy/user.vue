<template>
  <s-pharmacy-page assistant tab="user">
    <view class="member-head">
      <view class="fs-row" :class="{ 'profile-link': profile && api.updateProfile }" :role="profile && api.updateProfile ? 'button' : undefined" :tabindex="profile && api.updateProfile ? 0 : undefined" @keyup.enter="openProfile" @keyup.space.prevent="openProfile" @tap="openProfile">
        <view class="avatar"><image v-if="profile?.avatar && failedAvatar !== profile.avatar" :src="profile.avatar" mode="aspectFill" :key="profile.avatar" @error="failedAvatar = profile.avatar" /><uni-icons v-else type="person" size="30" color="#176b5b" /></view>
        <view class="fs-grow">
          <view class="fs-heading">{{ profile ? profile.name : '欢迎来到 FirstSun' }}</view>
          <view class="fs-muted">{{ profile ? maskedMobile : '登录，开启便捷购药体验' }}</view>
        </view>
        <uni-icons v-if="profile && api.updateProfile" type="right" size="18" color="#526b5b" />
      </view>
      <view v-if="profile" class="membership">
        <text>{{ profile.level }}</text>
        <text>
          当前积分
          <text class="points">{{ profile.points }}</text>
        </text>
      </view>
      <button v-else class="fs-primary fs-gap" @tap="go('login')">登录 / 注册</button>
    </view>
    <s-pharmacy-state v-if="profile && (loading || error)" :loading="loading" :error="error" @retry="load" />
    <view class="fs-section">
      <view v-if="!profile" class="fs-muted">登录后可查看订单、处方和咨询记录。</view>
      <view class="fs-between">
        <text class="fs-title">我的订单</text>
        <button class="fs-text-btn" @tap="open('order')">
          全部订单
          <uni-icons type="right" size="13" color="#176b5b" />
        </button>
      </view>
      <view class="order-shortcuts">
        <button
          v-for="item in shortcuts"
          :key="item.status"
          @tap="open('order?status=' + item.status)"
        >
          <uni-icons :type="item.icon" size="26" color="#526b5b" />
          <text>{{ item.name }}</text>
        </button>
      </view>
    </view>
    <view class="fs-section">
      <view class="fs-title">购药服务</view>
      <view class="fs-menu" @tap="open('consultation')"><view class="fs-row"><uni-icons type="chatbubble" size="22" color="#526b5b" /><text>咨询与回复</text></view><uni-icons type="right" size="16" color="#8b968f" /></view>
      <view class="fs-menu" @tap="open('address')">
        <view class="fs-row">
          <uni-icons type="location" size="22" color="#526b5b" />
          <text>收货地址</text>
        </view>
        <uni-icons type="right" size="16" color="#8b968f" />
      </view>
      <view class="fs-menu" @tap="open('prescription-upload')">
        <view class="fs-row">
          <uni-icons type="paperclip" size="22" color="#526b5b" />
          <text>处方申请记录</text>
        </view>
        <uni-icons type="right" size="16" color="#8b968f" />
      </view>
      <view v-if="api.store.phone" class="fs-menu" @tap="contact">
        <view class="fs-row">
          <uni-icons type="phone" size="22" color="#526b5b" />
          <text>拨打门店电话</text>
        </view>
        <uni-icons type="right" size="16" color="#8b968f" />
      </view>
    </view>
    <view class="fs-section">
      <view class="fs-menu" @tap="open('notifications')"><view class="fs-row"><uni-icons type="chat" size="22" color="#62756c" /><text>业务通知</text></view><uni-icons type="right" size="16" color="#62756c" /></view>
      <view class="fs-menu" @tap="open('ai')"><view class="fs-row"><uni-icons type="chatbubble" size="22" color="#62756c" /><text>AI 购药助手</text></view><uni-icons type="right" size="16" color="#62756c" /></view>
    </view>
    <view v-if="profile" class="fs-pad">
      <button class="fs-outline" @tap="logout">退出登录</button>
    </view>
    <view class="fs-footer">FirstSun 药店</view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { go, requireLogin, confirm, useRequest, accountKey } from './usePharmacy';
  const profile = ref(null);
  let profileOwner = '';
  const failedAvatar = ref('');
  const openProfile = () => {
    if (profile.value && api.updateProfile) open('profile');
  };
  const { loading, error, run } = useRequest();
  const maskedMobile = computed(() => {
    const mobile = profile.value?.mobile;
    return mobile ? mobile.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : '会员';
  });
  const shortcuts = [
    { status: 'unpaid', name: '待支付', icon: 'wallet' },
    { status: 'review', name: '待审核', icon: 'help' },
    { status: 'ready', name: '待收取', icon: 'shop' },
    { status: 'completed', name: '已完成', icon: 'checkbox' },
  ];
  const load = () => {
    if (!api.session()) {
      profile.value = null;
      error.value = '';
      return;
    }
    const owner = accountKey();
    return run(async () => {
      try {
        const fresh = await api.profile();
        if (owner === accountKey()) {
          profile.value = fresh;
          failedAvatar.value = '';
        }
      } catch (e) {
        if (!api.session()) {
          profile.value = null;
          error.value = '';
          return;
        }
        if (owner !== accountKey()) return;
        throw e;
      }
    });
  };
  const open = (path) => {
    if (requireLogin()) go(path);
  };
  const contact = () => {
    if (api.store.phone) uni.makePhoneCall({ phoneNumber: api.store.phone });

  };
  const logout = async () => {
    if (await confirm('退出登录', '确定退出当前账户？')) {
      await api.logout();
      profile.value = null;
      error.value = '';
    }
  };
  onShow(() => {
    const owner = accountKey();
    if (owner !== profileOwner) { failedAvatar.value = ''; error.value = ''; }
    profileOwner = owner;
    const session = api.session();
    if (session) {
      profile.value = session;
      load();
    } else { profile.value = null; error.value = ''; }
  });
</script>
<style scoped>
  .member-head {
    padding: 24px 16px;
    background: #f3f7f1;
  }
  .avatar {
    width: 54px;
    height: 54px;
    border-radius: 50%;
    background: #eaf2ed;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }
  .avatar image { width: 54px; height: 54px; border-radius: 50%; }
  .profile-link { cursor: pointer; }
  .membership {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid #e5ebe8;
    margin-top: 24px;
    padding-top: 16px;
    color: #526b5b;
    font-size: 14px;
  }
  .points {
    font-size: 25px;
    font-weight: 600;
    margin-left: 8px;
    color: #176b5b;
  }
  .order-shortcuts {
    display: flex;
    margin-top: 12px;
  }
  .order-shortcuts button {
    flex: 1;
    padding: 8px 0;
    background: #fff;
    display: flex;
    flex-direction: column;
    font-size: 13px;
    gap: 10px;
  }
</style>
