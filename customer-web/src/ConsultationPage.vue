<script setup>
import {onMounted,onUnmounted,ref} from 'vue';
import api from '@/sheep/api/pharmacy/server';
import {formatDate} from '@/sheep/api/pharmacy/common';
import {identity} from './session.js';
import {sameIdentity} from './guards.js';
import {kindLabel,storeAttribution,mergeById,prependById,newClientRequestId} from './consultation-core.js';
const props=defineProps({user:Object});
const emit=defineEmits(['login']);
const conversations=ref([]),total=ref(0),conversation=ref(null),messages=ref([]),input=ref(''),failed=ref(null);
const loading=ref(false),busy=ref(false),polling=ref(false),error=ref(''),hasOlder=ref(false),pageNo=ref(1);
const currentStore=ref({id:null,name:''});
let disposed=false,generation=0,timer=0,opening=null,refreshQueued=false,bound=identity(),boundStore=null;
const current=(g,id)=>!disposed&&g===generation&&sameIdentity(bound,identity())&&!!props.user&&(!id||conversation.value?.id===id);
const storeChanged=()=>boundStore!=null&&Number(api.store?.id)!==Number(boundStore);
function stopTimers(){clearTimeout(timer);timer=0;polling.value=false;refreshQueued=false;}
function halt(){generation++;opening=null;stopTimers();loading.value=false;busy.value=false;}
function schedule(){clearTimeout(timer);if(disposed||!conversation.value||!props.user)return;timer=setTimeout(async()=>{await refresh(true);if(!disposed&&conversation.value)schedule();},5000);}
async function ensureCurrentStore(){if(!api.store?.id&&api.refreshStore)await api.refreshStore();currentStore.value={id:api.store?.id??null,name:api.store?.name||''};}
function abandonStore(){generation++;boundStore=null;opening=null;stopTimers();conversation.value=null;messages.value=[];failed.value=null;input.value='';loading.value=false;busy.value=false;error.value='当前门店已变化，已停止原来的会话刷新。';if(!disposed&&props.user)loadList();}
async function loadList(more=false){if(loading.value||!props.user)return;const g=generation;loading.value=true;if(!more)error.value='';try{await ensureCurrentStore();if(!current(g))return;const next=more?pageNo.value+1:1;const result=await api.consultationPage(next);if(!current(g))return;const list=Array.isArray(result?.list)?result.list:[];conversations.value=more?mergeById(conversations.value,list):list;total.value=Number.isFinite(Number(result?.total))?Number(result.total):conversations.value.length;pageNo.value=next;}catch(e){if(current(g))error.value=e.message||'会话列表加载失败';}finally{if(current(g))loading.value=false;}}
async function markRead(g,id,list){const last=list?.length?list[list.length-1]:null;if(!last||!current(g,id))return;await api.readConsultation(id,last.id);}
async function enter(id){generation++;const g=generation;stopTimers();loading.value=true;error.value='';failed.value=null;input.value='';messages.value=[];hasOlder.value=false;try{await ensureCurrentStore();if(!current(g))return;boundStore=api.store?.id??null;const result=await api.consultationMessages(id);if(!current(g)||storeChanged()){if(storeChanged()&&current(g))abandonStore();return;}conversation.value=result.conversation;messages.value=result.list||[];hasOlder.value=!!result.hasMore;try{await markRead(g,id,messages.value);}catch(e){if(current(g,id))error.value=e.message||'已读状态更新失败';}if(current(g,id))schedule();}catch(e){if(current(g))error.value=e.message||'消息加载失败';}finally{if(current(g))loading.value=false;}}
// 发起期间拒绝连点；编号、代际和门店校验在后续收发里继续生效。
async function start(kind){if(busy.value||loading.value||!props.user||(kind!=='PHARMACIST'&&kind!=='SERVICE'))return;const g=generation,token={};opening=token;busy.value=true;error.value='';try{await ensureCurrentStore();if(!current(g)||opening!==token)return;const id=await api.openConsultation(kind);if(!current(g)||opening!==token)return;await enter(id);}catch(e){if(current(g)&&opening===token)error.value=e.message||'暂时无法发起会话';}finally{if(opening===token){opening=null;busy.value=false;}}}
async function refresh(background=false){if(!conversation.value||polling.value||loading.value||disposed){if(polling.value)refreshQueued=true;return;}const g=generation,id=conversation.value.id;polling.value=true;if(!background)error.value='';try{let result;do{if(!current(g,id)||storeChanged()){if(storeChanged()&&g===generation)abandonStore();return;}const after=messages.value.length?messages.value[messages.value.length-1].id:0;result=await api.consultationMessages(id,{after});if(!current(g,id)||storeChanged()){if(storeChanged()&&g===generation)abandonStore();return;}conversation.value=result.conversation;messages.value=mergeById(messages.value,result.list||[]);try{await markRead(g,id,result.list||[]);}catch(e){if(current(g,id))error.value=e.message||'已读状态更新失败';}}while(result?.hasMore&&result.list?.length&&!disposed&&current(g,id));if(current(g,id)&&!error.value)error.value='';}catch(e){if(current(g,id))error.value=e.message||'消息刷新失败';}finally{if(current(g,id)){polling.value=false;if(refreshQueued){refreshQueued=false;await refresh(true);}}}}
async function older(){if(loading.value||polling.value||!conversation.value||!messages.value.length)return;const g=generation,id=conversation.value.id;loading.value=true;try{const result=await api.consultationMessages(id,{before:messages.value[0].id});if(!current(g,id)||storeChanged()){if(storeChanged()&&g===generation)abandonStore();return;}messages.value=prependById(result.list||[],messages.value);hasOlder.value=!!result.hasMore;}catch(e){if(current(g,id))error.value=e.message||'更早消息加载失败';}finally{if(current(g,id))loading.value=false;}}
async function send(retry){if(busy.value||!conversation.value)return;if(failed.value&&!retry)return;const content=(retry?.content??input.value).trim();if(!content||content.length>1000){error.value='请输入 1–1000 字文字';return;}const g=generation,id=conversation.value.id;const request=retry||{conversationId:id,clientRequestId:newClientRequestId(),content};if(request.conversationId!==id)return;failed.value=request;busy.value=true;error.value='';try{await api.sendConsultation(request);if(!current(g,id))return;failed.value=null;input.value='';await refresh();}catch(e){if(current(g,id))error.value=e.message||'发送结果未确认';}finally{if(current(g,id))busy.value=false;}}
function discard(){if(busy.value)return;failed.value=null;input.value='';error.value='';}
function back(){generation++;boundStore=null;stopTimers();conversation.value=null;messages.value=[];failed.value=null;input.value='';loading.value=false;error.value='';loadList();}
function onKeydown(event){if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();if(!failed.value)send();}}
onMounted(()=>{bound=identity();return props.user?loadList():undefined;});
onUnmounted(()=>{disposed=true;halt();conversations.value=[];conversation.value=null;messages.value=[];failed.value=null;input.value='';error.value='';boundStore=null;});
</script>
<template>
<main class="customer-page consult-page">
  <div class="breadcrumb"><a href="#home">首页</a><Icon name="chevron-right"/><span>药师 / 客服</span></div>
  <div class="customer-title"><div><div class="eyebrow">FIRSTSUN / STORE SUPPORT</div><h1>药师与客服</h1><p>给当前门店留言，由门店药师或客服人工回复。</p></div><a class="btn" href="#catalog">继续找药</a></div>
  <div v-if="!user" class="empty-state"><Icon name="circle-user-round"/><h2>登录后联系门店</h2><p>会话和消息只对当前会员可见。</p><button class="btn primary" @click="emit('login')">登录会员账号</button></div>
  <template v-else>
    <div v-if="error" class="error-panel" role="alert">{{error}}<button v-if="!failed" class="btn link" :disabled="loading||busy" @click="conversation?refresh():loadList()">重新加载</button></div>
    <p class="consult-store"><Icon name="store"/>{{currentStore.id?storeAttribution({storeId:currentStore.id},currentStore):'正在读取当前门店…'}}</p>
    <div v-if="!conversation">
      <section class="consult-choices" aria-label="发起咨询">
        <article class="consult-option">
          <span class="consult-icon"><Icon name="stethoscope"/></span>
          <div><h2>咨询药师</h2><p>向门店药师了解药品信息和用药注意事项。</p></div>
          <button class="btn primary" type="button" :disabled="busy||loading" @click="start('PHARMACIST')">药师文字咨询<Icon name="arrow-right"/></button>
        </article>
        <article class="consult-option">
          <span class="consult-icon"><Icon name="headset"/></span>
          <div><h2>联系门店客服</h2><p>咨询订单、到店取货及其他门店服务。</p></div>
          <button class="btn" type="button" :disabled="busy||loading" @click="start('SERVICE')">门店客服咨询<Icon name="arrow-right"/></button>
        </article>
      </section>
      <p class="consult-privacy small muted">请勿在咨询中发送身份证号、密码或支付凭据。处方材料请通过 <a href="#rx-submit">处方申请</a> 提交。</p>
      <section class="paper consult-history">
        <div class="paper-head"><div><h2>我的会话</h2><p class="small muted">查看门店回复或继续上次的咨询。</p></div><button class="btn" type="button" :disabled="loading" @click="loadList()"><Icon name="refresh-cw"/>刷新列表</button></div>
        <div v-if="loading" class="skeleton" aria-busy="true" aria-label="正在读取会话"></div>
        <div v-else-if="!conversations.length&&!error" class="empty-state"><h2>暂无咨询会话</h2><p>可以选择药师或客服，向当前门店发起文字咨询。</p></div>
        <button v-for="item in conversations" :key="item.id" class="consult-row" type="button" @click="enter(item.id)">
          <span class="consult-icon"><Icon :name="item.kind==='PHARMACIST'?'stethoscope':'headset'"/></span>
          <span class="consult-row-body">
            <span class="consult-row-title"><strong>{{kindLabel(item.kind)}}</strong><span v-if="item.unread" class="tag">{{item.unread}} 条未读</span></span>
            <span class="small muted">{{storeAttribution(item,currentStore)}} · {{formatDate(item.updateTime)||'时间未提供'}}</span>
          </span>
          <span class="consult-state" :class="{attended:item.attended}">{{item.attended?'已接待':'等待接待'}}</span><Icon name="chevron-right" class="consult-chevron"/>
        </button>
        <nav v-if="conversations.length<total" class="pagination" aria-label="会话分页"><button class="btn" :disabled="loading" @click="loadList(true)">更多会话</button><span>已显示 {{conversations.length}} / {{total}}</span></nav>
      </section>
    </div>
    <section v-else class="paper consult-thread">
      <div class="paper-head">
        <div class="consult-thread-title"><span class="consult-icon"><Icon :name="conversation.kind==='PHARMACIST'?'stethoscope':'headset'"/></span><div><h2>{{kindLabel(conversation.kind)}}</h2><p class="small muted">{{storeAttribution(conversation,currentStore)}}</p></div></div>
        <button class="btn" :disabled="busy" @click="back">返回会话列表</button>
      </div>
      <div class="consult-thread-status" role="status"><span class="consult-state" :class="{attended:conversation.attended}">{{conversation.attended?'已接待':'等待接待'}}</span><span>{{conversation.attended?'门店回复会保存在本会话。':'可以先留言，门店人员接待后会回复。'}}</span></div>
      <div class="consult-messages">
      <div v-if="loading&&!messages.length" class="skeleton" aria-busy="true" aria-label="正在读取消息"></div>
      <button v-if="hasOlder" class="btn" :disabled="loading||polling" @click="older">查看更早消息</button>
      <div v-if="!messages.length&&!loading" class="empty-state"><Icon name="message-circle"/><h2>还没有消息</h2><p>在下方输入想咨询的问题。</p></div>
      <article v-for="message in messages" :key="message.id" class="consult-bubble" :class="{mine:message.senderType==='MEMBER'}">
        <p class="small muted">{{message.senderType==='MEMBER'?'你':message.senderName||'门店人员'}} · {{formatDate(message.createTime)||'时间未提供'}}</p>
        <p class="consult-text">{{message.content}}</p>
      </article>
      </div>
      <div class="consult-compose-panel">
      <div v-if="failed&&!busy" class="error-panel" role="alert">
        <p>发送结果未确认。重试会沿用同一请求编号，不会自动再生成一条。</p>
        <p class="consult-text">{{failed.content}}</p>
        <button class="btn" :disabled="busy" @click="send(failed)">重试发送</button>
        <button class="btn" :disabled="busy" @click="discard">放弃本次发送</button>
      </div>
      <form class="consult-compose" @submit.prevent="send()">
        <textarea v-model="input" maxlength="1000" aria-label="咨询内容" placeholder="输入文字，等待门店人工回复" :disabled="busy||!!failed" @keydown="onKeydown"></textarea>
        <button class="btn primary" :disabled="busy||!!failed||!input.trim()"><Icon name="send"/>{{busy?'正在发送…':'发送'}}</button>
      </form>
      <p class="small muted">Enter 发送 · Shift + Enter 换行</p>
      </div>
    </section>
  </template>
