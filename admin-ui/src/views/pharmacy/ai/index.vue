<template>
  <div class="ai-page" :class="{ embedded }">
    <el-card class="chat" shadow="never">
      <div class="chat-toolbar">
        <el-button plain :disabled="generating || formSubmitting" @click="newConversation">新对话</el-button>
        <el-button v-if="canConfirm('CREATE_DRUG')" plain :disabled="generating || formSubmitting" @click="appendDrugForm()">新增药品</el-button>
        <el-popover
          v-if="canMaintainIndex"
          placement="bottom-end"
          title="索引维护"
          :width="280"
          trigger="click"
        >
          <template #reference><el-button plain>设置</el-button></template>
          <p>为当前租户重建已配置语料的知识索引。</p>
          <el-button :loading="reindexingKnowledge" @click="reindexKnowledge"
            >重建知识索引</el-button
          >
        </el-popover>
      </div>
      <el-collapse class="mobile-commands">
        <el-collapse-item title="会话历史" name="history">
          <div v-for="item in visibleConversations" :key="item.id" class="history-item">
            <button type="button" class="conversation" :disabled="generating || formSubmitting || !!executing" @click="selectConversation(item.id)">{{ item.title }}</button>
            <el-button v-if="item.messages.length" text :disabled="generating || formSubmitting || !!executing" @click="manageConversation(item, 'rename')">重命名</el-button>
            <el-button v-if="item.messages.length" text :disabled="generating || formSubmitting || !!executing" @click="manageConversation(item, 'delete')">删除</el-button>
          </div>
        </el-collapse-item>
        <el-collapse-item title="操作记录" name="commands">
          <div v-for="command in commands" :key="command.commandId" class="command-link">
            <strong :title="commandObject(command)">{{ commandObject(command) }}</strong>
            <div>{{ commandLabel(command.tool) }} · {{ statusLabel(command.status) }}</div>
            <div class="hint">有效期 {{ previewDate(command.expiresAt, 'MM-DD HH:mm:ss') }}</div>
          </div>
        </el-collapse-item>
      </el-collapse>
      <div ref="messageArea" class="messages">
        <div v-for="(item, index) in messages" :key="index" class="message" :class="item.role">
          <div class="bubble" :class="{ 'form-bubble': item.drugDraft }">
            <MarkdownView v-if="item.drugNavigation || item.text" :content="item.drugNavigation ? drugNavigationText : item.text || ''" />
            <div v-if="item.drugNavigation && (canOpenDrugArchive || canOpenDrugReview)" class="drug-navigation-actions">
              <el-button v-if="canOpenDrugArchive" type="primary" @click="openDrugPage('PharmacyBaseDrug')">打开药品档案</el-button>
              <el-button v-if="canOpenDrugReview" @click="openDrugPage('PharmacyDrugReview')">查看待审核药品</el-button>
            </div>
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
            <DrugCreateCard v-if="item.drugDraft" v-model:draft="item.drugDraft" v-model:submission="item.drugSubmission" @command="recordDrugCommand" @busy="formSubmitting = $event" />
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
                <el-descriptions-item label="有效期">{{
                  previewDate(item.preview.expiresAt, 'YYYY-MM-DD HH:mm:ss')
                }}</el-descriptions-item>
              </el-descriptions>
              <CommandChanges
                v-if="item.preview.tool !== 'CREATE_PURCHASE_DRAFT'"
                :command="item.preview"
              />
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
                  :disabled="!canConfirm(item.preview.tool)"
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
        <div class="prompt-actions">
          <el-button size="small" :disabled="generating || formSubmitting || !canReadDrugs" @click="fillExample('查询板蓝根颗粒的药品档案')">填入查药提示词</el-button>
          <el-button size="small" :disabled="generating || formSubmitting || !canReadInventory" @click="fillExample('查询板蓝根颗粒在当前门店的可售库存')">填入库存提示词</el-button>
          <el-button v-if="canConfirm('CREATE_DRUG')" size="small" :disabled="generating || formSubmitting" @click="fillExample(drugCreatePrompt)">填入新增药品提示词</el-button>
        </div>
        <el-button
          v-if="canReadInventory"
          :disabled="!canCreatePurchase"
          plain
          @click="inventoryPurchaseDialog?.open()"
          >生成采购草稿</el-button
        >
        <el-alert
          v-if="knowledgeReindexResult"
          :title="knowledgeReindexResult.text"
          :type="knowledgeReindexResult.type"
          show-icon
          @close="knowledgeReindexResult = undefined"
        />
        <el-input
          ref="composerInput"
          v-model="content"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="输入要查询的药品名称或当前门店库存问题"
          @keydown.ctrl.enter="send"
        />
        <div class="composer-actions">
          <span class="hint">Ctrl + Enter 发送；填写卡片后点击提交确认新增</span>
          <el-button v-if="generating" @click="stop">停止生成</el-button>
          <el-button
            v-else
            type="primary"
            :disabled="!content.trim() || !canMaintainIndex || formSubmitting"
            @click="send"
            >发送</el-button
          >
        </div>
      </div>
    </el-card>
    <el-card class="conversations" shadow="never">
      <template #header>
        <span>会话</span>
      </template>
      <el-input v-model="historyQuery" clearable aria-label="搜索会话" placeholder="搜索会话标题" />
      <p class="hint">会话保存在此浏览器</p>
      <p v-if="historyUnavailable" class="hint" role="status">浏览器无法保存会话，请检查存储设置。</p>
      <p v-if="!visibleConversations.length" class="hint">没有匹配的会话</p>
      <div v-for="item in visibleConversations" :key="item.id" class="history-item">
        <button
          type="button" class="conversation" :class="{ active: item.id === activeId }"
          :title="item.title" :disabled="generating || formSubmitting || !!executing"
          :aria-current="item.id === activeId ? 'true' : undefined"
          @click="selectConversation(item.id)"
        >{{ item.messages.length ? item.title : '未开始的会话' }}</button>
        <el-dropdown v-if="item.messages.length" trigger="click" @command="manageConversation(item, $event)">
          <el-button text :disabled="generating || formSubmitting || !!executing" :aria-label="'管理会话：' + item.title">⋯</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="rename">重命名</el-dropdown-item>
              <el-dropdown-item command="delete">删除会话</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <el-divider>操作记录</el-divider>
      <p v-if="!commands.length" class="hint">暂无操作记录</p>
      <div v-for="command in commands" :key="command.commandId" class="command-link">
        <strong :title="commandObject(command)">{{ commandObject(command) }}</strong>
        <div>{{ commandLabel(command.tool) }} · {{ statusLabel(command.status) }}</div>
        <div class="hint">有效期 {{ previewDate(command.expiresAt, 'MM-DD HH:mm:ss') }}</div>
        <span class="hint">#{{ command.commandId }}</span>
      </div>
    </el-card>

    <InventoryPurchaseDialog ref="inventoryPurchaseDialog" @preview="appendPurchasePreview" />
  </div>
