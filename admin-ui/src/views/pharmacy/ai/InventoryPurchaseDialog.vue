<template>
  <el-dialog
    v-model="visible"
    title="库存预警与采购预览"
    width="min(1200px, 96vw)"
    destroy-on-close
    @close="close"
  >
    <el-alert
      title="勾选需要采购的药品，并填写采购数量和含税单价。生成预览后须再次确认创建采购草稿。"
      description="单次最多 20 项。预览不创建采购单；确认后的草稿仍需走现有审批、收货流程。"
      type="info"
      :closable="false"
      show-icon
      class="mb-4"
    />
    <el-form inline @submit.prevent="load">
      <el-form-item label="药品名称">
        <el-input v-model="keyword" clearable maxlength="64" placeholder="筛选本店库存预警" />
      </el-form-item>
      <el-form-item>
        <el-button :loading="loading" :disabled="submitting || !canRead" @click="load"
          >查询预警</el-button
        >
      </el-form-item>
    </el-form>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <div v-loading="loading">
      <template v-if="loaded && alerts">
        <p>当前门店 #{{ alerts.storeId }} · {{ alerts.rule }}</p>
        <el-alert
          v-if="alerts.hasMore"
          title="当前仅显示前 20 条预警，请按药品名称缩小查询范围。"
          type="warning"
          :closable="false"
          class="mb-4"
        />
        <el-empty v-if="rows.length === 0" description="当前筛选没有库存预警" />
        <el-table v-else :data="rows" border class="mb-4" :show-overflow-tooltip="true">
          <el-table-column label="采购" width="65" align="center">
            <template #default="{ row }">
              <el-checkbox
                v-model="row.selected"
                :disabled="!canCreate || submitting"
                :aria-label="`采购${row.drugName}`"
              />
            </template>
          </el-table-column>
          <el-table-column prop="drugCode" label="药品编码" min-width="110" />
          <el-table-column prop="drugName" label="药品" min-width="160" />
          <el-table-column prop="specification" label="规格" min-width="130" />
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column prop="availableQty" label="可用库存" width="100" align="right" />
          <el-table-column prop="minStock" label="最低库存" width="100" align="right" />
          <el-table-column label="采购数量" width="145">
            <template #default="{ row }">
              <el-input-number
                v-model="row.orderQty"
                :min="1"
                :precision="0"
                :controls="false"
                :disabled="!canCreate || submitting"
                placeholder="请填写"
                :aria-label="`${row.drugName}采购数量`"
                class="!w-full"
              />
            </template>
          </el-table-column>
          <el-table-column label="含税单价（元）" width="155">
            <template #default="{ row }">
              <el-input-number
                v-model="row.unitPrice"
                :min="0"
                :precision="2"
                :controls="false"
                :disabled="!canCreate || submitting"
                placeholder="请填写"
                :aria-label="`${row.drugName}含税单价`"
                class="!w-full"
              />
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-if="!canCreate"
          title="当前账号没有创建采购单权限，可查看库存预警。"
          type="warning"
          :closable="false"
        />
        <el-form v-else :model="form" label-width="110px" :disabled="submitting">
          <el-row :gutter="20">
            <el-col :xs="24" :md="12">
              <el-form-item label="供应商" required>
                <el-select
                  v-model="form.supplierId"
                  filterable
                  clearable
                  placeholder="请选择已审核通过的供应商"
                  class="!w-full"
                >
                  <el-option
                    v-for="supplier in suppliers"
                    :key="supplier.id"
                    :value="supplier.id"
                    :label="`${supplier.supplierName}（${supplier.supplierCode}）`"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="收货仓库">
                <el-select
                  v-model="form.warehouseId"
                  clearable
                  :disabled="!canWarehouse"
                  placeholder="可留空，收货时须确定"
                  class="!w-full"
                >
                  <el-option
                    v-for="warehouse in warehouses"
                    :key="warehouse.id"
                    :value="warehouse.id"
                    :label="`${warehouse.whName}（${warehouse.whCode}）`"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="下单日期" required>
                <el-date-picker
                  v-model="form.orderDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="请选择下单日期"
                  class="!w-full"
                />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="预计到货">
                <el-date-picker
                  v-model="form.expectDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="可不填写"
                  class="!w-full"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="备注">
            <el-input
              v-model="form.remark"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
          <p v-if="!suppliers.length" class="text-[var(--el-color-warning)]"
            >暂无可采购供应商，请先完成供应商启用与首营审核。</p
          >
          <p v-if="!canWarehouse">当前账号无仓库查询权限，仓库可留空，收货时须由有权限人员确定。</p>
          <p v-else-if="!warehouses.length">本店暂无启用仓库，可留空，收货时须确定。</p>
        </el-form>
      </template>
    </div>
    <el-alert v-if="previewError" :title="previewError" type="error" :closable="false" show-icon />
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
        v-hasPermi="['pharmacy:purchase:order:create']"
        type="primary"
        :loading="submitting"
        :disabled="!canCreate || !loaded || loading || !rows.some((row) => row.selected)"
        @click="submit"
        >生成采购预览</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { checkPermi } from '@/utils/permission'
import { PharmacyAiPurchaseApi } from '@/api/pharmacy/ai/purchase'
import type {
  AiPurchaseRequest,
  InventoryAlerts,
  InventoryAlertStock
} from '@/api/pharmacy/ai/purchase'
import type { PharmacyAiCommand } from '@/api/pharmacy/ai'
import { getSimpleSupplierList, type SupplierSimpleVO } from '@/api/pharmacy/purchase/supplier'
import { getWarehousePage, type WarehouseVO } from '@/api/pharmacy/inventory/warehouse'

