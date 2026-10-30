<script setup>
import {ref,onUnmounted} from 'vue';
import account from './account.js';
import {localTestLogin} from './config.js';
const emit=defineEmits(['logged-in']),mobile=ref(''),password=ref(''),busy=ref(false),error=ref('');
let disposed=false;
onUnmounted(()=>{disposed=true;mobile.value='';password.value='';});
async function submit(){
 if(!localTestLogin||busy.value)return;busy.value=true;error.value='';
 try{const p=await account.loginPassword(mobile.value.trim(),password.value,()=>!disposed);if(disposed)return;password.value='';emit('logged-in',p);}catch(e){if(!disposed)error.value=e.message;}finally{if(!disposed)busy.value=false;}
}
</script>
<template><form v-if="localTestLogin" @submit.prevent="submit"><p class="small muted">独立本机验收。未设置密码的账号请使用验证码登录。</p><label class="form-field">手机号<input v-model="mobile" inputmode="numeric" maxlength="11" :disabled="busy" autocomplete="off" required></label><label class="form-field">密码<input v-model="password" type="password" :disabled="busy" autocomplete="off" required></label><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary full" :disabled="busy">{{busy?'正在登录…':'使用密码登录'}}</button></form></template>
