<script setup>
import {ref,watch,onUnmounted} from 'vue';
import account from './account.js';
import {localTestLogin} from './config.js';
const emit=defineEmits(['logged-in']);
const mobile=ref(''),code=ref(''),email=ref(''),password=ref(''),confirm=ref(''),agreed=ref(false),busy=ref(false),sending=ref(false),sentMobile=ref(''),cooldown=ref(0),error=ref('');
let disposed=false,generation=0,timer;
function resetCode(){generation++;clearInterval(timer);sentMobile.value='';code.value='';cooldown.value=0;sending.value=false;}
watch(mobile,resetCode,{flush:'sync'});
onUnmounted(()=>{disposed=true;resetCode();password.value='';confirm.value='';email.value='';mobile.value='';});
async function sendCode(){
 const m=mobile.value.trim();if(!localTestLogin||busy.value||sending.value||cooldown.value||!agreed.value||!/^1[3-9][0-9]{9}$/.test(m))return;
 resetCode();const g=generation;const valid=()=>!disposed&&g===generation; sending.value=true;error.value='';
 try{await account.sendCode(m,valid);if(!valid())return;sentMobile.value=m;const until=Date.now()+60000;cooldown.value=60;timer=setInterval(()=>{cooldown.value=Math.max(0,Math.ceil((until-Date.now())/1000));if(!cooldown.value)clearInterval(timer);},1000);}catch(e){if(valid())error.value=e.message;}finally{if(valid())sending.value=false;}
}
async function submit(){
 if(!localTestLogin||busy.value||sending.value||!agreed.value||sentMobile.value!==mobile.value.trim()||!/^1[3-9][0-9]{9}$/.test(mobile.value.trim())||!/^[0-9]{6}$/.test(code.value))return;
 if(password.value&&password.value!==confirm.value){error.value='两次密码不一致';return;}
 const g=generation,valid=()=>!disposed&&g===generation;busy.value=true;error.value='';
 try{const p=await account.register({mobile:mobile.value.trim(),email:email.value,password:password.value},code.value,valid);if(!valid())return;password.value='';confirm.value='';code.value='';emit('logged-in',p);}catch(e){if(valid())error.value=e.message;}finally{if(valid())busy.value=false;}
}
</script>
<template><form v-if="localTestLogin" @submit.prevent="submit"><p class="small muted">独立本机注册验收，不发送真实短信。已有账号请返回登录；邮箱仅作未验证联系方式。</p><label class="form-field">手机号<input v-model="mobile" inputmode="numeric" maxlength="11" autocomplete="off" :disabled="busy" required></label><label class="consent spaced"><input v-model="agreed" type="checkbox" :disabled="busy">我知道这是本机验收，使用合成会员。</label><button type="button" class="btn full" :disabled="busy||sending||cooldown>0||!agreed" @click="sendCode">{{sending?'正在请求…':cooldown?cooldown+' 秒后可重发':'获取注册验证码'}}</button><p v-if="sentMobile" class="small muted">已请求，请读取受限运行说明。</p><label class="form-field">验证码<input v-model="code" type="password" inputmode="numeric" maxlength="6" autocomplete="off" :disabled="busy" required></label><label class="form-field">邮箱（选填，不用于登录）<input v-model="email" type="email" maxlength="255" autocomplete="off" :disabled="busy"></label><label class="form-field">密码（选填，可之后设置）<input v-model="password" type="password" autocomplete="new-password" :disabled="busy"><span class="small muted">8–64字符，UTF-8最多72字节；不填可继续验证码登录。</span></label><label v-if="password" class="form-field">确认密码<input v-model="confirm" type="password" autocomplete="new-password" :disabled="busy" required></label><p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary full" :disabled="busy||sending||!agreed||sentMobile!==mobile.trim()">{{busy?'正在注册…':'注册并登录'}}</button></form></template>