</template>

<script setup lang="ts">
import DrugCreateCard from './DrugCreateCard.vue'
import { drugCreatePrompt, readDrugCreatePrompt } from './drugCreatePrompt'
import CommandChanges from './CommandChanges.vue'
import MarkdownView from '@/components/MarkdownView/index.vue'
import { ElMessageBox } from 'element-plus'
import { useAiAssistantStore } from '@/store/modules/aiAssistant'
import { useUserStore } from '@/store/modules/user'
import { getTenantId, getVisitTenantId } from '@/utils/auth'
import { PharmacyAiApi, PharmacyAiCommand } from '@/api/pharmacy/ai'
import InventoryPurchaseDialog from './InventoryPurchaseDialog.vue'
import { hasPermission } from '@/directives/permission/hasPermi'
import { formatDate } from '@/utils/formatTime'

const props = withDefaults(defineProps<{ embedded?: boolean; visible?: boolean }>(), {
  embedded: false, visible: true
})
const user = useUserStore()
const historyQuery = ref('')
const historyUnavailable = ref(false)

type Message = {
  role: 'user' | 'assistant'
  text?: string
  toolResult?: any
  preview?: PharmacyAiCommand
  requestId?: string
  errorMessage?: string
  drugNavigation?: boolean
  stopped?: boolean
  drugDraft?: Record<string, any>
  drugSubmission?: { commandId: number; status: string; drugId?: number }
}

