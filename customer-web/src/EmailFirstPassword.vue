<script setup>
import {ref,onUnmounted} from 'vue';
import account from './account.js';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
const props=defineProps({disabled:Boolean});
const emit=defineEmits(['profile','busy']),code=ref(''),password=ref(''),confirm=ref(''),sent=ref(false),busy=ref(false),sending=ref(false),cooldown=ref(0),error=ref('');
let disposed=false,timer;const owner=identity(),valid=()=>!disposed&&sameIdentity(owner,identity());
onUnmounted(()=>{disposed=true;emit('busy',false);clearInterval(timer);sent.value=false;cooldown.value=0;code.value='';password.value='';confirm.value='';});
async function sendCode(){
 if(props.disabled||busy.value||sending.value||cooldown.value||!valid())return;sending.value=true;emit('busy',true);sent.value=false;code.value='';error.value='';
 try{await account.sendEmailFirstPasswordCode(valid);if(!valid())return;sent.value=true;cooldown.value=60;const until=Date.now()+60000;timer=setInterval(()=>{cooldown.value=Math.max(0,Math.ceil((until-Date.now())/1000));if(!cooldown.value)clearInterval(timer);},1000);}catch(e){if(valid())error.value=e.message;}finally{if(valid()){sending.value=false;emit('busy',false);}}
}
async function submit(){
 if(props.disabled||busy.value||sending.value||!sent.value||!/^\d{6}$/.test(code.value)||!valid())return;
 if(password.value!==confirm.value){error.value='两次密码不一致';return;}busy.value=true;emit('busy',true);error.value='';
 try{const p=await account.setEmailFirstPassword(password.value,code.value,valid);if(!valid())return;code.value='';password.value='';confirm.value='';emit('profile',p);}catch(e){if(valid())error.value=e.message;}finally{if(valid()){busy.value=false;emit('busy',false);}}
}
</script>
<template><form @submit.prevent="submit"><h2>首次设置密码</h2><p class="small muted">验证码发送到当前账号已验证的登录邮箱。此入口不能覆盖已设置的密码。</p><button type="button" class="btn" :disabled="props.disabled||busy||sending||cooldown>0" @click="sendCode">{{sending?'正在发送…':cooldown?cooldown+' 秒后可重发':'发送设密验证码'}}</button><p v-if="sent" class="small muted" role="status">验证码已发送，请检查邮箱及垃圾邮件。</p><label class="form-field">邮箱验证码<input v-model="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" :disabled="props.disabled||busy" required></label><label class="form-field">新密码<input v-model="password" type="password" autocomplete="new-password" :disabled="props.disabled||busy" required><span class="small muted">8–64字符，UTF-8最多72字节。</span></label><label class="form-field">确认新密码<input v-model="confirm" type="password" autocomplete="new-password" :disabled="props.disabled||busy" required></label><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary" :disabled="props.disabled||busy||sending||!sent">{{busy?'正在设置…':'首次设置密码'}}</button></form></template>
