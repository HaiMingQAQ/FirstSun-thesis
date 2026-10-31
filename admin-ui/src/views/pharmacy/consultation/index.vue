<template>
  <ContentWrap>
    <el-alert
      title="门店人工文字咨询：仅本店在职人员接待；药师会话另校验执业资质。不支持诊断、开方或自动回复。类型、接待状态和未读只筛选当前页，接口不提供这些跨页条件，也不能查看其他门店。"
      type="info"
      :closable="false"
    />
    <el-form :inline="true" class="mt-4" @submit.prevent>
      <el-form-item label="咨询类型">
        <el-select v-model="kindFilter" clearable placeholder="全部" class="!w-140px">
          <el-option label="药师咨询" value="PHARMACIST" />
          <el-option label="门店客服" value="SERVICE" />
        </el-select>
      </el-form-item>
      <el-form-item label="接待状态">
        <el-select v-model="statusFilter" clearable placeholder="全部" class="!w-150px">
          <el-option label="等待接待" value="waiting" />
          <el-option label="我接待中" value="mine" />
          <el-option label="已由他人接待" value="other" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-checkbox v-model="unreadOnly">仅看未读</el-checkbox>
      </el-form-item>
      <el-form-item>
        <el-button :loading="loading" @click="load">刷新接待列表</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mt-4" />
    <el-table v-loading="loading" :data="visibleConversations" class="mt-4" empty-text="当前页没有符合筛选的会话">
      <el-table-column prop="id" label="会话" width="90" />
      <el-table-column prop="storeId" label="门店" width="90" />
      <el-table-column label="咨询类型" min-width="110">
        <template #default="{ row }">{{ kindLabel(row.kind) }}</template>
      </el-table-column>
      <el-table-column label="接待状态" min-width="130">
        <template #default="{ row }">{{ receptionLabel(row) }}</template>
      </el-table-column>
      <el-table-column label="未读" width="80">
        <template #default="{ row }">
          <el-tag v-if="Number(row.unread) > 0" type="danger">{{ row.unread }}</el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column label="最近更新" min-width="170">
        <template #default="{ row }">{{ formatEpoch(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">查看会话</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="mt-4 page-note">当前筛选未读 {{ filteredUnread }} 条。分页以服务端总数为准，每页 20 条。</div>
    <el-pagination v-model:current-page="pageNo" :page-size="20" :total="total" layout="total, prev, pager, next" class="mt-4" @current-change="load" />
  </ContentWrap>
  <el-dialog v-model="dialog" title="门店人工接待" width="min(760px, 94vw)" @closed="close">
    <template v-if="conversation">
      <el-alert
        :title="conversation.mine ? '已由您接待，回复将持久保存并增加顾客未读。' : '尚未领取；领取后才可回复。已被他人领取的会话不能查看或回复。'"
        type="info"
        :closable="false"
      />
      <div class="mt-4">{{ kindLabel(conversation.kind) }} · {{ receptionLabel(conversation) }} · 未读 {{ conversation.unread }}</div>
      <el-button v-if="!conversation.mine" v-hasPermi="['pharmacy:consultation:reply']" :loading="busy" class="mt-4" @click="take">领取接待</el-button>
      <el-alert v-if="threadError" :title="threadError" type="error" :closable="false" class="mt-4" />
      <el-button v-if="hasOlder" class="mt-4" :disabled="polling || busy" @click="older">查看更早消息</el-button>
      <div class="messages" tabindex="0" aria-label="会话消息">
        <el-empty v-if="!thread.length && !threadError" description="顾客尚未发送文字" />
        <div v-for="message in thread" :key="message.id" :class="['message', { staff: message.senderType === 'STAFF' }]">
          <div class="sender">{{ message.senderName }} · {{ formatEpoch(message.createTime) }}</div>
          <div class="body">{{ message.content }}</div>
        </div>
      </div>
      <template v-if="conversation.mine">
        <el-alert v-if="failed" title="发送结果未确认，请沿用原编号重试，避免重复发送。" type="warning" :closable="false" />
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          :disabled="busy || !!failed"
          placeholder="请输入人工回复；不进行诊断或开方"
          @keydown.ctrl.enter.prevent="reply"
          @keydown.meta.enter.prevent="reply"
        />
        <el-button v-hasPermi="['pharmacy:consultation:reply']" type="primary" class="mt-4" :loading="busy" :disabled="!input.trim() && !failed" @click="reply">{{ failed ? '重试发送' : '发送回复' }}</el-button>
        <el-button v-if="failed" :disabled="busy" @click="discard">放弃本次发送</el-button>
      </template>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import * as Api from '@/api/pharmacy/consultation'
