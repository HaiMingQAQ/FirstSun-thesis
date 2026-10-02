// Deferred page-script tests, not browser or real-backend acceptance.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.join(__dirname,'../consultation.vue'),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^\s*import .*;$/gm,'');
let account='tenant:member-a',show,hide,resolveMessages,resolveSend,blockMessages=false,blockSend=false;
const reads=[],sends=[],opens=[];
const result={conversation:{id:1,kind:'SERVICE',attended:false},list:[{id:1,senderType:'STAFF',content:'test'}],hasMore:false};
const context=vm.createContext({ref:value=>({value}),onShow:fn=>show=fn,onHide:fn=>hide=fn,onUnload(){},accountKey:()=>account,toast(){},go(){},formatDate(){},setTimeout(){return 1},clearTimeout(){},
 api:{session:()=>({userId:account}),store:{name:'test'},openConsultation:()=>new Promise(r=>opens.push(r)),consultationPage:async()=>({list:[],total:0}),consultationMessages:id=>blockMessages?new Promise(r=>resolveMessages=r):Promise.resolve({...result,conversation:{...result.conversation,id}}),readConsultation:async(...args)=>reads.push(args),sendConsultation:request=>{sends.push(request);return blockSend?new Promise(r=>resolveSend=r):Promise.resolve(2)}}});
vm.runInContext(source+';globalThis.page={start,enter,refresh,send,input,failed,conversation,messages,busy,loading};',context);const tick=()=>new Promise(r=>setImmediate(r));
(async()=>{
 show();await tick();const oldOpen=context.page.start('SERVICE');account='tenant:member-b';show();await tick();const newOpen=context.page.start('PHARMACIST');opens[0](1);await oldOpen;assert.equal(context.page.busy.value,true,'old account finally must not clear new account opening lock');opens[1](2);await newOpen;assert.equal(context.page.busy.value,false);assert.equal(context.page.conversation.value.id,2);
 account='tenant:member-a';show();await tick();reads.length=0;
 show();await tick();blockMessages=true;const first=context.page.enter(1);hide();resolveMessages(result);await first;assert.equal(reads.length,0,'hidden page must not mark new response read');assert.equal(context.page.conversation.value,null);assert.equal(context.page.loading.value,false);
 blockMessages=false;show();await tick();await context.page.enter(1);assert.equal(context.page.conversation.value.id,1);
 reads.length=0;blockMessages=true;const refresh=context.page.refresh(true);hide();resolveMessages({...result,list:[{id:3,senderType:'STAFF',content:'hidden response'}]});await refresh;assert.equal(reads.length,0);assert.equal(context.page.messages.value.some(m=>m.id===3),false);
 blockMessages=false;show();await tick();blockSend=true;context.page.input.value='pending send';const sending=context.page.send();const request=context.page.failed.value;assert(request);hide();resolveSend(2);await sending;assert.equal(context.page.failed.value.clientRequestId,request.clientRequestId);assert.equal(context.page.busy.value,false);
 blockSend=false;show();await tick();await context.page.send(context.page.failed.value);assert.equal(sends[1].clientRequestId,sends[0].clientRequestId);assert.equal(context.page.failed.value,null);
 blockMessages=true;const old=context.page.refresh();account='tenant:member-b';show();await tick();resolveMessages(result);await old;assert.equal(context.page.messages.value.length,0);assert.equal(context.page.conversation.value,null);
 console.log('PASS: hidden responses not rendered/read, pending send retains retry ID, account change discards old conversation; page-script stubs only.');
})().catch(e=>{console.error(e);process.exitCode=1});
