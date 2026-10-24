<script setup>
import {ref,onMounted,onUnmounted} from 'vue';
import account from './account.js';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
import PasswordGate from '#test-password';
import EmailFirstPassword from './EmailFirstPassword.vue';
import MemberAvatar from './MemberAvatar.vue';
const props=defineProps({user:Object}),emit=defineEmits(['profile','logout','login']);
const passwordBusy=ref(false);
const profile=ref(null),nickname=ref(''),email=ref(''),busy=ref(false),loading=ref(false),error=ref(''),notice=ref('');
let disposed=false;const owner=identity(),valid=()=>!disposed&&sameIdentity(owner,identity());
function accept(p){if(!valid())return;profile.value=p;nickname.value=p.name;email.value=p.email;emit('profile',p);}
onMounted(async()=>{if(!props.user)return;loading.value=true;try{accept(await account.profile(valid));}catch(e){if(valid())error.value=e.message;}finally{if(valid())loading.value=false;}});
onUnmounted(()=>{disposed=true;nickname.value='';email.value='';profile.value=null;});
async function save(){if(busy.value||passwordBusy.value||!valid())return;busy.value=true;error.value='';notice.value='';try{accept(await account.saveProfile({nickname:nickname.value,email:email.value},valid));if(valid())notice.value='资料已保存';}catch(e){if(valid())error.value=e.message;}finally{if(valid())busy.value=false;}}
async function upload(event){const file=event.target.files?.[0];event.target.value='';if(!file||busy.value||passwordBusy.value||!valid())return;busy.value=true;error.value='';notice.value='';try{accept(await account.saveAvatar(file,valid));if(valid())notice.value='头像已保存';}catch(e){if(valid())error.value=e.message;}finally{if(valid())busy.value=false;}}
</script>
<template><main class="account-page"><div class="between"><div><p class="section-label">MY ACCOUNT</p><h1>个人中心</h1></div><button v-if="user" class="btn" @click="emit('logout')">退出登录</button></div><template v-if="!user"><p>请先登录查看个人资料。</p><button class="btn primary" @click="emit('login')">登录会员账号</button></template><p v-else-if="loading">正在读取会员资料…</p><template v-else-if="profile"><section class="account-card"><MemberAvatar :src="profile.avatar" :size="76" class="profile-avatar"/><label class="btn">上传头像<input type="file" accept="image/png,image/jpeg" :disabled="busy||passwordBusy" @change="upload"></label><p class="small muted">PNG/JPEG，最多5MB；头像公开显示。</p><p v-if="profile.loginEmail" class="login-email">登录邮箱：{{profile.loginEmail}}（已验证）</p><p>手机号：{{profile.mobile||'未填写'}}</p><p>密码：{{profile.passwordSet?'已设置':'未设置，可继续验证码登录'}}</p><form @submit.prevent="save"><label class="form-field">昵称<input v-model="nickname" maxlength="30" :disabled="busy||passwordBusy" required></label><label class="form-field">联系邮箱（选填，未验证，不用于登录）<input v-model="email" type="email" maxlength="255" :disabled="busy||passwordBusy"></label><button class="btn primary" :disabled="busy||passwordBusy">{{busy?'正在保存…':'保存资料'}}</button></form></section><section v-if="!profile.passwordSet&&profile.loginEmail" class="account-card"><EmailFirstPassword :disabled="busy" @busy="passwordBusy=$event" @profile="accept"/></section><section v-else-if="!profile.passwordSet&&profile.mobile" class="account-card"><PasswordGate :disabled="busy" @busy="passwordBusy=$event" @profile="accept"/></section></template><p v-if="error" class="error-panel" role="alert">{{error}}</p><p v-if="notice" role="status">{{notice}}</p><a class="btn link" href="#catalog">返回找药购药</a></main></template>
<style scoped>
.account-page{max-width:760px;margin:40px auto;padding:0 24px}.account-card{background:#fff;border:1px solid #e4e9e5;border-radius:16px;padding:24px;margin:24px 0}.profile-avatar{margin-right:16px}.form-field{margin-top:18px}.account-card input[type=file]{display:block;max-width:260px;margin-top:8px}.login-email{overflow-wrap:anywhere}
</style>