type PurchaseRow = InventoryAlertStock & {
  selected: boolean
  orderQty?: number
  unitPrice?: number
}
const emit = defineEmits<{ (e: 'preview', preview: PharmacyAiCommand): void }>()
const visible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const loaded = ref(false)
const keyword = ref('')
const loadError = ref('')
const previewError = ref('')
const alerts = ref<InventoryAlerts>()
const rows = ref<PurchaseRow[]>([])
const suppliers = ref<SupplierSimpleVO[]>([])
const warehouses = ref<WarehouseVO[]>([])
const form = reactive({
  supplierId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  orderDate: '',
  expectDate: '',
  remark: ''
})
const canRead = computed(
  () => checkPermi(['pharmacy:ai:chat']) && checkPermi(['pharmacy:inventory-batch:query'])
)
const canCreate = computed(() => canRead.value && checkPermi(['pharmacy:purchase:order:create']))
const canWarehouse = computed(() => checkPermi(['pharmacy:inventory-warehouse:query']))
let sequence = 0

const loadWarehouses = async () => {
  if (!canWarehouse.value) return []
  const list: WarehouseVO[] = []
  for (let pageNo = 1; ; pageNo++) {
    const page = await getWarehousePage({ pageNo, pageSize: 100, status: 1 })
    list.push(...page.list)
    if (list.length >= page.total) return list
    if (!page.list.length) throw new Error('仓库列表未完整返回，请重试')
  }
}

const load = async () => {
  if (!visible.value || loading.value || submitting.value) return
  loaded.value = false
  rows.value = []
  alerts.value = undefined
  loadError.value = ''
  previewError.value = ''
  if (!canRead.value) {
    loadError.value = '查看预警需要 AI 助手与本店库存查询权限'
    return
  }
  const requestId = ++sequence
  loading.value = true
  try {
    const [snapshot, supplierList, warehouseList] = await Promise.all([
      PharmacyAiPurchaseApi.inventoryAlerts({
        keyword: keyword.value.trim() || undefined,
        limit: 20
      }),
      getSimpleSupplierList(),
      loadWarehouses()
    ])
    if (requestId !== sequence || !visible.value || !canRead.value) return
    alerts.value = snapshot
    rows.value = snapshot.items.map((stock) => ({
      ...stock,
      selected: false,
      orderQty: undefined,
      unitPrice: undefined
    }))
    suppliers.value = supplierList
    warehouses.value = warehouseList.filter(
      (warehouse) => warehouse.storeId === snapshot.storeId && warehouse.status === 1
    )
    loaded.value = true
  } catch (error: any) {
    if (requestId === sequence)
      loadError.value = error?.message || error?.msg || '预警或采购选项加载失败，请重试'
  } finally {
    if (requestId === sequence) loading.value = false
  }
}

const submit = async () => {
  if (loading.value || submitting.value || !loaded.value || !canCreate.value) return
  previewError.value = ''
  const requestId = sequence
  try {
    const supplierId = form.supplierId
    if (!supplierId || !suppliers.value.some((supplier) => supplier.id === supplierId))
      throw new Error('请选择可采购的供应商')
    if (!form.orderDate) throw new Error('请选择下单日期')
    if (
      form.warehouseId &&
      !warehouses.value.some((warehouse) => warehouse.id === form.warehouseId)
    )
      throw new Error('请选择本店启用仓库，或留空后在收货时确定')
    const selected = rows.value.filter((row) => row.selected)
    if (!selected.length) throw new Error('请勾选需要采购的药品')
    const lines = selected.map((row) => {
      const orderQty = row.orderQty,
        unitPrice = row.unitPrice
      if (typeof orderQty !== 'number' || !Number.isInteger(orderQty) || orderQty <= 0)
        throw new Error(`请填写「${row.drugName}」的正整数采购数量`)
      if (typeof unitPrice !== 'number' || !Number.isFinite(unitPrice) || unitPrice < 0)
        throw new Error(`请填写「${row.drugName}」的含税单价，不能小于 0`)
      return { drugId: row.drugId, orderQty, unitPrice }
    })
    const purchase: AiPurchaseRequest = {
      supplierId,
      orderDate: form.orderDate,
      lines,
      warehouseId: form.warehouseId || undefined,
      expectDate: form.expectDate || undefined,
      remark: form.remark.trim() || undefined
    }
    submitting.value = true
    const preview = await PharmacyAiPurchaseApi.preview({ purchase })
    if (requestId !== sequence || !visible.value || !canCreate.value) return
    emit('preview', preview)
    visible.value = false
  } catch (error: any) {
    if (requestId === sequence)
      previewError.value = error?.message || error?.msg || '采购预览生成失败，请重试'
  } finally {
    if (requestId === sequence) submitting.value = false
  }
}

const close = () => {
  sequence++
  loaded.value = false
  loading.value = false
  submitting.value = false
  rows.value = []
  alerts.value = undefined
  suppliers.value = []
  warehouses.value = []
}
const open = () => {
  close()
  keyword.value = ''
  form.supplierId = undefined
  form.warehouseId = undefined
  form.orderDate = ''
  form.expectDate = ''
  form.remark = ''
  visible.value = true
  return load()
}
defineExpose({ open })
</script>
