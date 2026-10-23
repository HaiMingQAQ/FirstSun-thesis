<script setup>
import {ref,watch,onUnmounted} from 'vue';
import account from './account.js';
import {normalizeEmail,validEmail} from './email-address.js';
import {identity,clearLoginState} from './session.js';
import {sameIdentity} from './guards.js';
const emit=defineEmits(['logged-in']);
const mode=ref('code'),email=ref(''),code=ref(''),password=ref(''),busy=ref(false),sending=ref(false),sentEmail=ref(''),cooldown=ref(0),error=ref('');
let disposed=false,generation=0,timer,pendingOwner;
const owns=owner=>{const now=identity();return owner&&owner.epoch===now.epoch&&owner.tenant===now.tenant;};
function resetCode(){generation++;clearInterval(timer);sentEmail.value='';code.value='';cooldown.value=0;sending.value=false;}
watch(email,resetCode,{flush:'sync'});
function changeMode(next){if(busy.value)return;resetCode();password.value='';error.value='';mode.value=next;}
onUnmounted(()=>{disposed=true;resetCode();email.value='';password.value='';if(owns(pendingOwner))clearLoginState();pendingOwner=null;});
async function sendCode(){
 if(busy.value||sending.value||cooldown.value||!validEmail(email.value))return;
 const target=normalizeEmail(email.value);resetCode();const g=generation,owner=identity(),valid=()=>!disposed&&g===generation&&sameIdentity(owner,identity());sending.value=true;error.value='';
 try{await account.sendEmailCode(target,'login',valid);if(!valid())return;sentEmail.value=target;cooldown.value=60;const until=Date.now()+60000;timer=setInterval(()=>{cooldown.value=Math.max(0,Math.ceil((until-Date.now())/1000));if(!cooldown.value)clearInterval(timer);},1000);}catch(e){if(valid())error.value=e.message;}finally{if(!disposed&&g===generation)sending.value=false;}
}
async function submit(){
 if(busy.value||sending.value||!validEmail(email.value))return;
 const target=normalizeEmail(email.value);if(mode.value==='code'&&(sentEmail.value!==target||!/^\d{6}$/.test(code.value)))return;
 if(mode.value==='password'&&!password.value.trim())return;
 const g=generation,valid=()=>!disposed&&g===generation;busy.value=true;error.value='';
 try{const pending=mode.value==='code'?account.loginEmailCode(target,code.value,valid):account.loginEmailPassword(target,password.value,valid);pendingOwner=identity();const p=await pending;if(!valid()||!owns(pendingOwner))return;pendingOwner=null;code.value='';password.value='';emit('logged-in',p);}catch(e){if(valid()&&(owns(pendingOwner)||!identity().token))error.value=e.message;pendingOwner=null;}finally{if(valid())busy.value=false;}
}
</script>
<template><div class="row spaced"><button type="button" class="btn" :disabled="busy" :aria-pressed="mode==='code'" @click="changeMode('code')">邮箱验证码登录</button><button type="button" class="btn" :disabled="busy" :aria-pressed="mode==='password'" @click="changeMode('password')">邮箱密码登录</button></div><form @submit.prevent="submit"><p class="small muted">使用已注册并验证的邮箱登录。没有账号请先注册。</p><label class="form-field">登录邮箱<input v-model="email" type="email" maxlength="254" autocomplete="email" :disabled="busy" required></label><template v-if="mode==='code'"><button type="button" class="btn full" :disabled="busy||sending||cooldown>0||!validEmail(email)" @click="sendCode">{{sending?'正在发送…':cooldown?cooldown+' 秒后可重发':'发送登录验证码'}}</button><p v-if="sentEmail" class="small muted" role="status">验证码已发送，请检查邮箱及垃圾邮件。更换邮箱后须重新发送。</p><label class="form-field">邮箱验证码<input v-model="code" type="password" inputmode="numeric" maxlength="6" autocomplete="one-time-code" :disabled="busy" required></label></template><template v-else><label class="form-field">密码<input v-model="password" type="password" autocomplete="current-password" :disabled="busy" required></label><p class="small muted">未设置密码的账号可使用邮箱验证码登录。</p></template><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary full" :disabled="busy||sending||!validEmail(email)||(mode==='code'?(sentEmail!==email.trim().toLowerCase()||!/^\d{6}$/.test(code)):!password.trim())">{{busy?'正在登录…':'登录'}}</button></form></template>