import {
  createSerialQueue,
  errorText,
  filterConversations,
  formatEpoch,
  isCurrent,
  kindLabel,
  mergeMessages,
  nextSend,
  pendingSends,
  prependMessages,
  receptionLabel,
  replaceConversation,
  type SendRequest
} from './reception'

defineOptions({ name: 'PharmacyConsultation' })

const conversations = ref<Api.Conversation[]>([])
const total = ref(0)
const pageNo = ref(1)
const loading = ref(false)
const error = ref('')
const kindFilter = ref('')
const statusFilter = ref<'' | 'waiting' | 'mine' | 'other'>('')
const unreadOnly = ref(false)
const visibleConversations = computed(() =>
  filterConversations(conversations.value, { kind: kindFilter.value, status: statusFilter.value, unreadOnly: unreadOnly.value })
)
const filteredUnread = computed(() => visibleConversations.value.reduce((sum, item) => sum + Number(item.unread || 0), 0))

const dialog = ref(false)
const conversation = ref<Api.Conversation>()
const thread = ref<Api.Message[]>([])
const threadError = ref('')
const input = ref('')
const busy = ref(false)
const polling = ref(false)
const hasOlder = ref(false)
const failed = ref<SendRequest>()
const enqueue = createSerialQueue()
let generation = 0
let listGeneration = 0
let timer: ReturnType<typeof setTimeout> | undefined
let active = true

const current = (expected: number, id: number) => isCurrent(generation, expected, dialog.value, conversation.value?.id, id)

function syncList(next: Api.Conversation) {
  conversations.value = replaceConversation(conversations.value, next)
}

async function load() {
  const ticket = ++listGeneration
  loading.value = true
  error.value = ''
  try {
    const result = await Api.page(pageNo.value)
    if (ticket !== listGeneration) return
    conversations.value = result.list || []
    total.value = Number(result.total) || 0
  } catch (reason) {
    if (ticket !== listGeneration) return
    error.value = errorText(reason, '接待列表加载失败')
  } finally {
    if (ticket === listGeneration) loading.value = false
  }
}

async function markRead(expected: number, id: number, list: Api.Message[]) {
  if (!current(expected, id) || !conversation.value?.mine || !list.length) return true
  try {
    await Api.read(id, list[list.length - 1].id)
    return true
  } catch (reason) {
    if (current(expected, id)) threadError.value = errorText(reason, '已读状态更新失败，消息仍已显示')
    return false
  }
}

async function refreshBody() {
  if (!conversation.value || !dialog.value) return
  const id = conversation.value.id
  const expected = generation
  polling.value = true
  try {
    let guard = 0
    let readOk = true
    let after = 0
    let result: Api.Thread
    do {
      after = thread.value.length ? thread.value[thread.value.length - 1].id : 0
      result = await Api.messages(id, { after })
      if (!current(expected, id)) return
      // after 为 0 时接口返回最新一页，hasMore 表示还有更早消息，不能当成向后翻页。
      if (after === 0) hasOlder.value = result.hasMore
      conversation.value = result.conversation
      syncList(result.conversation)
      thread.value = mergeMessages(thread.value, result.list)
      readOk = (await markRead(expected, id, result.list)) && readOk
      guard++
    } while (result.hasMore && result.list.length && active && after > 0 && guard < 20)
    if (current(expected, id) && readOk && !failed.value) threadError.value = ''
  } catch (reason) {
    if (current(expected, id)) threadError.value = errorText(reason, '消息刷新失败')
  } finally {
    if (current(expected, id)) polling.value = false
  }
}

