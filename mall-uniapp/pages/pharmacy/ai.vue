<template>
  <s-pharmacy-page tab="ai" dock>
    <PharmacyAiChat ref="chat" />
  </s-pharmacy-page>
</template>

<script setup>
  import { ref } from 'vue';
  import { onShow, onHide } from '@dcloudio/uni-app';
  import PharmacyAiChat from './PharmacyAiChat.vue';

  const chat = ref(null);
  onShow(() => chat.value?.refreshIdentity());
  onHide(() => chat.value?.stop());
</script>
