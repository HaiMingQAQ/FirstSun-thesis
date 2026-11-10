<script setup>
import {ref,onMounted,onUnmounted} from 'vue';
import account from './account.js';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
import PasswordGate from '#test-password';
import EmailFirstPassword from './EmailFirstPassword.vue';
import EmailChangePassword from './EmailChangePassword.vue';
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
<template>
  <main class="customer-page account-page">
    <div class="breadcrumb"><a href="#home">首页</a><Icon name="chevron-right"/><span>个人中心</span></div>
    <div class="customer-title account-title">
      <div><p class="eyebrow">FIRSTSUN / MY ACCOUNT</p><h1>个人中心</h1><p>管理个人资料、登录方式和会员服务。</p></div>
      <button v-if="user" class="btn" type="button" @click="emit('logout')"><Icon name="log-out"/>退出登录</button>
    </div>
    <div v-if="!user" class="empty-state account-card">
      <Icon name="circle-user-round"/><h2>登录后查看个人中心</h2><p>在这里管理你的个人资料和账号安全。</p>
      <button class="btn primary" type="button" @click="emit('login')">登录会员账号</button>
    </div>
    <p v-else-if="loading" class="account-card muted" role="status">正在读取会员资料…</p>
    <template v-else-if="profile">
      <section class="account-summary" aria-label="会员资料概览">
        <div class="account-member">
          <MemberAvatar :src="profile.avatar" :size="76" class="profile-avatar"/>
          <div><p>{{profile.level||'会员'}}</p><h2>{{profile.name}}</h2></div>
        </div>
        <div class="avatar-action">
          <label class="btn avatar-picker"><Icon name="camera"/>更换头像<input type="file" accept="image/png,image/jpeg" :disabled="busy||passwordBusy" @change="upload"></label>
          <p class="small muted">支持 PNG 或 JPEG，最多 5 MB。头像会公开显示。</p>
        </div>
      </section>
      <nav class="account-shortcuts" aria-label="会员快捷入口">
        <a href="#orders"><Icon name="package"/><span>我的订单</span><Icon name="chevron-right"/></a>
        <a href="#prescriptions"><Icon name="file-check-2"/><span>我的处方</span><Icon name="chevron-right"/></a>
        <a href="#consult"><Icon name="headset"/><span>药师 / 客服</span><Icon name="chevron-right"/></a>
      </nav>
      <p v-if="error" class="error-panel" role="alert">{{error}}</p>
      <p v-if="notice" class="account-feedback" role="status">{{notice}}</p>
      <div class="account-grid">
        <section class="account-card">
          <h2><Icon name="user-round"/>个人资料</h2>
          <form @submit.prevent="save">
            <label class="form-field">昵称<input v-model="nickname" maxlength="30" :disabled="busy||passwordBusy" required></label>
            <label class="form-field">联系邮箱
              <input v-model="email" type="email" maxlength="255" :disabled="busy||passwordBusy" aria-describedby="contact-email-note">
            </label>
            <p id="contact-email-note" class="small muted">选填，此邮箱未经验证，不用于登录。</p>
            <button class="btn primary" :disabled="busy||passwordBusy">{{busy?'正在保存…':'保存资料'}}</button>
          </form>
        </section>
        <section class="account-card">
          <h2><Icon name="shield-check"/>账号安全</h2>
          <dl class="account-identities">
            <div><dt>登录邮箱</dt><dd>{{profile.loginEmail||'未绑定'}} <span v-if="profile.loginEmail" class="account-verified">已验证</span></dd></div>
            <div><dt>手机号</dt><dd>{{profile.mobile||'未填写'}}</dd></div>
            <div><dt>登录密码</dt><dd>{{profile.passwordSet?'已设置':'未设置，可继续使用验证码登录'}}</dd></div>
          </dl>
          <div v-if="profile.loginEmail||(!profile.passwordSet&&profile.mobile)" class="account-password">
            <EmailChangePassword v-if="profile.passwordSet&&profile.loginEmail" :disabled="busy" @busy="passwordBusy=$event"/>
            <EmailFirstPassword v-else-if="!profile.passwordSet&&profile.loginEmail" :disabled="busy" @busy="passwordBusy=$event" @profile="accept"/>
            <PasswordGate v-else-if="!profile.passwordSet&&profile.mobile" :disabled="busy" @busy="passwordBusy=$event" @profile="accept"/>
          </div>
        </section>
      </div>
    </template>
    <p v-else-if="error" class="error-panel" role="alert">{{error}}</p>
  </main>
</template>
<style scoped>
.account-page { max-width: 1152px; padding-bottom: 48px; }
.account-title { margin-bottom: 22px; }
.account-title .btn, .avatar-picker { display: inline-flex; align-items: center; gap: 8px; }
.account-title .btn svg, .avatar-picker svg, .account-card h2 svg { width: 18px; height: 18px; flex: none; }
.account-summary { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: clamp(18px,3vw,30px); border: 1px solid #d7e5da; border-radius: 18px; background: #edf5ef; }
.account-member { display: flex; align-items: center; gap: 16px; min-width: 0; }
.profile-avatar { flex: none; }
.account-member p { margin: 0 0 4px; font-size: 13px; color: var(--green); }
.account-member h2 { margin: 0; font-size: clamp(20px,3vw,28px); overflow-wrap: anywhere; }
.avatar-action { text-align: right; }
.avatar-action p { margin: 7px 0 0; }
.avatar-picker { position: relative; background: #fff; cursor: pointer; }
.avatar-picker:focus-within { outline: 2px solid var(--green); outline-offset: 3px; }
.avatar-picker input { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: pointer; }
.avatar-picker:has(input:disabled) { opacity: .65; cursor: default; }
.account-shortcuts, .account-grid { display: grid; gap: 14px; margin-top: 16px; }
.account-shortcuts { grid-template-columns: repeat(3,minmax(0,1fr)); }
.account-shortcuts a { display: flex; align-items: center; gap: 10px; min-height: 54px; padding: 10px 15px; border: 1px solid var(--line); border-radius: 12px; background: #fff; font-weight: 600; }
.account-shortcuts a:hover { border-color: var(--green); color: var(--green); }
.account-shortcuts svg { width: 18px; height: 18px; flex: none; color: var(--green); }
.account-shortcuts svg:last-child { margin-left: auto; }
.account-grid { grid-template-columns: minmax(0,1fr) minmax(0,1fr); align-items: start; }
.account-card { min-width: 0; padding: clamp(16px,3vw,24px); border: 1px solid var(--line); border-radius: 16px; background: #fff; }
.account-grid h2 { display: flex; align-items: center; gap: 9px; margin: 0 0 16px; font-size: 19px; }
.account-card h2 svg { color: var(--green); }
.account-card form > .btn { margin-top: 12px; }
.account-identities { margin: 0; }
.account-identities div { display: grid; grid-template-columns: 72px minmax(0,1fr); gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--line); font-size: 13px; }
.account-identities dt { color: var(--muted); }
.account-identities dd { margin: 0; overflow-wrap: anywhere; }
.account-verified { display: inline-block; margin-left: 4px; color: var(--green); font-size: 11px; }
.account-password { margin-top: 18px; }
.account-feedback { padding: 10px 14px; border-radius: 8px; color: var(--green); background: #edf5ef; }
@media (max-width: 720px) { .account-grid { grid-template-columns: 1fr; } }
@media (max-width: 520px) {
  .account-summary { align-items: flex-start; flex-direction: column; }
  .avatar-action { text-align: left; }
  .account-shortcuts { grid-template-columns: 1fr; gap: 7px; }
}
</style>
