import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildReviewPayload,
  channelLabel,
  describeInvalidate,
  describeReviewOutcome,
  initialDecision,
  materialSummary,
  parseItems,
  parseLocalDateTime,
  privateMaterialIds,
  recordStatusLabel,
  reviewLabel,
  sourceLabel,
  useLabel,
  validateReview,
  type ReviewInput
} from './review-flow.ts'

const now = Date.parse('2026-10-30T08:00:00')
const future = '2026-11-02 09:30:00'

const base = (patch: Partial<ReviewInput> = {}): ReviewInput => ({
  id: 15,
  wxMemberId: 4,
  isSpecial: 0,
  limitCheck: 0,
  prescribedItems: JSON.stringify([{ drugId: 9, qty: 2, drugName: '合成药品', specification: '10mg' }]),
  decision: 1,
  opinion: '',
  approvedItems: [{ drugId: 9, qty: 2, usage: '口服', dosage: '一次一片', drugName: '合成药品', specification: '10mg' }],
  approvedUntil: future,
  materialReady: true,
  now,
  ...patch
})

test('待审不预选驳回，已审回显服务端结果', () => {
  assert.equal(initialDecision(0), undefined)
  assert.equal(initialDecision(0, 1), 1)
  assert.equal(initialDecision(1), 1)
  assert.equal(initialDecision(2), 2)
  assert.equal(reviewLabel(0), '待审')
  assert.equal(recordStatusLabel(2), '作废')
  assert.equal(sourceLabel(2), '复诊续方')
  assert.equal(useLabel('HELD'), '待支付占用')
  assert.equal(useLabel('CUSTOM'), 'CUSTOM')
  assert.equal(channelLabel(8), '顾客申请')
  assert.equal(channelLabel(null), '门店登记')
})

test('顾客申请通过必须带材料、核准明细和未来截止时间', () => {
  assert.equal(validateReview(base()), null)
  const built = buildReviewPayload(base())
  assert.equal(built.payload?.reviewStatus, 1)
  assert.equal(typeof built.payload?.approvedUntil, 'number')
  assert.equal(built.payload?.approvedUntil, parseLocalDateTime(future))
  assert.equal(validateReview(base({ materialReady: false })), '请先成功读取私有处方材料')
  assert.equal(validateReview(base({ approvedItems: [] })), '请至少核准一项药品')
  assert.equal(validateReview(base({ approvedItems: [{ drugId: 9, qty: 3, usage: '口服', dosage: '一次一片' }] })), '核准数量需在 1 到申请数量之间，且不得新增药品')
  assert.equal(validateReview(base({ approvedItems: [{ drugId: 10, qty: 1, usage: '口服', dosage: '一次一片' }] })), '核准数量需在 1 到申请数量之间，且不得新增药品')
  assert.equal(validateReview(base({ approvedItems: [{ drugId: 9, qty: 1, usage: ' ', dosage: '一次一片' }] })), '请填写核准药品的用法和用量')
  assert.equal(validateReview(base({ approvedUntil: '2026-10-30 07:00:00' })), '请填写未来的有效截止时间')
  assert.equal(validateReview(base({ prescribedItems: '{' })), '申请明细无法读取，不能核准')
})

test('驳回和超量、特管的必填与服务端一致，驳回不提交核准明细', () => {
  assert.equal(validateReview(base({ decision: 2, opinion: '' })), '驳回时请填写审方意见')
  const rejected = buildReviewPayload(base({ decision: 2, opinion: ' 材料不全 ', materialReady: false, approvedItems: [] }))
  assert.equal(rejected.error, undefined)
  assert.equal(rejected.payload?.approvedItems, undefined)
  assert.equal(rejected.payload?.approvedUntil, undefined)
  assert.equal(rejected.payload?.reviewOpinion, '材料不全')
  assert.equal(validateReview(base({ wxMemberId: null, decision: 1, opinion: '', approvedItems: [] })), null)
  assert.equal(validateReview(base({ wxMemberId: null, limitCheck: 1, opinion: '' })), '超量处方通过时请填写审方意见')
  assert.equal(validateReview(base({ wxMemberId: null, isSpecial: 1, dblCheckBy: null })), '特管处方通过时请填写复核员工编号')
  const special = buildReviewPayload(base({ wxMemberId: null, isSpecial: 1, dblCheckBy: 6, approvedItems: [] }))
  assert.equal(special.payload?.dblCheckBy, 6)
  assert.equal(special.payload?.approvedItems, undefined)
  assert.equal(validateReview(base({ decision: undefined })), '请选择审核结果')
  assert.equal(validateReview(base({ opinion: '字'.repeat(301) })), '审方意见不能超过 300 字')
})

test('材料引用只接受私有编号，坏数据和作废结果不伪装成功', () => {
  assert.deepEqual(privateMaterialIds('["private:12","private:13"]'), [12, 13])
  assert.equal(materialSummary('["https://example.test/a.jpg"]').message, '历史公开材料不可用于本流程，请顾客重新提交')
  assert.equal(privateMaterialIds('["https://example.test/a.jpg"]'), null)
  assert.equal(parseItems('').invalid, false)
  assert.equal(parseItems('{').invalid, true)
  assert.equal(describeReviewOutcome({ reviewStatus: 1 }), 'confirmed')
  assert.equal(describeReviewOutcome({ reviewStatus: 0 }), 'pending')
  assert.equal(describeReviewOutcome(null), 'unknown')
  assert.equal(describeInvalidate({ status: 2 }), 'done')
  assert.equal(describeInvalidate({ status: 0 }), 'unchanged')
  assert.equal(describeInvalidate(null), 'unknown')
  assert.equal(parseLocalDateTime('2026-02-31 00:00:00'), null)
})
