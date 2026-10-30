import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  createClientRequestId,
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
  type Conversation
} from './reception.ts'

const row = (patch: Partial<Conversation>): Conversation => ({
  id: 1,
  storeId: 7,
  kind: 'SERVICE',
  attended: false,
  mine: false,
  unread: 0,
  updateTime: 1,
  ...patch
})

test('接待状态同时看本人和是否已被领取', () => {
  assert.equal(receptionLabel({ mine: true, attended: true }), '我接待中')
  assert.equal(receptionLabel({ mine: false, attended: true }), '已由他人接待')
  assert.equal(receptionLabel({ mine: false, attended: false }), '等待接待')
  assert.equal(kindLabel('PHARMACIST'), '药师咨询')
  assert.equal(kindLabel('SERVICE'), '门店客服')
})

test('筛选只作用于当前页，不改原列表', () => {
  const list = [
    row({ id: 1, kind: 'PHARMACIST', unread: 2 }),
    row({ id: 2, kind: 'SERVICE', mine: true, attended: true, unread: 0 }),
    row({ id: 3, kind: 'SERVICE', attended: true, unread: 4 })
  ]
  const waiting = filterConversations(list, { status: 'waiting' })
  assert.deepEqual(waiting.map((item) => item.id), [1])
  assert.deepEqual(filterConversations(list, { kind: 'SERVICE', unreadOnly: true }).map((item) => item.id), [3])
  assert.equal(list.length, 3)
})

test('消息按编号去重，更早的一页放在前面', () => {
  const current = [{ id: 2, senderType: 'MEMBER', senderName: '顾客', content: 'b', createTime: 2 }]
  const older = [
    { id: 1, senderType: 'MEMBER', senderName: '顾客', content: 'a', createTime: 1 },
    { id: 2, senderType: 'MEMBER', senderName: '顾客', content: 'b', createTime: 2 }
  ]
  const newer = [{ id: 2, senderType: 'STAFF', senderName: '药师', content: '重复', createTime: 2 }, { id: 3, senderType: 'STAFF', senderName: '药师', content: 'c', createTime: 3 }]
  assert.deepEqual(prependMessages(current, older).map((item) => item.id), [1, 2])
  assert.deepEqual(mergeMessages(current, newer).map((item) => item.content), ['b', 'c'])
})

test('结果未确认时重试沿用原编号，放弃后才允许新编号', () => {
  pendingSends.reset()
  const first = nextSend(8, '  请到店核对批号  ', undefined, 1710000000000, 'abc12345')
  assert.ok(first)
  assert.equal(first?.clientRequestId, 'staff_1710000000000_abc12345')
  assert.match(createClientRequestId(1710000000000, 'abc12345'), /^[a-zA-Z0-9_-]{8,64}$/)
  pendingSends.remember(first as NonNullable<typeof first>)
  const retry = nextSend(8, '另一段内容')
  assert.equal(retry?.clientRequestId, first?.clientRequestId)
  assert.equal(retry?.content, '请到店核对批号')
  pendingSends.clear(8)
  assert.equal(nextSend(8, '   '), null)
  pendingSends.reset()
})

test('关闭后的迟到响应不能写回新会话', () => {
  assert.equal(isCurrent(2, 2, true, 9, 9), true)
  assert.equal(isCurrent(3, 2, true, 9, 9), false)
  assert.equal(isCurrent(2, 2, false, 9, 9), false)
  assert.equal(isCurrent(2, 2, true, 10, 9), false)
})

test('列表同步只替换同一会话，时间按毫秒展示', () => {
  const list = [row({ id: 4, unread: 3 }), row({ id: 5, unread: 1 })]
  const next = replaceConversation(list, row({ id: 4, unread: 0, mine: true, attended: true }))
  assert.equal(next[0].unread, 0)
  assert.equal(list[0].unread, 3)
  assert.equal(formatEpoch(new Date(2026, 9, 30, 8, 9, 10).getTime()), '2026-10-30 08:09:10')
  assert.equal(formatEpoch('2026-10-30T08:09:10'), '2026-10-30 08:09:10')
  assert.equal(formatEpoch(null), '-')
  assert.equal(errorText('error', '发送结果未确认'), '发送结果未确认')
  assert.equal(errorText(new Error('领取失败'), '备用'), '领取失败')
})

test('刷新队列按开始顺序执行，不并行覆盖', async () => {
  const enqueue = createSerialQueue()
  const order: string[] = []
  let release: () => void = () => {}
  const first = enqueue(async () => {
    order.push('start1')
    await new Promise<void>((resolve) => {
      release = resolve
    })
    order.push('end1')
  })
  const second = enqueue(async () => {
    order.push('start2')
  })
  await Promise.resolve()
  assert.deepEqual(order, ['start1'])
  release()
  await first
  await second
  assert.deepEqual(order, ['start1', 'end1', 'start2'])
})
