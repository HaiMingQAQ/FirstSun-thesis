import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
function setup(file,names,account,props={user:{userId:1}},onEmit=()=>{}){
 const disposed=[],watchers=[],mounts=[],events=[];const state={token:'A',tenant:'901',epoch:1};
 const context={ref:value=>({value}),watch:(_,fn)=>watchers.push(fn),onMounted:fn=>mounts.push(fn),onUnmounted:fn=>disposed.push(fn),defineEmits:()=> (...args)=>{events.push(args);onEmit(...args);},defineProps:()=>props,account,localTestLogin:true,identity:()=>({...state}),sameIdentity:(a,b)=>JSON.stringify(a)===JSON.stringify(b),Date,setInterval:()=>1,clearInterval(){}};
 const source=fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'');vm.runInNewContext(source+';globalThis.view={'+names.join(',')+'}',context);
 return{...context.view,events,dispose:()=>disposed.forEach(fn=>fn()),watch:()=>watchers.forEach(fn=>fn()),mount:()=>mounts[0]?.(),change:()=>state.token='B'};
}
test('registration confirmation mismatch prevents any submit and cancellation clears sensitive refs',async()=>{
 let calls=0;const h=setup('LocalRegister.vue',['mobile','code','password','confirm','sentMobile','agreed','submit','error'],{register:()=>calls++});h.mobile.value='13900001091';h.sentMobile.value=h.mobile.value;h.code.value='123456';h.agreed.value=true;h.password.value='abc12345';h.confirm.value='different';await h.submit();assert.equal(calls,0);assert.equal(h.error.value,'两次密码不一致');h.dispose();assert.equal(h.password.value,'');assert.equal(h.confirm.value,'');assert.equal(h.code.value,'');
});
for(const file of ['LocalRegister.vue','PasswordLogin.vue'])test(file+' ignores cancelled success and clears password',async()=>{
 const d=deferred();let valid;const h=setup(file,['mobile','password','submit',...(file==='LocalRegister.vue'?['code','confirm','sentMobile','agreed']:[])],{register:(_,__,v)=>{valid=v;return d.promise;},loginPassword:(_,__,v)=>{valid=v;return d.promise;}});h.mobile.value='13900001091';h.password.value='abc12345';if(file==='LocalRegister.vue'){h.confirm.value=h.password.value;h.sentMobile.value=h.mobile.value;h.code.value='123456';h.agreed.value=true;}
 const pending=h.submit();assert.equal(valid(),true);h.dispose();assert.equal(valid(),false);d.resolve({userId:1});await pending;assert.equal(h.events.length,0);assert.equal(h.password.value,'');
});
test('first-password submit requires explicit fresh request and matching confirmation',async()=>{
 let calls=0;const h=setup('FirstPassword.vue',['code','password','confirm','sent','submit','error'],{setFirstPassword:()=>calls++});h.code.value='123456';h.password.value='abc12345';h.confirm.value='mismatch';await h.submit();assert.equal(calls,0);h.sent.value=true;await h.submit();assert.equal(h.error.value,'两次密码不一致');h.dispose();assert.equal(h.password.value,'');assert.equal(h.code.value,'');
});
test('late account profile after unmount does not emit or display',async()=>{
 const d=deferred(),h=setup('AccountPage.vue',['profile'],{profile:()=>d.promise});const pending=h.mount();h.dispose();d.resolve({userId:1,name:'OLD',email:'old@example.test'});await pending;assert.equal(h.profile.value,null);assert.equal(h.events.length,0);
});
test('optional password may be withdrawn after confirming it',async()=>{
 let calls=0;const h=setup('LocalRegister.vue',['mobile','code','password','confirm','sentMobile','agreed','submit'],{register:async data=>{calls++;assert.equal(data.password,'');return{userId:1};}});h.mobile.value='13900001091';h.sentMobile.value=h.mobile.value;h.code.value='123456';h.agreed.value=true;h.password.value='';h.confirm.value='previous12';await h.submit();assert.equal(calls,1);
});
test('profile save in progress disables first-password send and submit',async()=>{
 const d=deferred();let passwordCalls=0;const page=setup('AccountPage.vue',['busy','nickname','save'],{saveProfile:()=>d.promise});page.nickname.value='A';const pending=page.save();
 const props={get disabled(){return page.busy.value;}};const password=setup('FirstPassword.vue',['sent','code','password','confirm','sendCode','submit'],{sendPasswordCode:()=>{passwordCalls++;},setFirstPassword:()=>{passwordCalls++;}},props);password.sent.value=true;password.code.value='123456';password.password.value='new12345';password.confirm.value='new12345';await password.sendCode();password.sent.value=true;await password.submit();assert.equal(passwordCalls,0);d.resolve({userId:1,name:'A',email:''});await pending;
});
test('first-password emits ownership lock until its refreshed profile is applied',async()=>{
 const d=deferred();const h=setup('FirstPassword.vue',['sent','code','password','confirm','submit'],{setFirstPassword:()=>d.promise},{disabled:false});h.sent.value=true;h.code.value='123456';h.password.value='new12345';h.confirm.value='new12345';const pending=h.submit();assert.deepEqual(h.events[0],['busy',true]);d.resolve({passwordSet:true});await pending;assert.deepEqual(h.events.map(x=>x[0]),['busy','profile','busy']);assert.equal(h.events.at(-1)[1],false);
});
test('connected parent-child lock prevents stale false profile reads while password is becoming true',async()=>{
 const d=deferred();let oldReads=0;const page=setup('AccountPage.vue',['profile','passwordBusy','busy','nickname','save','upload','accept'],{saveProfile:async()=>{oldReads++;return{passwordSet:false};},saveAvatar:async()=>{oldReads++;return{passwordSet:false};}});page.nickname.value='A';
 const child=setup('FirstPassword.vue',['sent','code','password','confirm','submit'],{setFirstPassword:()=>d.promise},{get disabled(){return page.busy.value;}},(name,value)=>{if(name==='busy')page.passwordBusy.value=value;if(name==='profile')page.accept(value);});
 child.sent.value=true;child.code.value='123456';child.password.value='new12345';child.confirm.value='new12345';const pending=child.submit();
 await page.save();await page.upload({target:{files:[{size:100,type:'image/png'}],value:'selected'}});assert.equal(oldReads,0);
 d.resolve({userId:1,name:'A',email:'',passwordSet:true});await pending;assert.equal(page.profile.value.passwordSet,true);assert.equal(page.passwordBusy.value,false);
});
