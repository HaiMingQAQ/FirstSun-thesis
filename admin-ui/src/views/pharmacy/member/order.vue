<template>
  <ContentWrap class="pharmacy-panel">
    <!-- 搜索工作栏 -->
    <el-form
      class="-mb-15px"
      :model="queryParams"
      ref="queryFormRef"
      :inline="true"
      label-width="80px"
    >
      <el-form-item label="订单号" prop="orderNo">
        <el-input
          v-model="queryParams.orderNo"
          placeholder="请输入订单号"
          clearable
          class="!w-240px"
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="提货码" prop="pickupCode"><el-input v-model="queryParams.pickupCode" placeholder="精确查找待自提单" clearable @keyup.enter="handleQuery" /></el-form-item>
      <el-form-item label="会员ID" prop="memberId">
        <el-input
          v-model="queryParams.memberId"
          placeholder="请输入会员ID"
          clearable
          class="!w-240px"
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="订单状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="请选择订单状态" clearable class="!w-240px">
          <el-option
            v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_WX_ORDER_STATUS)"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="支付状态" prop="payStatus">
        <el-select v-model="queryParams.payStatus" placeholder="请选择支付状态" clearable class="!w-240px">
          <el-option
            v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_WX_PAY_STATUS)"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="订单类型" prop="orderType">
        <el-select v-model="queryParams.orderType" placeholder="请选择订单类型" clearable class="!w-240px">
          <el-option
            v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_WX_ORDER_TYPE)"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
        <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <!-- 列表 -->
  <ContentWrap class="pharmacy-panel">
    <el-alert v-if="pickingError" :title="pickingError" type="error" :closable="false" class="mb-4" />
    <el-table v-loading="loading" :data="list" empty-text="没有订单" :show-overflow-tooltip="true">
      <el-table-column label="订单号 / 履约" min-width="210" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <div class="order-number">{{ row.orderNo }}</div>
          <dict-tag :type="DICT_TYPE.PHARMACY_WX_ORDER_TYPE" :value="row.orderType" />
        </template>
      </el-table-column>
      <el-table-column label="订单状态" align="center" prop="status" width="120">
        <template #default="{ row }"><dict-tag :type="DICT_TYPE.PHARMACY_WX_ORDER_STATUS" :value="row.status" /></template>
      </el-table-column>
      <el-table-column label="支付状态" align="center" prop="payStatus" width="110">
        <template #default="{ row }"><dict-tag :type="DICT_TYPE.PHARMACY_WX_PAY_STATUS" :value="row.payStatus" /></template>
      </el-table-column>
      <el-table-column label="应付金额" align="right" width="140">
        <template #default="{ row }">
          <strong>¥{{ formatMoney(row.payableAmount) }}</strong>
          <div class="order-secondary">商品 ¥{{ formatMoney(row.goodsAmount) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="240" fixed="right">
        <template #default="scope">
          <el-button
            size="small"
            @click="openDetail(scope.row.id)"
            v-hasPermi="['pharmacy:member:order:query']"
          >
            详情
          </el-button>
          <el-button
            v-if="scope.row.status === 1 && scope.row.payStatus === 1"
            v-hasPermi="['pharmacy:member:order:update']"
            size="small"
            class="order-main-action"
            type="primary"
            :loading="pickingId === scope.row.id"
            :disabled="loading || pickingId !== undefined"
            @click="handlePicking(scope.row, false)"
          >开始拣货</el-button>
          <el-button
            v-if="scope.row.status === 2 && scope.row.payStatus === 1"
            v-hasPermi="['pharmacy:member:order:update']"
            size="small"
            class="order-main-action"
            type="primary"
            :loading="pickingId === scope.row.id"
            :disabled="loading || pickingId !== undefined"
            @click="handlePicking(scope.row, true)"
          >完成拣货</el-button>
          <el-dropdown
            v-if="scope.row.status === 0 || scope.row.status === 1"
            v-hasPermi="['pharmacy:member:order:cancel']"
            @command="handleCancel(scope.row.id)"
          >
            <el-button size="small">更多</el-button>
            <template #dropdown>
              <el-dropdown-menu><el-dropdown-item command="cancel" class="order-cancel-action">取消订单</el-dropdown-item></el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button
            v-if="scope.row.orderType === 0 && scope.row.payStatus === 1 && scope.row.status === 3"
            size="small"
            class="order-main-action"
            type="primary"
            @click="handleVerify(scope.row)"
            v-hasPermi="['pharmacy:member:order:verify']"
            :disabled="loading || verifyLoading || pickingId !== undefined"
          >
            核销
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <!-- 分页 -->
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </ContentWrap>

  <!-- 订单详情抽屉 -->
  <OrderDetail ref="detailRef" @success="handleDetailSuccess" />

  <!-- 核销对话框：提货码必填；核销员工由服务端根据登录用户确认 -->
  <el-dialog v-model="verifyVisible" title="订单核销" width="min(420px, 94vw)">
    <el-form ref="verifyFormRef" :model="verifyForm" :rules="verifyRules" label-width="80px">
      <el-form-item label="订单号">
        <span>{{ verifyForm.orderNo }}</span>
      </el-form-item>
      <el-form-item label="提货码" prop="pickupCode">
        <el-input
          v-model="verifyForm.pickupCode"
          placeholder="请输入提货码"
          clearable
          @keyup.enter="submitVerify"
        />
      </el-form-item>
      <el-form-item label="核销人">
        <span>由当前账号关联的在职员工档案确定</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="verifyVisible = false">取 消</el-button>
      <el-button type="primary" :loading="verifyLoading" @click="submitVerify">确认核销</el-button>
    </template>
  </el-dialog>
</template>
<script lang="ts" setup>
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import { checkPermi } from '@/utils/permission'
import * as OrderApi from '@/api/pharmacy/member/order'
import OrderDetail from './OrderDetail.vue'

defineOptions({ name: 'PharmacyMemberOrder' })

const message = useMessage() // 消息弹窗

const loading = ref(true) // 列表的加载中
const total = ref(0) // 列表的总页数
const list = ref([]) // 列表的数据
const pickingId = ref<number>()
const pickingError = ref('')
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  orderNo: '',
  pickupCode: '',
  memberId: undefined,
  status: undefined as number | undefined,
  payStatus: undefined,
  orderType: undefined
})
const queryFormRef = ref() // 搜索的表单

