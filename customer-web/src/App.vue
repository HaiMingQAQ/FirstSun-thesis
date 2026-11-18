<script setup>
import {computed,onMounted,onUnmounted,ref,watch,nextTick} from 'vue';
import api from '@/sheep/api/pharmacy/server';
import {money,normalizeDrug,formatDate} from '@/sheep/api/pharmacy/common';
import {createStreamController} from '@/sheep/api/pharmacy/sse';
import {identity,clearLoginState} from './session.js';
import {sameIdentity} from './guards.js';
import {localTestLogin} from './config.js';
import AiLauncher from './AiLauncher.vue'
import ProductCard from './ProductCard.vue';
import HomeHero from './HomeHero.vue';
import Recommendations from './Recommendations.vue';
import LoginGate from '#test-login';
import RegisterGate from '#test-register';
import EmailLogin from './EmailLogin.vue';
import EmailRegister from './EmailRegister.vue';
import EmailForgotPassword from './EmailForgotPassword.vue';
import MemberAvatar from './MemberAvatar.vue';
import AccountPage from './AccountPage.vue';
import account from './account.js';
import TradePages from './TradePages.vue';
import ConsultationPage from './ConsultationPage.vue';
import NotificationsPage from './NotificationsPage.vue';
import PrescriptionPages from './PrescriptionPages.vue';
import SearchSuggestions from './SearchSuggestions.vue';
import trade from './trade.js';
const route=ref('home'),routeOrderId=ref(''),routeQuery=ref(''),adding=ref(false),query=ref(''),category=ref('all'),page=ref(1),items=ref([]),categories=ref([]),count=ref(0),catalogLoading=ref(false),catalogError=ref(''),detail=ref(null),detailLoading=ref(false),detailError=ref(''),infoTab=ref('info'),storeName=ref('正在读取门店…');
const authScreen=ref('login'),authChannel=ref('email'),passwordNotice=ref('');
function changeAuthChannel(channel){if(channel==='mobile'&&!localTestLogin)return;authScreen.value='login';authChannel.value=channel;}
function openLocalLogin(){if(!localTestLogin||authBusy.value||user.value)return;changeAuthChannel('mobile');loginOpen.value=true;}
const suggestionBox=ref(null);
const cartCount=ref(0),cartQtyById=ref({});
function cartQtyFor(p){return cartQtyById.value[p?.id]?.qty||0;}
const catalogPageSize=9;
const catalogKeyword=ref(''),catalogStore=ref(null),catalogSample=ref([]);
const quickTerms=computed(()=>[...new Set(catalogSample.value
 .filter(product=>product.stock>0)
 .map(product=>product.genericName||product.name)
 .filter(Boolean))].slice(0,4));
