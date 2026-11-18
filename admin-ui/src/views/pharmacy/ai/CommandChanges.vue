<template>
  <table class="command-changes" aria-label="变更前后">
    <thead
      ><tr><th>字段</th><th>变更前</th><th>变更后</th></tr></thead
    >
    <tbody>
      <tr v-for="row in changes" :key="row.label"
        ><th scope="row">{{ row.label }}</th
        ><td>{{ row.before }}</td
        ><td>{{ row.after }}</td></tr
      >
      <tr v-if="!changes.length"><td colspan="3">无字段变化</td></tr>
    </tbody>
  </table>
</template>
<script setup lang="ts">
import type { PharmacyAiCommand } from '@/api/pharmacy/ai'
const props = defineProps<{ command: PharmacyAiCommand }>()
const labels: Record<string, string> = {
  id: '药品编号',
  drugCode: '药品编码',
  genericName: '通用名称',
  tradeName: '商品名称',
  categoryId: '分类编号',
  specification: '规格',
  manufacturer: '生产厂家',
  approvalNo: '批准文号',
  unit: '单位',
  retailPrice: '零售价',
  memberPrice: '会员价',
  costPrice: '成本价',
  minSalePrice: '最低售价',
  minStock: '库存下限',
  maxStock: '库存上限',
  status: '启用状态',
  saleableOnline: '线上可售',
  storageCond: '储存条件',
  isRx: '处方药标记',
  remark: '备注',
  description: '说明'
}
const display = (value: unknown) => {
  if (value === undefined) return '未提供'
  if (value === null) return '空值'
  if (value === '') return '空字符串'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}
const changes = computed(() => {
  const before = props.command.before || {}
  const after = props.command.after || {}
  return [...new Set([...Object.keys(before), ...Object.keys(after)])]
    .filter((key) => JSON.stringify(before[key]) !== JSON.stringify(after[key]))
    .map((key) => ({
      label: labels[key] || key,
      before: props.command.tool === 'CREATE_DRUG' ? '新增前无记录' : display(before[key]),
      after: props.command.tool === 'DELETE_DRUG' ? '删除后无记录' : display(after[key])
    }))
})
</script>
<style scoped>
.command-changes {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}
.command-changes th,
.command-changes td {
  padding: 8px;
  border: 1px solid var(--el-border-color-light);
  text-align: left;
  vertical-align: top;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.command-changes th {
  background: var(--el-fill-color-light);
}
.command-changes th:first-child {
  width: 25%;
}
</style>
