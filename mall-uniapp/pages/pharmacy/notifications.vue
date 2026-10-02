<template>
  <s-pharmacy-page>
    <s-pharmacy-state v-if="!loggedIn" title="登录后查看业务通知" action="去登录" @retry="go('login')" />
    <template v-else>
      <view class="fs-section fs-between"><text class="fs-title">业务通知</text><text class="fs-muted">{{ unread }} 条未读</text></view>
      <view class="fs-section"><button class="fs-outline" @tap="go('consultation')">查看人工咨询会话与未读</button></view>
      <s-pharmacy-state v-if="loading || error || !notices.length" :loading="loading" :error="error" title="暂无业务通知" @retry="load" />
      <view v-for="notice in notices" :key="notice.id" class="fs-section" @tap="open(notice)"><view class="fs-between"><text>{{ notice.content }}</text><text v-if="!notice.readStatus" class="fs-tag">未读</text></view><view class="fs-muted fs-gap">{{ formatDate(notice.createTime) }}</view><view v-if="notice.reference?.prescId" class="fs-muted">查看处方记录</view></view>
      <button v-if="notices.length < total" class="fs-outline fs-gap" :disabled="loading || busy" @tap="more">加载更多</button>
    </template>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onShow } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { formatDate } from '@/sheep/api/pharmacy/common';
  import { go, accountKey, useRequest, useAction } from './usePharmacy';
  const loggedIn = ref(false), notices = ref([]), unread = ref(0), total = ref(0);
  const { loading, error, run } = useRequest(), { busy, act } = useAction(); let page = 1, owner = '';
  const load = () => run(async () => { const result = await api.notifications(); notices.value = result.list; total.value = result.total; unread.value = await api.unreadNotifications(); page = 1; });
  const more = () => run(async () => { const result = await api.notifications(page + 1); notices.value.push(...result.list); total.value = result.total; page += 1; });
  const open = notice => act(async () => { await api.readNotification(notice.id); notice.readStatus = true; unread.value = await api.unreadNotifications(); if (notice.reference?.prescId) go('prescription-upload?id=' + notice.reference.prescId); });
  onShow(() => { loggedIn.value = !!api.session(); const account = accountKey(); if (owner !== account) { notices.value = []; unread.value = 0; } owner = account; if (loggedIn.value) load(); });
</script>
