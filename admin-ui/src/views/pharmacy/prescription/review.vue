<template>
  <div class="pharmacy-modern-page">
    <PharmacyPageHeader
      title="处方审核"
      eyebrow="PRESCRIPTION REVIEW"
      icon="ep:finished"
      total-label="待审处方"
      :total="total"
      :current-count="list.length"
      page-type="处方管理"
      :loading="loading"
      subtitle="FirstSun 药店管理系统 · 药师审方工作台"
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
          <el-select v-model="queryParams.source" placeholder="请选择" clearable class="!w-140px">
            <el-option v-for="item in SOURCE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="审方状态" prop="reviewStatus">
          <el-select v-model="queryParams.reviewStatus" placeholder="请选择" clearable class="!w-130px">
            <el-option v-for="item in REVIEW_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="记录状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="请选择" clearable class="!w-130px">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
          <el-button type="warning" plain @click="queryOnlyWaiting">
            <Icon icon="ep:alarm-clock" class="mr-5px" /> 只看待审
          </el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>

    <ContentWrap class="pharmacy-panel">
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="mb-12px" />
      <el-table v-loading="loading" :data="list">
        <el-table-column label="处方号" align="center" prop="prescNo" min-width="180" show-overflow-tooltip />
        <el-table-column label="门店" align="center" min-width="120" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.storeName || scope.row.storeId }}</template>
        </el-table-column>
        <el-table-column label="登记方式" align="center" width="100">
          <template #default="scope">{{ channelLabel(scope.row.wxMemberId) }}</template>
        </el-table-column>
        <el-table-column label="来源" align="center" width="110">
          <template #default="scope">{{ sourceLabel(scope.row.source) }}</template>
        </el-table-column>
        <el-table-column label="患者" align="center" prop="patientName" width="90" show-overflow-tooltip />
        <el-table-column label="年龄" align="center" prop="patientAge" width="70" />
        <el-table-column label="医师" align="center" prop="doctorName" width="90" show-overflow-tooltip />
        <el-table-column label="诊断" align="center" prop="diagnosis" min-width="120" show-overflow-tooltip />
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
        <el-table-column label="记录状态" align="center" width="90">
          <template #default="scope">{{ recordStatusLabel(scope.row.status) }}</template>
        </el-table-column>
        <el-table-column label="审方状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="reviewTagType(scope.row.reviewStatus)">{{ reviewLabel(scope.row.reviewStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="120" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.reviewStatus === 0 && scope.row.status === 0 && allowReview"
              v-hasPermi="['pharmacy:prescription:review']"
              link
              type="primary"
              @click="openReview(scope.row)"
            >
              审核
            </el-button>
            <el-button v-else link type="info" @click="openReview(scope.row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <el-dialog v-model="reviewVisible" :title="canEdit && submitState !== 'unknown' ? '药师审核' : '审核记录'" width="min(860px, 96vw)" destroy-on-close @closed="finishReview">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" class="mb-12px" />
      <el-descriptions v-if="reviewTarget" :column="2" border class="mb-12px">
        <el-descriptions-item label="处方号">{{ reviewTarget.prescNo }}</el-descriptions-item>
        <el-descriptions-item label="门店">{{ reviewTarget.storeName || reviewTarget.storeId }}</el-descriptions-item>
        <el-descriptions-item label="患者">{{ reviewTarget.patientName }}</el-descriptions-item>
        <el-descriptions-item label="年龄">{{ reviewTarget.patientAge ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="登记方式">{{ channelLabel(reviewTarget.wxMemberId) }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceLabel(reviewTarget.source) }}</el-descriptions-item>
        <el-descriptions-item label="医师">{{ reviewTarget.doctorName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="医院">{{ reviewTarget.hospital || '-' }}</el-descriptions-item>
        <el-descriptions-item label="诊断">{{ reviewTarget.diagnosis || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开方日期">{{ reviewTarget.prescDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="记录状态">{{ recordStatusLabel(reviewTarget.status) }}</el-descriptions-item>
        <el-descriptions-item label="审方状态">
          <el-tag :type="reviewTagType(reviewTarget.reviewStatus)">{{ reviewLabel(reviewTarget.reviewStatus) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="用法用量" :span="2">{{ reviewTarget.usageDesc || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-form v-if="reviewTarget" ref="reviewFormRef" :model="reviewForm" label-width="110px">
        <template v-if="reviewTarget.wxMemberId != null">
          <el-alert title="私有处方材料仅供同门店具备资质的药师审核。核准明细只允许生成一笔使用中的订单。身份证号不在此页展示。" type="info" :closable="false" />
          <el-form-item label="处方材料">
            <span v-if="materialLoading">正在读取私有材料…</span>
            <el-alert v-if="materialError" :title="materialError" type="error" :closable="false" />
            <el-image v-for="url in materialUrls" :key="url" :src="url" :preview-src-list="materialUrls" fit="contain" class="material-image" />
          </el-form-item>
          <el-form-item label="申请明细">
            <el-table :data="requestedItems" size="small" border empty-text="没有可读取的申请明细">
              <el-table-column label="药品" prop="drugName" min-width="140" show-overflow-tooltip />
              <el-table-column label="规格" prop="specification" min-width="100" show-overflow-tooltip />
              <el-table-column label="申请数量" prop="qty" width="90" align="center" />
            </el-table>
          </el-form-item>
          <el-form-item label="核准明细">
            <div class="approval-list">
              <el-empty v-if="!approvedItems.length" description="尚未保留核准项" />
              <div v-for="item in approvedItems" :key="item.drugId" class="approval-item">
                <div class="approval-title">{{ item.drugName || '药品' }} · {{ item.specification || '-' }} · 申请 {{ requestedMaximum(item.drugId) }}</div>
                <el-input-number v-model="item.qty" :min="1" :max="requestedMaximum(item.drugId)" :disabled="fieldsLocked" />
                <el-input v-model="item.usage" maxlength="128" placeholder="药师填写用法" :disabled="fieldsLocked" class="mt-8px" />
                <el-input v-model="item.dosage" maxlength="128" placeholder="药师填写用量" :disabled="fieldsLocked" class="mt-8px" />
                <el-button v-if="canEdit && submitState !== 'unknown'" link type="danger" @click="removeApproved(item.drugId)">不予核准此项</el-button>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="有效截止时间">
            <el-date-picker v-model="approvedUntil" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" :disabled="fieldsLocked" placeholder="由药师明确核准" />
          </el-form-item>
          <el-form-item v-if="reviewTarget.uses?.length" label="使用记录">
            <div>
              <div v-for="use in reviewTarget.uses" :key="use.wxOrderId">订单 {{ use.wxOrderId }} · {{ useLabel(use.status) }} {{ use.releaseReason || '' }}</div>
            </div>
          </el-form-item>
        </template>
        <el-form-item v-if="canEdit && submitState !== 'unknown'" label="审核结果">
          <el-radio-group v-model="reviewForm.reviewStatus">
            <el-radio :value="1">通过</el-radio>
            <el-radio :value="2">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="canEdit && submitState !== 'unknown' && reviewTarget.isSpecial === 1 && reviewForm.reviewStatus === 1" label="双人复核人">
          <el-input-number v-model="reviewForm.dblCheckBy" :min="1" :controls="false" placeholder="复核员工档案编号" class="!w-full" />
          <div class="field-hint">通过特管处方时必填。填写员工档案编号，不是登录账号。驳回时不需要。</div>
        </el-form-item>
        <el-form-item :label="opinionLabel">
          <el-input v-model="reviewForm.reviewOpinion" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="驳回或超量通过时必填" :disabled="fieldsLocked" />
        </el-form-item>
        <el-form-item v-if="!canEdit" label="审方药师">
          <el-input :model-value="reviewTarget.pharmacistName || (reviewTarget.pharmacistId ?? '-')" disabled />
        </el-form-item>
        <el-form-item v-if="!canEdit && reviewTarget.dblCheckBy" label="复核人">
          <el-input :model-value="reviewTarget.dblCheckByName || reviewTarget.dblCheckBy" disabled />
        </el-form-item>
        <el-alert v-if="submitState === 'unknown'" title="提交结果未确认。请先刷新这条处方，确认服务端状态后再操作，不要重复提交。" type="warning" :closable="false" />
      </el-form>
      <template #footer>
        <template v-if="canEdit">
          <el-button v-if="submitState === 'unknown'" :loading="reviewLoading" @click="reloadReview">刷新审核结果</el-button>
          <el-button v-else v-hasPermi="['pharmacy:prescription:review']" :loading="reviewLoading" type="primary" @click="submitReview">提交</el-button>
          <el-button :disabled="reviewLoading" @click="reviewVisible = false">取消</el-button>
        </template>
        <el-button v-else @click="reviewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { PrescRecordApi, PrescItemVO, PrescRecordVO } from '@/api/pharmacy/prescription/prescRecord'
import { checkPermi } from '@/utils/permission'
import { formatDate } from '@/utils/formatTime'
import {
  buildReviewPayload,
  channelLabel,
  describeReviewOutcome,
  errorText,
  initialDecision,
  materialSummary,
  parseItems,
  privateMaterialIds,
  recordStatusLabel,
  reviewLabel,
  sourceLabel,
  useLabel
} from './review-flow'

defineOptions({ name: 'PharmacyPrescriptionReview' })

const route = useRoute()
const message = useMessage()
const allowReview = computed(() => checkPermi(['pharmacy:prescription:review']))

const loading = ref(true)
const total = ref(0)
const list = ref<PrescRecordVO[]>([])
const listError = ref('')
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

const REVIEW_OPTIONS = [
  { value: 0, label: '待审' },
  { value: 1, label: '通过' },
  { value: 2, label: '驳回' }
]
const SOURCE_OPTIONS = [
  { value: 0, label: '纸质拍照' },
  { value: 1, label: '电子处方平台' },
  { value: 2, label: '复诊续方' }
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
    const data = await PrescRecordApi.getPrescRecordPage({ ...queryParams })
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
  handleQuery()
}
const queryOnlyWaiting = () => {
  queryParams.reviewStatus = 0
  handleQuery()
}

const reviewVisible = ref(false)
const reviewLoading = ref(false)
const reviewTarget = ref<PrescRecordVO | null>(null)
const reviewFormRef = ref()
const reviewForm = reactive({
  reviewStatus: undefined as number | undefined,
  reviewOpinion: '',
  reviewSnapshot: '',
  dblCheckBy: undefined as number | undefined
})
const approvedItems = ref<PrescItemVO[]>([])
const approvedUntil = ref('')
const materialUrls = ref<string[]>([])
const materialLoading = ref(false)
const materialError = ref('')
const detailError = ref('')
const submitState = ref<'idle' | 'unknown'>('idle')
const requestedItems = computed(() => parseItems(reviewTarget.value?.prescribedItems).items)
const canEdit = computed(() => reviewTarget.value?.reviewStatus === 0 && reviewTarget.value?.status === 0 && allowReview.value)
const fieldsLocked = computed(() => !canEdit.value || submitState.value === 'unknown')
const opinionLabel = computed(() => (reviewForm.reviewStatus === 2 || reviewTarget.value?.limitCheck === 1 ? '审方意见(必填)' : '审方意见'))
let reviewGeneration = 0
const consumedQuery = ref('')

const clearImages = () => {
  reviewGeneration++
  materialUrls.value.forEach((url) => URL.revokeObjectURL(url))
  materialUrls.value = []
  materialLoading.value = false
}
const finishReview = () => {
  // 关闭动画结束时如果已经打开下一张，不能清掉新的加载锁。
  if (reviewVisible.value) return
  clearImages()
  reviewLoading.value = false
}
const requestedMaximum = (id?: number) => requestedItems.value.find((item) => item.drugId === id)?.qty || 1
const removeApproved = (drugId?: number) => {
  approvedItems.value = approvedItems.value.filter((item) => item.drugId !== drugId)
}

const applyRecord = (row: PrescRecordVO) => {
  reviewTarget.value = row
  reviewForm.reviewStatus = initialDecision(row.reviewStatus)
  reviewForm.reviewOpinion = row.reviewOpinion ?? ''
  reviewForm.dblCheckBy = row.dblCheckBy ?? undefined
  const parsed = parseItems(row.approvedItems || row.prescribedItems)
  approvedItems.value = parsed.items.map((item) => ({ ...item }))
  approvedUntil.value = row.approvedUntil ? formatDate(row.approvedUntil, 'YYYY-MM-DD HH:mm:ss') : ''
  const index = list.value.findIndex((item) => item.id === row.id)
  if (index >= 0) list.value[index] = { ...list.value[index], ...row }
}

const openReview = async (row: Pick<PrescRecordVO, 'id'>) => {
  if (reviewLoading.value) return
  reviewLoading.value = true
  clearImages()
  const generation = reviewGeneration
  materialError.value = ''
  detailError.value = ''
  submitState.value = 'idle'
  approvedItems.value = []
  try {
    let fresh: PrescRecordVO
    try {
      fresh = await PrescRecordApi.getPrescRecord(row.id)
    } catch (reason) {
      if (generation === reviewGeneration) {
        detailError.value = errorText(reason, '读取处方失败，请刷新重试')
        message.error(detailError.value)
      }
      return
    }
    if (generation !== reviewGeneration) return
    applyRecord(fresh)
    reviewVisible.value = true
    if (fresh.wxMemberId == null) return
    const summary = materialSummary(fresh.images)
    const ids = privateMaterialIds(fresh.images)
    if (!summary.ok || !ids) {
      materialError.value = summary.message
      return
    }
    materialLoading.value = true
    try {
      for (const id of ids) {
        const blob = await PrescRecordApi.getPrivateMaterial(id)
        if (generation !== reviewGeneration) return
        if (!(blob instanceof Blob) || !['image/png', 'image/jpeg'].includes(blob.type.split(';')[0])) throw new Error('私有图片读取失败或无权查看')
        materialUrls.value.push(URL.createObjectURL(blob))
      }
    } catch (reason) {
      if (generation === reviewGeneration) materialError.value = errorText(reason, '私有材料读取失败')
    } finally {
      if (generation === reviewGeneration) materialLoading.value = false
    }
  } finally {
    if (generation === reviewGeneration) reviewLoading.value = false
  }
}

const reloadReview = async () => {
  if (!reviewTarget.value || reviewLoading.value) return
  const id = reviewTarget.value.id
  const generation = reviewGeneration
  reviewLoading.value = true
  try {
    const fresh = await PrescRecordApi.getPrescRecord(id)
    if (generation !== reviewGeneration || reviewTarget.value?.id !== id) return
    applyRecord(fresh)
    const outcome = describeReviewOutcome(fresh)
    if (outcome === 'confirmed') {
      submitState.value = 'idle'
      message.success(fresh.reviewStatus === 1 ? '服务端已记录为通过' : '服务端已记录为驳回')
      await getList()
    } else if (outcome === 'pending') {
      submitState.value = 'idle'
      message.warning('服务端仍是待审，可以继续审核')
    } else {
      submitState.value = 'unknown'
      message.warning('仍不能确认审核结果，请稍后再刷新')
    }
  } catch (reason) {
    submitState.value = 'unknown'
    message.warning(errorText(reason, '刷新审核结果失败，请稍后再试'))
  } finally {
    reviewLoading.value = false
  }
}

const submitReview = async () => {
  if (!reviewTarget.value || reviewLoading.value || submitState.value === 'unknown' || !allowReview.value) return
  const currentId = reviewTarget.value.id
  const generation = reviewGeneration
  const decision = buildReviewPayload({
    id: currentId,
    wxMemberId: reviewTarget.value.wxMemberId,
    isSpecial: reviewTarget.value.isSpecial,
    limitCheck: reviewTarget.value.limitCheck,
    prescribedItems: reviewTarget.value.prescribedItems,
    decision: reviewForm.reviewStatus,
    opinion: reviewForm.reviewOpinion,
    dblCheckBy: reviewForm.dblCheckBy,
    approvedItems: approvedItems.value,
    approvedUntil: approvedUntil.value,
    materialReady: reviewTarget.value.wxMemberId == null || (!materialLoading.value && !materialError.value && materialUrls.value.length > 0)
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
  if (generation !== reviewGeneration || reviewTarget.value?.id !== currentId) {
    reviewLoading.value = false
    getList()
    return
  }
  try {
    const fresh = await PrescRecordApi.getPrescRecord(currentId)
    if (generation !== reviewGeneration || reviewTarget.value?.id !== currentId) return
    applyRecord(fresh)
    const outcome = describeReviewOutcome(fresh)
    if (outcome === 'confirmed') {
      submitState.value = 'idle'
      message.success(fresh.reviewStatus === 1 ? '服务端已记录为通过' : '服务端已记录为驳回')
      try {
        await getList()
      } catch {
        listError.value = '审核结果已写入，但列表刷新失败，请再搜索核对'
      }
      reviewVisible.value = false
      return
    }
    submitState.value = 'idle'
    message.warning(submitted ? '服务端仍是待审，未记为审核成功，请核对后重试' : '提交没有完成，处方仍是待审，可以再次提交')
  } catch {
    if (generation !== reviewGeneration) return
    submitState.value = 'unknown'
    message.warning(submitted ? '审核请求已返回，但回读失败。请先刷新确认结果，不要直接重提。' : '提交结果未确认。请先刷新这条处方，不要直接重提。')
  } finally {
    if (generation === reviewGeneration) reviewLoading.value = false
  }
}

const openFromQuery = async () => {
  const key = route.query.id == null ? '' : String(route.query.id)
  if (!key || (consumedQuery.value === key && reviewVisible.value)) return
  const id = Number(key)
  if (!Number.isInteger(id) || id <= 0) return
  consumedQuery.value = key
  await openReview({ id })
  if (!reviewVisible.value) consumedQuery.value = ''
}

onMounted(async () => {
  queryParams.reviewStatus = 0
  await getList()
  await openFromQuery()
})
onActivated(openFromQuery)
onUnmounted(finishReview)
</script>
<style scoped>
.material-image { width: 120px; height: 120px; margin: 8px; }
.approval-list { width: 100%; }
.approval-item { margin-bottom: 16px; }
.approval-title { overflow-wrap: anywhere; margin-bottom: 8px; }
.field-hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
</style>