const catalogTabs=computed(()=>{
 const sampleCategories=new Set(catalogSample.value.map(product=>product.category));
 const groups=categories.value.filter(entry=>entry.id!=='all');
 const populated=groups.filter(entry=>sampleCategories.has(entry.id));
 return [{id:'all',name:'全部药品'},...(populated.length?populated:groups).slice(0,3)];
});
const catalogOtherCategories=computed(()=>categories.value.filter(entry=>!catalogTabs.value.some(tab=>tab.id===entry.id)));
function quickSearch(term){query.value=term;category.value='all';search();}
function resetCatalog(){query.value='';category.value='all';search();}
function selectOtherCategory(event){
 const entry=catalogOtherCategories.value.find(group=>String(group.id)===event.target.value);
 if(entry)pickCategory(entry.id);
}
function catalogStoreChanged(){
 if(catalogStore.value===api.store.id)return;
 catalogSample.value=[];
 if(route.value==='catalog'&&catalogStore.value!==null)loadCatalog();
 catalogStore.value=api.store.id;
}
function setCartSummary(rows){const map={};let total=0;for(const r of rows){map[r.drugId]={rowId:r.id,qty:r.qty};total+=Number(r.qty)||0;}cartQtyById.value=map;cartCount.value=total;}
function syncCartRows(storeId,rows){if(disposed||!user.value||storeId!==api.store.id)return;cartReadGeneration++;setCartSummary(rows);}
async function refreshCart(){const g=++cartReadGeneration,owner=identity(),storeId=api.store.id;const current=()=>!disposed&&g===cartReadGeneration&&!!user.value&&sameIdentity(owner,identity())&&storeId===api.store.id;if(!user.value||!storeId||typeof trade.cart!=='function'){setCartSummary([]);return;}try{const rows=await trade.cart(storeId);if(current())setCartSummary(rows||[]);}catch{if(current())setCartSummary([]);}}
async function decreaseCart(p){const row=cartQtyById.value[p?.id];if(!row||!user.value||!api.store.id||adding.value||disposed)return;const owner=identity(),storeId=api.store.id,write=++cartWriteGeneration;const current=()=>!disposed&&write===cartWriteGeneration&&!!user.value&&sameIdentity(owner,identity())&&storeId===api.store.id;adding.value=true;cartReadGeneration++;try{if(row.qty<=1)await trade.remove(storeId,row.rowId);else await trade.quantity(storeId,row.rowId,row.qty-1);if(current())await refreshCart();}catch(e){if(current())dialog.value=e.message;}finally{if(!disposed&&write===cartWriteGeneration)adding.value=false;}}
async function loadMore(){if(catalogLoading.value||query.value.trim()!==catalogKeyword.value||page.value*catalogPageSize>=count.value)return;await loadCatalog(true);}
const aiQuick=ref(false), quickQuestion=ref(null), quickScroll=ref(null);
const user=ref(null),authReady=ref(false),authBusy=ref(false),loginOpen=ref(false),dialog=ref(''),toastText=ref(''),navOpen=ref(false);
// 窄屏（≤1200px）导航收进汉堡菜单：点图标展开，点链接/点外部/按 Esc/放大窗口都会收起
// 单测与服务端渲染环境没有 document/matchMedia，这里先探测再用，避免非浏览器环境报错
const navDoc=typeof document==='undefined'?null:document;
function toggleNav(){navOpen.value=!navOpen.value;}
function navOutside(event){const hit=event.target instanceof Element&&event.target.closest('#header');if(navOpen.value&&!hit)navOpen.value=false;}
function navEscape(event){
 if(event.key!=='Escape')return;
 navOpen.value=false;
 if(route.value==='ai')historyOpen.value=false;
}
function navResized(event){if(event.matches)navOpen.value=false;}
const topics=ref([]),moreTopics=ref(false),historyLoading=ref(false),historyError=ref(''),historyOpen=ref(false),topicId=ref(null),topicStore=ref(null),moreTurns=ref(false),messages=ref([]),input=ref(''),busy=ref(false),chatScroll=ref(null),stockWarning=ref(false),pendingDeleteId=ref(null),clearPending=ref(false),deleting=ref(false);
const historyTrigger=ref(null),historyClose=ref(null);
watch(historyOpen,async(open)=>{
  await nextTick();
  if(route.value==='ai') (open?historyClose:historyTrigger).value?.focus();
});
function answerDisplay(message){
  const answer=message.answer.replace(/\*\*([^*]+)\*\*/g,'$1');
  // The server appends one final citation line to both streamed and saved answers.
  const citation=!message.loading&&/\r?\n依据说明书[：:]\s*([^\r\n]+)\s*$/.exec(answer);
  return citation?{body:answer.slice(0,citation.index).trimEnd(),evidence:citation[1].trim()}:{body:answer,evidence:''};
}
let disposed=false,generation=0,controller,active,typingTimer,toastTimer,publicGeneration=0,cartReadGeneration=0,cartWriteGeneration=0,wideQuery;
const isTrade=computed(()=>['cart','checkout','orders','order-detail'].includes(route.value));
const isPrescription=computed(()=>['prescriptions','rx-submit','rx-detail'].includes(route.value));
const consultDrugId=computed(()=>new URLSearchParams(routeQuery.value).get('drugId')||'');
const authName=computed(()=>user.value?.name||'会员');
const noticeUnread=ref(0);
let noticeRequest=0;
async function refreshNoticeCount(){
  const request=++noticeRequest, owner=identity();
  if(!user.value){noticeUnread.value=0;return;}
  try{
    const count=await api.unreadNotifications();
    if(!disposed&&request===noticeRequest&&sameIdentity(owner,identity())){
      noticeUnread.value=Number.isSafeInteger(count)&&count>0?count:0;
    }
  }catch{
    if(!disposed&&request===noticeRequest&&sameIdentity(owner,identity()))noticeUnread.value=0;
  }
}
watch([()=>user.value?.userId,route],refreshNoticeCount);
const currentOwner=()=>JSON.stringify(identity());
const newId=()=>`consult_${Date.now()}_${crypto.randomUUID().slice(0,8)}`;
function notify(t){toastText.value=t;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toastText.value='',3500);}
function go(hash){if(location.hash==='#'+hash)parseRoute();else location.hash=hash;}
function notConnected(name){dialog.value=`${name}尚未接入。登录、找药、购物车、普通订单、门店文字咨询和处方申请可以使用。真实支付、处方结算和药师审核不在这里办理。`;}
function openConsult(){go('consult');}
function openProductConsult(){if(detail.value)go('consult?drugId='+detail.value.id);}
function stop(){generation++;historyLoading.value=false;controller?.abort();clearTimeout(typingTimer);typingTimer=null;if(active?.loading){active.loading=false;active.status='STOPPED';active.error='已停止接收回复；请求可能仍在处理，稍后可重载话题查看结果。';}busy.value=false;}
function resetPrivate(){closeSuggestions();stop();trade.reset();cartReadGeneration++;cartWriteGeneration++;cartCount.value=0;cartQtyById.value={};adding.value=false;if(location.hash.startsWith('#ai?'))history.replaceState(null,'','#ai');messages.value=[];topics.value=[];topicId.value=null;topicStore.value=null;moreTurns.value=false;moreTopics.value=false;historyError.value='';historyLoading.value=false;input.value='';user.value=null;noticeRequest++;noticeUnread.value=0;historyOpen.value=false;pendingDeleteId.value=null;clearPending.value=false;deleting.value=false;dialog.value='';}
function authCleared(){resetPrivate();authReady.value=true;}
function passwordChanged(){if(disposed)return;authScreen.value='login';authChannel.value='email';passwordNotice.value='密码已更新，请重新登录';loginOpen.value=true;}
async function logout(){if(authBusy.value)return;authBusy.value=true;resetPrivate();try{await account.logout();}finally{authBusy.value=false;}notify('已退出登录');}
// Credentials are user supplied and never stored by the application.
async function onLoggedIn(profile){if(disposed)return;user.value=profile;loginOpen.value=false;authScreen.value='login';authChannel.value='email';passwordNotice.value='';notify('登录成功');if(route.value==='ai'||aiQuick.value)await loadTopics();if(route.value==='catalog')loadCatalog();if(user.value)refreshCart();}
async function loadCatalog(append=false){
 append=append===true;
 const request=++publicGeneration;
 const input=query.value,keyword=input.trim(),selectedCategory=category.value;
 const requestedPage=append?page.value+1:1;
 const current=()=>!disposed&&request===publicGeneration&&input===query.value&&selectedCategory===category.value;
 if(!append){page.value=1;items.value=[];count.value=0;catalogKeyword.value=keyword;}
 catalogLoading.value=true;
 catalogError.value='';
 stockWarning.value=false;
 try{
  const [list,result]=await Promise.all([
   api.loadCategories(),
   api.productPage({keyword,category:selectedCategory,pageNo:requestedPage,pageSize:catalogPageSize}),
  ]);
  if(!current())return;
  categories.value=list;
  items.value=append?[...items.value,...result.list]:result.list;
  page.value=requestedPage;
  count.value=result.total;
  storeName.value=api.store.name;
  catalogStore.value=api.store.id;
  if(!keyword&&selectedCategory==='all'&&!append)catalogSample.value=result.list;
 }catch(e){
  if(current())catalogError.value=e.message;
 }finally{
  if(!disposed&&request===publicGeneration)catalogLoading.value=false;
 }
}
async function loadDetail(id){const g=++publicGeneration;detail.value=null;detailLoading.value=true;detailError.value='';infoTab.value='info';stockWarning.value=false;try{if(!/^\d+$/.test(id||''))throw Error('商品链接无效');const p=await api.product(id);if(g!==publicGeneration)return;detail.value=p;storeName.value=api.store.name;}catch(e){if(g===publicGeneration)detailError.value=e.message;}finally{if(g===publicGeneration)detailLoading.value=false;}}
function closeSuggestions(){suggestionBox.value?.close();}
function search(){closeSuggestions();page.value=1;loadCatalog();}
function selectSearchProduct(id){go('detail?id='+id);}
function pickCategory(id){category.value=id;page.value=1;loadCatalog();}
function aiProducts(rows=[]){return rows.map(p=>normalizeDrug({id:p.id,tradeName:p.name,genericName:p.genericName,specification:p.specification,manufacturer:p.manufacturer,isRx:0,drugType:p.drugType,memberPrice:p.price},{stock:p.availableQty,image:p.imageUrl,approval:p.approvalNo}));}
function restored(t){const r=t.response,status=r?.status||'PENDING';return{id:t.clientMessageId,recordId:t.id,content:t.content,context:[],answer:r?.answer||'',products:aiProducts(r?.products),status,source:r?.source,queriedAt:formatDate(r?.queriedAt),loading:false,error:status==='FAILED'?'本次回复未完成，可重新提问。':status==='PENDING'?'请求可能仍在处理，请稍后重载话题。':''};}
async function loadTopics(append=false){append=append===true;if(!user.value)return;const owner=currentOwner(),g=generation;historyLoading.value=true;historyError.value='';try{const rows=await api.aiTopics(append?topics.value.at(-1)?.id:undefined);if(owner!==currentOwner()||g!==generation)return;topics.value=append?[...topics.value,...rows]:rows;moreTopics.value=rows.length===30;}catch(e){if(owner===currentOwner()&&g===generation)historyError.value=e.message;}finally{if(owner===currentOwner()&&g===generation)historyLoading.value=false;}}
async function openTopic(id,earlier=false){if(!user.value)return;stop();const owner=currentOwner(),g=generation;historyLoading.value=true;historyError.value='';if(!earlier){messages.value=[];topicId.value=null;topicStore.value=null;}try{const d=await api.aiTopic(id,earlier?messages.value[0]?.recordId:undefined);if(owner!==currentOwner()||g!==generation)return;const rows=d.turns.map(restored);messages.value=earlier?[...rows,...messages.value]:rows;topicId.value=d.topic.id;topicStore.value=d.topic.storeId;moreTurns.value=d.hasMore;historyOpen.value=false;history.replaceState(null,'',`#ai?topic=${d.topic.id}`);}catch(e){if(owner===currentOwner()&&g===generation)historyError.value=e.message;}finally{if(owner===currentOwner()&&g===generation)historyLoading.value=false;}}
async function newTopic(){if(!user.value){loginOpen.value=true;return;}if(historyLoading.value)return;stop();messages.value=[];topicId.value=null;topicStore.value=null;input.value='';moreTurns.value=false;history.replaceState(null,'','#ai');const owner=currentOwner(),g=generation;historyLoading.value=true;try{const t=await api.createAiTopic();if(owner!==currentOwner()||g!==generation)return;topicId.value=t.id;topicStore.value=t.storeId;topics.value=[t,...topics.value];history.replaceState(null,'',`#ai?topic=${t.id}`);historyOpen.value=false;}catch(e){if(owner===currentOwner()&&g===generation)historyError.value=e.message;}finally{if(owner===currentOwner()&&g===generation)historyLoading.value=false;}}
// 删除对话：行内二次确认，避免原生 confirm 打断演示；只删自己名下的话题（后端再校验一次归属）
function askDeleteTopic(id){if(historyLoading.value||deleting.value)return;clearPending.value=false;pendingDeleteId.value=pendingDeleteId.value===id?null:id;}
function askClearTopics(){if(historyLoading.value||deleting.value||!topics.value.length)return;pendingDeleteId.value=null;clearPending.value=true;}
async function deleteTopic(t){if(!t||deleting.value||historyLoading.value||!user.value)return;const owner=currentOwner(),g=generation;deleting.value=true;historyError.value='';try{await api.deleteAiTopic(t.id);if(owner!==currentOwner()||g!==generation)return;topics.value=topics.value.filter(item=>item.id!==t.id);pendingDeleteId.value=null;if(topicId.value===t.id){stop();messages.value=[];topicId.value=null;topicStore.value=null;moreTurns.value=false;history.replaceState(null,'','#ai');}notify('对话已删除');}catch(e){if(owner===currentOwner()&&g===generation)historyError.value=e.message||'删除失败，请重试';}finally{if(owner===currentOwner()&&g===generation)deleting.value=false;}}
async function clearTopics(){if(deleting.value||historyLoading.value||!user.value)return;const owner=currentOwner(),g=generation;deleting.value=true;historyError.value='';try{await api.clearAiTopics();if(owner!==currentOwner()||g!==generation)return;stop();topics.value=[];messages.value=[];topicId.value=null;topicStore.value=null;moreTurns.value=false;moreTopics.value=false;clearPending.value=false;pendingDeleteId.value=null;history.replaceState(null,'','#ai');notify('对话已清空');}catch(e){if(owner===currentOwner()&&g===generation)historyError.value=e.message||'清空失败，请重试';}finally{if(owner===currentOwner()&&g===generation)deleting.value=false;}}
async function queryAI(message){if(busy.value||historyLoading.value||!user.value)return;const owner=currentOwner(),g=generation;const current=()=>g===generation&&owner===currentOwner()&&!!user.value;controller=createStreamController();const signal=controller.signal;active=message;busy.value=true;message.loading=true;message.error='';message.answer='';message.products=[];const queue=[];const type=()=>{if(!current()||signal.aborted)return;message.answer+=queue.splice(0,4).join('');typingTimer=queue.length?setTimeout(type,20):null;};try{if(!topicId.value){const t=await api.createAiTopic();if(!current()||signal.aborted)return;topicId.value=t.id;topicStore.value=t.storeId;if(route.value==='ai')history.replaceState(null,'',`#ai?topic=${t.id}`);}const result=await api.consultStream({clientMessageId:message.id,content:message.content,context:message.context,topicId:topicId.value,storeId:topicStore.value},(event,data)=>{if(!current()||signal.aborted)return;if(event==='delta'){queue.push(...Array.from(data.text||''));if(!typingTimer)type();}},signal);if(!current()||signal.aborted)return;if(result.status==='FAILED'){clearTimeout(typingTimer);typingTimer=null;message.answer+=queue.splice(0).join('');}else while(queue.length&&current()&&!signal.aborted)await new Promise(r=>setTimeout(r,20));if(!current()||signal.aborted)return;message.status=result.status;if(result.status!=='FAILED'||!message.answer||result.answer?.startsWith(message.answer))message.answer=result.answer||message.answer;message.error=result.status==='FAILED'?'本次回复未完成，请重试。':'';message.products=aiProducts(result.products);message.source=result.source;message.queriedAt=formatDate(result.queriedAt);}catch(e){if(!current())return;message.answer+=queue.splice(0).join('');message.status='NETWORK_ERROR';message.error=e.message||'回复中断，请重试';}finally{if(current()){clearTimeout(typingTimer);typingTimer=null;message.loading=false;busy.value=false;loadTopics();}}}
function send(text=input.value){if(busy.value||historyLoading.value||!text.trim())return;if(!user.value){loginOpen.value=true;return;}const context=messages.value.filter(m=>m.status==='SUCCESS').flatMap(m=>[{role:'user',content:m.content},{role:'assistant',content:m.answer.slice(0,1000)}]).slice(-6);messages.value.push({id:newId(),content:text.trim().slice(0,500),context,answer:'',products:[],loading:true});input.value='';queryAI(messages.value.at(-1));}
function retry(m){if(m.status==='FAILED')m.id=newId();queryAI(m);}
const canShowAiLauncher=computed(()=>!['ai','cart','checkout','rx-submit','rx-detail','order-detail','consult'].includes(route.value)&&!loginOpen.value&&!dialog.value)
function openQuickAi(withProduct=false){
  if(withProduct&&detail.value&&!busy.value&&!input.value.trim()){
    const product=detail.value;
    input.value=`查询${product.genericName||product.name}的价格和库存`;
  }
  aiQuick.value=true;
  nextTick(()=>quickQuestion.value?.focus());
}
function closeQuickAi(){
  aiQuick.value=false;
  nextTick(()=>document.querySelector('.assistant-ball')?.focus());
}
function expandQuickAi(){
  aiQuick.value=false;
  go(topicId.value?`ai?topic=${topicId.value}`:'ai');
}
function askProduct(){openQuickAi(true);}
watch(route,()=>{if(!canShowAiLauncher.value&&!loginOpen.value)aiQuick.value=false;});
watch(()=>messages.value.at(-1)?.answer,()=>nextTick(()=>{
  if(quickScroll.value)quickScroll.value.scrollTop=quickScroll.value.scrollHeight;
}));
async function openLinkedTopic(id){
 const owner=currentOwner();
 const opening=openTopic(id),g=generation;
 await opening;
 if(route.value==='ai'&&g===generation&&owner===currentOwner()&&String(topicId.value)===id)loadTopics();
}
function parseRoute(){closeSuggestions();navOpen.value=false;const [key,qs]=location.hash.slice(1).split('?'),params=new URLSearchParams(qs);const next=['home','ai','catalog','recommend','detail','cart','checkout','orders','order-detail','account','consult','notifications','prescriptions','rx-submit','rx-detail'].includes(key)?key:'home';if(route.value==='ai'&&next!=='ai')stop();route.value=next;routeOrderId.value=params.get('id')||'';routeQuery.value=qs||'';document.body.dataset.page=next;window.scrollTo(0,0);if(next==='catalog')loadCatalog();else if(next==='detail')loadDetail(params.get('id'));else if(next==='ai'&&user.value){if(params.get('topic')&&String(topicId.value)!==params.get('topic'))openLinkedTopic(params.get('topic'));else loadTopics();}}
async function addToCart(p){if(!user.value){loginOpen.value=true;return;}if(adding.value||disposed)return;const owner=identity(),storeId=api.store.id,write=++cartWriteGeneration;const current=()=>!disposed&&write===cartWriteGeneration&&!!user.value&&sameIdentity(owner,identity())&&storeId===api.store.id;adding.value=true;cartReadGeneration++;try{if(!storeId)throw Error('请先读取门店商品');await trade.add(storeId,p,1);if(current()){notify('已加入当前门店购物车');await refreshCart();}}catch(e){if(current())dialog.value=e.message;}finally{if(!disposed&&write===cartWriteGeneration)adding.value=false;}}
let backdropStarted=false,backdropEnded=false,backdropPointer;
function closeDialog(){backdropStarted=false;backdropEnded=false;loginOpen.value=false;dialog.value='';authScreen.value='login';authChannel.value='email';passwordNotice.value='';}
function backdropDown(event){backdropEnded=false;backdropStarted=event.target===event.currentTarget&&(event.button==null||event.button===0)&&event.isPrimary!==false;backdropPointer=event.pointerId;}
function backdropUp(event){backdropEnded=event.target===event.currentTarget&&(event.pointerId==null||backdropPointer==null||event.pointerId===backdropPointer);}
function backdropCancel(){backdropStarted=false;backdropEnded=false;}
function backdropClick(event){const close=backdropStarted&&backdropEnded&&event.target===event.currentTarget&&(event.pointerId==null||backdropPointer==null||event.pointerId===backdropPointer);backdropStarted=false;if(close)closeDialog();}
function stockFailed(){stockWarning.value=true;}
watch(()=>messages.value.map(m=>m.answer+m.error).join(''),async()=>{const el=chatScroll.value;if(!el||(messages.value.length===1&&messages.value[0].recordId&&el.scrollTop===0))return;const near=el.scrollHeight-el.scrollTop-el.clientHeight<160;await nextTick();if(near)el.scrollTop=el.scrollHeight;});
onMounted(async()=>{
 window.addEventListener('hashchange',parseRoute);window.addEventListener('firstsun-auth-clear',authCleared);window.addEventListener('firstsun-password-changed',passwordChanged);window.addEventListener('firstsun-stock-error',stockFailed);window.addEventListener('firstsun-store-change',catalogStoreChanged);navDoc?.addEventListener('pointerdown',navOutside);navDoc?.addEventListener('keydown',navEscape);wideQuery=typeof window!=='undefined'&&window.matchMedia?window.matchMedia('(min-width:1201px)'):null;wideQuery?.addEventListener?.('change',navResized);parseRoute();
 const owner=identity(),current=()=>!disposed&&sameIdentity(owner,identity());
 try{if(api.session()){const profile=await api.profile();if(current())user.value=profile;}}
 catch{if(current())clearLoginState();}
 if(disposed)return;
 authReady.value=true;if(current()&&route.value==='ai'&&user.value)parseRoute();if(current()&&user.value)refreshCart();
});
onUnmounted(()=>{disposed=true;stop();clearTimeout(toastTimer);window.removeEventListener('hashchange',parseRoute);window.removeEventListener('firstsun-auth-clear',authCleared);window.removeEventListener('firstsun-password-changed',passwordChanged);window.removeEventListener('firstsun-stock-error',stockFailed);window.removeEventListener('firstsun-store-change',catalogStoreChanged);navDoc?.removeEventListener('pointerdown',navOutside);navDoc?.removeEventListener('keydown',navEscape);wideQuery?.removeEventListener?.('change',navResized);});
</script>
<template>
<header id="header" :class="{'home-header':route==='home'}"><button type="button" class="nav-toggle" :class="{open:navOpen}" :aria-expanded="navOpen?'true':'false'" aria-controls="global-nav" :aria-label="navOpen?'收起导航菜单':'展开导航菜单'" @click="toggleNav"><Icon :name="navOpen?'x':'menu'"/></button><a class="brand" href="#home" aria-label="FirstSun 网上药房首页"><span class="wordmark">FirstSun<span class="brand-dot">.</span></span><span class="brand-sub">网上药房</span></a><nav id="global-nav" class="nav" :class="{open:navOpen}" aria-label="全局导航" @click="navOpen=false"><a href="#home" :class="{active:route==='home'}" :aria-current="route==='home'?'page':null">首页</a><a href="#catalog" :class="{active:route==='catalog'||route==='detail'}">找药购药</a><a href="#recommend" :class="{active:route==='recommend'}" :aria-current="route==='recommend'?'page':null" aria-label="商品推荐">推荐</a><a href="#ai" class="ai-nav" :class="{active:route==='ai'}"><Icon name="sparkles"/>AI 助手<span class="nav-ai-label">AI</span></a><a href="#prescriptions" :class="{active:isPrescription}">处方申请记录</a><a href="#orders" :class="{active:route==='orders'||route==='order-detail'}">我的订单</a><a href="#consult" class="consult-nav" :class="{active:route==='consult'}" :aria-current="route==='consult'?'page':null"><Icon name="headset"/>咨询与回复</a></nav><div class="header-tools"><button type="button" class="prototype local-auth-entry" v-if="localTestLogin" :disabled="authBusy||!!user" aria-label="打开本机手机号验收" @click="openLocalLogin">独立本机验收</button><a href="#notifications" class="tool notice-tool" :aria-label="user&&noticeUnread?'消息通知，'+noticeUnread+' 条未读':'消息通知'" :aria-current="route==='notifications'?'page':null" title="消息通知"><Icon name="bell"/><span v-if="user&&noticeUnread" class="notice-badge" aria-hidden="true">{{noticeUnread>99?'99+':noticeUnread}}</span></a><button class="tool cart-tool" aria-label="购物车" @click="go('cart')"><Icon name="shopping-bag"/><span class="label">购物车</span><span v-if="cartCount>0" class="cart-count">{{cartCount}}</span></button><button v-if="!user" class="tool auth-entry" aria-label="登录" :disabled="authBusy" @click="loginOpen=true"><Icon name="circle-user-round"/><span class="auth-name">登录</span></button><button v-else class="tool auth-entry" @click="go('account')" aria-label="个人中心"><MemberAvatar class="header-avatar" :src="user.avatar" :size="24" alt=""/><span class="auth-name">{{authName}}</span></button></div></header>
<AccountPage v-if="route==='account'" :key="user?.userId||'guest'" :user="user" @profile="user=$event" @logout="logout" @login="loginOpen=true"/><TradePages v-else-if="isTrade" :key="route+':'+routeOrderId+':'+(user?.userId||'guest')" :route="route" :order-id="routeOrderId" :user="user" @login="loginOpen=true" @notice="notify" @cart-read="syncCartRows" @cart-changed="refreshCart"/><ConsultationPage v-else-if="route==='consult'" :key="'consult:'+routeQuery+':'+(user?.userId||'guest')" :drug-id="consultDrugId" :user="user" @login="loginOpen=true"/><NotificationsPage v-else-if="route==='notifications'" :key="'notifications:'+(user?.userId||'guest')" :user="user" @login="loginOpen=true" @read="refreshNoticeCount"/><PrescriptionPages v-else-if="isPrescription" :key="route+':'+routeQuery+':'+(user?.userId||'guest')" :route="route" :query="routeQuery" :user="user" @login="loginOpen=true" @notice="notify"/><main v-else-if="route==='home'" class="shop-page shop-home">
 <HomeHero/>
 <Recommendations featured/>
 <section id="home-guide" class="home-guide" tabindex="-1" aria-labelledby="home-guide-title">
 <span class="home-guide-icon"><Icon name="file-check-2"/></span>
 <div><h2 id="home-guide-title">购药前，多一份了解。</h2><p>查看商品与规格，核对用药信息，再按门店流程购买。<br>处方药可提交处方材料并查看审核进度；电脑端暂不支持处方结算。</p></div>
 <button type="button" @click="openConsult">咨询与回复 <Icon name="arrow-right"/></button>
