// 门店咨询接待的纯前端规则。服务端仍是权限、归属和去重的最终依据。

export interface Conversation {
  id: number
  storeId: number
  kind: string
  attended: boolean
  mine: boolean
  unread: number
  updateTime: number
}

export interface ChatMessage {
  id: number
  senderType: string
  senderName: string
  content: string
  createTime: number
}

export interface SendRequest {
  conversationId: number
  clientRequestId: string
  content: string
}

export interface ReceptionFilter {
  kind?: string
  status?: '' | 'waiting' | 'mine' | 'other'
  unreadOnly?: boolean
}

const pending = new Map<number, SendRequest>()

/** 结果未确认的发送在关闭弹窗或离开页面后仍保留原编号，避免换号重发。 */
export const pendingSends = {
  get(id: number) {
    return pending.get(id)
  },
  remember(send: SendRequest) {
    pending.set(send.conversationId, { ...send })
  },
  clear(id: number) {
    pending.delete(id)
  },
  reset() {
    pending.clear()
  }
}

export function receptionLabel(row: { mine?: boolean; attended?: boolean }) {
  if (row.mine) return '我接待中'
  if (row.attended) return '已由他人接待'
  return '等待接待'
}

export function kindLabel(kind?: string) {
  if (kind === 'PHARMACIST') return '药师咨询'
  if (kind === 'SERVICE') return '门店客服'
  return kind || '未知类型'
}

/** 只筛选已经取回的当前页。接口没有类型、未读或接待状态条件。 */
export function filterConversations<T extends Conversation>(list: T[], filter: ReceptionFilter) {
  return list.filter((row) => {
    if (filter.kind && row.kind !== filter.kind) return false
    if (filter.status === 'waiting' && (row.mine || row.attended)) return false
    if (filter.status === 'mine' && !row.mine) return false
    if (filter.status === 'other' && !(row.attended && !row.mine)) return false
    if (filter.unreadOnly && !(Number(row.unread) > 0)) return false
    return true
  })
}

export function mergeMessages(current: ChatMessage[], incoming: ChatMessage[]) {
  const known = new Set(current.map((item) => item.id))
  const extra = incoming.filter((item) => !known.has(item.id))
  return extra.length ? current.concat(extra) : current
}

export function prependMessages(current: ChatMessage[], older: ChatMessage[]) {
  const known = new Set(current.map((item) => item.id))
  const extra = older.filter((item) => !known.has(item.id))
  return extra.length ? extra.concat(current) : current
}

export function createClientRequestId(now = Date.now(), random = Math.random().toString(36).slice(2, 10)) {
  return `staff_${now}_${random}`.replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 64)
}

export function nextSend(
  conversationId: number,
  draft: string,
  failed?: SendRequest,
  now = Date.now(),
  random = Math.random().toString(36).slice(2, 10)
): SendRequest | null {
  const saved = failed || pendingSends.get(conversationId)
  if (saved) return saved.conversationId === conversationId ? saved : null
  const content = draft.trim()
  if (!content || content.length > 1000) return null
  return { conversationId, clientRequestId: createClientRequestId(now, random), content }
}

export function errorText(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error && error !== 'error') return error
  return fallback
}

export function formatEpoch(value: unknown) {
  if (value == null || value === '') return '-'
  if (typeof value === 'number' && Number.isFinite(value)) return formatDateParts(new Date(value))
  if (typeof value === 'string') {
    if (/^\d{13}$/.test(value)) return formatDateParts(new Date(Number(value)))
    if (/^\d{4}-\d{2}-\d{2}/.test(value)) return value.replace('T', ' ').slice(0, 19)
  }
  return '-'
}

function formatDateParts(date: Date) {
  if (Number.isNaN(date.getTime())) return '-'
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function isCurrent(generation: number, expected: number, open: boolean, activeId: number | undefined, id: number) {
  return generation === expected && open && activeId === id
}

/** 保证领取后的刷新不会被更早开始的轮询覆盖。 */
export function createSerialQueue() {
  let chain = Promise.resolve()
  return function enqueue<T>(task: () => Promise<T>) {
    const run = chain.then(task, task)
    chain = run.then(
      () => undefined,
      () => undefined
    )
    return run
  }
}

export function replaceConversation<T extends { id: number }>(list: T[], next: T) {
  const index = list.findIndex((item) => item.id === next.id)
  if (index < 0) return list
  const copy = list.slice()
  copy[index] = { ...copy[index], ...next }
  return copy
}