function schedule() {
  clearTimeout(timer)
  if (active && dialog.value) {
    timer = setTimeout(() => {
      enqueue(() => refreshBody()).finally(() => schedule())
    }, 5000)
  }
}

async function open(row: Api.Conversation) {
  generation++
  clearTimeout(timer)
  const expected = generation
  const id = row.id
  conversation.value = row
  thread.value = []
  threadError.value = ''
  hasOlder.value = false
  polling.value = false
  busy.value = false
  const pending = pendingSends.get(id)
  failed.value = pending
  input.value = pending?.content || ''
  dialog.value = true
  try {
    const result = await Api.messages(id)
    if (!current(expected, id)) return
    conversation.value = result.conversation
    syncList(result.conversation)
    thread.value = result.list
    hasOlder.value = result.hasMore
    await markRead(expected, id, result.list)
  } catch (reason) {
    if (current(expected, id)) threadError.value = errorText(reason, '会话加载失败')
  }
  if (current(expected, id)) schedule()
}

async function older() {
  if (!conversation.value || polling.value || busy.value || !thread.value.length) return
  const id = conversation.value.id
  const expected = generation
  await enqueue(async () => {
    if (!current(expected, id) || !thread.value.length) return
    polling.value = true
    try {
      const result = await Api.messages(id, { before: thread.value[0].id })
      if (!current(expected, id)) return
      thread.value = prependMessages(thread.value, result.list)
      hasOlder.value = result.hasMore
      conversation.value = result.conversation
      syncList(result.conversation)
    } catch (reason) {
      if (current(expected, id)) threadError.value = errorText(reason, '历史消息加载失败')
    } finally {
      if (current(expected, id)) polling.value = false
    }
  })
}

async function take() {
  if (busy.value || !conversation.value || conversation.value.mine) return
  const id = conversation.value.id
  const expected = generation
  busy.value = true
  try {
    await Api.claim(id)
    if (!current(expected, id) || !conversation.value) return
    conversation.value = { ...conversation.value, mine: true, attended: true }
    syncList(conversation.value)
    await enqueue(() => refreshBody())
  } catch (reason) {
    if (current(expected, id)) threadError.value = errorText(reason, '领取失败')
  } finally {
    if (expected === generation && conversation.value?.id === id) busy.value = false
  }
}

async function reply() {
  if (busy.value || !conversation.value?.mine) return
  const id = conversation.value.id
  const expected = generation
  const request = nextSend(id, input.value, failed.value)
  if (!request) return
  busy.value = true
  try {
    await Api.send(request)
    pendingSends.clear(id)
    if (!current(expected, id)) return
    failed.value = undefined
    input.value = ''
    await enqueue(() => refreshBody())
  } catch (reason) {
    pendingSends.remember(request)
    if (!current(expected, id)) return
    failed.value = request
    threadError.value = errorText(reason, '发送结果未确认，请沿用原编号重试')
  } finally {
    if (expected === generation && conversation.value?.id === id) busy.value = false
  }
}

function discard() {
  if (!conversation.value) return
  pendingSends.clear(conversation.value.id)
  failed.value = undefined
  input.value = ''
  threadError.value = ''
}

function close() {
  generation++
  clearTimeout(timer)
  thread.value = []
  conversation.value = undefined
  input.value = ''
  failed.value = undefined
  threadError.value = ''
  polling.value = false
  busy.value = false
  if (active) load()
}

function leave() {
  active = false
  clearTimeout(timer)
  generation++
  if (dialog.value) dialog.value = false
}

onMounted(load)
onActivated(() => {
  active = true
  load()
  schedule()
})
onDeactivated(leave)
onBeforeUnmount(leave)
</script>
<style scoped>
.page-note { color: #617368; font-size: 13px; }
.messages { max-height: 440px; overflow-y: auto; margin: 16px 0; }
.message { padding: 14px; margin: 12px 40px 12px 0; background: #f4f6f2; border-radius: 12px; }
.message.staff { margin-left: 40px; margin-right: 0; background: #edf5f0; }
.sender { font-size: 12px; color: #617368; }
.body { margin-top: 8px; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
</style>