/** 金额格式化 */
const formatMoney = (val: number) => {
  return val ? val.toFixed(2) : '0.00'
}

/** 查询订单列表 */
const getList = async () => {
  loading.value = true
  try {
    const data = await OrderApi.getOrderPage(queryParams)
    list.value = data.list
    total.value = data.total
    pickingError.value = ''
  } finally {
    loading.value = false
  }
}

/** 操作成功后刷新列表 */
const handleDetailSuccess = async () => {
  await getList()
}

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pickupCode = queryParams.pickupCode.trim()
  if (queryParams.pickupCode) queryParams.status = 3
  queryParams.pageNo = 1
  getList()
}

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value.resetFields()
  handleQuery()
}

/** 查看详情 */
const detailRef = ref()
const openDetail = (id: number) => {
  detailRef.value.open(id)
}

const handlePicking = async (row: OrderApi.MemberOrderVO, finish: boolean) => {
  const id = row.id, expected = finish ? 2 : 1, label = finish ? '完成拣货' : '开始拣货'
  if (!id || loading.value || pickingId.value !== undefined || row.payStatus !== 1 || row.status !== expected
    || !checkPermi(['pharmacy:member:order:update'])) return
  pickingId.value = id
  pickingError.value = ''
  let changed = false
  try {
    await message.confirm(`确认${label}订单「${row.orderNo}」？`, label)
    if (row.status !== expected || row.payStatus !== 1 || !checkPermi(['pharmacy:member:order:update'])) return
    if (finish) await OrderApi.finishPicking(id)
    else await OrderApi.startPicking(id)
    changed = true
    await getList()
    message.success(`${label}成功`)
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') pickingError.value = changed
      ? `${label}已完成，但订单列表刷新失败，请重新查询`
      : error?.message || error?.msg || `${label}失败，请重试`
  } finally {
    pickingId.value = undefined
  }
}

/** 取消订单：取消原因由操作人填写并落库 */
const handleCancel = async (id: number) => {
  try {
    const { value } = await message.prompt('请输入取消原因', '取消订单')
    await OrderApi.cancelOrder(id, value || '后台取消')
    message.success('订单取消成功')
    await getList()
  } catch {}
}

/** 核销订单：只提交订单与提货码，服务端从登录用户解析核销员工 */
const verifyVisible = ref(false)
const verifyLoading = ref(false)
const verifyFormRef = ref()
const verifyForm = reactive({
  id: undefined as number | undefined,
  orderNo: '',
  pickupCode: ''
})
const verifyRules = reactive({
  pickupCode: [{ required: true, message: '提货码不能为空', trigger: 'blur' }]
})

const handleVerify = (row: any) => {
  if (loading.value || row.orderType !== 0 || row.payStatus !== 1 || row.status !== 3) return
  verifyForm.id = row.id
  verifyForm.orderNo = row.orderNo
  verifyForm.pickupCode = ''
  verifyVisible.value = true
}

const submitVerify = async () => {
  if (!verifyFormRef.value) return
  const valid = await verifyFormRef.value.validate()
  if (!valid) return
  verifyLoading.value = true
  try {
    await OrderApi.verifyOrder(verifyForm.id!, verifyForm.pickupCode)
    message.success('订单核销成功')
    verifyVisible.value = false
    await getList()
  } catch {
    // 失败时保留后端业务错误提示（由请求拦截器统一弹出），此处不做覆盖
  } finally {
    verifyLoading.value = false
  }
}

/** 初始化 **/
const route = useRoute()
watch(() => route.query.status, (value) => {
  const status = Number(value)
  queryParams.status = [1, 2, 3].includes(status) ? status : undefined
  if (queryParams.status !== undefined) {
    queryParams.orderNo = ''
    queryParams.pickupCode = ''
    queryParams.memberId = undefined
    queryParams.payStatus = undefined
    queryParams.orderType = undefined
  }
  queryParams.pageNo = 1
  getList()
}, { immediate: true })
</script>

<style scoped>
.order-number { white-space: normal; overflow-wrap: anywhere; user-select: all; margin-bottom: 4px; }
.order-secondary { color: #606266; font-size: 14px; }
.pharmacy-panel :deep(.el-dropdown) { margin-left: 8px; }
.order-main-action { min-height: 32px; background: #176b5b; border-color: #176b5b; color: #fff; }
.order-main-action:not(.is-disabled):hover { background: #125447; border-color: #125447; }
.order-cancel-action { color: #b42318; font-size: 14px; }
</style>