type Conversation = {
  id: number
  title: string
  messages: Message[]
}

const message = useMessage()
const reindexingKnowledge = ref(false)
const knowledgeReindexResult = ref<{ type: 'success' | 'warning' | 'error'; text: string }>()

const reindexKnowledge = async () => {
  if (reindexingKnowledge.value || !canMaintainIndex.value) return
  reindexingKnowledge.value = true
  try {
    try {
      await message.confirm(
        '将为当前租户读取已配置的药店语料并重建向量索引。确认开始重建？',
        '重建知识索引'
      )
    } catch {
      return
    }
    knowledgeReindexResult.value = undefined
    const result = await PharmacyAiApi.reindexKnowledge()
    knowledgeReindexResult.value = result.vectorStore
      ? {
          type: 'success',
          text: `索引重建完成，匹配 ${result.drugs} 款商品，写入 ${result.chunks} 个片段。`
        }
      : { type: 'warning', text: '本次未写入索引片段，请检查当前租户的语料和商品。' }
  } catch (error: unknown) {
    const detail = error instanceof Error ? error.message : typeof error === 'string' ? error : ''
    knowledgeReindexResult.value = {
      type: 'error',
      text: detail ? `索引重建未能确认完成：${detail}` : '索引重建未能确认完成，请稍后重试。'
    }
  } finally {
    reindexingKnowledge.value = false
  }
}
const canMaintainIndex = computed(() => hasPermission(['pharmacy:ai:chat']))
const canReadDrugs = computed(
  () => canMaintainIndex.value && hasPermission(['pharmacy:base:drug:query'])
)
const fillExample = (text: string) => {
  if (generating.value) return
  content.value = text
  nextTick(() => composerInput.value?.focus())
}
const commandObject = (command: PharmacyAiCommand) => {
  if (command.tool === 'CREATE_PURCHASE_DRAFT') {
    const display = command.after?.display
    return display?.supplier?.name
      ? (display.storeName || '当前门店') + ' · ' + display.supplier.name
      : '采购草稿'
  }
  return (
    command.after?.genericName ||
    command.before?.genericName ||
    command.after?.drugCode ||
    command.before?.drugCode ||
    '药品 #' + (command.after?.id || command.before?.id || '未提供')
  )
}
const router = useRouter()
const inventoryPurchaseDialog = ref<InstanceType<typeof InventoryPurchaseDialog>>()
const canReadInventory = computed(
  () => hasPermission(['pharmacy:ai:chat']) && hasPermission(['pharmacy:inventory-batch:query'])
)
const canCreatePurchase = computed(
  () => canReadInventory.value && hasPermission(['pharmacy:purchase:order:create'])
)
const canConfirm = (tool: string) =>
  canMaintainIndex.value &&
  hasPermission([
    {
      CREATE_DRUG: 'pharmacy:base:drug:create',
      UPDATE_DRUG: 'pharmacy:base:drug:update',
      DELETE_DRUG: 'pharmacy:base:drug:delete',
      CREATE_PURCHASE_DRAFT: 'pharmacy:purchase:order:create'
    }[tool] || '__unregistered_ai_action__'
  ])
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
const composerInput = ref<{ focus: () => void }>()
const launcher = useAiAssistantStore()
const generating = ref(false)
const formSubmitting = ref(false)
const executing = ref<number>()
const messageArea = ref<HTMLElement>()
let controller: AbortController | undefined
const historyKey = computed(() =>
  user.getUser.id ? `pharmacy-ai-history:${getTenantId()}:${getVisitTenantId()}:${user.getUser.id}` : ''
)
const visibleConversations = computed(() => conversations.value.filter((item) =>
  item.title.toLocaleLowerCase().includes(historyQuery.value.trim().toLocaleLowerCase())
))
let restoring = false
let restoredSnapshot: string | null = null

