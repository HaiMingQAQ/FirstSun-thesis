<script setup>
import {ref,onUnmounted} from 'vue';
import api from '@/sheep/api/pharmacy/server';
import {identity,clearLoginState} from './session.js';
import {localTestLogin} from './config.js';
const emit=defineEmits(['logged-in']);const mobile=ref(''),code=ref(''),agreed=ref(false),busy=ref(false),error=ref('');
// A login may legitimately issue a new token; epoch + tenant identify its operation.
let disposed=false,pendingOwner=null;
function sameOperation(owner){const now=identity();return owner&&owner.epoch===now.epoch&&owner.tenant===now.tenant;}
onUnmounted(()=>{const owner=pendingOwner;disposed=true;pendingOwner=null;if(sameOperation(owner))clearLoginState();});
async function submit(){
 if(!localTestLogin||busy.value||!agreed.value)return;
 const m=mobile.value.trim(),c=code.value;
 if(!/^1[3-9]\d{9}$/.test(m)||!/^\d{6}$/.test(c))return;
 busy.value=true;error.value='';clearLoginState();
 const owner=identity();pendingOwner=owner;
 const current=()=>!disposed&&pendingOwner===owner&&sameOperation(owner);
 try{
  const profile=await api.login(m,c);
  if(!current())return;
  pendingOwner=null;code.value='';mobile.value='';emit('logged-in',profile);
 }catch(e){
  if(!current())return;
  pendingOwner=null;clearLoginState();error.value=e.message;
 }finally{if(!disposed)busy.value=false;}
}
</script>
<template><form v-if="localTestLogin" @submit.prevent="submit"><p class="small muted">独立本机验收登录，账号与验证码由本机后端校验。仅使用合成会员。</p><label class="form-field">测试手机号<input v-model="mobile" name="mobile" inputmode="numeric" maxlength="11" autocomplete="off" :disabled="busy" required></label><label class="form-field">运行时验证码<input v-model="code" name="code" type="password" inputmode="numeric" maxlength="6" autocomplete="off" :disabled="busy" required></label><label class="consent spaced"><input v-model="agreed" type="checkbox" :disabled="busy">我知道这是独立本机验收，不会发送真实短信。</label><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary full" :disabled="busy||!agreed||!/^1[3-9]\d{9}$/.test(mobile)||!/^\d{6}$/.test(code)">{{busy?'正在登录…':'登录 / 注册验收会员'}}</button></form><p v-else>此地址不允许本机验收登录。</p></template>
