// 处方审核前端规则，与管理端审方接口的必填条件对齐。不在本地宣布通过或驳回。

export interface ApprovalItem {
  drugId?: number
  qty?: number
  usage?: string
  dosage?: string
  drugName?: string
  specification?: string
}

export interface ReviewInput {
  id: number
  wxMemberId?: number | null
  isSpecial?: number
  limitCheck?: number
  prescribedItems?: string | null
  decision?: number
  opinion?: string
  dblCheckBy?: number | null
  approvedItems: ApprovalItem[]
  approvedUntil?: string
  materialReady: boolean
  now?: number
}

const REVIEW_LABELS: Record<number, string> = { 0: '待审', 1: '通过', 2: '驳回' }
const STATUS_LABELS: Record<number, string> = { 0: '有效', 1: '已完成', 2: '作废' }
const SOURCE_LABELS: Record<number, string> = { 0: '纸质拍照', 1: '电子处方平台', 2: '复诊续方' }
const USE_LABELS: Record<string, string> = { HELD: '待支付占用', PAID: '已支付使用', RELEASED: '未支付已释放' }

export function reviewLabel(value?: number) {
  return value == null ? '-' : (REVIEW_LABELS[value] ?? String(value))
}

export function recordStatusLabel(value?: number) {
  return value == null ? '-' : (STATUS_LABELS[value] ?? String(value))
}

export function sourceLabel(value?: number) {
  return value == null ? '-' : (SOURCE_LABELS[value] ?? String(value))
}

export function useLabel(status?: string) {
  if (!status) return '-'
  return USE_LABELS[status] || status
}

export function channelLabel(wxMemberId?: number | null) {
  return wxMemberId != null ? '顾客申请' : '门店登记'
}

export function parseItems(value?: string | null): { items: ApprovalItem[]; invalid: boolean } {
  if (!value || !value.trim()) return { items: [], invalid: false }
  try {
    const parsed = JSON.parse(value)
    if (!Array.isArray(parsed)) return { items: [], invalid: true }
    return { items: parsed, invalid: false }
  } catch {
    return { items: [], invalid: true }
  }
}

export function materialSummary(images?: string | null) {
  if (!images || !images.trim()) return { ok: false, count: 0, message: '没有处方材料' }
  try {
    const refs = JSON.parse(images)
    if (!Array.isArray(refs) || !refs.length) return { ok: false, count: 0, message: '没有处方材料' }
    if (refs.some((item) => typeof item !== 'string' || !/^private:\d+$/.test(item))) {
      return { ok: false, count: refs.length, message: '历史公开材料不可用于本流程，请顾客重新提交' }
    }
    return { ok: true, count: refs.length, message: '' }
  } catch {
    return { ok: false, count: 0, message: '处方材料无法读取' }
  }
}

export function privateMaterialIds(images?: string | null) {
  if (!materialSummary(images).ok) return null
  return (JSON.parse(images as string) as string[]).map((item) => Number(item.slice('private:'.length)))
}

/** 已审记录回显原结果；待审不预选，避免把 0 显示成驳回。门店台账可传入原有的默认通过。 */
export function initialDecision(reviewStatus?: number, pendingDefault?: number) {
  if (reviewStatus === 1 || reviewStatus === 2) return reviewStatus
  return pendingDefault
}

export function parseLocalDateTime(value?: string) {
  if (!value) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/.exec(value.trim())
  if (!match) return null
  const parts = match.slice(1).map(Number)
  const date = new Date(parts[0], parts[1] - 1, parts[2], parts[3], parts[4], parts[5])
  if (Number.isNaN(date.getTime())) return null
  if (date.getFullYear() !== parts[0] || date.getMonth() !== parts[1] - 1 || date.getDate() !== parts[2]) return null
  return date.getTime()
}

export function validateReview(input: ReviewInput) {
  if (input.decision !== 1 && input.decision !== 2) return '请选择审核结果'
  const opinion = (input.opinion || '').trim()
  if (opinion.length > 300) return '审方意见不能超过 300 字'
  if (input.decision === 2 && !opinion) return '驳回时请填写审方意见'
  if (input.decision === 1 && input.limitCheck === 1 && !opinion) return '超量处方通过时请填写审方意见'
  if (input.decision === 1 && input.isSpecial === 1 && !(Number(input.dblCheckBy) > 0)) return '特管处方通过时请填写复核员工编号'
  if (input.wxMemberId != null && input.decision === 1) {
    if (!input.materialReady) return '请先成功读取私有处方材料'
    const requested = parseItems(input.prescribedItems)
    if (requested.invalid) return '申请明细无法读取，不能核准'
    if (!input.approvedItems.length) return '请至少核准一项药品'
    const maximum = new Map(requested.items.map((item) => [item.drugId, item.qty]))
    const seen = new Set<number>()
    for (const item of input.approvedItems) {
      if (item.drugId == null || seen.has(item.drugId)) return '核准药品不能重复或为空'
      seen.add(item.drugId)
      const limit = maximum.get(item.drugId)
      if (limit == null || item.qty == null || item.qty < 1 || item.qty > limit) return '核准数量需在 1 到申请数量之间，且不得新增药品'
      const usage = item.usage?.trim() || ''
      const dosage = item.dosage?.trim() || ''
      if (!usage || !dosage) return '请填写核准药品的用法和用量'
      if (usage.length > 128 || dosage.length > 128) return '用法或用量不能超过 128 字'
    }
    const until = parseLocalDateTime(input.approvedUntil)
    if (until == null || until <= (input.now ?? Date.now())) return '请填写未来的有效截止时间'
  }
  return null
}

export function buildReviewPayload(input: ReviewInput) {
  const error = validateReview(input)
  if (error) return { error }
  const payload: {
    id: number
    reviewStatus: number
    reviewOpinion: string
    dblCheckBy?: number
    approvedItems?: ApprovalItem[]
    approvedUntil?: number
  } = {
    id: input.id,
    reviewStatus: input.decision as number,
    reviewOpinion: (input.opinion || '').trim()
  }
  if (input.decision === 1 && input.isSpecial === 1) payload.dblCheckBy = Number(input.dblCheckBy)
  if (input.wxMemberId != null && input.decision === 1) {
    payload.approvedItems = input.approvedItems.map((item) => ({
      drugId: item.drugId,
      qty: item.qty,
      usage: item.usage?.trim(),
      dosage: item.dosage?.trim(),
      drugName: item.drugName,
      specification: item.specification
    }))
    payload.approvedUntil = parseLocalDateTime(input.approvedUntil) as number
  }
  return { payload }
}

export function describeReviewOutcome(fresh: { reviewStatus?: number } | null) {
  if (!fresh || (fresh.reviewStatus !== 0 && fresh.reviewStatus !== 1 && fresh.reviewStatus !== 2)) return 'unknown' as const
  if (fresh.reviewStatus === 0) return 'pending' as const
  return 'confirmed' as const
}

export function describeInvalidate(fresh: { status?: number } | null) {
  if (!fresh || (fresh.status !== 0 && fresh.status !== 1 && fresh.status !== 2)) return 'unknown' as const
  return fresh.status === 2 ? ('done' as const) : ('unchanged' as const)
}

export function errorText(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error && error !== 'error') return error
  return fallback
}
