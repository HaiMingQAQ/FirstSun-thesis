<template>
  <div class="ai-page" :class="{ embedded }">
    <el-card class="conversations" shadow="never">
      <template #header>
        <div class="header"
          ><span>AI 助手</span><el-button link @click="newConversation">新对话</el-button></div
        >
      </template>
      <div
        v-for="item in conversations"
        :key="item.id"
        class="conversation"
        :class="{ active: item.id === activeId }"
        @click="selectConversation(item.id)"
      >
        {{ item.title }}
      </div>
      <el-divider>待确认操作</el-divider>
      <div v-for="command in commands" :key="command.commandId" class="command-link">
        #{{ command.commandId }} {{ commandLabel(command.tool) }} ·
        {{ statusLabel(command.status) }}
      </div>
    </el-card>

    <el-card class="chat" shadow="never">
      <div ref="messageArea" class="messages">
        <el-empty
          v-if="messages.length === 0"
          description="可查询药品、当前门店库存与预警，或生成药品变更和采购草稿预览"
        />
        <div v-for="(item, index) in messages" :key="index" class="message" :class="item.role">
          <div class="bubble">
            <MarkdownView v-if="item.text" :content="item.text" />
            <el-alert
              v-if="item.errorMessage"
              type="error"
              :closable="false"
              :title="item.errorMessage"
              :description="item.requestId ? `追踪编号：${item.requestId}` : undefined"
              show-icon
            />
            <el-alert
              v-if="item.stopped"
              type="info"
              :closable="false"
              title="已停止生成；已返回的工具结果或预览仍可查看，写操作需要单独确认。"
            />
            <el-card v-if="item.toolResult" class="tool-card" shadow="never">
              <template #header>工具结果 · {{ item.toolResult.tool }}</template>
              <div>{{ summarizeResult(item.toolResult.result) }}</div>
            </el-card>
            <el-card v-if="item.preview" class="tool-card preview" shadow="never">
              <template #header>{{ commandLabel(item.preview.tool) }}预览</template>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="命令"
                  >#{{ item.preview.commandId }}</el-descriptions-item
                >
                <el-descriptions-item label="状态">{{
                  statusLabel(item.preview.status)
                }}</el-descriptions-item>
                <el-descriptions-item
                  v-if="item.preview.tool !== 'CREATE_PURCHASE_DRAFT'"
                  label="变更"
                  >{{ diffText(item.preview) }}</el-descriptions-item
                >
                <el-descriptions-item label="有效期">{{
                  previewDate(item.preview.expiresAt, 'YYYY-MM-DD HH:mm:ss')
                }}</el-descriptions-item>
              </el-descriptions>
              <template v-if="item.preview.tool === 'CREATE_PURCHASE_DRAFT'">
                <el-alert
                  type="info"
                  :closable="false"
                  title="确认后仅创建采购草稿；审批、发出和入库需要在采购业务页面另行操作。"
                />
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="门店"
                    >{{ item.preview.after?.display?.storeName }}（#{{
                      item.preview.after?.display?.storeId
                    }}）</el-descriptions-item
                  >
                  <el-descriptions-item label="供应商"
                    >{{ item.preview.after?.display?.supplier?.name }}（#{{
                      item.preview.after?.display?.supplier?.id
                    }}）</el-descriptions-item
                  >
                  <el-descriptions-item label="仓库">{{
                    warehouseText(item.preview.after?.display?.warehouse)
                  }}</el-descriptions-item>
                  <el-descriptions-item label="采购日期">{{
                    previewDate(item.preview.after?.display?.orderDate)
                  }}</el-descriptions-item>
                  <el-descriptions-item label="预计到货">{{
                    previewDate(item.preview.after?.display?.expectDate)
                  }}</el-descriptions-item>
                  <el-descriptions-item label="采购金额"
                    >{{ money(item.preview.after?.display?.totalAmount) }} 元</el-descriptions-item
                  >
                  <el-descriptions-item label="折扣金额"
                    >{{
                      money(item.preview.after?.display?.discountAmount)
                    }}
                    元</el-descriptions-item
                  >
                  <el-descriptions-item label="应付金额"
                    >{{
                      money(item.preview.after?.display?.payableAmount)
                    }}
                    元</el-descriptions-item
                  >
                </el-descriptions>
                <el-table :data="item.preview.after?.display?.lines || []" size="small" border>
                  <el-table-column label="药品" min-width="180">
                    <template #default="{ row }"
                      >{{ row.stock?.drugName }}（#{{ row.stock?.drugId }}）<br />{{
                        row.stock?.specification
                      }}</template
                    >
                  </el-table-column>
                  <el-table-column label="可用 / 下限" min-width="105"
                    ><template #default="{ row }"
                      >{{ row.stock?.availableQty }} / {{ row.stock?.minStock }}</template
                    ></el-table-column
                  >
                  <el-table-column prop="orderQty" label="采购数量" width="100" />
                  <el-table-column label="单价" width="100"
                    ><template #default="{ row }">{{
                      money(row.unitPrice)
                    }}</template></el-table-column
                  >
                  <el-table-column prop="discountRate" label="折扣率" width="90" />
                  <el-table-column label="金额" width="100"
                    ><template #default="{ row }">{{
                      money(row.amount)
                    }}</template></el-table-column
                  >
                </el-table>
                <el-button
                  v-if="item.preview.status === 'EXECUTED' && item.preview.result?.purchaseOrderId"
                  type="primary"
                  link
                  @click="router.push('/pharmacy-base/purchase/order')"
                  >查看采购草稿 #{{ item.preview.result.purchaseOrderId }}</el-button
                >
              </template>
              <div
                class="actions"
                v-if="item.preview.status === 'PENDING' && item.preview.confirmationToken"
              >
                <el-button
                  type="danger"
                  :loading="executing === item.preview.commandId"
                  @click="confirmCommand(item.preview)"
                  >确认{{ commandLabel(item.preview.tool) }}</el-button
                >
                <el-button
                  :disabled="executing === item.preview.commandId"
                  @click="cancelCommand(item.preview)"
                  >取消</el-button
                >
              </div>
              <el-alert
                v-else-if="item.preview.status === 'PENDING'"
                type="info"
                :closable="false"
                title="确认凭证不可用；请先核对操作状态，如需新操作，请重新生成预览"
              />
              <el-alert
                v-if="item.preview.errorMessage"
                type="error"
                :closable="false"
                :title="item.preview.errorMessage"
              />
            </el-card>
          </div>
        </div>
      </div>
      <div class="composer">
        <el-button v-if="canReadInventory" plain @click="inventoryPurchaseDialog?.open()"
          >库存预警 / 采购草稿</el-button
        >
        <el-input
          v-model="content"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="例如：查询阿莫西林；查看药品 1 的当前门店库存；把药品 1 的零售价改为 18.50 元"
          @keydown.ctrl.enter="send"
        />
        <div class="composer-actions">
          <span class="hint">Ctrl + Enter 发送；写操作必须再次确认</span>
          <el-button v-if="generating" @click="stop">停止生成</el-button>
          <el-button v-else type="primary" :disabled="!content.trim()" @click="send"
            >发送</el-button
          >
        </div>
      </div>
    </el-card>
    <InventoryPurchaseDialog ref="inventoryPurchaseDialog" @preview="appendPurchasePreview" />
  </div>
