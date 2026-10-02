// Execute checkout script with deferred real-adapter contract stubs; not HTTP/UI integration.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.join(__dirname,'../checkout.vue'),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^\s*import .*;$/gm,'');
let account='tenant:member-a',hook,redirects=0;
const pending=[];
const context=vm.createContext({ref:value=>({value}),computed:fn=>({get value(){return fn()}}),onShow:fn=>hook=fn,
  accountKey:()=>account,api:{session:()=>({userId:account}),mockPaymentAvailable:async()=>false,checkout:()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))},
  go(){},toast(){},uni:{redirectTo(){redirects++}},formatDate(){},money(){}});
vm.runInContext(source+';globalThis.state={data,loading,error,busy,remark};',context);
const tick=()=>new Promise(r=>setImmediate(r));
(async()=>{
 hook();await tick();assert.equal(pending.length,1);
 account='tenant:member-b';hook();await tick();assert.equal(pending.length,2);
 pending[0].resolve({prescription:{materialIds:[123]},items:[]});await tick();assert.equal(context.state.data.value,null);assert.equal(context.state.loading.value,true);
 pending[1].resolve({items:[],owner:'member-b'});await tick();assert.equal(context.state.data.value.owner,'member-b');assert.equal(context.state.loading.value,false);
 account='tenant:member-c';hook();await tick();account='tenant:member-d';hook();await tick();
 pending[2].reject(new Error('old private error'));await tick();assert.equal(context.state.error.value,'');assert.equal(context.state.loading.value,true);
 pending[3].resolve({items:[],owner:'member-d'});await tick();assert.equal(context.state.data.value.owner,'member-d');assert.equal(redirects,0);
 console.log('PASS: checkout page discards previous-account success/error and preserves current loading state; script stubs only.');
})().catch(e=>{console.error(e);process.exitCode=1});