</section>
<footer class="shop-footer"><span class="wordmark">FirstSun.</span><p>让健康，成为日常。</p></footer>
</main>
<Recommendations v-else-if="route==='recommend'"/>
<main v-else-if="route==='catalog'" class="shop-page catalog-page">
 <section class="catalog-search" aria-labelledby="catalog-title">
  <div class="catalog-title">
   <span class="live-store"><Icon name="store"/>{{storeName}}</span>
   <h1 id="catalog-title">找药购药</h1>
  </div>
  <SearchSuggestions ref="suggestionBox" v-model="query" :category="category" :store-id="api.store.id" :loading="catalogLoading" @search="search" @select="selectSearchProduct"/>
  <div v-if="quickTerms.length" class="catalog-shortcuts">
   <span>本店可搜</span>
   <div class="catalog-shortcut-terms">
    <button v-for="term in quickTerms" :key="term" type="button" @click="quickSearch(term)">{{term}}</button>
   </div>
  </div>
 </section>
 <div class="catalog-work">
  <section class="shop-products catalog-results" aria-label="商品结果">
   <nav class="catalog-filters" aria-label="商品分类">
    <div class="catalog-tabs">
     <button v-for="entry in catalogTabs" :key="entry.id" type="button" :aria-pressed="category===entry.id" @click="pickCategory(entry.id)">{{entry.name}}</button>
    </div>
    <select v-if="catalogOtherCategories.length" aria-label="更多分类" :value="catalogOtherCategories.some(entry=>entry.id===category)?category:''" @change="selectOtherCategory">
     <option value="" disabled>更多分类</option>
     <option v-for="entry in catalogOtherCategories" :key="entry.id" :value="entry.id">{{entry.name}}</option>
    </select>
   </nav>
   <div class="catalog-result-heading">
    <h2>{{catalogKeyword?'搜索结果':categories.find(entry=>entry.id===category)?.name||'全部药品'}}</h2>
    <span v-if="!catalogError||items.length" role="status">{{catalogLoading&&!items.length?'正在查找…':`共 ${count} 件 · 当前门店`}}</span>
   </div>
   <div v-if="stockWarning" class="error-panel" role="alert">库存暂不可用，请稍后重试或联系门店。</div>
   <div v-if="catalogError&&!items.length" class="empty-state" role="alert">
    <Icon name="wifi-off"/>
    <h3>商品暂时加载失败</h3>
    <p>{{catalogError}}</p>
    <button class="btn primary" @click="loadCatalog">重新加载</button>
   </div>
   <div v-else-if="catalogLoading&&!items.length" class="products" aria-busy="true">
    <div v-for="n in 6" :key="n" class="skeleton"></div>
   </div>
   <template v-else>
    <div v-if="catalogError" class="error-panel" role="alert">{{catalogError}}</div>
    <div v-if="items.length" class="products">
     <ProductCard v-for="p in items" :key="p.id" :product="p" :stock-unknown="stockWarning" :cart-qty="cartQtyFor(p)" @unavailable="notConnected" :adding="adding" @add="addToCart" @decrease="decreaseCart"/>
    </div>
    <div v-else class="catalog-empty">
     <Icon name="search-x"/>
     <div>
      <h3>{{catalogKeyword?'本店未找到匹配药品':category==='all'?'本店暂无商品':'当前分类暂无商品'}}</h3>
      <p>{{catalogKeyword?`“${catalogKeyword}”未匹配到商品。可以换用通用名或商品名再搜。`:'试试其他分类，或查看本店全部药品。'}}</p>
      <div class="catalog-empty-actions">
       <button v-if="category!=='all'" type="button" class="btn" @click="pickCategory('all')">清除分类</button>
       <button v-if="catalogKeyword" type="button" class="btn" @click="suggestionBox?.focusInput()">换个药名</button>
       <button type="button" class="btn" @click="resetCatalog">浏览全部药品</button>
       <button type="button" class="btn primary" @click="openConsult">咨询与回复</button>
      </div>
     </div>
    </div>
    <div v-if="count>items.length" class="load-more-wrap">
     <button class="load-more" :disabled="catalogLoading||query.trim()!==catalogKeyword" @click="loadMore">{{catalogLoading?'正在加载…':'加载更多'}}</button>
     <p class="load-more-note">已展示 {{items.length}} / 共 {{count}} 件</p>
    </div>
   </template>
  </section>
  <aside class="catalog-help" aria-label="购药帮助">
   <div><h2>咨询与回复</h2><p>选择问药师或问订单与取货，也可查看历史回复。</p><button type="button" @click="openConsult">查看回复或发起咨询 <Icon name="arrow-right"/></button></div>
   <div><h2>处方申请</h2><p>上传处方并提交申请，审核结果可在处方申请记录中查看。</p><a href="#rx-submit">上传处方申请 <Icon name="arrow-right"/></a></div>
   <div><h2>AI 找药</h2><p>按药名查询本店商品和对应规格。</p><a href="#ai">打开 AI 助手 <Icon name="arrow-right"/></a></div>
  </aside>
 </div>
