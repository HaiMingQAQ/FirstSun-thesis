import test from 'node:test';
import assert from 'node:assert/strict';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
async function harness(){
 const {createAccountApi}=await import('../src/account-core.js');
 let state={token:'',tenant:'901',epoch:0},profile=null;
 const calls=[],queue=[];
 const api=createAccountApi({identity:()=>({...state}),clearLoginState:()=>{state={...state,token:'',epoch:state.epoch+1};profile=null;},setTokens:data=>state.token=data.accessToken,cacheProfile:p=>profile=p,localTestLogin:true,fetch:async(url,options)=>{calls.push({url,options});return queue.shift();},FormData:class{append(...args){this.args=args;}}});
 return{api,calls,queue,reply:data=>({ok:true,status:200,json:async()=>({code:0,data})}),switchMember:()=>state={token:'B',tenant:'901',epoch:state.epoch+1},switchTenant:()=>state={...state,tenant:'902'},setToken:t=>state.token=t,state:()=>state,profile:()=>profile};
}
test('registration sends code header separately, keeps password bytes, loads real profile without resend',async()=>{
 const h=await harness();h.queue.push(h.reply({accessToken:'A'}),h.reply({id:1,nickname:'A',email:'a@example.test',passwordSet:true}));
 const p=await h.api.register({mobile:'13900001091',password:' secret123 ',email:' a@example.test '},'123456');
 assert.equal(h.calls.length,2);assert.equal(h.calls[0].url,'/app-api/member/auth/register');assert.equal(h.calls[0].options.headers['X-Sms-Code'],'123456');
 assert.deepEqual(JSON.parse(h.calls[0].options.body),{mobile:'13900001091',password:' secret123 ',email:'a@example.test'});assert.equal(p.email,'a@example.test');assert.equal(h.profile().userId,1);
});
test('registration allows omitted email/password and password login uses JSON only',async()=>{
 const h=await harness();h.queue.push(h.reply({accessToken:'A'}),h.reply({id:1,passwordSet:false}));await h.api.register({mobile:'13900001091'},'123456');assert.deepEqual(JSON.parse(h.calls[0].options.body),{mobile:'13900001091'});
 h.queue.push(h.reply({accessToken:'C'}),h.reply({id:1,passwordSet:true}));await h.api.loginPassword('13900001091','abc12345');assert.equal(h.calls[2].url,'/app-api/member/auth/login');assert.equal(h.calls[2].options.headers['X-Sms-Code'],undefined);assert.equal(JSON.parse(h.calls[2].options.body).password,'abc12345');
});
for(const change of ['member','cancel'])test(`late registration response after ${change} cannot install token or fetch profile`,async()=>{
 const h=await harness(),d=deferred();let valid=true;h.queue.push(d.promise);const pending=h.api.register({mobile:'13900001091'},'123456',()=>valid);
 if(change==='member')h.switchMember();else valid=false;d.resolve(h.reply({accessToken:'OLD'}));await assert.rejects(pending);assert.equal(h.calls.length,1);assert.equal(h.state().token,change==='member'?'B':'');assert.equal(h.profile(),null);
});
test('stale avatar upload cannot send follow-up profile write for new member',async()=>{
 const h=await harness();h.setToken('A');const d=deferred();h.queue.push(d.promise);const pending=h.api.saveAvatar({size:100,type:'image/png'});h.switchMember();d.resolve(h.reply('http://local/avatar.png'));await assert.rejects(pending);assert.equal(h.calls.length,1);assert.equal(h.state().token,'B');assert.equal(h.calls[0].options.headers['Content-Type'],undefined);
});
test('avatar chain uses the same identity and fetches saved real profile',async()=>{
 const h=await harness();h.setToken('A');h.queue.push(h.reply('http://local/avatar.png'),h.reply(true),h.reply({id:1,avatar:'http://local/avatar.png'}));const p=await h.api.saveAvatar({size:100,type:'image/png'});assert.equal(p.avatar,'http://local/avatar.png');assert.equal(h.calls.length,3);assert(h.calls.every(x=>x.options.headers.Authorization==='A'));assert.deepEqual(JSON.parse(h.calls[1].options.body),{avatar:'http://local/avatar.png'});
});
test('explicit email clearing persists as empty string and profile state is refreshed',async()=>{
 const h=await harness();h.setToken('A');h.queue.push(h.reply(true),h.reply({id:1,email:''}));await h.api.saveProfile({nickname:' A ',email:''});assert.deepEqual(JSON.parse(h.calls[0].options.body),{nickname:'A',email:''});assert.equal(h.profile().email,'');
});
test('first password sends no identity selector and refreshes passwordSet',async()=>{
 const h=await harness();h.setToken('A');h.queue.push(h.reply(true),h.reply({id:1,passwordSet:true}));const p=await h.api.setFirstPassword('abc12345','123456');assert.deepEqual(JSON.parse(h.calls[0].options.body),{password:'abc12345'});assert.equal(h.calls[0].options.headers['X-Sms-Code'],'123456');assert.equal(p.passwordSet,true);
});
test('late old 401 must not clear the new member',async()=>{
 const h=await harness();h.setToken('A');const d=deferred();h.queue.push(d.promise);const pending=h.api.saveProfile({nickname:'A'});h.switchMember();d.resolve({ok:false,status:401,json:async()=>({code:401})});await assert.rejects(pending);assert.equal(h.state().token,'B');
});
test('password and avatar validation reject invalid input before fetch',async()=>{
 const h=await harness();for(const password of ['short','        ','中'.repeat(25)])await assert.rejects(h.api.register({mobile:'13900001091',password},'123456'));
 h.setToken('A');await assert.rejects(h.api.saveAvatar({size:5*1024*1024+1,type:'image/png'}));await assert.rejects(h.api.saveAvatar({size:10,type:'image/gif'}));assert.equal(h.calls.length,0);
});
test('existing short password remains valid input to password login',async()=>{
 const h=await harness();h.queue.push(h.reply({accessToken:'A'}),h.reply({id:1,passwordSet:true}));await h.api.loginPassword('13900001091','123456');assert.equal(JSON.parse(h.calls[0].options.body).password,'123456');
});
test('tenant change while uploading blocks subsequent writes',async()=>{
 const h=await harness();h.setToken('A');const d=deferred();h.queue.push(d.promise);const pending=h.api.saveAvatar({size:100,type:'image/png'});h.switchTenant();d.resolve(h.reply('http://local/a.png'));await assert.rejects(pending);assert.equal(h.calls.length,1);assert.equal(h.state().tenant,'902');
});
test('cancelled save after PUT cannot fetch profile or alter cache',async()=>{
 const h=await harness();h.setToken('A');let valid=true;const d=deferred();h.queue.push(d.promise);const pending=h.api.saveProfile({nickname:'A'},()=>valid);valid=false;d.resolve(h.reply(true));await assert.rejects(pending);assert.equal(h.calls.length,1);assert.equal(h.profile(),null);
});
test('late profile response after member change cannot populate new cache',async()=>{
 const h=await harness();h.setToken('A');const d=deferred();h.queue.push(h.reply(true),d.promise);const pending=h.api.saveProfile({nickname:'A'});await new Promise(r=>setImmediate(r));h.switchMember();d.resolve(h.reply({id:1,nickname:'OLD'}));await assert.rejects(pending);assert.equal(h.profile(),null);assert.equal(h.state().token,'B');
});
test('cancelled authentication profile step clears only its own issued token',async()=>{
 const h=await harness();let valid=true;const d=deferred();h.queue.push(h.reply({accessToken:'A'}),d.promise);const pending=h.api.register({mobile:'13900001091'},'123456',()=>valid);await new Promise(r=>setImmediate(r));assert.equal(h.state().token,'A');valid=false;d.resolve(h.reply({id:1}));await assert.rejects(pending);assert.equal(h.state().token,'');assert.equal(h.profile(),null);
});
test('first-password code request has no client target and never sets password implicitly',async()=>{
 const h=await harness();h.setToken('A');h.queue.push(h.reply(true));await h.api.sendPasswordCode();assert.equal(h.calls.length,1);assert.equal(h.calls[0].url,'/app-api/member/user/send-password-code');assert.equal(h.calls[0].options.body,undefined);
});
test('current 401 clears current session once and never retries',async()=>{
 const h=await harness();h.setToken('A');h.queue.push({ok:false,status:401,json:async()=>({code:401})});await assert.rejects(h.api.profile());assert.equal(h.calls.length,1);assert.equal(h.state().token,'');
});
test('production authentication gate rejects before any fetch',async()=>{
 const {createAccountApi}=await import('../src/account-core.js');let calls=0;const api=createAccountApi({localTestLogin:false,fetch:()=>calls++});await assert.rejects(api.register({mobile:'13900001091'},'123456'));await assert.rejects(api.loginPassword('13900001091','123456'));await assert.rejects(api.sendPasswordCode());await assert.rejects(api.setFirstPassword('abc12345','123456'));assert.equal(calls,0);
});
