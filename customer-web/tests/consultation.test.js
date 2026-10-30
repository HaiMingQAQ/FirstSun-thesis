import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as Vue from 'vue';
import {renderToString} from 'vue/server-renderer';
import {compile} from '@vue/compiler-dom';
import {kindLabel,storeAttribution,mergeById,prependById,newClientRequestId,sameClientRequest} from '../src/consultation-core.js';
import {sameIdentity} from '../src/guards.js';

const deferred=()=>{let resolve,reject;const promise=new Promise((ok,no)=>{resolve=ok;reject=no;});return{promise,resolve,reject};};
const source=file=>fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8');

test('message ids merge forward and backward without duplicates',()=>{
 const first=mergeById([{id:1,content:'a'}],[{id:1,content:'a'},{id:2,content:'b'}]);
 assert.deepEqual(first.map(item=>item.id),[1,2]);
 const older=prependById([{id:9,content:'older'},{id:1,content:'dup'}],first);
 assert.deepEqual(older.map(item=>item.id),[9,1,2]);
});

test('request ids stay within the server pattern and compare the whole send',()=>{
 const id=newClientRequestId(1710000000000,'abc12345');
 assert.match(id,/^[a-zA-Z0-9_-]{8,64}$/);
 const request={conversationId:4,clientRequestId:id,content:'你好'};
 assert.equal(sameClientRequest(request,{...request}),true);
 assert.equal(sameClientRequest(request,{...request,clientRequestId:id+'x'}),false);
});

test('store attribution uses the current store id and does not relabel another store',()=>{
 const store={id:407,name:'演示门店'};
 assert.match(storeAttribution({storeId:407},store),/当前门店/);
 assert.match(storeAttribution({storeId:407},store),/演示门店/);
 assert.match(storeAttribution({storeId:88},store),/^其他门店/);
 assert.doesNotMatch(storeAttribution({storeId:88},store),/当前门店/);
 assert.equal(kindLabel('PHARMACIST'),'药师文字咨询');
 assert.equal(kindLabel('SERVICE'),'门店客服');
});

function page(extraApi={}){
 let state={token:'member-a',tenant:'163',epoch:1},seq=0;
 const timeouts=new Map(),mounted=[],unmounted=[],events=[];
 const props={user:{userId:7}};
 const api={store:{id:407,name:'演示门店'},refreshStore:async()=>api.store,consultationPage:async()=>({list:[],total:0}),consultationMessages:async()=>({conversation:{id:1,kind:'SERVICE',storeId:407,attended:false},list:[],hasMore:false}),readConsultation:async()=>true,openConsultation:async()=>1,sendConsultation:async()=>2,...extraApi};
 const context={ref:value=>({value}),onMounted:fn=>mounted.push(fn),onUnmounted:fn=>unmounted.push(fn),defineProps:()=>props,defineEmits:()=> (...args)=>events.push(args),identity:()=>({...state}),sameIdentity,api,setTimeout:(fn,delay)=>{const id=++seq;timeouts.set(id,{fn,delay});return id;},clearTimeout:id=>timeouts.delete(id),...{kindLabel,storeAttribution,mergeById,prependById,newClientRequestId}};
 const script=source('ConsultationPage.vue').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'');
 vm.runInNewContext(script+'\nglobalThis.view={conversations,total,conversation,messages,input,failed,loading,busy,polling,error,hasOlder,pageNo,currentStore,loadList,start,enter,refresh,older,send,discard,back,onKeydown};',context);
 return{...context.view,api,props,events,state,mount:()=>mounted.at(-1)?.(),dispose:()=>unmounted.forEach(fn=>fn()),switchMember:()=>{state={token:'member-b',tenant:'163',epoch:2};},timers:()=>[...timeouts.values()],fire:()=>{const jobs=[...timeouts.values()];timeouts.clear();return Promise.all(jobs.map(job=>job.fn()));}};
}