<footer class="shop-footer"><span class="wordmark">FirstSun.</span><p>请核对实际药品资料，或在药师指导下使用。</p><div class="footer-creds"><span><b>药师留言咨询</b> · <a href="#consult">咨询与回复</a></span><span><b>取药方式</b> · 到店自提 · 门店配送</span><span><b>退换说明</b> · 以门店实际规则为准</span></div></footer></main>
<main v-else-if="route==='detail'" class="page-container"><div class="breadcrumb"><a href="#catalog">找药购药</a><Icon name="chevron-right"/><span>商品详情</span></div><div v-if="detailLoading" class="skeleton" aria-busy="true"></div><div v-else-if="detailError" class="empty-state" role="alert"><h2>商品暂不可查看</h2><p>{{detailError}}</p><a href="#catalog" class="btn primary">返回列表</a></div><template v-else-if="detail"><div class="detail-top"><section><div class="hero-photo"><ProductCard :product="detail" image-only/></div><p class="gallery-caption">包装图仅供识别，请以实物为准</p></section><section class="detail-info"><div class="tags"><span class="tag" :class="{rx:detail.rx}">{{detail.rx?'Rx 处方药':'OTC / 商品档案'}}</span><span class="tag neutral">{{storeName}}</span></div><h1>{{detail.genericName||detail.name}}</h1><p v-if="detail.genericName&&detail.name&&detail.name!==detail.genericName" class="detail-trade-name">商品名 {{detail.name}}</p><p class="detail-spec">{{detail.specification||'规格暂未提供'}}</p><div class="detail-price"><div><span class="small">当前售价</span><span class="price"><small>¥</small>{{money(detail.price)}}</span></div><span class="stock">{{stockWarning?'库存暂不可用':`可售库存 ${detail.stock} 件`}}</span></div><dl class="details-list"><dt>生产厂家</dt><dd>{{detail.manufacturer||'商品档案暂未提供'}}</dd><dt>商品规格</dt><dd><span class="spec-option">{{detail.specification||'商品档案暂未提供'}}</span></dd></dl><div v-if="detail.rx" class="rx-notice">处方药需提交医生处方。可以在此提交申请并查看审核结果；核准后的结算与付款尚未在电脑端开放。</div><div class="buy-actions"><a v-if="detail.rx" class="btn primary" :href="'#rx-submit?drugId='+detail.id">提交处方申请</a><button v-else class="btn primary" :disabled="adding||stockWarning||detail.stock<=0" @click="addToCart(detail)">{{adding?'正在加购…':'加入购物车'}}</button><button class="btn" @click="openProductConsult"><Icon name="headset"/>咨询与回复</button><button class="btn link" @click="askProduct"><Icon name="sparkles"/>AI 查商品</button></div><p class="detail-alert">请仔细阅读药品说明书，或在药师指导下使用。</p></section></div><div class="detail-below"><section><div class="detail-tabs"><button :class="{active:infoTab==='info'}" @click="infoTab='info'">商品信息</button><button :class="{active:infoTab==='leaflet'}" @click="infoTab='leaflet'">说明书</button></div><table v-if="infoTab==='info'" class="data-table"><tbody><tr><th>通用名称</th><td>{{detail.genericName||'商品档案暂未提供'}}</td></tr><tr><th>规格</th><td>{{detail.specification||'商品档案暂未提供'}}</td></tr><tr><th>厂家</th><td>{{detail.manufacturer||'商品档案暂未提供'}}</td></tr><tr><th>批准文号</th><td>{{detail.rx?'处方资料受限':detail.approval}}</td></tr></tbody></table><div v-else class="notice-panel leaflet-empty">
  <p v-if="detail.rx">处方药说明书请咨询门店药师。</p>
  <template v-else-if="detail.leaflet">
    <h2>药品说明书</h2>
    <p class="small">{{detail.leaflet.source}}<template v-if="detail.leaflet.revised"> · 修订于 {{detail.leaflet.revised}}</template></p>
    <dl v-if="detail.leaflet.sections.length" class="leaflet-summary">
      <template v-for="section in detail.leaflet.sections" :key="section.label">
        <dt>{{section.label}}</dt><dd>{{section.text}}</dd>
      </template>
    </dl>
    <a class="btn primary" :href="detail.leaflet.url" target="_blank" rel="noopener noreferrer">查看完整说明书</a>
  </template>
  <p v-else>本商品说明书暂未上线。</p>
  <button class="btn leaflet-contact" @click="openProductConsult"><Icon name="headset"/>咨询门店药师</button>
