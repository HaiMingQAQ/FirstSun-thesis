import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {compile} from '@vue/compiler-dom';
import {normalizeEmail,validEmail} from '../src/email-address.js';
const source=file=>fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');
function script(file,names,extra={}){
 const hooks=[],events=[];const context={ref:Vue.ref,computed:Vue.computed,watch(){},onMounted(){},onUnmounted:fn=>hooks.push(fn),nextTick:async()=>{},defineEmits:()=> (...args)=>events.push(args),identity:()=>({token:'',tenant:'901',epoch:1}),sameIdentity:(a,b)=>JSON.stringify(a)===JSON.stringify(b),clearLoginState(){},normalizeEmail,validEmail,account:{},setInterval:()=>1,clearInterval(){},setTimeout:()=>1,clearTimeout(){},window:{removeEventListener(){}},Date,...extra};
 vm.runInNewContext(source(file).split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')+';globalThis.view={'+names+'};',context);
 return{view:context.view,events,dispose:()=>hooks.forEach(fn=>fn())};
}
const login=extra=>script('EmailLogin.vue','mode,email,code,password,busy,sending,sentEmail,cooldown,error,sendCode,submit,changeMode,emit,validEmail',extra);
function component(template,state,components={}){return{setup:()=>state,components,render:new Function('Vue',compile(template,{mode:'function',prefixIdentifiers:true}).code)(Vue)};}
async function renderLogin(h){const full=source('EmailLogin.vue'),start=full.indexOf('<template>')+10,end=full.lastIndexOf('</template>');return renderToString(Vue.createSSRApp(component(full.slice(start,end),h.view)));}

test('ordinary login starts with email and masked password and offers a plain code-login link',async()=>{
 const h=login();assert.equal(h.view.mode.value,'password');const html=await renderLogin(h);assert.match(html,/autocomplete="current-password"/);assert(!html.includes('one-time-code'));assert.match(html,/>验证码登录<\/button>/);assert(!html.includes('邮箱密码登录'));assert(!html.includes('使用已注册并验证'));h.dispose();
});
test('code login remains available to accounts without a password and explicitly sends once',async()=>{
 let sends=0,consumes=0;const h=login({account:{sendEmailCode:async()=>sends++,loginEmailCode:async()=>{consumes++;return{userId:19};}}}),v=h.view;v.changeMode('code');v.email.value='member@example.test';await v.sendCode();v.code.value='123456';const html=await renderLogin(h);assert.match(html,/type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code"/);assert.match(html,/>密码登录<\/button>/);await v.submit();assert.equal(sends,1);assert.equal(consumes,1);assert.equal(h.events[0][0],'logged-in');h.dispose();
});
test('switching from password to code clears the old secret and errors',()=>{
 const h=login(),v=h.view;v.password.value='old-secret';v.code.value='123456';v.sentEmail.value='old@example.test';v.error.value='old error';v.changeMode('code');assert.equal(v.password.value,'');assert.equal(v.code.value,'');assert.equal(v.sentEmail.value,'');assert.equal(v.error.value,'');assert.equal(v.cooldown.value,0);h.dispose();
});
test('normal modal has no channel selectors even when local acceptance is enabled',async()=>{
 const h=login(),app=source('App.vue'),start=app.indexOf('<div v-if="loginOpen||dialog"'),end=app.indexOf('<div v-if="toastText"',start),state={loginOpen:true,dialog:'',authScreen:'login',authChannel:'email',authBusy:false,localTestLogin:true,closeDialog(){},backdropDown(){},backdropUp(){},backdropCancel(){},backdropClick(){},changeAuthChannel(){},onLoggedIn(){}};
 const empty={render:()=>null};const html=await renderToString(Vue.createSSRApp(component(app.slice(start,end),state,{Icon:empty,EmailRegister:empty,RegisterGate:empty,LoginGate:empty,EmailLogin:component(source('EmailLogin.vue').slice(source('EmailLogin.vue').indexOf('<template>')+10,source('EmailLogin.vue').lastIndexOf('</template>')),h.view)})));
 assert(!html.includes('邮箱账号'));assert(!html.includes('本机手机号验收'));assert.match(html,/没有账号？立即注册/);h.dispose();
});
test('the existing local badge is the gated external entry and closing returns to email',()=>{
 for(const gate of [false,true]){const h=script('App.vue',"authChannel,authScreen,loginOpen,closeDialog,openLocalLogin:typeof openLocalLogin==='function'?openLocalLogin:null",{localTestLogin:gate}),v=h.view;assert.equal(typeof v.openLocalLogin,'function');v.openLocalLogin();assert.equal(v.loginOpen.value,gate);assert.equal(v.authChannel.value,gate?'mobile':'email');v.closeDialog();assert.equal(v.authChannel.value,'email');h.dispose();}
});
