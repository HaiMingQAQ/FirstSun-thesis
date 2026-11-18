<script setup>
import {ref,watch,onUnmounted,nextTick} from 'vue';
import account from './account.js';
import {normalizeEmail,validEmail} from './email-address.js';
import {identity,clearLoginState} from './session.js';
import {sameIdentity} from './guards.js';
const emit=defineEmits(['logged-in','register','forgot']);
const mode=ref('password'),email=ref(''),code=ref(''),password=ref(''),busy=ref(false),sending=ref(false),sentEmail=ref(''),cooldown=ref(0),error=ref('');
const passwordVisible=ref(false),passwordInput=ref(null);
async function togglePassword(){
 if(busy.value||sending.value)return;
 const input=passwordInput.value,start=input?.selectionStart,end=input?.selectionEnd;
 passwordVisible.value=!passwordVisible.value;
 await nextTick();
 input?.focus({preventScroll:true});
 if(start!=null&&end!=null)input?.setSelectionRange(start,end);
}
let disposed=false,generation=0,timer,pendingOwner;
const owns=owner=>{const now=identity();return owner&&owner.epoch===now.epoch&&owner.tenant===now.tenant;};
function resetCode(){generation++;clearInterval(timer);sentEmail.value='';code.value='';cooldown.value=0;sending.value=false;}
watch(email,resetCode,{flush:'sync'});
function changeMode(next){if(busy.value)return;resetCode();password.value='';passwordVisible.value=false;error.value='';mode.value=next;}
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
<template>
<form class="email-login-form" @submit.prevent="submit" :aria-busy="busy||sending">
 <label class="form-field">邮箱<input v-model="email" type="email" maxlength="254" autocomplete="email" :disabled="busy" required></label>
 <template v-if="mode==='code'">
  <div class="form-field"><label for="email-login-code">验证码</label><div class="email-code-row"><input id="email-login-code" v-model="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" :disabled="busy" required><button type="button" class="btn" :disabled="busy||sending||cooldown>0||!validEmail(email)" @click="sendCode">{{sending?'正在发送…':cooldown?cooldown+' 秒后可重发':'获取验证码'}}</button></div></div>
  <p v-if="sentEmail" class="small muted" role="status">验证码已发送，请检查邮箱及垃圾邮件。</p>
 </template>
 <div v-else class="form-field">
  <label for="email-login-password">密码</label>
  <div class="email-password-field">
   <input id="email-login-password" ref="passwordInput" v-model="password" :type="passwordVisible?'text':'password'" autocomplete="current-password" :disabled="busy" required>
   <button type="button" class="icon-btn email-password-toggle" :aria-label="passwordVisible?'隐藏密码':'显示密码'" :aria-pressed="passwordVisible" aria-controls="email-login-password" :disabled="busy||sending" @pointerdown.prevent @click="togglePassword"><Icon :name="passwordVisible?'eye-off':'eye'"/></button>
  </div>
 </div>
 <p v-if="error" class="error-panel" role="alert">{{error}}</p>
 <button class="btn primary full" :disabled="busy||sending||!validEmail(email)||(mode==='code'?(sentEmail!==email.trim().toLowerCase()||!/^\d{6}$/.test(code)):!password.trim())">{{busy?'正在登录…':'登录'}}</button>
 <div class="email-login-actions"><button type="button" class="btn link" :disabled="busy" @click="changeMode(mode==='code'?'password':'code')">{{mode==='code'?'密码登录':'验证码登录'}}</button><button type="button" class="btn link" :disabled="busy" @click="emit('forgot')">忘记密码</button><button type="button" class="btn link" :disabled="busy" @click="emit('register')">没有账号？立即注册</button></div>
</form>
</template>
<style scoped>
.email-login-form{display:flex;flex-direction:column;gap:20px}
.email-login-form>.form-field,.email-login-form>p{margin:0}
.email-code-row{display:flex;align-items:center;gap:10px}
.email-code-row input{min-width:0;flex:1}
.email-code-row .btn{flex-shrink:0;padding-inline:14px}
.email-login-actions{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap}
.email-login-actions .btn{padding:0;font-size:13px}
.email-password-field { position: relative; }
.email-password-field input { width: 100%; padding-right: 52px; }
.email-password-toggle { position: absolute; top: 0; bottom: 0; right: 4px; margin-block: auto; width: 40px; height: 40px; border-radius: 6px; color: var(--muted); }
.email-password-toggle svg { width: 18px; height: 18px; }
.email-password-toggle:focus-visible { outline: 2px solid var(--green); outline-offset: 1px; }
.email-password-toggle:disabled { opacity: .45; cursor: not-allowed; }
</style>