</template>

<script setup lang="ts">
import MarkdownView from '@/components/MarkdownView/index.vue'
import { PharmacyAiApi, PharmacyAiCommand } from '@/api/pharmacy/ai'
import InventoryPurchaseDialog from './InventoryPurchaseDialog.vue'
import { hasPermission } from '@/directives/permission/hasPermi'
import { formatDate } from '@/utils/formatTime'

withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })

type Message = {
  role: 'user' | 'assistant'
  text?: string
  toolResult?: any
  preview?: PharmacyAiCommand
  requestId?: string
  errorMessage?: string
  stopped?: boolean
}

type Conversation = {
  id: number
  title: string
  messages: Message[]
}

const message = useMessage()
const router = useRouter()
const inventoryPurchaseDialog = ref<InstanceType<typeof InventoryPurchaseDialog>>()
const canReadInventory = computed(
  () => hasPermission(['pharmacy:ai:chat']) && hasPermission(['pharmacy:inventory-batch:query'])
)
const initialId = Date.now()
const activeId = ref(initialId)
const conversations = ref<Conversation[]>([{ id: initialId, title: '新对话', messages: [] }])
const messages = computed<Message[]>({
  get: () => conversations.value.find((item) => item.id === activeId.value)?.messages || [],
  set: (value) => {
    const conversation = conversations.value.find((item) => item.id === activeId.value)
    if (conversation) conversation.messages = value
  }
})
const commands = ref<PharmacyAiCommand[]>([])
const content = ref('')
const generating = ref(false)
const executing = ref<number>()
const messageArea = ref<HTMLElement>()
let controller: AbortController | undefined
const DRAFT_KEY = 'pharmacy-ai-assistant-draft'
const HISTORY_KEY = 'pharmacy-ai-assistant-history'

