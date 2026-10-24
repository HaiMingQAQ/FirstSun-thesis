<script setup>
import {ref,onUnmounted} from 'vue';
import account from './account.js';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
const props=defineProps({disabled:Boolean}),emit=defineEmits(['busy']);
const oldPassword=ref(''),password=ref(''),confirm=ref(''),busy=ref(false),error=ref('');
let disposed=false;const owner=identity(),valid=()=>!disposed&&sameIdentity(owner,identity());
onUnmounted(()=>{disposed=true;emit('busy',false);oldPassword.value='';password.value='';confirm.value='';});
async function submit(){
 if(props.disabled||busy.value||!valid()||!oldPassword.value.trim())return;
 if(password.value!==confirm.value){error.value='两次密码不一致';return;}
 busy.value=true;emit('busy',true);error.value='';
 try{await account.changeEmailPassword(oldPassword.value,password.value,valid);}
 catch(e){if(valid())error.value=e.message;}
 finally{if(!disposed){oldPassword.value='';password.value='';confirm.value='';busy.value=false;emit('busy',false);}}
}
</script>
<template><form class="password-security-form" @submit.prevent="submit"><h2>修改密码</h2><p class="small muted">修改成功后，所有旧会话都会退出，需要重新登录。</p>
 <label class="form-field">原密码<input v-model="oldPassword" type="password" autocomplete="current-password" :disabled="props.disabled||busy" required></label>
 <label class="form-field">新密码<input v-model="password" type="password" autocomplete="new-password" :disabled="props.disabled||busy" required><span class="small muted">8–64字符，UTF-8最多72字节。</span></label>
 <label class="form-field">确认新密码<input v-model="confirm" type="password" autocomplete="new-password" :disabled="props.disabled||busy" required></label>
 <p v-if="error" class="error-panel" role="alert">{{error}}</p><button class="btn primary" :disabled="props.disabled||busy">{{busy?'正在修改…':'修改密码并退出登录'}}</button>
</form></template>
<style scoped>
.password-security-form{display:flex;flex-direction:column;gap:20px}.password-security-form>.form-field,.password-security-form>p,.password-security-form>h2{margin:0}
</style>
