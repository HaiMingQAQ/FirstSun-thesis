import test from 'node:test';
import assert from 'node:assert/strict';
import {createAccountApi} from '../src/account-core.js';
import {normalizeEmail,validEmail} from '../src/email-address.js';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
function harness(localTestLogin=false){
 let state={token:'A',tenant:'901',epoch:1},cached=null;
 const calls=[],queue=[];
 const api=createAccountApi({identity:()=>({...state}),clearLoginState:()=>{state={...state,token:'',epoch:state.epoch+1};cached=null;},setTokens:data=>{if(!data?.accessToken)throw Error('missing token');state.token=data.accessToken;},cacheProfile:p=>cached=p,localTestLogin,fetch:async(url,options)=>{calls.push({url,options});return queue.shift();}});
 return{api,calls,queue,reply:data=>({ok:true,status:200,json:async()=>({code:0,data})}),state:()=>({...state}),cached:()=>cached,switchMember:()=>state={token:'B',tenant:'901',epoch:state.epoch+1},switchTenant:()=>state={...state,tenant:'902'}};
}
test('email-only profile retains verified login identity separately from editable contact email',async()=>{
 const h=harness();h.queue.push(h.reply({id:19,mobile:null,nickname:'邮箱会员',loginEmail:'verified@example.test',email:'contact@example.test',passwordSet:false}));
 const p=await h.api.profile();assert.equal(p.loginEmail,'verified@example.test');assert.equal(p.email,'contact@example.test');assert.equal(p.mobile,null);assert.equal(h.cached().loginEmail,p.loginEmail);
});
test('clearing unverified contact email does not discard verified login identity',async()=>{
 const h=harness();h.queue.push(h.reply(true),h.reply({id:19,mobile:null,loginEmail:'verified@example.test',email:''}));
 const p=await h.api.saveProfile({nickname:'邮箱会员',email:'',loginEmail:'untrusted@example.test'});assert.deepEqual(JSON.parse(h.calls[0].options.body),{nickname:'邮箱会员',email:''});assert.equal(p.loginEmail,'verified@example.test');assert.equal(p.email,'');
});
test('unverified old contact address is never projected as a login identity',async()=>{
 const h=harness();h.queue.push(h.reply({id:3,email:'legacy@example.test'}));assert.equal((await h.api.profile()).loginEmail,'');
});
test('production email registration uses email proof, optional password bytes and no mobile',async()=>{
 const h=harness();h.queue.push(h.reply({accessToken:'MAIL'}),h.reply({id:19,mobile:null,loginEmail:'member@example.test',passwordSet:true}));
 const p=await h.api.registerEmail({email:' Member@Example.Test ',password:' secret123 '},'123456');assert.equal(p.loginEmail,'member@example.test');assert.equal(p.mobile,null);assert.equal(h.calls.length,2);assert.equal(h.calls[0].url,'/app-api/member/auth/email-register');assert.deepEqual(JSON.parse(h.calls[0].options.body),{email:'member@example.test',password:' secret123 '});assert.equal(h.calls[0].options.headers['X-Email-Code'],'123456');assert.equal(h.calls[0].options.headers['X-Sms-Code'],undefined);assert.equal(h.calls[1].options.headers.Authorization,'MAIL');assert.equal(h.cached().userId,19);
});
test('email registration can omit a password and code login uses a distinct non-register endpoint',async()=>{
 const h=harness();h.queue.push(h.reply({accessToken:'REGISTER'}),h.reply({id:19,passwordSet:false}));await h.api.registerEmail({email:'member@example.test'},'123456');assert.deepEqual(JSON.parse(h.calls[0].options.body),{email:'member@example.test'});
 h.queue.push(h.reply({accessToken:'LOGIN'}),h.reply({id:19,passwordSet:false}));await h.api.loginEmailCode(' Member@Example.Test ','654321');assert.equal(h.calls[2].url,'/app-api/member/auth/email-login');assert.deepEqual(JSON.parse(h.calls[2].options.body),{email:'member@example.test'});assert.equal(h.calls[2].options.headers['X-Email-Code'],'654321');assert.equal(h.calls.length,4);
});
for(const scene of ['register','login'])test('explicit email code send carries '+scene+' purpose without implicit authentication',async()=>{
 const h=harness();h.queue.push(h.reply(true));await h.api.sendEmailCode(' Member+tag@Example.Test ',scene);assert.equal(h.calls.length,1);assert.equal(h.calls[0].url,'/app-api/member/auth/send-email-code');assert.deepEqual(JSON.parse(h.calls[0].options.body),{email:'member+tag@example.test',scene});assert.equal(h.state().token,'A');assert.equal(h.cached(),null);
});
test('email password login keeps nonblank legacy short passwords and never sends a code',async()=>{
 const h=harness();h.queue.push(h.reply({accessToken:'MAIL'}),h.reply({id:19,passwordSet:true}));await h.api.loginEmailPassword('member@example.test',' 123 ');assert.equal(h.calls[0].url,'/app-api/member/auth/email-password-login');assert.deepEqual(JSON.parse(h.calls[0].options.body),{email:'member@example.test',password:' 123 '});assert.equal(h.calls[0].options.headers['X-Email-Code'],undefined);assert.equal(h.calls[0].options.headers['X-Sms-Code'],undefined);
});
test('email first-password send and consume never accept a target and refresh passwordSet',async()=>{
 const h=harness();h.queue.push(h.reply(true),h.reply(true),h.reply({id:19,loginEmail:'member@example.test',passwordSet:true}));await h.api.sendEmailFirstPasswordCode();const p=await h.api.setEmailFirstPassword('new12345','123456');assert.equal(h.calls[0].url,'/app-api/member/auth/send-email-first-password-code');assert.equal(h.calls[0].options.body,undefined);assert.equal(h.calls[1].url,'/app-api/member/auth/email-first-password');assert.deepEqual(JSON.parse(h.calls[1].options.body),{password:'new12345'});assert.equal(h.calls[1].options.headers['X-Email-Code'],'123456');assert(h.calls.every(c=>c.options.headers.Authorization==='A'));assert.equal(p.passwordSet,true);
});
test('email validation is ASCII canonical and preserves provider aliases',()=>{
 assert.equal(normalizeEmail(' A.B+tag@Example.TEST '),'a.b+tag@example.test');assert(validEmail('x@a.test'));
 for(const value of ['',null,'a@localhost','a@@b.test','a b@b.test','a..b@b.test','.a@b.test','a.@b.test','邮箱@b.test','a@-b.test','a@b-.test','a@b..test','a@'+('b'.repeat(64))+'.test','a'.repeat(65)+'@b.test','a'.repeat(64)+'@'+'b'.repeat(63)+'.'+'c'.repeat(63)+'.'+'d'.repeat(62)])assert.equal(validEmail(value),false,String(value));
});
test('invalid email proof, purposes and new passwords reject before any fetch',async()=>{
 const h=harness();for(const password of ['short','        ','中'.repeat(25)])await assert.rejects(h.api.registerEmail({email:'member@example.test',password},'123456'));
 await assert.rejects(h.api.sendEmailCode('member@example.test','first-password'));await assert.rejects(h.api.sendEmailCode('invalid','login'));await assert.rejects(h.api.loginEmailCode('member@example.test','12'));await assert.rejects(h.api.loginEmailPassword('member@example.test','  '));await assert.rejects(h.api.setEmailFirstPassword('new12345','bad'));assert.equal(h.calls.length,0);assert.equal(h.state().token,'A');
});
const authCalls={register:h=>h.api.registerEmail({email:'member@example.test'},'123456'),code:h=>h.api.loginEmailCode('member@example.test','123456'),password:h=>h.api.loginEmailPassword('member@example.test','123')};
for(const [name,start] of Object.entries(authCalls))for(const change of ['member','tenant'])test(`${name} late email authentication after ${change} cannot adopt tokens or load profile`,async()=>{
 const h=harness(),d=deferred();h.queue.push(d.promise);const pending=start(h);if(change==='member')h.switchMember();else h.switchTenant();d.resolve(h.reply({accessToken:'STALE'}));await assert.rejects(pending,/账号已变化/);assert.equal(h.calls.length,1);assert.equal(h.state().token,change==='member'?'B':'');assert.equal(h.cached(),null);
});
for(const stage of ['token','profile'])test(`cancelled email ${stage} response cannot leave a token or cache`,async()=>{
 const h=harness(),d=deferred();let valid=true;if(stage==='profile')h.queue.push(h.reply({accessToken:'MAIL'}));h.queue.push(d.promise);const pending=h.api.registerEmail({email:'member@example.test'},'123456',()=>valid);if(stage==='profile')await new Promise(r=>setImmediate(r));valid=false;d.resolve(h.reply(stage==='token'?{accessToken:'STALE'}:{id:19,loginEmail:'member@example.test'}));await assert.rejects(pending);assert.equal(h.state().token,'');assert.equal(h.cached(),null);
});
test('late profile after email login cannot overwrite member B or clear B on 401',async()=>{
 for(const result of [{ok:false,status:401,json:async()=>({code:401})},{ok:true,status:200,json:async()=>({code:0,data:{id:19,loginEmail:'old@example.test'}})}]){
  const h=harness(),d=deferred();h.queue.push(h.reply({accessToken:'MAIL'}),d.promise);const pending=h.api.loginEmailCode('member@example.test','123456');await new Promise(r=>setImmediate(r));h.switchMember();d.resolve(result);await assert.rejects(pending);assert.equal(h.state().token,'B');assert.equal(h.cached(),null);
 }
});
test('unavailable email service fails visibly without fallback, resend or token adoption',async()=>{
 const h=harness();h.queue.push({ok:true,status:200,json:async()=>({code:1030006021,msg:'邮箱认证暂不可用'})});await assert.rejects(h.api.sendEmailCode('member@example.test','login'),/邮箱认证暂不可用/);assert.equal(h.calls.length,1);assert.equal(h.state().token,'A');
});
test('email first-password old 401 cannot clear a newer member or read their profile',async()=>{
 const h=harness(),d=deferred();h.queue.push(d.promise);const pending=h.api.setEmailFirstPassword('new12345','123456');h.switchMember();d.resolve({ok:false,status:401,json:async()=>({code:401})});await assert.rejects(pending);assert.equal(h.state().token,'B');assert.equal(h.calls.length,1);
});