watch(content, (value) => sessionStorage.setItem(DRAFT_KEY, value))
watch(
  [activeId, conversations],
  () => {
    const safeConversations = conversations.value.map((conversation) => ({
      ...conversation,
      messages: conversation.messages.map((item) => ({
        ...item,
        preview: item.preview ? { ...item.preview, confirmationToken: undefined } : undefined
      }))
    }))
    sessionStorage.setItem(
      HISTORY_KEY,
      JSON.stringify({ activeId: activeId.value, conversations: safeConversations })
    )
  },
  { deep: true }
)

const selectConversation = (id: number) => {
  activeId.value = id
  nextTick(() => messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight }))
}

const titleFromMessage = (text: string) => {
  const normalized = text.replace(/\s+/g, ' ').trim()
  return normalized.length > 18 ? `${normalized.slice(0, 18)}…` : normalized
}

const newConversation = () => {
  const emptyConversation = conversations.value.find((item) => item.messages.length === 0)
  if (emptyConversation) {
    selectConversation(emptyConversation.id)
    return
  }
  activeId.value = Date.now()
  conversations.value.unshift({ id: activeId.value, title: '新对话', messages: [] })
}

const send = async () => {
  const question = content.value.trim()
  if (!question || generating.value) return
  content.value = ''
  const conversation = conversations.value.find((item) => item.id === activeId.value)
  if (conversation?.messages.length === 0) conversation.title = titleFromMessage(question)
  messages.value.push({ role: 'user', text: question })
  const answer = reactive<Message>({ role: 'assistant', text: '' })
  messages.value.push(answer)
  generating.value = true
  const requestController = new AbortController()
  controller = requestController
  try {
    await PharmacyAiApi.stream(
      { conversationId: activeId.value, clientMessageId: crypto.randomUUID(), content: question },
      requestController.signal,
      (event) => {
        const data = event.data ? JSON.parse(event.data) : {}
        if (event.event === 'metadata') answer.requestId = data.requestId
        if (event.event === 'delta') answer.text = (answer.text || '') + (data.content || '')
        if (event.event === 'tool_result') answer.toolResult = data
        if (event.event === 'tool_preview') {
          answer.preview = data
          commands.value.unshift(data)
        }
        if (event.event === 'error') {
          answer.errorMessage = data.message || 'AI 未能完成本次请求，请稍后重试。'
          answer.requestId = data.requestId || answer.requestId
        }
        nextTick(() => messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight }))
      }
    )
  } catch (error: any) {
    if (!requestController.signal.aborted && error?.name !== 'AbortError')
      answer.errorMessage = '连接异常终止，回答可能不完整；请检查网络后重试。'
  } finally {
    if (requestController.signal.aborted) answer.stopped = true
    if (controller === requestController) {
      generating.value = false
      controller = undefined
    }
  }
}

const stop = () => {
  controller?.abort()
  generating.value = false
}

const appendPurchasePreview = (preview: PharmacyAiCommand) => {
  messages.value.push({
    role: 'assistant',
    preview,
    text: '仅采购草稿预览，确认前不会创建，不会审批、发出或入库。'
  })
  commands.value.unshift(preview)
  nextTick(() => messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight }))
}

const refreshCommandState = async (command: PharmacyAiCommand) => {
  try {
    const current = ((await PharmacyAiApi.recentCommands()) || []).find(
      (item: PharmacyAiCommand) => item.commandId === command.commandId
    )
    if (current) {
      command.status = current.status
      command.errorMessage = current.errorMessage || command.errorMessage
    }
  } catch {
    /* 保留当前状态与错误提示，确认凭证已移除，避免自动重复确认。 */
  }
}

const confirmCommand = async (command: PharmacyAiCommand) => {
  if (executing.value === command.commandId || command.status !== 'PENDING') return
  if (!command.confirmationToken) return message.error('确认凭证不存在或页面已刷新，请重新生成预览')
  executing.value = command.commandId
  try {
    const result = await PharmacyAiApi.confirm(command.commandId, command.confirmationToken)
    command.status = 'EXECUTED'
    command.result = result
    command.confirmationToken = undefined
    command.errorMessage = undefined
    message.success(
      command.tool === 'CREATE_PURCHASE_DRAFT'
        ? `已创建采购草稿 #${result.purchaseOrderId}`
        : `${commandLabel(command.tool)}成功`
    )
  } catch (error: any) {
    command.confirmationToken = undefined
    command.errorMessage = error?.message || '确认未能完成，请先核对操作状态。'
    await refreshCommandState(command)
  } finally {
    executing.value = undefined
  }
}

const cancelCommand = async (command: PharmacyAiCommand) => {
  if (executing.value === command.commandId) return
  executing.value = command.commandId
  try {
    await PharmacyAiApi.cancel(command.commandId)
    command.status = 'CANCELLED'
    command.errorMessage = undefined
  } catch (error: any) {
    command.errorMessage = error?.message || '取消未能完成，请先核对操作状态。'
    await refreshCommandState(command)
  } finally {
    command.confirmationToken = undefined
    executing.value = undefined
  }
}

