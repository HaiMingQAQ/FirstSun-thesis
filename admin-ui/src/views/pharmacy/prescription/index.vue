<template>
  <div class="pharmacy-modern-page">
    <PharmacyPageHeader
      title="处方台账"
      eyebrow="PRESCRIPTION LEDGER"
      icon="ep:document"
      total-label="处方总数"
      :total="total"
      :current-count="list.length"
      page-type="处方管理"
      :loading="loading"
      subtitle="FirstSun 药店管理系统 · 处方审核与统一支付"
    />
    <ContentWrap class="pharmacy-panel">
      <el-form class="-mb-15px" :model="queryParams" ref="queryFormRef" :inline="true" label-width="80px">
        <el-form-item label="处方号" prop="prescNo">
          <el-input v-model="queryParams.prescNo" placeholder="处方号" clearable class="!w-200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="患者" prop="patientName">
          <el-input v-model="queryParams.patientName" placeholder="患者姓名" clearable class="!w-140px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="来源" prop="source">
          <el-select v-model="queryParams.source" placeholder="请选择" clearable class="!w-130px">
            <el-option v-for="item in SOURCE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="审方状态" prop="reviewStatus">
          <el-select v-model="queryParams.reviewStatus" placeholder="请选择" clearable class="!w-120px">
            <el-option v-for="item in REVIEW_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="记录状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="请选择" clearable class="!w-120px">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="开方日期">
          <el-date-picker
            v-model="dateRange"
            value-format="YYYY-MM-DD"
            type="daterange"
            range-separator="-"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="!w-260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
          <el-button type="primary" plain @click="router.push('/pharmacy-rx/prescription/create')">
            <Icon icon="ep:plus" class="mr-5px" /> 登记处方
          </el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>

    <ContentWrap class="pharmacy-panel">
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="mb-12px" />
      <el-table v-loading="loading" :data="list">
        <el-table-column label="处方号" align="center" prop="prescNo" min-width="180" show-overflow-tooltip />
        <el-table-column label="门店" align="center" prop="storeName" min-width="140" show-overflow-tooltip />
        <el-table-column label="登记方式" align="center" width="100">
          <template #default="scope">{{ channelLabel(scope.row.wxMemberId) }}</template>
        </el-table-column>
        <el-table-column label="来源" align="center" width="110">
          <template #default="scope">{{ sourceLabel(scope.row.source) }}</template>
        </el-table-column>
        <el-table-column label="患者" align="center" prop="patientName" width="90" show-overflow-tooltip />
        <el-table-column label="医师" align="center" prop="doctorName" width="90" show-overflow-tooltip />
        <el-table-column label="审方状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="reviewTagType(scope.row.reviewStatus)">{{ reviewLabel(scope.row.reviewStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="记录状态" align="center" width="90">
          <template #default="scope">{{ recordStatusLabel(scope.row.status) }}</template>
        </el-table-column>
        <el-table-column label="特管" align="center" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.isSpecial === 1" type="danger" size="small">双人复核</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="超量" align="center" width="70">
          <template #default="scope">
            <el-tag v-if="scope.row.limitCheck === 1" type="warning" size="small">是</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="开方日期" align="center" prop="prescDate" width="110" />
        <el-table-column label="操作" align="center" min-width="220" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="handleDetail(scope.row.id)">详情</el-button>
            <el-button
              v-if="scope.row.reviewStatus === 0 && scope.row.status === 0"
              v-hasPermi="['pharmacy:prescription:review']"
              link
              type="success"
              @click="openReview(scope.row)"
            >
              {{ scope.row.wxMemberId != null ? '去审核' : '审核' }}
            </el-button>
            <el-button v-if="scope.row.status === 0" v-hasPermi="['pharmacy:prescription:update']" link type="danger" :loading="invalidatingId === scope.row.id" @click="handleInvalidate(scope.row)">
              作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <el-drawer v-model="detailVisible" title="处方详情" size="min(760px, 90vw)" destroy-on-close @closed="closeDetail">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" class="mb-12px" />
      <div v-if="detailLoading">正在读取处方…</div>
      <el-descriptions v-else-if="detail" :column="2" border>
        <el-descriptions-item label="处方号">{{ detail.prescNo }}</el-descriptions-item>
        <el-descriptions-item label="门店">{{ detail.storeName || detail.storeId }}</el-descriptions-item>
        <el-descriptions-item label="登记方式">{{ channelLabel(detail.wxMemberId) }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceLabel(detail.source) }}</el-descriptions-item>
        <el-descriptions-item label="患者">{{ detail.patientName }}</el-descriptions-item>
        <el-descriptions-item label="年龄">{{ detail.patientAge ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="医师">{{ detail.doctorName ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="医院">{{ detail.hospital ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="诊断">{{ detail.diagnosis ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="开方日期">{{ detail.prescDate ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="记录状态">{{ recordStatusLabel(detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="审方状态">
          <el-tag :type="reviewTagType(detail.reviewStatus)">{{ reviewLabel(detail.reviewStatus) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="审方药师" :span="2">{{ detail.pharmacistName ?? (detail.pharmacistId ?? '-') }}</el-descriptions-item>
        <el-descriptions-item label="复核人" :span="2">{{ detail.dblCheckByName || detail.dblCheckBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审方意见" :span="2">{{ detail.reviewOpinion ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="用法用量" :span="2">{{ detail.usageDesc ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="有效截止" :span="2">{{ detail.approvedUntil ? formatDate(detail.approvedUntil) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="处方材料" :span="2">{{ materialText }}</el-descriptions-item>
        <el-descriptions-item label="申请明细" :span="2">
          <el-table :data="requestedItems" size="small" border empty-text="没有申请明细">
            <el-table-column label="药品" prop="drugName" min-width="120" show-overflow-tooltip />
            <el-table-column label="规格" prop="specification" min-width="100" show-overflow-tooltip />
            <el-table-column label="申请数量" prop="qty" width="90" align="center" />
          </el-table>
        </el-descriptions-item>
        <el-descriptions-item label="核准明细" :span="2">
          <el-table :data="approvedItems" size="small" border empty-text="尚未核准">
            <el-table-column label="药品" prop="drugName" min-width="120" show-overflow-tooltip />
            <el-table-column label="规格" prop="specification" min-width="100" show-overflow-tooltip />
            <el-table-column label="核准数量" prop="qty" width="90" align="center" />
            <el-table-column label="用法" prop="usage" min-width="100" show-overflow-tooltip />
            <el-table-column label="用量" prop="dosage" min-width="100" show-overflow-tooltip />
          </el-table>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.uses?.length" label="使用记录" :span="2">
          <div v-for="use in detail.uses" :key="use.wxOrderId">订单 {{ use.wxOrderId }} · {{ useLabel(use.status) }} {{ use.releaseReason || '' }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>

    <el-dialog v-model="reviewVisible" title="药师审核" width="min(560px, 94vw)" destroy-on-close @closed="closeReview">
      <el-alert v-if="reviewTarget?.wxMemberId == null" title="这里只审核门店登记处方。顾客申请请到处方审核页查看材料和核准明细。" type="info" :closable="false" class="mb-12px" />
      <el-form ref="reviewFormRef" :model="reviewForm" label-width="110px">
        <el-form-item label="处方号">
          <el-input :model-value="reviewTarget?.prescNo" disabled />
        </el-form-item>
        <el-form-item label="患者">
          <el-input :model-value="reviewTarget?.patientName" disabled />
        </el-form-item>
        <el-form-item label="审核结果">
          <el-radio-group v-model="reviewForm.reviewStatus" :disabled="submitState === 'unknown'">
            <el-radio :value="1">通过</el-radio>
            <el-radio :value="2">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="reviewTarget?.isSpecial === 1 && reviewForm.reviewStatus === 1" label="双人复核人">
          <el-input-number v-model="reviewForm.dblCheckBy" :min="1" :controls="false" :disabled="submitState === 'unknown'" placeholder="复核员工档案编号" class="!w-full" />
        </el-form-item>
        <el-form-item :label="reviewForm.reviewStatus === 2 || reviewTarget?.limitCheck === 1 ? '审方意见(必填)' : '审方意见'">
          <el-input v-model="reviewForm.reviewOpinion" type="textarea" :rows="3" maxlength="300" show-word-limit :disabled="submitState === 'unknown'" placeholder="驳回或超量通过时必填" />
        </el-form-item>
        <el-alert v-if="submitState === 'unknown'" title="提交结果未确认。请先刷新这条处方，不要直接重提。" type="warning" :closable="false" />
      </el-form>
      <template #footer>
        <el-button v-if="submitState === 'unknown'" :loading="reviewLoading" @click="reloadLedgerReview">刷新审核结果</el-button>
        <el-button v-else v-hasPermi="['pharmacy:prescription:review']" :loading="reviewLoading" type="primary" @click="submitReview">提交</el-button>
        <el-button :disabled="reviewLoading" @click="reviewVisible = false">取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { PrescRecordApi, PrescRecordVO } from '@/api/pharmacy/prescription/prescRecord'
import { formatDate } from '@/utils/formatTime'
import {
  buildReviewPayload,
  channelLabel,
  describeInvalidate,
  describeReviewOutcome,
  errorText,
  initialDecision,
  materialSummary,
  parseItems,
  recordStatusLabel,
  reviewLabel,
  sourceLabel,
  useLabel
} from './review-flow'

defineOptions({ name: 'PharmacyPrescriptionIndex' })

const router = useRouter()
const message = useMessage()

const loading = ref(true)
const total = ref(0)
const list = ref<PrescRecordVO[]>([])
const listError = ref('')
const dateRange = ref<string[]>([])
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  prescNo: '',
  patientName: '',
  source: undefined as number | undefined,
  reviewStatus: undefined as number | undefined,
  status: undefined as number | undefined
})
const queryFormRef = ref()
let listGeneration = 0

const SOURCE_OPTIONS = [
  { value: 0, label: '纸质拍照' },
  { value: 1, label: '电子处方平台' },
  { value: 2, label: '复诊续方' }
]
const REVIEW_OPTIONS = [
  { value: 0, label: '待审' },
  { value: 1, label: '通过' },
  { value: 2, label: '驳回' }
]
const STATUS_OPTIONS = [
  { value: 0, label: '有效' },
  { value: 1, label: '已完成' },
  { value: 2, label: '作废' }
]
const reviewTagType = (value: number) => (value === 1 ? 'success' : value === 2 ? 'danger' : 'info')

const getList = async () => {
  const ticket = ++listGeneration
  loading.value = true
  listError.value = ''
  try {
    const params: Record<string, unknown> = { ...queryParams }
    if (dateRange.value?.length === 2) params.prescDate = dateRange.value
    const data = await PrescRecordApi.getPrescRecordPage(params)
    if (ticket !== listGeneration) return
    list.value = data.list
    total.value = data.total
  } catch (reason) {
    if (ticket !== listGeneration) return
    listError.value = errorText(reason, '处方列表加载失败')
  } finally {
    if (ticket === listGeneration) loading.value = false
  }
}
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}
const resetQuery = () => {
  queryFormRef.value?.resetFields()
  dateRange.value = []
  handleQuery()
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<PrescRecordVO | null>(null)
let detailGeneration = 0
const requestedItems = computed(() => parseItems(detail.value?.prescribedItems).items)
const approvedItems = computed(() => parseItems(detail.value?.approvedItems).items)
const materialText = computed(() => {
  if (detail.value?.wxMemberId == null) return '门店登记处方，材料以登记信息为准'
  const summary = materialSummary(detail.value?.images)
  if (!summary.ok) return summary.message
  return `私有材料 ${summary.count} 张，请在处方审核中查看，此处不展示图片`
})

const handleDetail = async (id: number) => {
  const generation = ++detailGeneration
  detailError.value = ''
  detailLoading.value = true
  detailVisible.value = true
  if (detail.value?.id !== id) detail.value = null
  try {
    const row = await PrescRecordApi.getPrescRecord(id)
    if (generation !== detailGeneration) return
    detail.value = row
  } catch (reason) {
    if (generation !== detailGeneration) return
    detail.value = null
    detailError.value = errorText(reason, '处方详情加载失败')
  } finally {
    if (generation === detailGeneration) detailLoading.value = false
  }
}
const closeDetail = () => {
  detailGeneration++
  detailLoading.value = false
}

const reviewVisible = ref(false)
const reviewLoading = ref(false)
const reviewTarget = ref<PrescRecordVO | null>(null)
const reviewFormRef = ref()
const reviewForm = reactive({
  reviewStatus: 1 as number | undefined,
  reviewOpinion: '',
  reviewSnapshot: '',
  dblCheckBy: undefined as number | undefined
})
const submitState = ref<'idle' | 'unknown'>('idle')
let reviewGeneration = 0

const openReview = async (row: PrescRecordVO) => {
  if (reviewLoading.value) return
  if (row.wxMemberId != null) {
    router.push({ path: '/pharmacy-rx/prescription/review', query: { id: String(row.id) } })
    return
  }
  reviewLoading.value = true
  const generation = ++reviewGeneration
  try {
    const fresh = await PrescRecordApi.getPrescRecord(row.id)
    if (generation !== reviewGeneration) return
    if (fresh.reviewStatus !== 0 || fresh.status !== 0) {
      message.warning(`处方当前是${reviewLabel(fresh.reviewStatus)} / ${recordStatusLabel(fresh.status)}，不能重复审核`)
      const index = list.value.findIndex((item) => item.id === fresh.id)
      if (index >= 0) list.value[index] = { ...list.value[index], ...fresh }
      return
    }
    reviewTarget.value = fresh
    reviewForm.reviewStatus = initialDecision(fresh.reviewStatus, 1)
    reviewForm.reviewOpinion = ''
    reviewForm.dblCheckBy = undefined
    submitState.value = 'idle'
    reviewVisible.value = true
  } catch (reason) {
    if (generation === reviewGeneration) message.error(errorText(reason, '读取处方失败，请刷新重试'))
  } finally {
    if (generation === reviewGeneration) reviewLoading.value = false
  }
}
const closeReview = () => {
  if (reviewVisible.value) return
  reviewGeneration++
  reviewLoading.value = false
}

const applyReviewResult = async (id: number, submitted: boolean, refreshOnly = false) => {
  const fresh = await PrescRecordApi.getPrescRecord(id)
  const index = list.value.findIndex((item) => item.id === id)
  if (index >= 0) list.value[index] = { ...list.value[index], ...fresh }
  if (detail.value?.id === id) detail.value = fresh
  const outcome = describeReviewOutcome(fresh)
  if (outcome === 'confirmed') {
    submitState.value = 'idle'
    message.success(fresh.reviewStatus === 1 ? '服务端已记录为通过' : '服务端已记录为驳回')
    reviewVisible.value = false
    await getList()
    return
  }
  if (outcome === 'pending') {
    submitState.value = 'idle'
    reviewTarget.value = fresh
    message.warning(refreshOnly ? '服务端仍是待审，可以继续审核' : submitted ? '服务端仍是待审，未记为审核成功，请核对后重试' : '提交没有完成，处方仍是待审，可以再次提交')
    return
  }
  submitState.value = 'unknown'
  message.warning('仍不能确认审核结果，请先刷新')
}

const submitReview = async () => {
  if (!reviewTarget.value || reviewLoading.value || submitState.value === 'unknown' || reviewTarget.value.wxMemberId != null) return
  const currentId = reviewTarget.value.id
  const generation = reviewGeneration
  const decision = buildReviewPayload({
    id: currentId,
    wxMemberId: null,
    isSpecial: reviewTarget.value.isSpecial,
    limitCheck: reviewTarget.value.limitCheck,
    decision: reviewForm.reviewStatus,
    opinion: reviewForm.reviewOpinion,
    dblCheckBy: reviewForm.dblCheckBy,
    approvedItems: [],
    materialReady: true
  })
  if (decision.error || !decision.payload) {
    message.warning(decision.error || '请补全审核信息')
    return
  }
  reviewLoading.value = true
  let submitted = false
  try {
    await PrescRecordApi.reviewPrescRecord({ ...decision.payload, reviewSnapshot: reviewForm.reviewSnapshot })
    submitted = true
  } catch {
    submitted = false
  }
  if (generation !== reviewGeneration) {
    reviewLoading.value = false
    return
  }
  try {
    await applyReviewResult(currentId, submitted)
  } catch {
    if (generation !== reviewGeneration) return
    submitState.value = 'unknown'
    message.warning(submitted ? '审核请求已返回，但回读失败。请先刷新确认结果，不要直接重提。' : '提交结果未确认。请先刷新这条处方，不要直接重提。')
  } finally {
    if (generation === reviewGeneration) reviewLoading.value = false
  }
}

const reloadLedgerReview = async () => {
  if (!reviewTarget.value || reviewLoading.value) return
  const id = reviewTarget.value.id
  const generation = reviewGeneration
  reviewLoading.value = true
  try {
    await applyReviewResult(id, false, true)
    if (generation !== reviewGeneration) return
  } catch (reason) {
    if (generation === reviewGeneration) message.warning(errorText(reason, '刷新审核结果失败，请稍后再试'))
  } finally {
    if (generation === reviewGeneration) reviewLoading.value = false
  }
}

const invalidatingId = ref<number | null>(null)
const handleInvalidate = async (row: PrescRecordVO) => {
  if (invalidatingId.value) return
  invalidatingId.value = row.id
  try {
    await message.confirm(`确认作废处方「${row.prescNo}」？`)
  } catch {
    invalidatingId.value = null
    return
  }
  let submitted = false
  try {
    await PrescRecordApi.invalidatePrescRecord(row.id)
    submitted = true
  } catch {
    submitted = false
  }
  try {
    const fresh = await PrescRecordApi.getPrescRecord(row.id)
    const outcome = describeInvalidate(fresh)
    const index = list.value.findIndex((item) => item.id === row.id)
    if (index >= 0) list.value[index] = { ...list.value[index], ...fresh }
    if (detail.value?.id === row.id) detail.value = fresh
    if (outcome === 'done') {
      message.success('服务端已记录为作废')
      await getList()
      return
    }
    message.warning(submitted ? '作废请求已返回，但记录尚未作废' : '作废没有完成，记录状态未变')
  } catch {
    message.warning(submitted ? '作废请求已返回，但回读失败，请刷新后再确认' : '作废结果未确认，请刷新后再操作')
  } finally {
    invalidatingId.value = null
  }
}

onMounted(() => {
  getList()
})
</script>