function restoreHistory(key: string, force = false) {
  let stored: string | null = null
  try { stored = key ? localStorage.getItem(key) : null } catch { historyUnavailable.value = true; return }
  if (!force && stored === restoredSnapshot) return
  restoredSnapshot = stored
  restoring = true
  historyUnavailable.value = false
  const empty = { id: Date.now(), title: '新对话', messages: [] }
  conversations.value = [empty]
  activeId.value = empty.id
  try {
    const saved = JSON.parse(stored || 'null')
    if (Array.isArray(saved?.conversations) && saved.conversations.length) {
      conversations.value = saved.conversations
      activeId.value = conversations.value.some((item) => item.id === saved.activeId)
        ? saved.activeId : conversations.value[0].id
    }
  } catch {
    historyUnavailable.value = true
  } finally {
    restoring = false
  }
}

watch(historyKey, (key, previous) => {
  if (previous) { stop(); content.value = ''; historyQuery.value = '' }
  restoreHistory(key, true)
}, { immediate: true })
watch([activeId, conversations, generating], () => {
  if (restoring || generating.value || !historyKey.value) return
  try {
    const history = conversations.value.filter((item) => item.messages.length).map((item) => ({
      id: item.id,
      title: item.title,
      messages: item.messages.map(({ role, text, errorMessage, stopped, drugDraft, drugSubmission, drugNavigation }) => ({
        role, text, errorMessage, stopped, drugDraft, drugSubmission, drugNavigation
      })).filter((message) => message.text || message.errorMessage || message.stopped || message.drugDraft || message.drugNavigation)
    }))
    const snapshot = JSON.stringify({ activeId: activeId.value, conversations: history })
    localStorage.setItem(historyKey.value, snapshot)
    restoredSnapshot = snapshot
    historyUnavailable.value = false
  } catch {
    historyUnavailable.value = true
  }
}, { deep: true, flush: 'sync' })
watch(() => props.visible, (visible) => {
  if (visible && !generating.value && !executing.value) restoreHistory(historyKey.value)
})
onActivated(() => {
  if (!generating.value && !executing.value) restoreHistory(historyKey.value)
})

async function manageConversation(item: Conversation, command: string) {
  if (generating.value || formSubmitting.value || executing.value) return
  const key = historyKey.value
  try {
    if (command === 'rename') {
      const { value } = await ElMessageBox.prompt('输入便于查找的会话标题', '重命名会话', {
        inputValue: item.title, inputValidator: (name) => !!name?.trim() && name.trim().length <= 40
          || '请输入 1–40 个字符'
      })
      if (key === historyKey.value && conversations.value.includes(item)) item.title = value.trim()
    } else if (command === 'delete') {
      await ElMessageBox.confirm(`删除「${item.title}」？本机聊天记录将无法恢复。`, '删除会话', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
      })
      if (key !== historyKey.value || !conversations.value.includes(item) || generating.value || formSubmitting.value || executing.value) return
      if (activeId.value === item.id) content.value = ''
      conversations.value = conversations.value.filter((row) => row.id !== item.id)
      if (!conversations.value.length) newConversation()
      else if (activeId.value === item.id) selectConversation(conversations.value[0].id)
    }
  } catch {
    // Closing the confirmation leaves the conversation intact.
  }
}

const selectConversation = (id: number) => {
  if (generating.value || formSubmitting.value || executing.value) return
  if (activeId.value !== id) content.value = ''
  activeId.value = id
  nextTick(() => messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight }))
}

const titleFromMessage = (text: string) => {
  const normalized = text.replace(/\s+/g, ' ').trim()
  return normalized.length > 18 ? `${normalized.slice(0, 18)}…` : normalized
}

const newConversation = () => {
  if (generating.value || formSubmitting.value || executing.value) return
  content.value = ''
  const emptyConversation = conversations.value.find((item) => item.messages.length === 0)
  if (emptyConversation) {
    selectConversation(emptyConversation.id)
    return
  }
  activeId.value = Date.now()
  conversations.value.unshift({ id: activeId.value, title: '新对话', messages: [] })
}

