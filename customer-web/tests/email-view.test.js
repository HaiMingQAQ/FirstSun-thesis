import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {createAccountApi} from '../src/account-core.js';
import {normalizeEmail,validEmail} from '../src/email-address.js';
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};};
function setup(file,names,account={},props={disabled:false,user:{userId:19}},onEmit=()=>{}){
 let state={token:'',tenant:'901',epoch:1},now=0,id=0,cached=null;const watchers=[],unmounts=[],mounts=[],events=[],intervals=new Map(),calls=[],queue=[];
 class Clock extends Date{static now(){return now;}}
 const identity=()=>({...state}),sameIdentity=(a,b)=>a.token===b.token&&a.tenant===b.tenant&&a.epoch===b.epoch;
 const clearLoginState=()=>{state={...state,token:'',epoch:state.epoch+1};cached=null;};
 const context={ref:value=>({value}),watch:(_,fn)=>watchers.push(fn),onMounted:fn=>mounts.push(fn),onUnmounted:fn=>unmounts.push(fn),defineEmits:()=> (...args)=>{events.push(args);onEmit(...args);},defineProps:()=>props,account,identity,sameIdentity,clearLoginState,normalizeEmail,validEmail,Date:Clock,setInterval:fn=>{const n=++id;intervals.set(n,fn);return n;},clearInterval:n=>intervals.delete(n)};
 const script=fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'');vm.runInNewContext(script+';globalThis.view={'+names.join(',')+'};',context);
 return{...context.view,context,events,calls,queue,identity,cached:()=>cached,watch:()=>watchers.forEach(fn=>fn()),mount:()=>mounts[0]?.(),dispose:()=>unmounts.forEach(fn=>fn()),elapse:ms=>{now+=ms;[...intervals.values()].forEach(fn=>fn());},switchMember:()=>state={token:'B',tenant:'901',epoch:state.epoch+1},switchTenant:()=>state={...state,tenant:'902'},reply:data=>({ok:true,status:200,json:async()=>({code:0,data})}),useActualApi(){context.account=createAccountApi({identity,clearLoginState,setTokens:data=>state.token=data.accessToken,cacheProfile:p=>cached=p,localTestLogin:false,fetch:async(url,options)=>{calls.push({url,options});return queue.shift();}});}};
}
function auth(file='EmailLogin.vue',account={}){
 const names=['email','code','password','sentEmail','busy','sending','cooldown','error','sendCode','submit',...(file==='EmailLogin.vue'?['mode','changeMode']:['confirm'])];
 const h=setup(file,names,account);if(file==='EmailLogin.vue')h.changeMode('code');h.email.value='Member@Example.Test';h.watch();return h;
}
function ready(h){h.sentEmail.value='member@example.test';h.code.value='123456';return h;}
for(const file of ['EmailLogin.vue','EmailRegister.vue'])test(file+' requires explicit successful send and never resends on consume',async()=>{
 let sends=0,consumes=0;const consume=async()=>{consumes++;return{userId:19,mobile:null,loginEmail:'member@example.test'};};
 const h=auth(file,{sendEmailCode:async(email,scene)=>{sends++;assert.equal(email,'member@example.test');assert.equal(scene,file==='EmailLogin.vue'?'login':'register');},loginEmailCode:consume,registerEmail:consume});
 h.code.value='123456';await h.submit();assert.equal(consumes,0);await h.sendCode();h.code.value='123456';await h.submit();assert.equal(sends,1);assert.equal(consumes,1);assert.equal(h.events[0][0],'logged-in');h.dispose();
});
for(const file of ['EmailLogin.vue','EmailRegister.vue'])test(file+' prevents duplicate sends, allows failed retry and expires cooldown',async()=>{
 const d=deferred();let sends=0;const h=auth(file,{sendEmailCode:()=>{sends++;return sends===1?d.promise:Promise.resolve();}});const pending=h.sendCode();await h.sendCode();assert.equal(sends,1);d.reject(Error('邮箱服务暂不可用'));await pending;assert.equal(h.sentEmail.value,'');assert.equal(h.error.value,'邮箱服务暂不可用');await h.sendCode();assert.equal(h.cooldown.value,60);await h.sendCode();assert.equal(sends,2);h.elapse(60000);assert.equal(h.cooldown.value,0);await h.sendCode();assert.equal(sends,3);h.dispose();
});
for(const file of ['EmailLogin.vue','EmailRegister.vue'])for(const change of ['email','unmount','member','tenant'])test(`${file} late send after ${change} cannot mark an old address as sent`,async()=>{
 const d=deferred(),h=auth(file,{sendEmailCode:()=>d.promise});const pending=h.sendCode();h.code.value='123456';if(change==='email'){h.email.value='next@example.test';h.watch();}if(change==='unmount')h.dispose();if(change==='member')h.switchMember();if(change==='tenant')h.switchTenant();d.resolve();await pending;assert.equal(h.sentEmail.value,'');assert.equal(h.cooldown.value,0);assert.equal(h.sending.value,false);h.dispose();
});
test('changing a sent email clears proof and blocks submit for its replacement',async()=>{
 let consumes=0;const h=auth('EmailLogin.vue',{sendEmailCode:async()=>{},loginEmailCode:async()=>consumes++});await h.sendCode();h.code.value='123456';h.email.value='new@example.test';h.watch();assert.equal(h.code.value,'');assert.equal(h.cooldown.value,0);h.code.value='123456';await h.submit();assert.equal(consumes,0);h.dispose();
});
test('switching email login mode invalidates pending sends and clears passwords and cooldown',async()=>{
 const d=deferred(),h=auth('EmailLogin.vue',{sendEmailCode:()=>d.promise});const pending=h.sendCode();h.password.value='secret123';h.code.value='123456';h.changeMode('password');d.resolve();await pending;assert.equal(h.mode.value,'password');assert.equal(h.password.value,'');assert.equal(h.code.value,'');assert.equal(h.sentEmail.value,'');assert.equal(h.cooldown.value,0);h.dispose();
});
test('email password mode accepts a nonblank legacy password without requesting any code',async()=>{
 let sends=0,logins=0;const h=auth('EmailLogin.vue',{sendEmailCode:async()=>sends++,loginEmailPassword:async(email,password)=>{logins++;assert.equal(email,'member@example.test');assert.equal(password,'123');return{userId:19};}});h.changeMode('password');h.password.value='  ';await h.submit();assert.equal(logins,0);h.password.value='123';await h.submit();assert.equal(logins,1);assert.equal(sends,0);assert.equal(h.password.value,'');assert.equal(h.events.length,1);h.dispose();
});
test('registration confirmation mismatch blocks submit; withdrawn optional password may remain empty',async()=>{
 let consumes=0;const h=ready(auth('EmailRegister.vue',{registerEmail:async data=>{consumes++;assert.equal(data.password,'');return{userId:19};}}));h.password.value='new12345';h.confirm.value='different';await h.submit();assert.equal(consumes,0);assert.equal(h.error.value,'两次密码不一致');h.password.value='';await h.submit();assert.equal(consumes,1);h.dispose();assert.equal(h.confirm.value,'');
});
for(const file of ['EmailLogin.vue','EmailRegister.vue'])for(const change of ['unmount','member','tenant'])test(`${file} ignores delayed authentication after ${change}`,async()=>{
 const d=deferred(),h=ready(auth(file,{loginEmailCode:()=>d.promise,registerEmail:()=>d.promise}));h.password.value='new12345';if(file==='EmailRegister.vue')h.confirm.value=h.password.value;const pending=h.submit();if(change==='unmount'){h.dispose();h.switchMember();}if(change==='member')h.switchMember();if(change==='tenant')h.switchTenant();d.resolve({userId:19});await pending;assert.equal(h.events.length,0);if(change!=='tenant')assert.equal(h.identity().token,'B');h.dispose();
});
for(const file of ['EmailLogin.vue','EmailRegister.vue'])test(file+' completes token, profile, session and emit before successful dialog unmount',async()=>{
 const h=ready(auth(file));h.useActualApi();h.queue.push(h.reply({accessToken:'MAIL',refreshToken:'REFRESH'}),h.reply({id:19,mobile:null,loginEmail:'member@example.test',email:'',passwordSet:false}));await h.submit();assert.equal(h.calls.length,2);assert.equal(h.identity().token,'MAIL');assert.equal(h.cached().loginEmail,'member@example.test');assert.equal(h.events[0][1].mobile,null);assert(!JSON.stringify(h.cached()).includes('123456'));h.dispose();assert.equal(h.identity().token,'MAIL');
});
test('closing after token adoption but before real profile completes clears only its own authentication',async()=>{
 const h=ready(auth('EmailRegister.vue')),d=deferred();h.useActualApi();h.queue.push(h.reply({accessToken:'MAIL'}),d.promise);const pending=h.submit();await new Promise(r=>setImmediate(r));assert.equal(h.identity().token,'MAIL');h.dispose();assert.equal(h.identity().token,'');h.switchMember();d.resolve(h.reply({id:19,loginEmail:'old@example.test'}));await pending;assert.equal(h.identity().token,'B');assert.equal(h.cached(),null);assert.equal(h.events.length,0);
});
test('current unavailable authentication error is displayed without emission or hidden resend',async()=>{
 const h=ready(auth());h.useActualApi();h.queue.push({ok:true,status:200,json:async()=>({code:1030006021,msg:'邮箱认证暂不可用'})});await h.submit();assert.equal(h.error.value,'邮箱认证暂不可用');assert.equal(h.calls.length,1);assert.equal(h.events.length,0);assert.equal(h.identity().token,'');h.dispose();
});
function first(account={},props={disabled:false},onEmit){return setup('EmailFirstPassword.vue',['code','password','confirm','sent','busy','sending','cooldown','error','sendCode','submit'],account,props,onEmit);}
test('email first-password requires fresh send, matching confirmation and clears secrets on unmount',async()=>{
 let calls=0;const h=first({sendEmailFirstPasswordCode:async()=>{},setEmailFirstPassword:async()=>{calls++;return{passwordSet:true};}});h.code.value='123456';h.password.value='new12345';h.confirm.value='different';await h.submit();assert.equal(calls,0);await h.sendCode();assert.equal(h.code.value,'');h.code.value='123456';await h.submit();assert.equal(h.error.value,'两次密码不一致');assert.equal(calls,0);h.confirm.value=h.password.value;await h.submit();assert.equal(calls,1);assert.equal(h.events.at(-2)[0],'profile');assert.deepEqual(h.events.at(-1),['busy',false]);h.dispose();assert.equal(h.password.value,'');assert.equal(h.code.value,'');assert.equal(h.cooldown.value,0);assert.equal(h.sent.value,false);
});
test('email first-password cannot send or submit while parent owns a profile save',async()=>{
 let calls=0;const h=first({sendEmailFirstPasswordCode:()=>calls++,setEmailFirstPassword:()=>calls++},{disabled:true});h.sent.value=true;h.code.value='123456';h.password.value=h.confirm.value='new12345';await h.sendCode();await h.submit();assert.equal(calls,0);h.dispose();
});
for(const stage of ['send','consume'])test('email first-password ignores stale '+stage+' success after identity change',async()=>{
 const d=deferred(),h=first({sendEmailFirstPasswordCode:()=>d.promise,setEmailFirstPassword:()=>d.promise});h.sent.value=true;h.code.value='123456';h.password.value=h.confirm.value='new12345';const pending=stage==='send'?h.sendCode():h.submit();h.switchMember();d.resolve({passwordSet:true});await pending;assert.equal(h.events.filter(e=>e[0]==='profile').length,0);if(stage==='send')assert.equal(h.sent.value,false);assert.equal(h.identity().token,'B');h.dispose();
});
test('email first-password keeps parent lock until the refreshed profile is accepted',async()=>{
 const d=deferred();let lock=false,reads=0;const page=setup('AccountPage.vue',['passwordBusy','busy','nickname','save','upload','profile','accept'],{saveProfile:async()=>{reads++;},saveAvatar:async()=>{reads++;}});const h=first({setEmailFirstPassword:()=>d.promise},{get disabled(){return page.busy.value;}},(event,value)=>{if(event==='busy'){lock=value;page.passwordBusy.value=value;}if(event==='profile')page.accept(value);});h.sent.value=true;h.code.value='123456';h.password.value=h.confirm.value='new12345';const pending=h.submit();assert.equal(lock,true);await page.save();await page.upload({target:{files:[{size:100,type:'image/png'}],value:'chosen'}});assert.equal(reads,0);d.resolve({userId:19,name:'邮箱会员',mobile:null,loginEmail:'member@example.test',email:'',passwordSet:true});await pending;assert.equal(page.profile.value.passwordSet,true);assert.equal(lock,false);h.dispose();page.dispose();
});
test('personal center accepts an email-only member and saves contact separately from login identity',async()=>{
 let data;const p={userId:19,name:'邮箱会员',mobile:null,loginEmail:'verified@example.test',email:'contact@example.test',passwordSet:false};const h=setup('AccountPage.vue',['profile','nickname','email','save'],{profile:async()=>p,saveProfile:async value=>{data=value;return{...p,email:''};}});await h.mount();assert.equal(h.profile.value.loginEmail,'verified@example.test');h.email.value='';await h.save();assert.deepEqual({...data},{nickname:'邮箱会员',email:''});assert.equal(h.profile.value.loginEmail,'verified@example.test');h.dispose();
});
test('production email forms and email first-password contain no local or SMS instructions',()=>{
 for(const file of ['EmailLogin.vue','EmailRegister.vue','EmailFirstPassword.vue']){const source=fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');for(const text of ['localTestLogin','本机验收','运行说明','X-Sms-Code','手机号收'])assert(!source.includes(text),file+': '+text);}
 const app=fs.readFileSync(new URL('../src/App.vue',import.meta.url),'utf8');assert(app.includes("authChannel=ref('email')"));assert(app.includes("import EmailLogin from './EmailLogin.vue'"));assert(app.includes("import EmailRegister from './EmailRegister.vue'"));
 const page=fs.readFileSync(new URL('../src/AccountPage.vue',import.meta.url),'utf8');assert(page.includes('profile.loginEmail}}（已验证）'));assert(!page.includes('v-model="loginEmail"'));assert(page.includes('!profile.passwordSet&&profile.loginEmail'));
});
