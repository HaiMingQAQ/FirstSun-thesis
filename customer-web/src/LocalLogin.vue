<script setup>
import {ref,watch,onUnmounted} from 'vue';
import api from '@/sheep/api/pharmacy/server';
import {identity,clearLoginState} from './session.js';
import {localTestLogin} from './config.js';
const emit=defineEmits(['logged-in']);const mobile=ref(''),code=ref(''),agreed=ref(false),busy=ref(false),error=ref('');
const sending=ref(false),sentMobile=ref(''),sendError=ref(''),cooldown=ref(0);
// A login may legitimately issue a new token; epoch + tenant identify its operation.
let disposed=false,pendingOwner=null,sendGeneration=0,cooldownTimer;
function sameOperation(owner){const now=identity();return owner&&owner.epoch===now.epoch&&owner.tenant===now.tenant;}
function resetSend(){sendGeneration++;clearInterval(cooldownTimer);sending.value=false;sentMobile.value='';cooldown.value=0;sendError.value='';code.value='';}
watch(mobile,resetSend,{flush:'sync'});
onUnmounted(()=>{const owner=pendingOwner;disposed=true;pendingOwner=null;resetSend();if(sameOperation(owner))clearLoginState();});
async function sendCode(){
 const m=mobile.value.trim();if(!localTestLogin||busy.value||sending.value||cooldown.value||!agreed.value||!/^1[3-9]\d{9}$/.test(m))return;
 resetSend();const g=sendGeneration,owner=identity();sending.value=true;
 const current=()=>!disposed&&g===sendGeneration&&mobile.value.trim()===m&&sameOperation(owner)&&owner.token===identity().token;
 try{await api.sendLoginCode(m);if(!current())return;sentMobile.value=m;const until=Date.now()+60000;cooldown.value=60;cooldownTimer=setInterval(()=>{cooldown.value=Math.max(0,Math.ceil((until-Date.now())/1000));if(!cooldown.value)clearInterval(cooldownTimer);},1000);}
 catch(e){if(current())sendError.value=e.message||'请求失败，请重试';}
 finally{if(!disposed&&g===sendGeneration)sending.value=false;}
}
async function submit(){
 if(!localTestLogin||busy.value||sending.value||!agreed.value)return;
 const m=mobile.value.trim(),c=code.value;
 if(sentMobile.value!==m||!/^1[3-9]\d{9}$/.test(m)||!/^\d{6}$/.test(c))return;
 busy.value=true;error.value='';clearLoginState();
 const owner=identity();pendingOwner=owner;
 const current=()=>!disposed&&pendingOwner===owner&&sameOperation(owner);
 try{
  const profile=await api.loginWithCode(m,c);
  if(!current())return;
  pendingOwner=null;code.value='';mobile.value='';emit('logged-in',profile);
 }catch(e){
  if(!current())return;
  pendingOwner=null;clearLoginState();error.value=e.message;
 }finally{if(!disposed)busy.value=false;}
}
</script>
<template><form v-if="localTestLogin" @submit.prevent="submit"><p class="small muted">独立本机验收登录，仅使用合成会员，不会发送真实短信。请求后请从受限运行说明读取当前验证码，提交时由后端正常校验。</p><label class="form-field">测试手机号<input v-model="mobile" name="mobile" inputmode="numeric" maxlength="11" autocomplete="off" :disabled="busy" required></label><button type="button" class="btn full" :disabled="busy||sending||cooldown>0||!agreed||!/^1[3-9]\d{9}$/.test(mobile)" @click="sendCode">{{sending?'正在请求…':cooldown?`${cooldown} 秒后可重发`:'获取本机验证码'}}</button><p v-if="sentMobile" class="small muted" role="status">已请求本机验证码，请读取受限运行说明。更换手机号后须重新请求。</p><p v-if="sendError" class="error-panel" role="alert">{{sendError}}</p><label class="form-field">运行时验证码<input v-model="code" name="code" type="password" inputmode="numeric" maxlength="6" autocomplete="off" :disabled="busy" required></label><label class="consent spaced"><input v-model="agreed" type="checkbox" :disabled="busy">我知道这是独立本机验收，不会发送真实短信。</label><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary full" :disabled="busy||sending||sentMobile!==mobile.trim()||!agreed||!/^1[3-9]\d{9}$/.test(mobile)||!/^\d{6}$/.test(code)">{{busy?'正在登录…':'登录 / 注册验收会员'}}</button></form><p v-else>此地址不允许本机验收登录。</p></template>