test('opening, paging, dedupe, read cursor and same-id retry are executed',async()=>{
 const reads=[],sends=[],pages=[];
 const h=page({
  consultationPage:async pageNo=>{pages.push(pageNo);return pageNo===1?{list:[{id:8,kind:'SERVICE',storeId:88,attended:false,unread:1,updateTime:'2026-10-30T09:00:00'}],total:2}:{list:[{id:8,kind:'SERVICE',storeId:88,unread:0},{id:9,kind:'PHARMACIST',storeId:407,attended:true,unread:0}],total:2};},
  openConsultation:async()=>15,
  consultationMessages:async(id,params)=>{
   if(params?.before)return{conversation:{id,kind:'SERVICE',storeId:407,attended:false},list:[{id:3,senderType:'STAFF',senderName:'客服',content:'更早'} ,{id:4,senderType:'MEMBER',senderName:'顾客',content:'第一句'}],hasMore:false};
   if(params?.after)return{conversation:{id,kind:'SERVICE',storeId:407,attended:true},list:[{id:4,senderType:'MEMBER',senderName:'顾客',content:'第一句'},{id:5,senderType:'STAFF',senderName:'客服',content:'已看到'}],hasMore:false};
   return{conversation:{id,kind:'SERVICE',storeId:407,attended:false},list:[{id:4,senderType:'MEMBER',senderName:'顾客',content:'第一句'}],hasMore:true};
  },
  readConsultation:async(...args)=>reads.push(args),
  sendConsultation:async request=>{sends.push(request);if(sends.length===1)throw Error('网络中断');return 6;}
 });
 await h.mount();
 assert.equal(pages[0],1);
 assert.match(h.conversations.value[0]?storeAttribution(h.conversations.value[0],h.currentStore.value):'',/其他门店/);
 await h.loadList(true);
 assert.deepEqual(pages,[1,2]);
 assert.deepEqual(h.conversations.value.map(item=>item.id),[8,9]);
 await h.start('SERVICE');
 assert.equal(h.conversation.value.id,15);
 assert.equal(h.conversation.value.attended,false);
 assert.deepEqual(reads.at(-1),[15,4]);
 h.input.value='  请问库存  ';
 await h.send();
 assert.equal(sends.length,1);
 assert.equal(h.failed.value.clientRequestId,sends[0].clientRequestId);
 assert.equal(h.failed.value.content,'请问库存');
 await h.send(h.failed.value);
 assert.equal(sends[1].clientRequestId,sends[0].clientRequestId);
 assert.equal(sends[1].content,sends[0].content);
 assert.equal(h.failed.value,null);
 assert.deepEqual(h.messages.value.map(item=>item.id),[4,5]);
 await h.older();
 assert.deepEqual(h.messages.value.map(item=>item.id),[3,4,5]);
 let prevented=false;
 h.onKeydown({key:'Enter',shiftKey:true,preventDefault(){prevented=true;}});
 assert.equal(prevented,false);
 h.input.value='下一句';
 h.onKeydown({key:'Enter',shiftKey:false,isComposing:false,preventDefault(){prevented=true;}});
 assert.equal(prevented,true);
 assert.equal(sends.at(-1).content,'下一句');
 h.dispose();
});

test('late response after leave, member switch or store switch cannot land in the new view',async()=>{
 for(const mode of ['unmount','member','store']){
  const pending=deferred();let calls=0;
  const h=page({consultationMessages:(id,params)=>params?.after?pending.promise:Promise.resolve({conversation:{id,kind:'SERVICE',storeId:407,attended:false},list:[{id:1,senderType:'MEMBER',content:'原消息'}],hasMore:false}),readConsultation:async()=>{}});
  await h.mount();await h.enter(3);const running=h.refresh(true);calls=0;h.api.consultationMessages=()=>{calls++;return pending.promise;};
  const second=h.refresh(true);
  assert.equal(calls,0);
  if(mode==='unmount')h.dispose();
  if(mode==='member')h.switchMember();
  if(mode==='store')h.api.store.id=500;
  pending.resolve({conversation:{id:3,kind:'SERVICE',storeId:407,attended:true},list:[{id:2,senderType:'STAFF',content:'迟到回复'}],hasMore:false});
  await running;await second;
  assert.equal(h.messages.value.some(item=>item.id===2),false);
  if(mode==='store')assert.equal(h.conversation.value,null);
  if(mode!=='store')assert.equal(h.messages.value.some(item=>item.content==='迟到回复'),false);
 }
});

test('polling does not overlap and unmount clears the pending timer',async()=>{
 const h=page();await h.mount();await h.enter(1);
 assert.equal(h.timers().length,1);assert.equal(h.timers()[0].delay,5000);
 h.dispose();assert.equal(h.timers().length,0);
});

test('unknown send keeps the original request and discard drops it without another post',async()=>{
 let posts=0;const pending=deferred();
 const h=page({sendConsultation:()=>{posts++;return pending.promise;},consultationMessages:async id=>({conversation:{id,kind:'PHARMACIST',storeId:407,attended:false},list:[],hasMore:false})});
 await h.mount();await h.start('PHARMACIST');
 h.input.value='第一次';const first=h.send();await Promise.resolve();assert.equal(h.failed.value.content,'第一次');assert.equal(posts,1);await h.send();assert.equal(posts,1);
 h.dispose();pending.resolve(9);await first;assert.equal(posts,1);assert.equal(h.messages.value.length,0);assert.equal(h.failed.value,null);
 const alive=page({consultationMessages:async id=>({conversation:{id,kind:'SERVICE',storeId:407,attended:false},list:[],hasMore:false}),sendConsultation:async()=>{posts++;throw Error('发送结果未确认');}});
 await alive.mount();await alive.enter(2);alive.input.value='   ';await alive.send();assert.equal(posts,1);
 alive.input.value='留下';await alive.send();assert.equal(posts,2);assert.equal(alive.failed.value.content,'留下');const kept=alive.failed.value.clientRequestId;await alive.send(alive.failed.value);assert.equal(posts,3);assert.equal(alive.api&&kept,kept);assert.equal(alive.failed.value.clientRequestId,kept);alive.discard();assert.equal(alive.failed.value,null);
});

