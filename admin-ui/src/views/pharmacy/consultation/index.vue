<template>
  <ContentWrap>
    <el-alert title="门店人工文字咨询：仅本店在职人员接待；药师会话另校验执业资质。不支持诊断、开方或自动回复。" type="info" :closable="false" />
    <el-button class="mt-4" :loading="loading" @click="load">刷新接待列表</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mt-4" />
    <el-table v-loading="loading" :data="conversations" class="mt-4">
      <el-table-column prop="id" label="会话" width="90" /><el-table-column prop="storeId" label="门店" width="100" />
      <el-table-column label="咨询类型"><template #default="{ row }">{{ row.kind === 'PHARMACIST' ? '药师咨询' : '门店客服' }}</template></el-table-column>
      <el-table-column label="接待状态"><template #default="{ row }">{{ row.mine ? '我接待中' : '等待接待' }}</template></el-table-column>
      <el-table-column prop="unread" label="未读" width="90" />
      <el-table-column label="操作" width="170"><template #default="{ row }"><el-button link type="primary" @click="open(row)">查看会话</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="pageNo" :page-size="20" :total="total" layout="total, prev, pager, next" class="mt-4" @current-change="load" />
  </ContentWrap>
  <el-dialog v-model="dialog" title="门店人工接待" width="min(760px, 94vw)" @closed="close">
    <template v-if="conversation">
      <el-alert :title="conversation.mine ? '已由您接待，回复将持久保存并增加顾客未读。' : '尚未领取；领取后才可回复，其他接待人员不能访问已领取会话。'" type="info" :closable="false" />
      <el-button v-if="!conversation.mine" v-hasPermi="['pharmacy:consultation:reply']" :loading="busy" class="mt-4" @click="take">领取接待</el-button>
      <el-alert v-if="threadError" :title="threadError" type="error" :closable="false" class="mt-4" />
      <el-button v-if="hasOlder" class="mt-4" :disabled="polling" @click="older">查看更早消息</el-button>
      <div class="messages">
        <el-empty v-if="!thread.length" description="顾客尚未发送文字" />
        <div v-for="message in thread" :key="message.id" :class="['message', { staff: message.senderType === 'STAFF' }]">
          <div class="sender">{{ message.senderName }} · {{ new Date(message.createTime).toLocaleString() }}</div><div class="body">{{ message.content }}</div>
        </div>
      </div>
      <template v-if="conversation.mine">
        <el-alert v-if="failed" title="发送结果未确认，请沿用原编号重试，避免重复发送。" type="warning" :closable="false" />
        <el-input v-model="input" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="busy || !!failed" placeholder="请输入人工回复；不进行诊断或开方" />
        <el-button v-hasPermi="['pharmacy:consultation:reply']" type="primary" class="mt-4" :loading="busy" :disabled="!input.trim() && !failed" @click="reply">{{ failed ? '重试发送' : '发送回复' }}</el-button>
        <el-button v-if="failed" :disabled="busy" @click="discard">放弃本次发送</el-button>
      </template>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import * as Api from '@/api/pharmacy/consultation'
defineOptions({ name: 'PharmacyConsultation' })
const conversations=ref<Api.Conversation[]>([]), total=ref(0), pageNo=ref(1), loading=ref(false), error=ref('')
const dialog=ref(false), conversation=ref<Api.Conversation>(), thread=ref<Api.Message[]>([]), threadError=ref(''), input=ref(''), busy=ref(false), polling=ref(false), hasOlder=ref(false)
type Send = { conversationId: number; clientRequestId: string; content: string }
const failed=ref<Send>(); let generation=0, timer: ReturnType<typeof setTimeout> | undefined, active=true
const current=(g: number,id: number)=>g===generation && dialog.value && conversation.value?.id===id
async function load(){if(loading.value)return;loading.value=true;error.value='';try{const result=await Api.page(pageNo.value);conversations.value=result.list;total.value=result.total}catch(e:any){error.value=e.message||'接待列表加载失败'}finally{loading.value=false}}
async function open(row: Api.Conversation){generation++;clearTimeout(timer);const g=generation;conversation.value=row;thread.value=[];input.value='';failed.value=undefined;threadError.value='';hasOlder.value=false;dialog.value=true;polling.value=false;busy.value=false;try{const result=await Api.messages(row.id);if(!current(g,row.id))return;conversation.value=result.conversation;thread.value=result.list;hasOlder.value=result.hasMore;await markRead(g,row.id,result.list)}catch(e:any){if(current(g,row.id))threadError.value=e.message||'会话加载失败'}schedule()}
async function markRead(g:number,id:number,list:Api.Message[]){if(current(g,id)&&conversation.value?.mine&&list.length)await Api.read(id,list[list.length-1].id)}
function schedule(){clearTimeout(timer);if(active&&dialog.value)timer=setTimeout(async()=>{await refresh();schedule()},5000)}
async function refresh(){if(!conversation.value||polling.value)return;const id=conversation.value.id,g=generation;polling.value=true;try{let result:Api.Thread;do{result=await Api.messages(id,{after:thread.value.length?thread.value[thread.value.length-1].id:0});if(!current(g,id))return;conversation.value=result.conversation;const known=new Set(thread.value.map(m=>m.id));thread.value.push(...result.list.filter(m=>!known.has(m.id)));await markRead(g,id,result.list)}while(result.hasMore&&result.list.length&&active);if(current(g,id))threadError.value=''}catch(e:any){if(current(g,id))threadError.value=e.message||'消息刷新失败'}finally{if(current(g,id))polling.value=false}}
async function older(){if(!conversation.value||polling.value||!thread.value.length)return;const id=conversation.value.id,g=generation;polling.value=true;try{const result=await Api.messages(id,{before:thread.value[0].id});if(current(g,id)){thread.value=[...result.list,...thread.value];hasOlder.value=result.hasMore}}catch(e:any){if(current(g,id))threadError.value=e.message||'历史消息加载失败'}finally{if(current(g,id))polling.value=false}}
async function take(){if(busy.value||!conversation.value)return;const id=conversation.value.id,g=generation;busy.value=true;try{await Api.claim(id);if(current(g,id))await refresh()}catch(e:any){if(current(g,id))threadError.value=e.message||'领取失败'}finally{if(current(g,id))busy.value=false}}
async function reply(){if(busy.value||!conversation.value)return;const id=conversation.value.id,g=generation;const request=failed.value||{conversationId:id,clientRequestId:`staff_${Date.now()}_${Math.random().toString(36).slice(2,10)}`,content:input.value.trim()};if(!request.content)return;busy.value=true;try{await Api.send(request);if(!current(g,id))return;failed.value=undefined;input.value='';await refresh()}catch(e:any){if(current(g,id)){failed.value=request;threadError.value=e.message||'发送结果未确认'}}finally{if(current(g,id))busy.value=false}}
function discard(){failed.value=undefined;input.value=''}
function close(){generation++;clearTimeout(timer);thread.value=[];conversation.value=undefined;input.value='';failed.value=undefined;polling.value=false;busy.value=false;load()}
onMounted(load);onActivated(()=>{active=true;load();schedule()});onDeactivated(()=>{active=false;clearTimeout(timer);dialog.value=false;close()});onBeforeUnmount(()=>{active=false;clearTimeout(timer);generation++})
</script>
<style scoped>
.messages{max-height:440px;overflow-y:auto;margin:16px 0}.message{padding:14px;margin:12px 40px 12px 0;background:#f4f6f2;border-radius:12px}.message.staff{margin-left:40px;margin-right:0;background:#edf5f0}.sender{font-size:12px;color:#617368}.body{margin-top:8px;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}
</style>
