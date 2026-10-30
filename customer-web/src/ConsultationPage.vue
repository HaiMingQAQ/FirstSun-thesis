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
  <div class="breadcrumb"><a href="#catalog">找药购药</a><Icon name="chevron-right"/><span>药师 / 客服</span></div>
  <div class="customer-title"><div><div class="eyebrow">FIRSTSUN / CONSULT</div><h1>联系当前门店</h1><p>由本门店药师或客服人工回复，不是 AI。消息送达不代表已有人员接待，不会自动生成回复。</p></div><a class="btn" href="#catalog">返回找药</a></div>
  <div v-if="!user" class="empty-state"><Icon name="circle-user-round"/><h2>登录后联系门店</h2><p>会话和消息只对当前会员可见。</p><button class="btn primary" @click="emit('login')">登录会员账号</button></div>
  <template v-else>
    <div v-if="error" class="error-panel" role="alert">{{error}}<button v-if="!failed" class="btn link" :disabled="loading||busy" @click="conversation?refresh():loadList()">重新加载</button></div>
    <p class="small muted">{{currentStore.id?storeAttribution({storeId:currentStore.id},currentStore):'正在读取当前门店…'}}</p>
    <div v-if="!conversation" class="consult-layout">
      <section class="paper pad">
        <h2>发起咨询</h2>
        <p class="small muted">向当前门店留言。请勿发送身份证号、密码或支付凭据。处方材料请使用处方入口。</p>
        <div class="page-actions">
          <button class="btn primary" :disabled="busy||loading" @click="start('PHARMACIST')">药师文字咨询</button>
          <button class="btn" :disabled="busy||loading" @click="start('SERVICE')">门店客服咨询</button>
        </div>
      </section>
      <section class="paper">
        <div class="paper-head"><h2>我的会话</h2><button class="btn" :disabled="loading" @click="loadList()">刷新列表</button></div>
        <div v-if="loading" class="skeleton" aria-busy="true" aria-label="正在读取会话"></div>
        <div v-else-if="!conversations.length&&!error" class="empty-state"><h2>暂无咨询会话</h2><p>可以选择药师或客服，向当前门店发起文字咨询。</p></div>
        <button v-for="item in conversations" :key="item.id" class="consult-row" @click="enter(item.id)">
          <span class="consult-row-title"><strong>{{kindLabel(item.kind)}}</strong><span v-if="item.unread" class="tag">{{item.unread}} 条未读</span></span>
          <span class="small muted">{{storeAttribution(item,currentStore)}} · {{item.attended?'已接待':'等待接待'}} · {{formatDate(item.updateTime)||'时间未提供'}}</span>
        </button>
        <nav v-if="conversations.length<total" class="pagination" aria-label="会话分页"><button class="btn" :disabled="loading" @click="loadList(true)">更多会话</button><span>已显示 {{conversations.length}} / {{total}}</span></nav>
      </section>
    </div>
    <section v-else class="paper consult-thread">
      <div class="paper-head">
        <div><h2>{{kindLabel(conversation.kind)}}</h2><p class="small muted">{{storeAttribution(conversation,currentStore)}} · {{conversation.attended?'已接待':'等待接待'}}</p></div>
        <button class="btn" :disabled="busy" @click="back">返回会话列表</button>
      </div>
      <div class="notice-panel" role="status">{{conversation.attended?'接待人员已领取，回复会保存在本会话。':'暂无人员接待，您可以留言；不会自动生成回复。'}}</div>
      <div v-if="loading&&!messages.length" class="skeleton" aria-busy="true" aria-label="正在读取消息"></div>
      <button v-if="hasOlder" class="btn" :disabled="loading||polling" @click="older">查看更早消息</button>
      <div v-if="!messages.length&&!loading" class="empty-state"><h2>还没有消息</h2><p>请发送文字说明。员工回复来自门店后台，本页不会编造回复。</p></div>
      <article v-for="message in messages" :key="message.id" class="consult-bubble" :class="{mine:message.senderType==='MEMBER'}">
        <p class="small muted">{{message.senderName||'发送者未提供'}} · {{formatDate(message.createTime)||'时间未提供'}}</p>
        <p class="consult-text">{{message.content}}</p>
      </article>
      <div v-if="failed&&!busy" class="error-panel" role="alert">
        <p>发送结果未确认。重试会沿用同一请求编号，不会自动再生成一条。</p>
        <p class="consult-text">{{failed.content}}</p>
        <button class="btn" :disabled="busy" @click="send(failed)">重试发送</button>
        <button class="btn" :disabled="busy" @click="discard">放弃本次发送</button>
      </div>
      <form class="consult-compose" @submit.prevent="send()">
        <textarea v-model="input" maxlength="1000" aria-label="咨询内容" placeholder="输入文字，等待门店人工回复" :disabled="busy||!!failed" @keydown="onKeydown"></textarea>
        <button class="btn primary" :disabled="busy||!!failed||!input.trim()">{{busy?'正在发送…':'发送'}}</button>
      </form>
      <p class="small muted">Enter 发送，Shift + Enter 换行。发送失败时请使用上方的重试，不要改成新编号重发。</p>
    </section>
  </template>
</main>
</template>
