// 登录校验把 0 当作启用。字典 pharmacy_member_status 写成了相反的 1 启用，页面不能跟那份字典。

export const MEMBER_ENABLED = 0
export const MEMBER_DISABLED = 1

export const MEMBER_STATUS_OPTIONS = [
  { value: MEMBER_ENABLED, label: '启用' },
  { value: MEMBER_DISABLED, label: '禁用' }
]

export function memberStatusLabel(status?: number | null) {
  if (status === MEMBER_ENABLED) return '启用'
  if (status === MEMBER_DISABLED) return '禁用'
  if (status == null) return '-'
  return `未知（${status}）`
}

export function isMemberEnabled(status?: number | null) {
  return status === MEMBER_ENABLED
}

export function toggledMemberStatus(status?: number | null) {
  return isMemberEnabled(status) ? MEMBER_DISABLED : MEMBER_ENABLED
}

export function parseMemberId(value: unknown) {
  if (value == null || value === '') return null
  const text = String(value).trim()
  if (!/^[1-9]\d*$/.test(text)) return undefined
  const id = Number(text)
  return Number.isSafeInteger(id) ? id : undefined
}

export const MEMBER_EXPORT_NOTICE =
  '导出文件由服务端生成，前端不能删减字段。文件包含用户编号、手机号、昵称、真实名字、性别、状态、积分和经验，不含注册 IP 和登录 IP。请只在工作需要时下载。'

export const POINT_EXPORT_NOTICE =
  '导出文件由服务端生成，前端不能删减字段。文件包含记录编号、用户编号、业务编码、业务类型、积分标题、积分变动和变动后积分，不含积分描述。用户编号可以对应到会员。'
