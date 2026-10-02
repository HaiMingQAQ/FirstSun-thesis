<template>
  <s-pharmacy-page tab="user">
    <view class="member-head">
      <view class="fs-row">
        <view class="avatar"><image v-if="profile?.avatar && !avatarFailed" :src="profile.avatar" mode="aspectFill" @error="avatarFailed = true" /><uni-icons v-else type="person" size="30" color="#176b5b" /></view>
        <view class="fs-grow">
          <view class="fs-heading">{{ profile ? profile.name : '欢迎来到 FirstSun' }}</view>
          <view class="fs-muted">{{ profile ? maskedMobile : '登录，开启便捷购药体验' }}</view>
        </view>
      </view>
      <view v-if="profile" class="membership">
        <text>{{ profile.level }}</text>
        <text>
          当前积分
          <text class="points">{{ profile.points }}</text>
        </text>
      </view>
      <button v-else class="fs-primary fs-gap" @tap="go('login')">登录 / 注册</button>
      <button v-if="profile && api.updateProfile" class="fs-text-btn fs-gap" @tap="editProfile">完善头像和昵称</button>
      <view v-if="editing" class="profile-editor fs-gap">
        <view class="fs-muted">微信登录不会自动提供头像和昵称，请主动选择并保存。</view>
        <!-- #ifdef MP-WEIXIN -->
        <button open-type="chooseAvatar" class="fs-outline fs-gap" :disabled="saving" @chooseavatar="chooseAvatar">选择微信头像</button>
        <input type="nickname" v-model="nickname" maxlength="30" :disabled="saving" placeholder="填写或选择微信昵称" @blur="nickname = $event.detail.value" />
        <!-- #endif -->
        <!-- #ifndef MP-WEIXIN -->
        <button class="fs-outline fs-gap" :disabled="saving" @tap="pickAvatar">选择头像</button>
        <input v-model="nickname" maxlength="30" :disabled="saving" placeholder="填写昵称" />
        <!-- #endif -->
        <image v-if="avatarFile" class="avatar-preview" :src="avatarFile" mode="aspectFill" />
        <view class="fs-row fs-gap"><button class="fs-primary" :disabled="saving || !nickname.trim()" @tap="saveProfile">{{ saving ? '保存中…' : '保存资料' }}</button><button class="fs-outline" :disabled="saving" @tap="editing = false">取消</button></view>
        <view v-if="profileError" class="fs-muted fs-gap">{{ profileError }}</view>
      </view>
    </view>
    <s-pharmacy-state v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <view class="fs-section">
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
          <text>处方资料</text>
        </view>
        <uni-icons type="right" size="16" color="#8b968f" />
      </view>
      <view class="fs-menu" @tap="contact">
        <view class="fs-row">
          <uni-icons type="phone" size="22" color="#526b5b" />
          <text>联系门店</text>
        </view>
        <uni-icons type="right" size="16" color="#8b968f" />
      </view>
    </view>
    <view class="fs-section">
      <view class="fs-menu" @tap="open('notifications')"><view class="fs-row"><uni-icons type="chat" size="22" color="#62756c" /><text>业务通知</text></view><uni-icons type="right" size="16" color="#62756c" /></view>
      <view class="fs-menu" @tap="open('consultation')"><view class="fs-row"><uni-icons type="chatbubble" size="22" color="#62756c" /><text>门店文字咨询</text></view><uni-icons type="right" size="16" color="#62756c" /></view>
      <view class="fs-menu" @tap="open('ai')"><view class="fs-row"><uni-icons type="chatbubble" size="22" color="#62756c" /><text>AI 购药助手</text></view><uni-icons type="right" size="16" color="#62756c" /></view>
    </view>
    <view v-if="profile" class="fs-pad">
      <button class="fs-outline" @tap="logout">退出登录</button>
    </view>
    <view class="fs-footer">FirstSun 药店 · 测试体验版</view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { go, requireLogin, confirm, useRequest, accountKey } from './usePharmacy';
  const profile = ref(null);
  let profileOwner = '';
  const editing = ref(false), saving = ref(false), nickname = ref(''), avatarFile = ref(''), avatarFailed = ref(false), profileError = ref('');
  function editProfile() { nickname.value = profile.value.name; avatarFile.value = ''; profileError.value = ''; editing.value = true; }
  function chooseAvatar(e) { avatarFile.value = e.detail.avatarUrl || ''; }
  function pickAvatar() { uni.chooseImage({ count: 1, success: r => { avatarFile.value = r.tempFilePaths[0]; } }); }
  async function saveProfile() {
    if (saving.value) return;
    const owner = accountKey(); saving.value = true; profileError.value = '';
    try { const updated = await api.updateProfile({ nickname: nickname.value, avatarFile: avatarFile.value }); if (owner !== accountKey()) return; profile.value = updated; avatarFailed.value = false; editing.value = false; }
    catch (e) { if (owner === accountKey()) profileError.value = e.message || '保存失败，请重试'; }
    finally { saving.value = false; }
  }
  const { loading, error, run } = useRequest();
  const maskedMobile = computed(() => {
    const mobile = profile.value?.mobile;
    return mobile ? mobile.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : '微信会员';
  });
  const shortcuts = [
    { status: 'unpaid', name: '待支付', icon: 'wallet' },
    { status: 'review', name: '待审核', icon: 'help' },
    { status: 'ready', name: '待收取', icon: 'shop' },
    { status: 'completed', name: '已完成', icon: 'checkbox' },
  ];
  const load = () =>
    run(async () => {
      try {
        profile.value = await api.profile();
        avatarFailed.value = false;
      } catch (e) {
        if (!api.session()) profile.value = null;
        throw e;
      }
    });
  const open = (path) => {
    if (requireLogin()) go(path);
  };
  const contact = () => {
    if (api.store.phone) uni.makePhoneCall({ phoneNumber: api.store.phone });
    else
      uni.showModal({
        title: api.store.name,
        content: `${api.store.address}\n营业时间：${api.store.hours}\n测试门店暂无联系电话，正式接入后可一键拨打。`,
        showCancel: false,
        confirmColor: '#176b5b',
      });
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
    if (owner !== profileOwner) { editing.value = false; nickname.value = ''; avatarFile.value = ''; profileError.value = ''; }
    profileOwner = owner;
    const session = api.session();
    if (session) {
      profile.value = session;
      load();
    } else { profile.value = null; editing.value = false; }
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
  .avatar-preview { width: 54px; height: 54px; border-radius: 50%; margin-top: 12px; }
  .profile-editor input { margin-top: 12px; padding: 12px; min-height: 44px; background: #fff; border: 1px solid #cedbd5; border-radius: 8px; }
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
