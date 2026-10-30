import test from 'node:test';
import assert from 'node:assert/strict';
import {createAccountApi} from '../src/account-core.js';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
function harness(){
 let state={token:'OLD-A',tenant:'901',epoch:1},cached={userId:9},notifications=0;
 const calls=[],queue=[];
 const api=createAccountApi({identity:()=>({...state}),clearLoginState:()=>{state={...state,token:'',epoch:state.epoch+1};cached=null;},setTokens:()=>{throw Error('Password actions must not auto-login');},cacheProfile:()=>{throw Error('Password actions must not refresh a profile');},onPasswordChanged:()=>notifications++,localTestLogin:false,fetch:async(url,options)=>{calls.push({url,options});return queue.shift();}});
 return{api,calls,queue,reply:data=>({ok:true,status:200,json:async()=>({code:0,data})}),state:()=>({...state}),cached:()=>cached,notices:()=>notifications,switchMember:()=>state={token:'NEW-B',tenant:'901',epoch:state.epoch+1},switchTenant:()=>state={...state,tenant:'902'}};
}
test('forgot-password send uses its dedicated endpoint and gives no account-specific result',async()=>{
 const h=harness();h.queue.push(h.reply(true));await h.api.sendResetEmailCode(' Member@Example.Test ');
 assert.equal(h.calls.length,1);assert.equal(h.calls[0].url,'/app-api/member/auth/send-email-reset-code');
 assert.deepEqual(JSON.parse(h.calls[0].options.body),{email:'member@example.test'});assert.equal(h.calls[0].options.headers.Authorization,undefined);assert.equal(h.state().token,'OLD-A');assert.equal(h.notices(),0);
});
test('reset submits email proof header and untrimmed new password then clears session without login',async()=>{
 const h=harness();h.queue.push(h.reply(true));await h.api.resetEmailPassword(' Member@Example.Test ',' new12345 ','123456');
 assert.equal(h.calls.length,1);const req=h.calls[0];assert.equal(req.url,'/app-api/member/auth/email-reset-password');
 assert.deepEqual(JSON.parse(req.options.body),{email:'member@example.test',password:' new12345 '});assert.equal(req.options.headers['X-Email-Code'],'123456');assert.equal(req.options.headers.Authorization,undefined);
 assert.equal(h.state().token,'');assert.equal(h.cached(),null);assert.equal(h.notices(),1);
});
test('change sends only old and new passwords for captured current member and ends session',async()=>{
 const h=harness();h.queue.push(h.reply(true));await h.api.changeEmailPassword('short',' new12345 ');
 assert.equal(h.calls.length,1);assert.equal(h.calls[0].url,'/app-api/member/auth/email-change-password');
 assert.deepEqual(JSON.parse(h.calls[0].options.body),{oldPassword:'short',password:' new12345 '});assert.equal(h.calls[0].options.headers.Authorization,'OLD-A');assert.equal(h.calls[0].options.headers['X-Email-Code'],undefined);
 assert.equal(h.state().token,'');assert.equal(h.cached(),null);assert.equal(h.notices(),1);
});
for(const method of ['reset','change'])for(const change of ['member','tenant','cancel'])test(method+' late success after '+change+' cannot clear a new session or announce success',async()=>{
 const h=harness(),d=deferred();let valid=true;h.queue.push(d.promise);
 const pending=method==='reset'?h.api.resetEmailPassword('member@example.test','new12345','123456',()=>valid):h.api.changeEmailPassword('old12345','new12345',()=>valid);
 if(change==='member')h.switchMember();if(change==='tenant')h.switchTenant();if(change==='cancel')valid=false;
 d.resolve(h.reply(true));await assert.rejects(pending);assert.equal(h.state().token,change==='member'?'NEW-B':'OLD-A');assert.equal(h.notices(),0);assert.equal(h.calls.length,1);
});
test('wrong old password leaves current credentials and cache untouched',async()=>{
 const h=harness();h.queue.push({ok:true,status:200,json:async()=>({code:1030006030,msg:'原密码不正确'})});await assert.rejects(h.api.changeEmailPassword('wrong','new12345'),/原密码不正确/);assert.equal(h.state().token,'OLD-A');assert.equal(h.cached().userId,9);assert.equal(h.notices(),0);
});
test('stale change 401 cannot clear member B',async()=>{
 const h=harness(),d=deferred();h.queue.push(d.promise);const pending=h.api.changeEmailPassword('old12345','new12345');h.switchMember();d.resolve({ok:false,status:401,json:async()=>({code:401})});await assert.rejects(pending);assert.equal(h.state().token,'NEW-B');assert.equal(h.notices(),0);
});
test('new policy and reset proof are validated before sending; legacy old password is not lengthened',async()=>{
 const h=harness();for(const p of ['short','        ','中'.repeat(25)]){await assert.rejects(h.api.resetEmailPassword('member@example.test',p,'123456'));await assert.rejects(h.api.changeEmailPassword('old',p));}
 await assert.rejects(h.api.resetEmailPassword('member@example.test','new12345','bad'));await assert.rejects(h.api.changeEmailPassword('   ','new12345'));assert.equal(h.calls.length,0);
});
