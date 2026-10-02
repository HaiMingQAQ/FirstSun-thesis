<template>
  <s-pharmacy-page :dock="!!conversation && loggedIn">
    <view class="fs-notice">由本门店药师或客服人工回复，不是 AI；不提供急救、诊断或医生开方。</view>
    <s-pharmacy-state v-if="!loggedIn" title="登录后联系门店" action="去登录" @retry="go('login')" />
    <template v-else>
      <template v-if="!conversation">
        <view class="fs-section"><view class="fs-title">联系当前门店</view><view class="fs-muted fs-gap">{{ api.store.name }} · 消息送达不代表已有人员接待，请等待人工回复。</view>
          <button class="fs-primary fs-gap" :disabled="busy" @tap="start('PHARMACIST')">药师文字咨询</button>
          <button class="fs-outline fs-gap" :disabled="busy" @tap="start('SERVICE')">门店客服咨询</button>
          <view class="fs-muted fs-gap">医生咨询仅为原型模拟，尚无真实医生服务，暂不开放。</view></view>
        <view class="fs-section fs-title">我的会话</view>
        <s-pharmacy-state v-if="loading || error || !conversations.length" :loading="loading" :error="error" title="暂无咨询会话" @retry="loadList" />
        <view v-for="item in conversations" :key="item.id" class="fs-section" @tap="enter(item.id)"><view class="fs-between"><text>{{ label(item.kind) }} · 门店 {{ item.storeId }}</text><text v-if="item.unread" class="fs-tag">{{ item.unread }} 条未读</text></view><view class="fs-muted fs-gap">{{ item.attended ? '已接待' : '等待接待' }} · {{ formatDate(item.updateTime) }}</view></view>
        <button v-if="conversations.length < total" class="fs-outline fs-gap" :disabled="loading" @tap="loadList(true)">更多会话</button>
      </template>
      <template v-else>
        <view class="fs-section fs-between"><text class="fs-title">{{ label(conversation.kind) }}</text><button class="fs-text-btn" :disabled="busy" @tap="back">会话列表</button></view>
        <view class="fs-notice">{{ conversation.attended ? '接待人员已领取，回复会保存在本会话。' : '暂无人员接待，您可以留言；不会自动生成回复。' }}</view>
        <s-pharmacy-state v-if="loading || error" :loading="loading" :error="error" @retry="refresh" />
        <button v-if="hasOlder" class="fs-outline fs-gap" :disabled="loading || polling" @tap="older">查看更早消息</button>
        <view v-if="!messages.length && !loading" class="fs-section fs-muted">请发送文字说明，不要发送身份证号、密码或支付凭据。处方材料请使用私有处方入口。</view>
        <view v-for="message in messages" :key="message.id" class="fs-section"><view :class="['bubble', { mine: message.senderType === 'MEMBER' }]"><view class="fs-muted">{{ message.senderName }} · {{ formatDate(message.createTime) }}</view><view class="fs-gap message-text">{{ message.content }}</view></view></view>
        <view v-if="failed && !busy" class="fs-section"><view class="fs-error">发送结果未确认，点击重试会沿用同一请求编号。</view><view class="message-text fs-gap">{{ failed.content }}</view><button class="fs-outline fs-gap" :disabled="busy" @tap="send(failed)">重试发送</button><button class="fs-text-btn fs-gap" :disabled="busy" @tap="discard">放弃本次发送</button></view>
      </template>
    </template>
    <view v-if="conversation && loggedIn" class="fs-dock compose"><textarea v-model="input" maxlength="1000" :disabled="busy || !!failed" placeholder="输入文字，等待门店人工回复" /><button class="fs-primary" :disabled="busy || !!failed || !input.trim()" :loading="busy" @tap="send()">发送</button></view>
  </s-pharmacy-page>
