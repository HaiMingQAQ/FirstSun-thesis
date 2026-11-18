<template>
  <s-pharmacy-page>
    <s-pharmacy-state v-if="!owner" title="登录后编辑个人资料" action="去登录" @retry="go('login')" />
    <s-pharmacy-state v-else-if="!profile" :loading="loading" :error="error" action="重试" @retry="load" />
    <template v-else>
      <view class="fs-section avatar-section">
        <view class="profile-avatar">
          <image v-if="source && failedSource !== source" :key="source" :src="source" mode="aspectFill" aria-label="头像预览" @error="onImageError" />
          <uni-icons v-else type="person" size="42" color="#176b5b" aria-label="默认头像" />
        </view>
        <!-- #ifdef MP-WEIXIN -->
        <button open-type="chooseAvatar" class="fs-outline" :disabled="saving" @chooseavatar="chooseAvatar">更换头像</button>
        <!-- #endif -->
        <!-- #ifndef MP-WEIXIN -->
        <button class="fs-outline" :disabled="saving" @tap="pickAvatar">更换头像</button>
        <!-- #endif -->
        <text class="fs-muted">支持 JPG 或 PNG，最大 5 MB</text>
        <text v-if="failedSource && !error" class="fs-muted">当前头像暂无法加载，可重新选择。</text>
      </view>
      <view class="fs-section">
        <view class="fs-field">
          <text class="fs-label">昵称</text>
          <!-- #ifdef MP-WEIXIN -->
          <input class="fs-input" type="nickname" v-model="nickname" maxlength="30" :disabled="saving" placeholder="填写昵称" @blur="nickname = $event.detail.value" />
          <!-- #endif -->
          <!-- #ifndef MP-WEIXIN -->
          <input class="fs-input" v-model="nickname" maxlength="30" :disabled="saving" placeholder="填写昵称" />
          <!-- #endif -->
          <text class="fs-muted">最多 30 个字符</text>
        </view>
        <view v-if="error" class="fs-danger fs-gap">{{ error }}</view>
        <view class="fs-row fs-gap">
          <button class="fs-outline fs-grow" :disabled="saving" @tap="back">取消</button>
          <button class="fs-primary fs-grow" :disabled="saving || !nickname.trim()" :loading="saving" @tap="save">{{ saving ? '保存中…' : '保存资料' }}</button>
        </view>
      </view>
    </template>
  </s-pharmacy-page>
</template>

<script setup>
  import { computed, ref } from 'vue';
  import { onShow, onUnload } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { accountKey, go, toast } from './usePharmacy';
  const owner = ref(''), profile = ref(null), nickname = ref(''), avatarFile = ref('');
  const loading = ref(false), saving = ref(false), error = ref(''), failedSource = ref('');
  const source = computed(() => avatarFile.value || profile.value?.avatar || '');
  let active = true;
  const isCurrent = (key) => active && !!key && accountKey() === key;

  async function load() {
    const key = owner.value;
    if (!key || loading.value) return;
    loading.value = true;
    error.value = '';
    try {
      const data = await api.profile();
      if (isCurrent(key)) {
        profile.value = data;
        nickname.value = data.name;
        failedSource.value = '';
      }
    } catch (e) {
      if (isCurrent(key)) error.value = e.message || '资料加载失败，请重试';
    } finally { if (owner.value === key) loading.value = false; }
  }

  onShow(() => {
    const current = api.updateProfile ? accountKey() : '';
    if (current !== owner.value) {
      owner.value = current;
      profile.value = null;
      nickname.value = avatarFile.value = error.value = failedSource.value = '';
      loading.value = saving.value = false;
    }
    if (current && !profile.value) load();
  });
  onUnload(() => { active = false; });

  function preview(path, file) {
    if (!path || saving.value) return;
    if (file?.size > 5 * 1024 * 1024 || (file?.type && !['image/jpeg', 'image/png'].includes(file.type))) {
      error.value = '请选择不超过 5 MB 的 JPG 或 PNG 图片';
      return;
    }
    avatarFile.value = path;
    error.value = failedSource.value = '';
  }
  function chooseAvatar(event) { preview(event.detail.avatarUrl); }
  function pickAvatar() {
    const key = owner.value;
    uni.chooseImage({ count: 1, success: (result) => {
      if (isCurrent(key)) preview(result.tempFilePaths[0], result.tempFiles?.[0]);
    } });
  }
  function onImageError() {
    if (avatarFile.value) {
      avatarFile.value = '';
      error.value = '无法预览所选图片，请重新选择';
    } else failedSource.value = source.value;
  }
  function back() {
    if (getCurrentPages().length > 1) uni.navigateBack();
    else uni.reLaunch({ url: '/pages/pharmacy/user' });
  }
  async function save() {
    const key = owner.value;
    if (saving.value || !nickname.value.trim() || !isCurrent(key)) return;
    saving.value = true;
    error.value = '';
    try {
      await api.updateProfile({ nickname: nickname.value, avatarFile: avatarFile.value });
      if (isCurrent(key)) { toast('资料已保存'); back(); }
    } catch (e) {
      if (isCurrent(key)) error.value = e.message || '保存失败，请重试';
    } finally { if (owner.value === key) saving.value = false; }
  }
</script>

<style scoped>
  .avatar-section { display: flex; flex-direction: column; align-items: center; gap: 12px; padding-top: 28px; padding-bottom: 24px; }
  .profile-avatar { width: 88px; height: 88px; border-radius: 50%; overflow: hidden; background: #edf5f0; display: flex; align-items: center; justify-content: center; }
  .profile-avatar image { width: 100%; height: 100%; }
</style>
