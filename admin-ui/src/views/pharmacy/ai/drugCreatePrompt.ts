const fields = {
  通用名称: 'genericName',
  通用名: 'genericName',
  药品编码: 'drugCode',
  规格: 'specification',
  销售单位: 'unit',
  商品名: 'tradeName',
  生产厂家: 'manufacturer',
  批准文号: 'approvalNo',
  所属分类编号: 'categoryId',
  分类编号: 'categoryId',
  所属分类: 'categoryId',
  零售价: 'retailPrice',
  药品类型: 'drugType',
  是否处方药: 'isRx',
  是否特殊管理药品: 'isSpecial',
  是否含麻黄碱类成分: 'isPseudoephedrine',
  是否需要冷链: 'isColdChain',
  储存条件: 'storageCond',
  医保类别: 'insuranceType',
  是否管理有效期: 'needExpiry',
  审核后是否允许线上销售: 'saleableOnline',
  启用状态: 'status',
  税率: 'taxRate',
  库存下限: 'minStock',
  库存上限: 'maxStock',
  备注: 'remark'
} as const

export const drugCreatePrompt = [
  '请打开「新增药品」卡片，按以下资料预填，留给我核对并提交。',
  ...Object.keys(fields)
    .filter((label) => !['通用名', '分类编号', '所属分类编号'].includes(label))
    .map((label) => label + '：')
].join('\n')

export function readDrugCreatePrompt(question: string) {
  if (!/^(?:请|帮我|麻烦)?[^。；\n]{0,24}(?:新增|添加|录入)(?:一(?:条|款|个))?药品/.test(question))
    return
  const pattern = new RegExp(
    `(^|[\\s，,；;、])(${Object.keys(fields).join('|')})[ \t]*[：:][ \t]*`,
    'g'
  )
  const matches = [...question.matchAll(pattern)]
  if (!matches.length) return
  const draft: Record<string, string> = {}
  matches.forEach((match, index) => {
    const key = fields[match[2] as keyof typeof fields]
    const start = match.index! + match[0].length
    const value = question
      .slice(start, matches[index + 1]?.index)
      .trim()
      .split(/[\r\n]/, 1)[0]
      .replace(/[，,；;、]+$/, '')
      .trim()
    if (value && !/^(?:【.*】|留空|未填写|待填写)$/.test(value)) draft[key] = value
  })
  return draft
}
