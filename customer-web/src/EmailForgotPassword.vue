<script setup>
import {ref,watch,onUnmounted} from 'vue';
import account from './account.js';
import {normalizeEmail,validEmail} from './email-address.js';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
const emit=defineEmits(['login']);
const email=ref(''),code=ref(''),password=ref(''),confirm=ref(''),sentEmail=ref(''),busy=ref(false),sending=ref(false),cooldown=ref(0),error=ref('');
let disposed=false,generation=0,timer;
function resetCode(){generation++;clearInterval(timer);sentEmail.value='';code.value='';cooldown.value=0;sending.value=false;}
watch(email,resetCode,{flush:'sync'});
onUnmounted(()=>{disposed=true;resetCode();email.value='';password.value='';confirm.value='';});
async function sendCode(){
 if(busy.value||sending.value||cooldown.value||!validEmail(email.value))return;
 const target=normalizeEmail(email.value);resetCode();const g=generation,owner=identity(),valid=()=>!disposed&&g===generation&&sameIdentity(owner,identity());sending.value=true;error.value='';
 try{await account.sendResetEmailCode(target,valid);if(!valid())return;sentEmail.value=target;cooldown.value=60;const until=Date.now()+60000;timer=setInterval(()=>{cooldown.value=Math.max(0,Math.ceil((until-Date.now())/1000));if(!cooldown.value)clearInterval(timer);},1000);}catch(e){if(valid())error.value=e.message;}finally{if(!disposed&&g===generation)sending.value=false;}
}
async function submit(){
 if(busy.value||sending.value||!validEmail(email.value)||sentEmail.value!==normalizeEmail(email.value)||!/^\d{6}$/.test(code.value))return;
 if(password.value!==confirm.value){error.value='两次密码不一致';return;}
 const owner=identity(),g=generation,valid=()=>!disposed&&g===generation;busy.value=true;error.value='';
 try{await account.resetEmailPassword(email.value,password.value,code.value,valid);}
 catch(e){if(valid()&&sameIdentity(owner,identity()))error.value=e.message;}
 finally{if(valid()){busy.value=false;code.value='';password.value='';confirm.value='';}}
}
</script>
<template><form class="password-security-form" @submit.prevent="submit" :aria-busy="busy||sending">
 <p class="small muted">使用已验证的登录邮箱找回密码，联系邮箱不能用于找回。</p>
 <label class="form-field">登录邮箱<input v-model="email" type="email" maxlength="254" autocomplete="email" :disabled="busy" required></label>
 <div class="form-field"><label for="email-reset-code">重置验证码</label><div class="email-code-row"><input id="email-reset-code" v-model="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" :disabled="busy" required><button type="button" class="btn" :disabled="busy||sending||cooldown>0||!validEmail(email)" @click="sendCode">{{sending?'正在请求…':cooldown?cooldown+' 秒后可重发':'获取验证码'}}</button></div></div>
 <p v-if="sentEmail" class="small muted" role="status">如果该邮箱可用于找回，我们会发送验证码，请检查收件箱及垃圾邮件。</p>
 <label class="form-field">新密码<input v-model="password" type="password" autocomplete="new-password" :disabled="busy" required><span class="small muted">8–64字符，UTF-8最多72字节。</span></label>
 <label class="form-field">确认新密码<input v-model="confirm" type="password" autocomplete="new-password" :disabled="busy" required></label>
 <p v-if="error" class="error-panel" role="alert">{{error}}</p>
 <button class="btn primary full" :disabled="busy||sending||sentEmail!==email.trim().toLowerCase()||!/^\d{6}$/.test(code)">{{busy?'正在重置…':'重置密码'}}</button>
 <button type="button" class="btn link full" :disabled="busy" @click="emit('login')">返回登录</button>
</form></template>
<style scoped>
.password-security-form{display:flex;flex-direction:column;gap:20px}.password-security-form>.form-field,.password-security-form>p{margin:0}.email-code-row{display:flex;align-items:center;gap:10px}.email-code-row input{min-width:0;flex:1}.email-code-row .btn{flex-shrink:0}
</style>