</div></section><aside class="detail-consult"><Icon name="sparkles"/><h3>AI 查商品</h3><p>查询当前商品的价格与库存。</p><button class="btn full" @click="askProduct">询问 AI 助手</button></aside></div></template></main>
<main v-else class="ai-layout ai-workspace">
  <button v-if="historyOpen" type="button" class="ai-history-shade" aria-label="关闭对话历史" tabindex="-1" @click="historyOpen=false"></button>
  <aside id="ai-history" class="history" :class="{'mobile-open':historyOpen}" aria-labelledby="ai-history-title">
    <div class="ai-history-head">
      <h2 id="ai-history-title">对话历史</h2>
      <button ref="historyClose" type="button" class="icon-btn mobile-close" aria-label="关闭历史" @click="historyOpen=false"><Icon name="x"/></button>
    </div>
    <button type="button" class="btn new-chat" @click="newTopic" :disabled="busy||historyLoading"><Icon name="plus"/>新建对话</button>
    <div class="ai-history-toolbar">
      <span>我的对话</span>
      <button class="icon-btn" type="button" aria-label="刷新历史" @click="loadTopics()" :disabled="historyLoading"><Icon name="refresh-cw"/></button>
      <button v-if="topics.length" type="button" class="icon-btn" aria-label="清空全部对话" :disabled="historyLoading||deleting" @click="askClearTopics"><Icon name="trash-2"/></button>
    </div>
    <div v-if="historyLoading" class="ai-history-state" role="status">正在读取话题…</div>
    <div v-if="historyError" class="error-panel" role="alert">{{historyError}}<button type="button" class="btn link" @click="loadTopics()">重试</button></div>
    <div class="history-list">
      <div v-if="clearPending" class="history-confirm" role="alert">
        <span>清空全部对话？清空后无法恢复。</span>
        <div class="confirm-actions">
          <button type="button" class="btn link danger" :disabled="deleting" @click="clearTopics">确认清空</button>
          <button type="button" class="btn link" :disabled="deleting" @click="clearPending=false">取消</button>
        </div>
      </div>
      <div v-for="t in topics" :key="t.id" class="history-row" :class="{active:topicId===t.id}">
        <div v-if="pendingDeleteId===t.id" class="history-confirm" role="alert">
          <span>删除「{{t.title||'新话题'}}」？删除后无法恢复。</span>
          <div class="confirm-actions">
            <button type="button" class="btn link danger" :disabled="deleting" @click="deleteTopic(t)">确认删除</button>
            <button type="button" class="btn link" :disabled="deleting" @click="pendingDeleteId=null">取消</button>
          </div>
        </div>
        <template v-else>
          <button type="button" class="history-item" :class="{active:topicId===t.id}" :aria-current="topicId===t.id?'true':null" :disabled="historyLoading" @click="openTopic(t.id)"><Icon name="message-square"/><span>{{t.title||'新话题'}}</span></button>
          <button type="button" class="topic-del" :aria-label="'删除对话：'+(t.title||'新话题')" :disabled="historyLoading||deleting" @click.stop="askDeleteTopic(t.id)"><Icon name="trash-2"/></button>
        </template>
      </div>
      <p v-if="!topics.length&&!historyLoading" class="ai-history-state">{{user?'暂无历史对话':'登录后查看你的对话'}}</p>
      <button v-if="moreTopics" type="button" class="btn link" @click="loadTopics(true)">更早话题</button>
    </div>
    <div class="history-bottom">
      <button type="button" class="btn full" @click="openConsult"><Icon name="headset"/>咨询与回复</button>
    </div>
  </aside>

  <section class="chat" aria-labelledby="ai-title">
    <div class="chat-head">
      <div class="row">
        <button ref="historyTrigger" type="button" class="icon-btn mobile-history" aria-label="对话历史" :aria-expanded="historyOpen" aria-controls="ai-history" @click="historyOpen=true"><Icon name="panel-left"/>历史</button>
        <span class="avatar-ai"><Icon name="sparkles"/></span>
        <div><h1 id="ai-title">AI 购药助手</h1><div class="status">{{user?'会员咨询':'登录后使用'}}</div></div>
      </div>
      <button v-if="topicId" type="button" class="btn" :disabled="busy||historyLoading" @click="openTopic(topicId)"><Icon name="refresh-cw"/>重载话题</button>
      <button v-else type="button" class="btn" @click="openConsult"><Icon name="headset"/>转人工</button>
    </div>

    <div ref="chatScroll" class="chat-scroll">
      <div class="chat-inner">
        <section v-if="!messages.length" class="chat-intro">
          <div class="ai-intro-icon"><Icon name="sparkles"/></div>
          <h2>想了解哪款药品？</h2>
          <p>输入药品名称，查询规格、门店价格和库存，也可以询问说明书里的信息。</p>
          <template v-if="user">
            <div class="suggestions" aria-label="示例问题">
              <button type="button" @click="send('查询板蓝根颗粒的价格和库存')" :disabled="busy||historyLoading"><Icon name="search"/>查询板蓝根颗粒</button>
              <button type="button" @click="send('处方药如何购买？')" :disabled="busy||historyLoading"><Icon name="file-text"/>处方药如何购买？</button>
            </div>
          </template>
          <button v-else type="button" class="btn primary ai-sign-in" @click="loginOpen=true">登录后开始咨询</button>
        </section>
        <template v-if="user">
          <button v-if="moreTurns" type="button" class="btn link" :disabled="historyLoading" @click="openTopic(topicId,true)">加载更早消息</button>
          <article v-for="m in messages" :key="m.id" class="ai-turn">
            <div class="user-message"><div class="user-bubble">{{m.content}}</div><span class="user-avatar" aria-label="你的提问">我</span></div>
            <div class="ai-answer">
              <div class="answer-heading"><span class="avatar-ai"><Icon name="sparkles"/></span>FirstSun AI</div>
              <div v-if="m.loading&&!m.answer" class="thinking" role="status">正在查询门店信息<span aria-hidden="true">•••</span></div>
              <p v-if="m.answer" class="answer-text" :class="{streaming:m.loading}">{{answerDisplay(m).body}}</p>
              <div v-if="m.error" class="error-panel" role="alert">
                {{m.error}}<button v-if="m.status!=='STOPPED'&&m.status!=='PENDING'" type="button" class="btn link" :disabled="busy||historyLoading" @click="retry(m)">重试本次请求</button>
              </div>
              <section v-if="answerDisplay(m).evidence" class="ai-evidence" aria-label="说明书依据">
                <Icon name="book-open"/>
                <div><h3>说明书依据</h3><p>{{answerDisplay(m).evidence}}</p></div>
              </section>
              <section v-if="m.products.length" class="ai-results" aria-label="匹配商品">
                <div class="result-caption"><h3>匹配商品 <span>{{m.products.length}} 款</span></h3><span>本次查询的价格和库存</span></div>
                <div class="ai-products">
                  <ProductCard v-for="p in m.products" :key="p.id" :product="p" :adding="adding" @add="addToCart" @unavailable="notConnected"/>
                </div>
              </section>
              <p v-if="m.source||m.queriedAt" class="answer-foot"><span v-if="m.source">{{m.source}}</span><span v-if="m.queriedAt">{{m.queriedAt}}</span></p>
            </div>
          </article>
        </template>
      </div>
    </div>

    <div class="composer-wrap">
      <form class="composer" @submit.prevent="send()">
        <label for="ai-question">向助手提问</label>
        <textarea id="ai-question" v-model="input" placeholder="输入药品名称或你想了解的问题…" maxlength="500" :disabled="busy||historyLoading||!user" @keydown.enter.exact.prevent="send()"></textarea>
        <div class="composer-tools">
          <span>Enter 发送 · Shift + Enter 换行</span>
          <button v-if="busy" class="btn primary send" type="button" @click="stop"><Icon name="square"/>停止接收</button>
          <button v-else class="btn primary send" type="submit" :disabled="!input.trim()||historyLoading||!user"><Icon name="arrow-up"/>发送</button>
        </div>
      </form>
      <p class="composer-note">AI 提供药品信息查询，不能代替医生或药师。</p>
    </div>
  </section>

  <aside class="rail" aria-label="购药服务">
    <h2>购药服务</h2>
    <a href="#catalog" class="service-link"><Icon name="pill"/><span class="grow">找药购药<span>浏览当前门店商品</span></span><Icon name="chevron-right"/></a>
    <a class="service-link" href="#rx-submit"><Icon name="file-check-2"/><span class="grow">上传处方申请<span>提交处方并查看审核结果</span></span><Icon name="chevron-right"/></a>
    <button type="button" class="service-link" @click="openConsult"><Icon name="headset"/><span class="grow">咨询与回复<span>查看历史回复或选择咨询问题</span></span><Icon name="chevron-right"/></button>
    <div class="rail-notice"><Icon name="shield-check"/><p>商品卡展示查询时的价格和库存，购买时以结算结果为准。</p></div>
  </aside>