// Answer known navigation questions from registered routes and existing permissions.
const canOpenDrugArchive = computed(() => canReadDrugs.value && router.hasRoute('PharmacyBaseDrug'))
const canOpenDrugReview = computed(() =>
  canReadDrugs.value && hasPermission(['pharmacy:base:drug:approve']) && router.hasRoute('PharmacyDrugReview')
)
const drugNavigationText = computed(() => {
  const steps: string[] = []
  if (canOpenDrugArchive.value) {
    steps.push('在左侧菜单打开 **药店业务 → 基础资料 → 药品档案**，用 **药品编码** 或 **通用名** 搜索。如果新增时选择了停用，请将 **启用状态** 切换为停用或清空筛选。')
  }
  if (canOpenDrugReview.value) {
    steps.push('要处理尚未审核的档案，打开 **药店业务 → 基础资料 → 药品审核**，选择 **待审核**。')
  }
  return steps.join('\n\n') || '当前账号没有可用的药品档案或药品审核入口，请联系管理员核对权限。'
})
const isDrugNavigationQuestion = (question: string) => {
  const normalized = question.replace(/[\s，。？！、,?!.]/g, '').replace(/^(请问|请告诉我)/, '').replace(/(呢|啊|吗)$/, '')
  const subject = '(?:刚刚?|新|刚新)?(?:新增|新建|添加|录入|加)(?:的|了)?(?:药品|药物|药品档案)'
  return new RegExp(`^(?:(?:在哪(?:里|儿)?(?:可以)?(?:看(?:到|查)?|查(?:看|询)?|找(?:到)?)|怎么(?:查看|查找|找|看))${subject}|${subject}(?:在哪(?:里|儿)?(?:(?:可以)?(?:查看|查找|找到|查|看))?|怎么(?:查看|查找|找|看)|去哪里(?:查看|找)))$`).test(normalized)
}
const openDrugPage = async (name: 'PharmacyBaseDrug' | 'PharmacyDrugReview') => {
  if (!(name === 'PharmacyBaseDrug' ? canOpenDrugArchive.value : canOpenDrugReview.value)) return
  try {
    await router.push({ name, query: name === 'PharmacyDrugReview' ? { status: '0' } : {} })
    if (router.currentRoute.value.name === name && props.embedded) launcher.close()
  } catch {
    message.error('页面打开失败，请重试。')
  }
}

const send = async () => {
  const question = content.value.trim()
  if (!question || generating.value || formSubmitting.value || !canMaintainIndex.value) return
  const navigationQuestion = isDrugNavigationQuestion(question)
  const drugDraft = canConfirm('CREATE_DRUG') ? readDrugCreatePrompt(question) : undefined
  if (!navigationQuestion && !drugDraft) generating.value = true
  content.value = ''
  const conversation = conversations.value.find((item) => item.id === activeId.value)
  if (conversation?.messages.length === 0) conversation.title = titleFromMessage(question)
  messages.value.push({ role: 'user', text: question })
  if (navigationQuestion) {
    messages.value.push({ role: 'assistant', drugNavigation: true })
    nextTick(() => messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight }))
    return
  }
  if (drugDraft) {
    appendDrugForm(drugDraft)
    return
  }
  const answer = reactive<Message>({ role: 'assistant', text: '' })
  messages.value.push(answer)
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
        if (event.event === 'form_request' && data.form === 'CREATE_DRUG') {
          if (canConfirm('CREATE_DRUG')) answer.drugDraft = data.draft || {}
          else answer.errorMessage = '当前账号没有新增药品权限，请联系管理员。'
        }
        if (event.event === 'tool_result') answer.toolResult = data
        if (event.event === 'tool_preview') {
          answer.preview = data
          commands.value.unshift(data)
        }
        if (event.event === 'error') {
          answer.errorMessage = data.message || 'AI 未能完成本次请求，请稍后重试。'
          answer.requestId = data.requestId || answer.requestId
        }
        nextTick(() => messageArea.value?.scrollTo({ top: answer.drugDraft ? (messageArea.value.lastElementChild as HTMLElement)?.offsetTop || 0 : messageArea.value.scrollHeight }))
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