function appHarness(){
 let state={token:'',tenant:'163',epoch:1};const events=[];let catalogCalls=0;
 const location={_hash:'#catalog',get hash(){return this._hash;},set hash(value){this._hash=String(value).startsWith('#')?String(value):'#'+value;}};
 const context={ref:value=>({value}),computed:fn=>({get value(){return fn();}}),watch(){},onMounted(){},onUnmounted(){},nextTick:async()=>{},identity:()=>({...state}),sameIdentity,clearLoginState(){},localTestLogin:false,trade:{reset(){}},window:{addEventListener(){},removeEventListener(){},scrollTo(){}},document:{body:{dataset:{}}},location,history:{replaceState(){}},crypto:{randomUUID:()=>'unit'},URLSearchParams,setTimeout:()=>1,clearTimeout(){},api:{store:{id:407,name:'演示门店'},session:()=>null,profile:async()=>({userId:1}),loadCategories:async()=>{catalogCalls++;return[];},productPage:async()=>{catalogCalls++;return{list:[],total:0};},aiTopics:async()=>[]}};
 const script=source('App.vue').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'');
 vm.runInNewContext(script+'\nglobalThis.view={openConsult,pendingConsult,loginOpen,user,onLoggedIn,closeDialog,parseRoute,route,notConnected};',context);
 return{...context.view,location,catalogCalls:()=>catalogCalls,events};
}

test('logged-out consult entry uses login and only resumes that action after success',async()=>{
 const h=appHarness();
 h.openConsult();
 assert.equal(h.pendingConsult.value,true);
 assert.equal(h.loginOpen.value,true);
 h.closeDialog();
 assert.equal(h.pendingConsult.value,false);
 await h.onLoggedIn({userId:3,name:'会员'});
 assert.notEqual(h.location.hash,'#consult');
 h.user.value=null;
 h.openConsult();
 await h.onLoggedIn({userId:3,name:'会员'});
 assert.equal(h.location.hash,'#consult');
 h.location.hash='#consult';
 const before=h.catalogCalls();
 h.parseRoute();
 assert.equal(h.route.value,'consult');
 assert.equal(h.catalogCalls(),before);
 h.user.value={userId:3};
 h.location.hash='#catalog';
 h.openConsult();
 assert.equal(h.location.hash,'#consult');
 assert.equal(h.loginOpen.value,false);
});

function component(template,state){return{setup:()=>state,components:{Icon:{props:['name'],render:()=>null}},render:new Function('Vue',compile(template,{mode:'function',prefixIdentifiers:true}).code)(Vue)};}
async function renderConsult(state){
 const full=source('ConsultationPage.vue');
 const template=full.slice(full.indexOf('<template>')+10,full.lastIndexOf('</template>'));
 return renderToString(Vue.createSSRApp(component(template,state)));
}

test('consultation text is escaped and empty, error and unattended states are explicit',async()=>{
 assert.equal(source('ConsultationPage.vue').includes('v-html'),false);
 const guest=await renderConsult({user:null,error:'',emit(){}});
 assert.match(guest,/登录后联系门店/);
 const failed=await renderConsult({user:{userId:1},error:'会话列表加载失败',failed:null,conversation:null,loading:false,busy:false,conversations:[],total:0,currentStore:{id:407,name:'演示门店'},storeAttribution,emit(){}});
 assert.match(failed,/会话列表加载失败/);
 const thread=await renderConsult({user:{userId:1},error:'',failed:{content:'未确认的原文',clientRequestId:'web_123_abcd'},conversation:{id:4,kind:'SERVICE',storeId:88,attended:false},loading:false,busy:false,polling:false,messages:[{id:1,senderType:'STAFF',senderName:'客服',content:'<img src=x onerror=alert(1)>'}],hasOlder:true,input:'',currentStore:{id:407,name:'演示门店'},storeAttribution,kindLabel,formatDate:()=>'2026-10-30 09:00'});
 assert.match(thread,/暂无人员接待/);
 assert.match(thread,/其他门店/);
 assert.match(thread,/未确认的原文/);
 assert.match(thread,/&lt;img src=x onerror=alert\(1\)&gt;/);
 assert.equal(thread.includes('<img'),false);
});