const commandLabel = (tool: string) =>
  ({
    CREATE_DRUG: '新增药品',
    UPDATE_DRUG: '修改药品',
    DELETE_DRUG: '删除药品',
    CREATE_PURCHASE_DRAFT: '创建采购草稿'
  })[tool] || tool
const money = (value: unknown) => (value == null ? '-' : Number(value).toFixed(2))
const previewDate = (value: any, pattern = 'YYYY-MM-DD') => {
  if (value == null) return '未指定'
  const date = Array.isArray(value)
    ? new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0, value[5] || 0)
    : value
  return formatDate(date, pattern)
}
const warehouseText = (value: any) =>
  typeof value === 'string'
    ? value
    : value?.whName
      ? `${value.whName}（#${value.id}）`
      : '未指定，收货时须确定'
const statusLabel = (status: string) =>
  ({
    PENDING: '待确认',
    EXECUTING: '执行中',
    EXECUTED: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    EXPIRED: '已过期'
  })[status] || status
const diffText = (command: PharmacyAiCommand) => {
  if (command.tool === 'CREATE_DRUG')
    return `将新增：${command.after?.genericName || command.after?.drugCode || '药品'}`
  if (command.tool === 'DELETE_DRUG')
    return `将删除：${command.before?.genericName || command.before?.drugCode || command.before?.id}`
  const changed = Object.keys(command.after || {}).filter(
    (key) => JSON.stringify(command.before?.[key]) !== JSON.stringify(command.after?.[key])
  )
  return (
    changed
      .map((key) => `${key}: ${command.before?.[key] ?? '-'} → ${command.after?.[key] ?? '-'}`)
      .join('；') || '无字段变化'
  )
}
const summarizeResult = (result: any) => {
  if (Array.isArray(result)) return `共返回 ${result.length} 条药品记录。`
  if (result?.rule)
    return `当前门店有 ${result.items?.length || 0} 条库存预警${result.hasMore ? '（还有更多）' : ''}。${result.rule}`
  if (result?.items) return `当前门店返回 ${result.items.length} 个药品的库存摘要。`
  if (result?.found === false) return '未找到对应药品。'
  return `已返回药品 ${result?.genericName || result?.drugCode || ''} 的详情。`
}

onMounted(async () => {
  content.value = sessionStorage.getItem(DRAFT_KEY) || ''
  const history = sessionStorage.getItem(HISTORY_KEY)
  if (history) {
    try {
      const saved = JSON.parse(history)
      if (Array.isArray(saved.conversations) && saved.conversations.length > 0) {
        conversations.value = saved.conversations.map((conversation: Conversation) => {
          const firstUserMessage = conversation.messages?.find((item) => item.role === 'user')?.text
          return {
            ...conversation,
            title:
              conversation.title === '新对话' && firstUserMessage
                ? titleFromMessage(firstUserMessage)
                : conversation.title,
            messages: conversation.messages || []
          }
        })
        activeId.value = conversations.value.some((item) => item.id === saved.activeId)
          ? saved.activeId
          : conversations.value[0].id
      }
    } catch {
      sessionStorage.removeItem(HISTORY_KEY)
    }
  }
  commands.value = (await PharmacyAiApi.recentCommands()) || []
})
onBeforeUnmount(stop)
</script>

<style scoped>
.ai-page {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  height: calc(100vh - 120px);
  padding: 16px;
}

.ai-page.embedded {
  width: 100%;
  height: 100%;
  padding: 12px;
  grid-template-columns: minmax(180px, 28%) minmax(0, 1fr);
  box-sizing: border-box;
}

.conversations,
.chat {
  height: 100%;
}

.header,
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.conversation,
.command-link {
  padding: 10px;
  cursor: pointer;
  border-radius: 6px;
}

.conversation.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.command-link {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.chat :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: calc(100% - 40px);
}

.messages {
  padding: 8px 20px;
  overflow: auto;
  flex: 1;
}

.message {
  display: flex;
  margin: 16px 0;
}

.message.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-radius: 10px;
  overflow-wrap: anywhere;
}

.user .bubble {
  color: white;
  background: var(--el-color-primary);
}

.tool-card {
  min-width: 440px;
  margin-top: 10px;
}

.preview {
  border-color: var(--el-color-warning);
}

.actions,
.composer-actions {
  margin-top: 12px;
}

.composer {
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color);
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (width <= 900px) {
  .ai-page.embedded {
    grid-template-columns: 1fr;
  }

  .ai-page.embedded .conversations {
    display: none;
  }

  .tool-card {
    min-width: 0;
  }
}
</style>
