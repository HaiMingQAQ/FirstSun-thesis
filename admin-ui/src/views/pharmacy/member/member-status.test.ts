import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  MEMBER_DISABLED,
  MEMBER_ENABLED,
  MEMBER_EXPORT_NOTICE,
  POINT_EXPORT_NOTICE,
  isMemberEnabled,
  memberStatusLabel,
  parseMemberId,
  toggledMemberStatus
} from './member-status.ts'

test('会员启用状态跟登录校验一致，不跟写反的字典', () => {
  assert.equal(MEMBER_ENABLED, 0)
  assert.equal(memberStatusLabel(0), '启用')
  assert.equal(memberStatusLabel(1), '禁用')
  assert.equal(memberStatusLabel(null), '-')
  assert.equal(memberStatusLabel(9), '未知（9）')
  assert.equal(isMemberEnabled(0), true)
  assert.equal(isMemberEnabled(1), false)
  assert.equal(toggledMemberStatus(0), MEMBER_DISABLED)
  assert.equal(toggledMemberStatus(1), MEMBER_ENABLED)
})

test('会员编号只接受正整数，空值表示不筛选', () => {
  assert.equal(parseMemberId(''), null)
  assert.equal(parseMemberId(' 18 '), 18)
  assert.equal(parseMemberId('0'), undefined)
  assert.equal(parseMemberId('12a'), undefined)
  assert.equal(parseMemberId('1.5'), undefined)
})

test('导出提示写明服务端文件里的个人信息，不包含 IP', () => {
  assert.match(MEMBER_EXPORT_NOTICE, /手机号/)
  assert.match(MEMBER_EXPORT_NOTICE, /真实名字/)
  assert.match(MEMBER_EXPORT_NOTICE, /不含注册 IP/)
  assert.match(POINT_EXPORT_NOTICE, /用户编号/)
  assert.match(POINT_EXPORT_NOTICE, /不含积分描述/)
})
