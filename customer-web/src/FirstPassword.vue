<script setup>
import {ref,onUnmounted} from 'vue';
import account from './account.js';
import {localTestLogin} from './config.js';
const props=defineProps({disabled:Boolean});
const emit=defineEmits(['profile','busy']),code=ref(''),password=ref(''),confirm=ref(''),sent=ref(false),busy=ref(false),sending=ref(false),cooldown=ref(0),error=ref('');
let disposed=false,timer;
const valid=()=>!disposed;
onUnmounted(()=>{disposed=true;emit('busy',false);clearInterval(timer);code.value='';password.value='';confirm.value='';});
async function sendCode(){
 if(!localTestLogin||props.disabled||busy.value||sending.value||cooldown.value)return;sending.value=true;emit('busy',true);sent.value=false;code.value='';error.value='';
 try{await account.sendPasswordCode(valid);if(disposed)return;sent.value=true;cooldown.value=60;const until=Date.now()+60000;timer=setInterval(()=>{cooldown.value=Math.max(0,Math.ceil((until-Date.now())/1000));if(!cooldown.value)clearInterval(timer);},1000);}catch(e){if(valid())error.value=e.message;}finally{if(valid()){sending.value=false;emit('busy',false);}}
}
async function submit(){
 if(!localTestLogin||props.disabled||busy.value||sending.value||!sent.value||!/^[0-9]{6}$/.test(code.value))return;
 if(password.value!==confirm.value){error.value='两次密码不一致';return;}busy.value=true;emit('busy',true);error.value='';
 try{const p=await account.setFirstPassword(password.value,code.value,valid);if(!valid())return;code.value='';password.value='';confirm.value='';emit('profile',p);}catch(e){if(valid())error.value=e.message;}finally{if(valid()){busy.value=false;emit('busy',false);}}
}
</script>
<template><form v-if="localTestLogin" @submit.prevent="submit"><h2>首次设置密码</h2><p class="small muted">验证码只发送到当前会员自己的手机号；本机验收不发送真实短信。此入口不能覆盖已设置的密码。</p><button type="button" class="btn" :disabled="props.disabled||busy||sending||cooldown>0" @click="sendCode">{{sending?'正在请求…':cooldown?cooldown+' 秒后可重发':'重新获取设密验证码'}}</button><label class="form-field">新验证码<input v-model="code" type="password" inputmode="numeric" maxlength="6" autocomplete="off" :disabled="props.disabled||busy" required></label><label class="form-field">新密码<input v-model="password" type="password" autocomplete="new-password" :disabled="props.disabled||busy" required><span class="small muted">8–64字符，UTF-8最多72字节。</span></label><label class="form-field">确认新密码<input v-model="confirm" type="password" autocomplete="new-password" :disabled="props.disabled||busy" required></label><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary" :disabled="props.disabled||busy||sending||!sent">{{busy?'正在设置…':'首次设置密码'}}</button></form></template>