</main>
<AiLauncher v-if="canShowAiLauncher && !aiQuick" :identity="'member-web:'+(user?.userId||'guest')"
  label="AI 找药" hint="可以问我价格、库存和规格" @open="openQuickAi(route==='detail')" />
<aside v-if="aiQuick && canShowAiLauncher" class="quick-ai" role="dialog" aria-label="AI 购药助手" @keydown.esc.stop="closeQuickAi">
  <header><strong>AI 购药助手</strong><button type="button" class="btn link" @click="expandQuickAi">展开完整对话</button><button type="button" class="icon-btn" aria-label="关闭 AI 助手" @click="closeQuickAi">×</button></header>
  <div ref="quickScroll" class="quick-ai-scroll">
    <template v-if="user">
      <div v-if="!messages.length" class="quick-ai-starters">
        <p>想了解哪款药品？</p>
        <button type="button" class="btn" @click="input='查询板蓝根颗粒的价格和库存';quickQuestion?.focus()">查价格和库存</button>
        <button type="button" class="btn" @click="input='处方药如何购买？';quickQuestion?.focus()">了解处方购药流程</button>
      </div>
      <article v-for="m in messages" :key="m.id">
        <strong>{{m.content}}</strong>
        <p v-if="m.loading&&!m.answer" role="status">正在查询…</p>
        <p v-if="m.answer">{{answerDisplay(m).body}}</p>
        <p v-if="answerDisplay(m).evidence">说明书依据：{{answerDisplay(m).evidence}}</p>
        <p v-if="m.source||m.queriedAt">{{m.source}} {{m.queriedAt}}</p>
        <p v-if="m.error" role="alert">{{m.error}}</p>
        <button v-if="m.error&&m.status!=='STOPPED'&&m.status!=='PENDING'" type="button" class="btn link" :disabled="busy||historyLoading" @click="retry(m)">重试</button>
      </article>
    </template>
    <button v-else class="btn primary" type="button" @click="loginOpen=true">登录后开始咨询</button>
  </div>
  <form v-if="user" @submit.prevent="send()">
    <textarea ref="quickQuestion" v-model="input" aria-label="向助手提问" placeholder="输入药品名称或问题" maxlength="500" :disabled="busy||historyLoading"></textarea>
    <button v-if="busy" class="btn" type="button" @click="stop">停止</button>
    <button v-else type="submit" class="btn primary" :disabled="!input.trim()||historyLoading">发送</button>
  </form>
  <footer><span>药品信息参考</span><button class="btn link" type="button" @click="openConsult">咨询药师</button></footer>