</template>
<script setup>
  import { ref } from 'vue';
  import { onShow, onHide, onUnload } from '@dcloudio/uni-app';
  import api from '@/sheep/api/pharmacy/client';
  import { accountKey, go, toast } from './usePharmacy';
  import { formatDate } from '@/sheep/api/pharmacy/common';
  const loggedIn=ref(false), conversations=ref([]), total=ref(0), conversation=ref(null), messages=ref([]), input=ref(''), failed=ref(null);
  const loading=ref(false), busy=ref(false), polling=ref(false), error=ref(''), hasOlder=ref(false);
  let owner='', generation=0, listPage=1, timer, visible=false, opening=null;
  const label=kind=>kind==='PHARMACIST'?'药师文字咨询':'门店客服';
  const current=(g,id)=>visible && g===generation && owner===accountKey() && (!id || conversation.value?.id===id);
  const stop=()=>{visible=false;generation++;opening=null;clearTimeout(timer);loading.value=false;polling.value=false;busy.value=false;};
  const schedule=()=>{clearTimeout(timer);if(visible && conversation.value)timer=setTimeout(async()=>{await refresh(true);schedule();},5000);};
  function clear(){generation++;opening=null;conversation.value=null;messages.value=[];conversations.value=[];input.value='';failed.value=null;error.value='';busy.value=false;loading.value=false;polling.value=false;}
  async function loadList(more=false){if(loading.value)return;const g=generation;loading.value=true;error.value='';try{const result=await api.consultationPage(more?listPage+1:1);if(!current(g))return;conversations.value=more?[...conversations.value,...result.list]:result.list;total.value=result.total;listPage=more?listPage+1:1;}catch(e){if(current(g))error.value=e.message;}finally{if(current(g))loading.value=false;}}
  async function start(kind){if(busy.value)return;const g=generation,request={};opening=request;busy.value=true;try{const id=await api.openConsultation(kind);if(current(g))await enter(id);}catch(e){if(current(g))toast(e.message);}finally{if(opening===request){opening=null;busy.value=false;}}}
  async function enter(id){generation++;const g=generation;clearTimeout(timer);loading.value=true;error.value='';failed.value=null;input.value='';messages.value=[];try{const result=await api.consultationMessages(id);if(!current(g))return;conversation.value=result.conversation;messages.value=result.list;hasOlder.value=result.hasMore;await markRead(g,id,result.list);schedule();}catch(e){if(current(g))error.value=e.message;}finally{if(current(g))loading.value=false;}}
  async function markRead(g,id,list){if(list.length&&current(g,id))await api.readConsultation(id,list[list.length-1].id);}
  async function refresh(background=false){if(!conversation.value||polling.value||loading.value)return;const g=generation,id=conversation.value.id;polling.value=true;if(!background)error.value='';try{let result;do{result=await api.consultationMessages(id,{after:messages.value.slice(-1)[0]?.id||0});if(!current(g,id))return;conversation.value=result.conversation;const known=new Set(messages.value.map(m=>m.id));messages.value.push(...result.list.filter(m=>!known.has(m.id)));await markRead(g,id,result.list);}while(result.hasMore&&result.list.length&&visible);if(current(g,id))error.value='';}catch(e){if(current(g,id))error.value=e.message;}finally{if(current(g,id))polling.value=false;}}
  async function older(){if(loading.value||polling.value||!messages.value.length)return;const g=generation,id=conversation.value.id;loading.value=true;try{const result=await api.consultationMessages(id,{before:messages.value[0].id});if(current(g,id)){messages.value=[...result.list,...messages.value];hasOlder.value=result.hasMore;}}catch(e){if(current(g,id))error.value=e.message;}finally{if(current(g,id))loading.value=false;}}
  async function send(retry){if(busy.value||!conversation.value)return;const content=retry?.content||input.value.trim();if(!content)return;const g=generation,id=conversation.value.id;const request=retry||{conversationId:id,clientRequestId:`text_${Date.now()}_${Math.random().toString(36).slice(2,10)}`,content};failed.value=request;busy.value=true;try{await api.sendConsultation(request);if(!current(g,id))return;failed.value=null;input.value='';await refresh();}catch(e){if(current(g,id))toast(e.message);}finally{if(current(g,id))busy.value=false;}}
  function back(){clearTimeout(timer);generation++;conversation.value=null;messages.value=[];failed.value=null;input.value='';loading.value=false;polling.value=false;loadList();}
  function discard(){failed.value=null;input.value='';}
  onShow(()=>{visible=true;loggedIn.value=!!api.session();const key=accountKey();if(key!==owner||!loggedIn.value)clear();owner=key;if(loggedIn.value){if(conversation.value){refresh();schedule();}else loadList();}});
  onHide(stop);onUnload(()=>{stop();generation++;});
</script>
<style scoped>
  .compose{align-items:flex-end}.compose textarea{flex:1;min-width:0;height:56px;font-size:15px;padding:10px}.bubble{padding:14px;background:#f4f6f2;border-radius:12px;margin-right:24px}.bubble.mine{background:#edf5f0;margin-left:24px;margin-right:0}.message-text{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}
</style>