const appendDrugForm = (draft: Record<string, any> = {}) => {
  if (!canConfirm('CREATE_DRUG') || generating.value || formSubmitting.value) return
  const conversation = conversations.value.find(item => item.id === activeId.value)
  if (conversation && !conversation.messages.length) conversation.title = '新增药品'
  messages.value.push({ role: 'assistant', text: '填写中文卡片，核对后点击提交即可新增待审核药品。', drugDraft: draft })
  nextTick(() => messageArea.value?.scrollTo({ top: (messageArea.value.lastElementChild as HTMLElement)?.offsetTop || 0 }))
}
const recordDrugCommand = (command: PharmacyAiCommand) => {
  const existing = commands.value.find(item => item.commandId === command.commandId)
  if (existing) Object.assign(existing, command)
  else commands.value.unshift(command)
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
  if (
    executing.value === command.commandId ||
    command.status !== 'PENDING' ||
    !canConfirm(command.tool)
  )
    return
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
const summarizeResult = (result: any) => {
  if (Array.isArray(result)) return `共返回 ${result.length} 条药品记录。`
  if (result?.rule)
    return `当前门店有 ${result.items?.length || 0} 条库存预警${result.hasMore ? '（还有更多）' : ''}。${result.rule}`
  if (result?.items) return `当前门店返回 ${result.items.length} 个药品的库存摘要。`
  if (result?.found === false) return '未找到对应药品。'
  return `已返回药品 ${result?.genericName || result?.drugCode || ''} 的详情。`
}

watch(() => [props.embedded && props.visible, launcher.draft] as const, ([visible, draft]) => {
  if (!visible || !draft) return
  if (!generating.value && !content.value.trim()) fillExample(draft)
  launcher.draft = ''
}, { immediate: true })

onMounted(async () => {
  commands.value = (await PharmacyAiApi.recentCommands()) || []
})
onBeforeUnmount(stop)
</script>

<style scoped>
.ai-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 16px;
  height: calc(100vh - 120px);
  padding: 16px;
}

.ai-page.embedded {
  width: 100%;
  height: 100%;
  padding: 12px;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 26%);
  box-sizing: border-box;
}

.conversations,
.chat {
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.conversations :deep(.el-card__body) {
  overflow: auto;
  max-height: calc(100% - 60px);
}
.mobile-commands {
  display: none;
}
.chat-toolbar {
  flex-wrap: wrap;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 12px;
}
.prompt-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.prompt-actions :deep(.el-button + .el-button) { margin-left: 0; }
.command-link {
  overflow-wrap: anywhere;
  border-bottom: 1px solid var(--el-border-color-light);
}

.header,
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-item { display: flex; align-items: center; gap: 4px; }
.history-item .conversation { flex: 1; min-width: 0; text-align: left; border: 0; background: transparent; color: inherit; }
.history-item .conversation.active { background: #e6f1ed; color: #176b5b; }

.conversation,
.command-link {
  padding: 10px;
  cursor: pointer;
  border-radius: 6px;
}

.conversation, .command-link strong {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.conversation.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.command-link {
  font-size: 14px;
  color: #606266;
}

.chat :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: calc(100% - 40px);
}

.messages {
  position: relative;
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
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-radius: 10px;
  overflow-wrap: anywhere;
}

.user .bubble {
  color: white;
  background: var(--el-color-primary);
}

.user .bubble :deep(*) { color: inherit; }
.form-bubble { width: 100%; background: transparent; padding: 0; }

.drug-navigation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.drug-navigation-actions :deep(.el-button + .el-button) { margin-left: 0; }

.tool-card {
  min-width: 0;
  width: 100%;
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
  font-size: 14px;
  color: #606266;
}

@media (width <= 900px) {
  .ai-page,
  .ai-page.embedded {
    grid-template-columns: 1fr;
  }

  .mobile-commands {
    display: block;
  }
  .ai-page .conversations {
    display: none;
  }

  .messages {
    padding: 8px 0;
  }
  .composer-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
