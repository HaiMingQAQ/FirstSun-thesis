import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {parse,compileScript} from '@vue/compiler-sfc';
import {compile} from '@vue/compiler-dom';
import {createAccountApi} from '../src/account-core.js';
import {normalizeEmail,validEmail} from '../src/email-address.js';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
const source=file=>fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');
function setup(file,names,props={disabled:false}){
 let state={token:'A',tenant:'901',epoch:1},notices=0;const disposed=[],watchers=[],calls=[],queue=[],events=[];
 const identity=()=>({...state}),sameIdentity=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
 const account=createAccountApi({identity,clearLoginState:()=>state={...state,token:'',epoch:state.epoch+1},setTokens:()=>{throw Error('No automatic login');},cacheProfile:()=>{throw Error('No post-update profile request');},localTestLogin:false,onPasswordChanged:()=>notices++,fetch:async(url,options)=>{calls.push({url,options});return queue.shift();}});
 const context={ref:Vue.ref,computed:Vue.computed,watch:(_,fn)=>watchers.push(fn),onMounted(){},onUnmounted:fn=>disposed.push(fn),defineProps:()=>props,defineEmits:()=> (...args)=>events.push(args),account,normalizeEmail,validEmail,identity,sameIdentity,Date,setInterval:()=>1,clearInterval(){},setTimeout:()=>1,clearTimeout(){},trade:{reset(){}},window:{removeEventListener(){}},location:{hash:'#catalog'},localTestLogin:false};
 vm.runInNewContext(source(file).split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')+';globalThis.view={'+names.join(',')+'};',context);
 return{...context.view,calls,queue,events,context,notices:()=>notices,identity,watch:()=>watchers.forEach(fn=>fn()),dispose:()=>disposed.forEach(fn=>fn()),switchMember:()=>state={token:'B',tenant:'901',epoch:state.epoch+1},reply:data=>({ok:true,status:200,json:async()=>({code:0,data})})};
}
function forgot(){return setup('EmailForgotPassword.vue',['email','code','password','confirm','sentEmail','busy','sending','error','sendCode','submit']);}
test('forgot-password explicitly sends once and submits proof without hidden resend or automatic login',async()=>{
 const h=forgot();h.email.value='Member@Example.Test';h.password.value='new12345';h.confirm.value='new12345';h.code.value='123456';await h.submit();assert.equal(h.calls.length,0);
 h.queue.push(h.reply(true));await h.sendCode();h.code.value='123456';h.queue.push(h.reply(true));await h.submit();
 assert.equal(h.calls.length,2);assert.equal(h.calls[0].url,'/app-api/member/auth/send-email-reset-code');assert.equal(h.calls[1].url,'/app-api/member/auth/email-reset-password');assert.equal(h.identity().token,'');assert.equal(h.notices(),1);assert.equal(h.password.value,'');assert.equal(h.confirm.value,'');h.dispose();
});
for(const change of ['unmount','member','email'])test('late reset send after '+change+' cannot validate a replacement form',async()=>{
 const h=forgot(),d=deferred();h.email.value='member@example.test';h.queue.push(d.promise);const pending=h.sendCode();
 if(change==='unmount')h.dispose();if(change==='member')h.switchMember();if(change==='email'){h.email.value='other@example.test';h.watch();}
 d.resolve(h.reply(true));await pending;assert.equal(h.sentEmail.value,'');assert.equal(h.notices(),0);if(change!=='unmount')h.dispose();
});
test('both reset and change require matching confirmation before requesting',async()=>{
 const h=forgot();h.email.value='member@example.test';h.sentEmail.value=h.email.value;h.code.value='123456';h.password.value='new12345';h.confirm.value='different';await h.submit();assert.equal(h.calls.length,0);assert.equal(h.error.value,'两次密码不一致');
 const c=setup('EmailChangePassword.vue',['oldPassword','password','confirm','submit','error']);c.oldPassword.value='old';c.password.value='new12345';c.confirm.value='different';await c.submit();assert.equal(c.calls.length,0);assert.equal(c.error.value,'两次密码不一致');h.dispose();c.dispose();
});
test('change respects parent lock and holds its busy lock across the complete operation',async()=>{
 const props={disabled:true},h=setup('EmailChangePassword.vue',['oldPassword','password','confirm','submit','busy'],props);
 h.oldPassword.value='old';h.password.value='new12345';h.confirm.value='new12345';await h.submit();assert.equal(h.calls.length,0);props.disabled=false;
 const d=deferred();h.queue.push(d.promise);const pending=h.submit();assert.deepEqual(h.events[0],['busy',true]);assert.equal(h.busy.value,true);d.resolve(h.reply(true));await pending;assert.equal(h.notices(),1);assert.equal(h.oldPassword.value,'');assert.equal(h.identity().token,'');h.dispose();
});
test('unmounted change completion cannot sign out another member or show success',async()=>{
 const h=setup('EmailChangePassword.vue',['oldPassword','password','confirm','submit']);h.oldPassword.value='old';h.password.value='new12345';h.confirm.value='new12345';
 const d=deferred();h.queue.push(d.promise);const pending=h.submit();h.dispose();h.switchMember();d.resolve(h.reply(true));await pending;assert.equal(h.identity().token,'B');assert.equal(h.notices(),0);assert.equal(h.password.value,'');
});
test('password-update notification returns the actual App handlers to email login and Esc clears the notice',()=>{
 const h=setup('App.vue',['passwordChanged','passwordNotice','authScreen','authChannel','loginOpen','closeDialog']);h.authScreen.value='forgot';h.passwordChanged();assert.equal(h.authScreen.value,'login');assert.equal(h.authChannel.value,'email');assert.equal(h.loginOpen.value,true);assert.match(h.passwordNotice.value,/重新登录/);h.closeDialog();assert.equal(h.passwordNotice.value,'');assert.equal(h.loginOpen.value,false);h.dispose();
});
async function render(file,values={}){
 const {descriptor}=parse(source(file));const render=new Function('Vue',compile(descriptor.template.content,{mode:'function'}).code)(Vue);
 const state={email:'member@example.test',code:'123456',password:'',confirm:'',oldPassword:'',sentEmail:'member@example.test',busy:false,sending:false,cooldown:0,error:'',props:{disabled:false},validEmail:()=>true,emit(){},sendCode(){},submit(){},...values};
 return renderToString(Vue.createSSRApp({setup:()=>state,render}));
}
const inputs=html=>[...html.matchAll(/<input\b[^>]*>/g)].map(x=>x[0]);
test('reset new-password and confirmation fields always occupy normal template layout',async()=>{
 const empty=inputs(await render('EmailForgotPassword.vue')),filled=inputs(await render('EmailForgotPassword.vue',{password:'new12345'}));assert.equal(empty.length,4);assert.equal(filled.length,4);
 assert.match(empty[1],/type="text"/);assert.match(empty[1],/inputmode="numeric"/);assert.match(empty[1],/autocomplete="one-time-code"/);assert.match(empty[2],/type="password"/);assert.match(empty[3],/type="password"/);
 assert.match(await render('EmailForgotPassword.vue'),/如果该邮箱可用于找回/);
});
test('change always shows old/new/confirmation and does not add an email-code step',async()=>{
 const empty=inputs(await render('EmailChangePassword.vue')),filled=inputs(await render('EmailChangePassword.vue',{password:'new12345'}));assert.equal(empty.length,3);assert.equal(filled.length,3);assert(empty.every(x=>x.includes('type="password"')));assert(!empty.some(x=>x.includes('one-time-code')));
});
for(const file of ['EmailForgotPassword.vue','EmailChangePassword.vue'])test(file+' compiles script and template offline',()=>{const {descriptor,errors}=parse(source(file));assert.deepEqual(errors,[]);assert.doesNotThrow(()=>compileScript(descriptor,{id:file,inlineTemplate:true}));});
