<template>
  <ContentWrap
    ><h2>库存查询</h2><p>默认使用当前在职员工关联门店。账面可用与销售判断分开显示。</p>
    <el-form inline @submit.prevent="search"
      ><el-form-item label="药品"
        ><el-input
          v-model="query.keyword"
          placeholder="药名、编码或完整条码"
          clearable
          @keyup.enter="search" /></el-form-item
      ><el-form-item label="仓库"
        ><el-select
          v-model="query.warehouseId"
          filterable
          clearable
          placeholder="全部仓库"
          style="width: 200px"
          ><el-option
            v-for="warehouse in warehouses"
            :key="warehouse.id"
            :label="warehouse.whName"
            :value="warehouse.id" /></el-select></el-form-item
      ><el-form-item label="库存状态"
        ><el-select v-model="query.stockState" clearable placeholder="全部状态" style="width: 180px"
          ><el-option label="可售库存" value="available" /><el-option
            label="已过期"
            value="expired" /><el-option label="含冻结数量" value="frozen" /><el-option
            label="质量停售"
            value="qualityBlocked" /><el-option
            label="药品或仓库停用"
            value="disabled" /></el-select></el-form-item
      ><el-form-item label="质量状态"
        ><el-select v-model="query.qualityStatus" placeholder="全部质量状态" clearable style="width: 140px"
          ><el-option label="正常" :value="0" /><el-option label="停售" :value="1" /><el-option
            label="不合格"
            :value="2" /></el-select></el-form-item
      ><el-button type="primary" @click="search">搜索</el-button
      ><el-button @click="reset">重置</el-button><el-button v-if="canAskAi" plain @click="askAi">AI 查库存</el-button></el-form
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" v-loading="loading" class="inventory-results">
      <el-table-column label="药名 / 编码" min-width="220" fixed="left">
        <template #default="{ row }">
          <div>{{ row.genericName || '药品档案不可用' }}</div>
          <div class="inventory-code">{{ row.drugCode }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="saleableQty" width="110">
        <template #header>
          <el-tooltip content="含当日到期，按有效货位扣除冻结计算，最终以选批为准" placement="top">
            <span>实际可售 ⓘ</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="销售判断" min-width="220">
        <template #default="{ row }">
          <div class="inventory-reason" :class="{ 'inventory-reason--blocked': row.saleableQty === 0 }">
            {{ reason(row) || '可售，付款前仍需选批' }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="账面可用" prop="qtyAvail" width="100" />
      <el-table-column label="冻结" prop="qtyFrozen" width="80" />
      <el-table-column label="效期" width="120">
        <template #default="{ row }"><span class="inventory-date">{{ expiryDate(row.expiryDate) }}</span></template>
      </el-table-column>
      <el-table-column label="规格" prop="specification" min-width="180" show-overflow-tooltip />
      <el-table-column label="仓库" prop="warehouseName" min-width="160" show-overflow-tooltip />
      <el-table-column label="批次" prop="batchNo" min-width="240" show-overflow-tooltip />
    </el-table>
    <Pagination
      :total="total"
      v-model:page="query.pageNo"
      v-model:limit="query.pageSize"
      @pagination="load"
    />
  </ContentWrap>
</template>
<script setup lang="ts">
import { hasPermission } from '@/directives/permission/hasPermi'
import { useAiAssistantStore } from '@/store/modules/aiAssistant'
import { getBatchPage, type BatchVO, type StockQuery } from '@/api/pharmacy/inventory/batch'
import { getWarehousePage, type WarehouseVO } from '@/api/pharmacy/inventory/warehouse'
const warehouses = ref<WarehouseVO[]>([])
const query = reactive<StockQuery>({ pageNo: 1, pageSize: 10, keyword: '' })
const assistant = useAiAssistantStore()
const canAskAi = computed(() =>
  hasPermission(['pharmacy:ai:chat']) && hasPermission(['pharmacy:inventory-batch:query'])
)
const askAi = () => {
  const keyword = query.keyword?.trim()
  assistant.openWithDraft(keyword ? `查询当前门店「${keyword}」的可售库存` : '')
}
const rows = ref<BatchVO[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref('')
// LocalDate may arrive as the global Jackson [year, month, day] representation.
const expiryDate = (value: string | number[] | null | undefined) => {
  if (Array.isArray(value)) {
    return `${value[0]}-${String(value[1]).padStart(2, '0')}-${String(value[2]).padStart(2, '0')}`
  }
  return value ? value.slice(0, 10) : '—'
}
const reason = (row: BatchVO) => {
  const today = new Date()
  const day = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  const expiry = expiryDate(row.expiryDate)
  return [
    expiry < day ? '已过期' : '',
    row.qualityStatus !== 0 ? '质量停售' : '',
    row.drugStatus !== 1 ? '药品停用或档案不可用' : '',
    row.warehouseStatus !== 1 ? '仓库停用' : '',
    row.qtyFrozen > 0 ? `冻结 ${row.qtyFrozen} 件` : '',
    row.saleableQty === 0 && row.qtyAvail > 0 && expiry >= day && row.qualityStatus === 0 && row.drugStatus === 1 && row.warehouseStatus === 1 ? '无可售货位库存' : '',
    row.qtyAvail <= 0 ? '无可用库存' : ''
  ]
    .filter(Boolean)
    .join('、')
}
const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const data = await getBatchPage(query)
    rows.value = data.list
    total.value = data.total
  } catch {
    rows.value = []
    total.value = 0
    error.value = '库存查询失败，请确认在职员工门店与库存查询权限后重试'
  } finally {
    loading.value = false
  }
}
const search = () => {
  query.pageNo = 1
  load()
}
const reset = () => {
  query.keyword = ''
  query.warehouseId = undefined
  query.qualityStatus = undefined
  query.stockState = undefined
  search()
}
onMounted(async () => {
  await load()
  try {
    let pageNo = 1
    while (true) {
      const data = await getWarehousePage({ pageNo, pageSize: 100 })
      warehouses.value.push(...data.list)
      if (warehouses.value.length >= data.total || !data.list.length) break
      pageNo++
    }
  } catch {
    error.value = '仓库名称读取失败，请刷新重试；库存查询仍可使用全部仓库'
  }
})
</script>

<style scoped>
.inventory-results { width: 100%; }
.inventory-results :deep(.cell) { word-break: normal; }
.inventory-code { overflow-wrap: anywhere; }
.inventory-date { white-space: nowrap; font-variant-numeric: tabular-nums; }
.inventory-reason { white-space: normal; overflow-wrap: break-word; line-height: 1.6; }
.inventory-reason--blocked { color: #b42318; }
</style>