</aside>
<div v-if="loginOpen||dialog" class="modal-shade" @pointerdown="backdropDown" @pointerup="backdropUp" @pointercancel="backdropCancel" @click="backdropClick" @keydown.esc="closeDialog"><section class="live-dialog" role="dialog" aria-modal="true" aria-labelledby="live-dialog-title"><div class="dialog-head"><h2 id="live-dialog-title">{{dialog?'服务尚未接入':authScreen==='register'?'会员注册':authScreen==='forgot'?'找回密码':'会员登录'}}</h2><button class="icon-btn" aria-label="关闭" @click="closeDialog"><Icon name="x"/></button></div><template v-if="dialog"><p>{{dialog}}</p><button class="btn primary full" @click="closeDialog">知道了</button></template><template v-else-if="!authBusy"><template v-if="authChannel==='email'"><p v-if="passwordNotice" class="small" role="status">{{passwordNotice}}</p><EmailRegister v-if="authScreen==='register'" @logged-in="onLoggedIn"/><EmailForgotPassword v-else-if="authScreen==='forgot'" @login="authScreen='login'"/><EmailLogin v-else @logged-in="onLoggedIn" @register="authScreen='register'" @forgot="authScreen='forgot'"/></template><template v-else><RegisterGate v-if="authScreen==='register'" @logged-in="onLoggedIn"/><LoginGate v-else @logged-in="onLoggedIn"/></template><button v-if="authScreen==='register'||authChannel==='mobile'" type="button" class="btn link full" @click="authScreen=authScreen==='register'?'login':'register'">{{authScreen==='register'?'已有账号？返回登录':'没有账号？立即注册'}}</button><button v-if="authChannel==='mobile'" type="button" class="btn link full" @click="changeAuthChannel('email')">返回邮箱登录</button></template></section></div><div v-if="toastText" class="live-toast" role="status">{{toastText}}</div>
</template>