</main>
</template>
<style scoped>
.consult-page { max-width: 1120px; padding-bottom: 48px; }
.consult-store { display: inline-flex; align-items: center; gap: 7px; margin: 0 0 14px; padding: 7px 11px; border-radius: 8px; background: #edf5ef; color: var(--green); font-size: 13px; }
.consult-store svg, .consult-page .btn svg, .consult-chevron { width: 16px; height: 16px; flex: none; }
.consult-page .btn { display: inline-flex; align-items: center; gap: 8px; }
.consult-choices { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.consult-option { display: grid; justify-items: start; gap: 13px; padding: clamp(17px,3vw,24px); border: 1px solid var(--line); border-radius: 16px; background: #fff; }
.consult-option h2, .consult-page .paper-head h2 { margin: 0; font-size: 19px; }
.consult-option p, .consult-page .paper-head p { margin: 5px 0 0; color: var(--muted); }
.consult-icon { display: inline-grid; place-items: center; flex: none; width: 42px; height: 42px; border-radius: 10px; background: #edf5ef; color: var(--green); }
.consult-icon svg { width: 21px; height: 21px; }
.consult-privacy { margin: 11px 0 22px; }
.consult-privacy a { color: var(--green); text-decoration: underline; text-underline-offset: 3px; }
.consult-history, .consult-thread { border-radius: 16px; }
.consult-history .consult-row { flex-direction: row; align-items: center; gap: 12px; padding: 15px 20px; }
.consult-history .consult-row:first-of-type { border-top: 0; }
.consult-row:hover { background: #f5f9f5; }
.consult-row-body { display: grid; gap: 3px; flex: 1; min-width: 0; overflow-wrap: anywhere; }
.consult-row-title { flex-wrap: wrap; }
.consult-state { display: inline-block; flex: none; border-radius: 20px; padding: 4px 9px; background: #f8f1e5; color: #755218; font-size: 12px; }
.consult-state.attended { background: #edf5ef; color: var(--green); }
.consult-chevron { color: var(--muted); }
.consult-thread-title { display: flex; align-items: center; gap: 12px; }
.consult-thread-status { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 10px clamp(14px,3vw,24px); border-bottom: 1px solid var(--line); color: var(--muted); font-size: 13px; }
.consult-messages { min-height: 180px; padding: 8px clamp(14px,3vw,24px); background: #fafbf8; }
.consult-messages .consult-bubble { width: fit-content; max-width: min(84%,660px); margin: 10px auto 10px 0; border: 1px solid var(--line); background: #fff; }
.consult-messages .consult-bubble.mine { margin-right: 0; margin-left: auto; background: #edf5ef; }
.consult-bubble p { margin: 0; }
.consult-bubble .consult-text { margin-top: 5px; overflow-wrap: anywhere; }
.consult-compose-panel { padding: 14px clamp(14px,3vw,24px); border-top: 1px solid var(--line); }
.consult-compose-panel > p { margin: 8px 0 0; }
.consult-compose { align-items: flex-end; margin: 0; padding: 0; }
.consult-compose textarea { background: #fafbf8; }
@media (max-width: 620px) {
  .consult-choices { grid-template-columns: 1fr; }
  .consult-history .consult-row { gap: 8px; padding-inline: 12px; }
  .consult-chevron { display: none; }
}
</style>
