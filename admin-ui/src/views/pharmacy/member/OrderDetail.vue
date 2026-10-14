<template>
  <el-drawer
    v-model="drawerVisible"
    title="订单详情"
    direction="rtl"
    size="700px"
    :before-close="handleClose"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-skeleton v-if="loading" :rows="5" animated />
    <div v-if="loaded">
      <!-- 订单基本信息 -->
      <el-divider content-position="left">订单信息</el-divider>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单号">{{ orderData.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="订单类型">
          <dict-tag :type="DICT_TYPE.PHARMACY_WX_ORDER_TYPE" :value="orderData.orderType" />
        </el-descriptions-item>
        <el-descriptions-item label="订单状态">
          <dict-tag :type="DICT_TYPE.PHARMACY_WX_ORDER_STATUS" :value="orderData.status" />
        </el-descriptions-item>
        <el-descriptions-item label="支付状态">
          <dict-tag :type="DICT_TYPE.PHARMACY_WX_PAY_STATUS" :value="orderData.payStatus" />
        </el-descriptions-item>
        <el-descriptions-item label="会员ID">{{ orderData.memberId }}</el-descriptions-item>
        <el-descriptions-item label="门店ID">{{ orderData.storeId }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">
          {{ formatDate(orderData.createTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="支付单号">{{ orderData.payNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="支付时间">
          {{ formatDate(orderData.paidAt) || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="完成时间">
          {{ formatDate(orderData.finishAt) || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="核销人">{{ orderData.verifyBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="核销时间">
          {{ formatDate(orderData.verifyAt) || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="过期时间">
          {{ formatDate(orderData.expireAt) || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="取货码">{{ orderData.pickupCode || '-' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 金额信息 -->
      <el-divider content-position="left">金额信息</el-divider>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="商品金额">
          ¥{{ formatMoney(orderData.goodsAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="优惠金额">
          <span class="text-green-500">-¥{{ formatMoney(orderData.discountAmount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="优惠券金额">
          <span class="text-green-500">-¥{{ formatMoney(orderData.couponAmount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="运费金额">
          ¥{{ formatMoney(orderData.freightAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="应付金额" :span="2">
          <span class="text-red-500 text-lg font-bold">
            ¥{{ formatMoney(orderData.payableAmount) }}
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 其他信息 -->
      <el-divider content-position="left">其他信息</el-divider>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="处方ID">
          {{ orderData.prescId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="取消原因">
          {{ orderData.cancelReason || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="订单备注">
          {{ orderData.remark || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 订单明细 -->
      <el-divider content-position="left">订单明细</el-divider>
      <el-empty description="暂无明细数据" v-if="!orderItems.length" />
      <el-table v-else :data="orderItems" size="small">
        <el-table-column label="商品名称" prop="drugName" />
        <el-table-column label="规格" prop="specification" />
        <el-table-column label="批号" prop="batchNo" />
        <el-table-column label="数量" align="center" prop="qty" width="80" />
        <el-table-column label="单价" align="center" prop="price" width="100">
          <template #default="scope">¥{{ formatMoney(scope.row.price) }}</template>
        </el-table-column>
        <el-table-column label="小计" align="center" prop="lineAmount" width="100">
          <template #default="scope">¥{{ formatMoney(scope.row.lineAmount) }}</template>
        </el-table-column>
      </el-table>
    </div>
    <template #footer>
      <el-button
        v-if="loaded && orderData.status === 1 && orderData.payStatus === 1"
        v-hasPermi="['pharmacy:member:order:update']"
        type="primary"
        :loading="picking"
        :disabled="loading || picking"
        @click="handlePicking(false)"
      >开始拣货</el-button>
      <el-button
        v-if="loaded && orderData.status === 2 && orderData.payStatus === 1"
        v-hasPermi="['pharmacy:member:order:update']"
        type="primary"
        :loading="picking"
        :disabled="loading || picking"
        @click="handlePicking(true)"
      >完成拣货</el-button>
    </template>
  </el-drawer>
</template>
<script lang="ts" setup>
import { DICT_TYPE } from '@/utils/dict'
import { formatDate } from '@/utils/formatTime'
import { checkPermi } from '@/utils/permission'
import * as OrderApi from '@/api/pharmacy/member/order'

defineOptions({ name: 'PharmacyMemberOrderDetail' })

const drawerVisible = ref(false)
const loading = ref(false)
const loaded = ref(false)
const picking = ref(false)
const error = ref('')
let sequence = 0

const orderData = ref<OrderApi.MemberOrderVO>({
  id: undefined,
  orderNo: '',
  memberId: 0,
  storeId: 0,
  orderType: 0,
  goodsAmount: 0,
  couponAmount: 0,
  freightAmount: 0,
  discountAmount: 0,
  payableAmount: 0,
  payStatus: 0,
  status: 0,
  payNo: '',
  paidAt: new Date(),
  prescId: 0,
  cancelReason: '',
  remark: '',
  finishAt: new Date(),
  pickupCode: '',
  verifyBy: '',
  verifyAt: new Date(),
  expireAt: new Date()
})

const orderItems = ref<OrderApi.MemberOrderLineVO[]>([])

/** 金额格式化 */
const formatMoney = (val: number) => {
  return val ? val.toFixed(2) : '0.00'
}

const load = async (id: number) => {
  const requestId = ++sequence
  loading.value = true
  loaded.value = false
  orderItems.value = []
  error.value = ''
  try {
    const [data, lines] = await Promise.all([OrderApi.getOrder(id), OrderApi.getOrderLineList(id)])
    if (requestId !== sequence || !drawerVisible.value) return false
    orderData.value = data
    orderItems.value = lines
    loaded.value = true
    return true
  } catch (failure: any) {
    if (requestId === sequence) error.value = failure?.message || failure?.msg || '订单详情加载失败，请重新打开重试'
    return false
  } finally {
    if (requestId === sequence) loading.value = false
  }
}

/** 打开详情抽屉 */
const open = (id: number) => {
  drawerVisible.value = true
  return load(id)
}
defineExpose({ open })

const emit = defineEmits(['success'])

const handlePicking = async (finish: boolean) => {
  const id = orderData.value.id, expected = finish ? 2 : 1, label = finish ? '完成拣货' : '开始拣货'
  if (!id || !loaded.value || loading.value || picking.value || orderData.value.payStatus !== 1
    || orderData.value.status !== expected || !checkPermi(['pharmacy:member:order:update'])) return
  const requestId = sequence
  picking.value = true
  try {
    await message.confirm(`确认${label}订单「${orderData.value.orderNo}」？`, label)
    if (requestId !== sequence || !drawerVisible.value || !checkPermi(['pharmacy:member:order:update'])) return
    if (finish) await OrderApi.finishPicking(id)
    else await OrderApi.startPicking(id)
    if (requestId !== sequence || !drawerVisible.value) return
    emit('success')
    const refreshId = sequence + 1, refreshed = await load(id)
    if (refreshId !== sequence || !drawerVisible.value) return
    if (refreshed) message.success(`${label}成功`)
    else error.value = `${label}已完成，但订单详情刷新失败，请重新打开查看`
  } catch (failure: any) {
    if (requestId === sequence && failure !== 'cancel' && failure !== 'close') error.value = failure?.message || failure?.msg || `${label}失败，请重试`
  } finally {
    picking.value = false
  }
}

const message = useMessage()

/** 关闭前重置 */
const handleClose = (done: () => void) => {
  sequence++
  loaded.value = false
  loading.value = false
  orderItems.value = []
  done()
}
</script>