<style scoped>
.quick-ai { position:fixed; z-index:100; right:16px; bottom:16px; width:min(400px,calc(100vw - 32px)); max-height:min(590px,calc(100dvh - 40px)); display:flex; flex-direction:column; background:#fff; border:1px solid #cadcd2; border-radius:14px; color:#203b32; box-shadow:0 10px 30px #123e3533; }
.quick-ai header,.quick-ai footer { display:flex; align-items:center; justify-content:space-between; padding:8px 12px; gap:8px; }
.quick-ai header { border-bottom:1px solid #e2eae5; }
.quick-ai header strong { margin-right:auto; }
.quick-ai-scroll { overflow:auto; overscroll-behavior:contain; padding:12px; min-height:100px; }
.quick-ai-scroll article { padding:10px; background:#f2f7f4; border-radius:8px; margin:0 0 10px; overflow-wrap:anywhere; }
.quick-ai-scroll article p { white-space:pre-wrap; }
.quick-ai-starters { display:flex; gap:8px; flex-wrap:wrap; }
.quick-ai-starters p { width:100%; }
.quick-ai form { display:flex; gap:8px; align-items:end; padding:8px 12px; }
.quick-ai textarea { flex:1; min-width:0; resize:vertical; min-height:48px; max-height:120px; border:1px solid #b5cbbd; border-radius:8px; padding:8px; font:inherit; }
.quick-ai footer { font-size:13px; color:#52685b; }

.leaflet-empty p { margin:0 0 16px }
.leaflet-summary dt { font-weight:700; margin-top:12px }
.leaflet-summary dd { margin:4px 0 12px; line-height:1.65 }
.leaflet-contact { margin:4px 0 4px 8px }
.leaflet-empty .btn { display:inline-flex; align-items:center; gap:8px }
.leaflet-empty svg,.buy-actions svg { width:18px; height:18px; flex:none }
.buy-actions { flex-wrap:wrap }
.detail-trade-name { margin:8px 0; color:var(--muted); font-size:14px; overflow-wrap:anywhere }

.local-auth-entry{background:transparent}
#header .consult-nav svg{width:17px;height:17px;color:var(--green)}
.notice-tool{position:relative}
.notice-tool[aria-current="page"]{color:var(--green);background:#edf5ef}
.notice-badge{position:absolute;top:1px;right:0;min-width:16px;padding:1px 3px;border-radius:12px;background:var(--green);color:#fff;font-size:10px;line-height:14px;text-align:center}
:global(body:is([data-page=home],[data-page=recommend])){background:#fafbf8}
#header.home-header{position:sticky;top:0;z-index:20;height:90px;padding-inline:max(48px,calc((100% - 1280px)/2));background:#fafbf8;border-bottom-color:#e4eae4}
.shop-page.shop-home{width:min(1280px,calc(100% - 96px));max-width:none;padding:28px 0 0}
.shop-home .shop-footer{margin-top:0}
.home-guide{display:flex;align-items:center;gap:24px;margin-top:60px;border-top:1px solid var(--line);padding:34px 0}
.home-guide-icon{display:grid;place-items:center;flex:none;width:56px;height:56px;border:1px solid #d7e5da;border-radius:50%;background:#edf5ef;color:#0e5e3f}
.home-guide-icon svg{width:24px;height:24px}
.home-guide h2{margin:0;font-size:19px;font-weight:500}
.home-guide p{margin:9px 0 0;color:#52685b;font-size:12px;line-height:1.8;text-wrap:pretty}
.home-guide button{display:flex;align-items:center;gap:8px;margin-left:auto;color:#0e5e3f;font-size:13px;min-height:44px;white-space:nowrap}
.home-guide button:hover{text-decoration:underline;text-underline-offset:4px}
.home-guide button svg{width:17px;height:17px}

#header .header-tools { flex-shrink: 0; }
#header :is(.auth-entry,.cart-tool) { min-width: 44px; min-height: 44px; height: 44px; flex-shrink: 0; }
#header .auth-entry > .header-avatar { display: inline-flex; flex: none; }
#header .header-avatar :deep(.avatar-placeholder svg) { width: 24px; height: 24px; }
#header .auth-entry > svg { width: 24px; height: 24px; flex: none; }
#header .auth-entry > .auth-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#header :is(.tool,.nav-toggle):focus-visible { outline: 2px solid var(--green); outline-offset: 3px; }
@media (max-width: 1280px) {
 #header { gap: 20px; padding-inline: 24px; }
 #header .nav { gap: 16px; }
 #header .auth-entry > .auth-name { display: none; }
 #header .brand-sub { display: none; }
}
@media (max-width: 1200px) {
 #header { flex-wrap: nowrap; }
 #header .nav-toggle { display: inline-flex; width: 44px; height: 44px; }
 #header .nav { display: none; }
 #header .nav.open { display: flex; position: absolute; top: 100%; left: 0; right: 0; width: auto; height: auto; flex-direction: column; align-items: stretch; gap: 0; padding: 6px 0; background: #fff; border-bottom: 1px solid var(--line); box-shadow: 0 18px 30px rgba(18,43,36,.11); }
 #header .nav.open a { min-height: 48px; padding: 0 20px; font-size: 15px; border-bottom: 1px solid #eff4f0; }
 #header .nav.open a:last-child { border-bottom: 0; }
 #header .nav.open a.active { background: #f1f6f2; font-weight: 600; }
 #header .nav.open a.active::after { display: none; }
}

@media(min-width:1600px){#header.home-header{padding-inline:max(60px,calc((100% - 1420px)/2))}.shop-page.shop-home{width:min(1420px,calc(100% - 120px))}}
@media(max-width:1100px){#header.home-header{padding-inline:24px}.shop-page.shop-home{width:calc(100% - 48px)}}
@media(min-width:701px) and (max-width:900px){
 #header{gap:16px;padding-inline:20px}
 #header .nav{gap:12px}
 #header .nav a{font-size:13px;gap:5px}
 #header .consult-nav svg{display:none}
 #header .header-tools{gap:8px}
 #header :is(.nav-ai-label,.cart-tool .label,.auth-entry>.auth-name){display:none}
}
@media(max-width:760px){
 #header.home-header{padding-inline:16px}
 .shop-page.shop-home{width:calc(100% - 32px);padding-top:16px}
 .home-guide{align-items:flex-start;flex-wrap:wrap;gap:12px;margin-top:36px;padding:24px 0}
 .home-guide-icon{width:44px;height:44px}.home-guide>div{flex:1 1 calc(100% - 56px)}
 .home-guide button{margin-left:56px;padding:0}
}
@media(max-width:700px){#header.home-header{height:64px;padding-inline:14px}}
@media(max-width:360px){
 #header{column-gap:6px}
 #header .header-tools{gap:6px}
 #header .nav a{font-size:12px;gap:4px}
}
</style>
<style scoped src="./ai-workspace.css"></style>

<style src="./catalog-page.css"></style>
