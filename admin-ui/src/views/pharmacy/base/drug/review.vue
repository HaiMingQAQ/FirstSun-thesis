<template>
  <div class="pharmacy-page pharmacy-modern-page drug-review">
    <header class="review-heading">
      <div><h1>药品审核</h1><p>核对药品资料、图片与说明书，确认后再完成审核。</p></div>
      <el-button :loading="loading" @click="loadPage">刷新</el-button>
    </header>
    <ContentWrap class="pharmacy-panel" v-if="canReview">
      <el-tabs v-model="activeStatus" @tab-change="search">
        <el-tab-pane
          v-for="item in statuses"
          :key="item.value"
          :name="item.value"
          :label="`${item.label}${counts[item.value] == null ? '' : '（' + counts[item.value] + '）'}`"
        />
      </el-tabs>
      <el-form inline @submit.prevent="search">
        <el-form-item label="通用名"
          ><el-input
            v-model="query.genericName"
            placeholder="输入药品通用名"
            clearable
            @keyup.enter="search"
        /></el-form-item>
        <el-form-item label="药品编码"
          ><el-input
            v-model="query.drugCode"
            placeholder="输入药品编码"
            clearable
            @keyup.enter="search"
        /></el-form-item>
        <el-form-item
          ><el-button type="primary" @click="search">搜索</el-button
          ><el-button @click="reset">重置</el-button></el-form-item
        >
      </el-form>
      <p class="review-hint">状态数量随搜索条件更新，包含已停用的药品。</p>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="药品" min-width="250">
          <template #default="{ row }"
            ><div class="review-drug">
              <el-image
                v-if="row.imageUrl"
                :src="row.imageUrl"
                fit="contain"
                class="review-thumb"
              />
              <div
                ><strong>{{ row.genericName }}</strong
                ><div class="review-secondary">{{ row.drugCode }}</div></div
              >
            </div></template
          >
        </el-table-column>
        <el-table-column label="规格" prop="specification" min-width="160" show-overflow-tooltip />
        <el-table-column
          label="生产厂家"
          prop="manufacturer"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column label="批准文号" prop="approvalNo" min-width="160" />
        <el-table-column label="审核状态" width="110"
          ><template #default="{ row }"
            ><dict-tag
              :type="DICT_TYPE.PHARMACY_DRUG_APPROVE_STATUS"
              :value="row.approveStatus" /></template
        ></el-table-column>
        <el-table-column label="操作" fixed="right" width="130"
          ><template #default="{ row }">
            <el-button
              :type="row.approveStatus === 0 ? 'primary' : 'default'"
              :plain="row.approveStatus !== 0"
              @click="openDetail(row.id)"
              >{{ row.approveStatus === 0 ? '去审核' : '查看详情' }}</el-button
            >
          </template></el-table-column
        >
        <template #empty
          ><el-empty
            :description="
              listError
                ? '加载失败，请点击刷新重试'
                : activeStatus === 0
                  ? '没有待审核药品'
                  : '没有符合条件的药品'
            "
        /></template>
      </el-table>
      <Pagination
        :total="total"
        v-model:page="query.pageNo"
        v-model:limit="query.pageSize"
        @pagination="loadPage"
      />
    </ContentWrap>
    <el-alert
      v-else
      title="需要药品查询及审核权限才能使用此页面，请联系管理员。"
      type="warning"
      :closable="false"
    />

    <el-dialog
      v-model="detailVisible"
      title="药品审核详情"
      width="min(1080px, 94vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      :before-close="beforeClose"
    >
      <div v-loading="detailLoading" class="review-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <template v-else-if="detail">
          <div class="detail-heading"
            ><h2>{{ detail.genericName }}</h2
            ><dict-tag :type="DICT_TYPE.PHARMACY_DRUG_APPROVE_STATUS" :value="detail.approveStatus"
          /></div>
          <h3>基本资料</h3>
          <el-descriptions :column="2" border>
            <el-descriptions-item
              v-for="field in basicFields"
              :key="field.key"
              :label="field.label"
              >{{ detail[field.key] ?? '未填写' }}</el-descriptions-item
            >
            <el-descriptions-item label="药品分类">{{ categoryName }}</el-descriptions-item>
            <el-descriptions-item label="药品类型"
              ><dict-tag :type="DICT_TYPE.PHARMACY_DRUG_TYPE" :value="detail.drugType"
            /></el-descriptions-item>
            <el-descriptions-item v-for="field in flagFields" :key="field.key" :label="field.label"
              ><dict-tag :type="DICT_TYPE.PHARMACY_YES_NO" :value="detail[field.key]"
            /></el-descriptions-item>
            <el-descriptions-item label="储存条件"
              ><dict-tag :type="DICT_TYPE.PHARMACY_STORAGE_COND" :value="detail.storageCond"
            /></el-descriptions-item>
            <el-descriptions-item label="医保类别"
              ><dict-tag :type="DICT_TYPE.PHARMACY_INSURANCE_TYPE" :value="detail.insuranceType"
            /></el-descriptions-item>
            <el-descriptions-item label="启用状态"
              ><dict-tag :type="DICT_TYPE.PHARMACY_STATUS" :value="detail.status"
            /></el-descriptions-item>
          </el-descriptions>
          <h3>图片与说明书</h3>
          <div v-if="pictures.length" class="review-images"
            ><el-image
              v-for="src in pictures"
              :key="src"
              :src="src"
              :preview-src-list="pictures"
              preview-teleported
              fit="contain"
            /><span>点击图片可放大查看</span></div
          >
          <el-alert
            v-else
            title="未上传药品图片，请核对资料是否完整。"
            type="warning"
            :closable="false"
          />
          <p
            ><el-link
              v-if="instructionsLink"
              :href="instructionsLink"
              target="_blank"
              rel="noopener noreferrer"
              type="primary"
              >查看完整说明书（新窗口）</el-link
            ><span v-else>未提供可打开的说明书附件</span></p
          >
          <h3>药品说明</h3
          ><div class="review-copy">{{ detail.description || '未填写药品说明' }}</div> <h3>备注</h3
          ><div class="review-copy">{{ detail.remark || '无' }}</div>
          <template v-if="detail.approveStatus !== 0">
            <h3>审核结果</h3>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="审核员工编号">{{
                detail.auditBy ?? '未记录'
              }}</el-descriptions-item>
              <el-descriptions-item label="审核时间">{{
                detail.auditAt ? formatDate(detail.auditAt) : '未记录'
              }}</el-descriptions-item>
              <el-descriptions-item label="审核意见" :span="2"
                ><span class="review-copy">{{
                  detail.auditOpinion || '无'
                }}</span></el-descriptions-item
              >
            </el-descriptions>
          </template>
        </template>
      </div>
      <template #footer
        ><div class="review-footer">
          <div v-if="detail?.approveStatus === 0 && !detailError" class="review-opinion">
            <label for="drug-review-opinion">审核意见（驳回必填）</label>
            <el-input
              id="drug-review-opinion"
              v-model="opinion"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="如需修改，请说明具体原因"
              :disabled="submitting"
              @input="opinionError = ''"
            />
            <span v-if="opinionError" role="alert" class="review-error">{{ opinionError }}</span>
          </div>
          <div class="review-actions"
            ><el-button :disabled="submitting" @click="detailVisible = false">关闭</el-button>
            <template v-if="detail?.approveStatus === 0 && !detailError">
              <el-button
                type="danger"
                plain
                :disabled="submitting || detailLoading"
                @click="submit(2)"
                >驳回</el-button
              >
              <el-button
                type="primary"
                :loading="submitting"
                :disabled="detailLoading"
                @click="submit(1)"
                >通过审核</el-button
              >
            </template>
          </div>
        </div></template
      >
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import * as DrugApi from '@/api/pharmacy/base/drug'
import * as CategoryApi from '@/api/pharmacy/base/category'
import { DICT_TYPE } from '@/utils/dict'
import { checkPermi } from '@/utils/permission'
import { formatDate } from '@/utils/formatTime'

defineOptions({ name: 'PharmacyDrugReview' })
const route = useRoute()
const message = useMessage()
const canReview = computed(
  () => checkPermi(['pharmacy:base:drug:query']) && checkPermi(['pharmacy:base:drug:approve'])
)
const statuses = [
  { value: 0, label: '待审核' },
  { value: 1, label: '已通过' },
  { value: 2, label: '已驳回' }
]
const activeStatus = ref(0)
const counts = ref<Record<number, number | null>>({ 0: null, 1: null, 2: null })
const query = reactive({ pageNo: 1, pageSize: 10, genericName: '', drugCode: '' })
const rows = ref<DrugApi.DrugVO[]>([])
const total = ref(0)
const loading = ref(false)
const listError = ref('')
let generation = 0
const loadPage = async () => {
  if (!canReview.value) return
  const ticket = ++generation
  loading.value = true
  listError.value = ''
  rows.value = []
  const params = { ...query }
  try {
    const [page, ...totals] = await Promise.all([
      DrugApi.getDrugPage({ ...params, approveStatus: activeStatus.value }),
      ...statuses.map((item) =>
        DrugApi.getDrugPage({ ...params, pageNo: 1, pageSize: 1, approveStatus: item.value })
      )
    ])
    if (ticket !== generation) return
    rows.value = page.list
    total.value = page.total
    statuses.forEach((item, index) => {
      counts.value[item.value] = totals[index].total
    })
  } catch {
    if (ticket !== generation) return
    total.value = 0
    counts.value = { 0: null, 1: null, 2: null }
    listError.value = '审核列表加载失败，请点击刷新重试。'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
const search = () => {
  query.pageNo = 1
  loadPage()
}
const reset = () => {
  query.genericName = ''
  query.drugCode = ''
  search()
}
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<DrugApi.DrugVO>()
const opinion = ref('')
const opinionError = ref('')
const submitting = ref(false)
const categories = ref<any[]>([])
const categoryName = computed(
  () =>
    categories.value.find((item) => item.id === detail.value?.categoryId)?.catName ||
    (detail.value?.categoryId ? `分类编号 ${detail.value.categoryId}` : '未填写')
)
const pictures = computed(() => [
  ...new Set(
    [detail.value?.imageUrl, ...(detail.value?.images || [])].filter((src): src is string => !!src)
  )
])
const instructionsLink = computed(() => {
  if (!detail.value?.instructionsUrl) return ''
  try {
    const url = new URL(detail.value.instructionsUrl, window.location.origin)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch {
    return ''
  }
})
const basicFields: { key: keyof DrugApi.DrugVO; label: string }[] = [
  { key: 'drugCode', label: '药品编码' },
  { key: 'tradeName', label: '商品名' },
  { key: 'specification', label: '规格' },
  { key: 'dosageForm', label: '剂型' },
  { key: 'manufacturer', label: '生产厂家' },
  { key: 'approvalNo', label: '批准文号' },
  { key: 'unit', label: '计量单位' },
  { key: 'conversionRatio', label: '换算比率' },
  { key: 'retailPrice', label: '零售价（元）' },
  { key: 'memberPrice', label: '会员价（元）' },
  { key: 'costPrice', label: '成本价（元）' },
  { key: 'minSalePrice', label: '最低售价（元）' },
  { key: 'minStock', label: '最低库存' },
  { key: 'maxStock', label: '最高库存' },
  { key: 'taxRate', label: '税率（%）' },
  { key: 'spellCode', label: '拼音码' },
  { key: 'defaultLocationId', label: '默认货位编号' }
]
const flagFields: { key: keyof DrugApi.DrugVO; label: string }[] = [
  { key: 'isRx', label: '处方药' },
  { key: 'isSpecial', label: '特殊药品' },
  { key: 'isPseudoephedrine', label: '含麻黄碱' },
  { key: 'isColdChain', label: '冷链' },
  { key: 'saleableOnline', label: '线上可售' },
  { key: 'needExpiry', label: '效期管理' }
]
let detailGeneration = 0
const openDetail = async (id: number) => {
  if (!canReview.value || submitting.value) return
  const ticket = ++detailGeneration
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = undefined
  opinion.value = ''
  opinionError.value = ''
  try {
    const data = await DrugApi.getDrug(id)
    if (ticket === detailGeneration) detail.value = data
  } catch {
    if (ticket === detailGeneration) detailError.value = '药品详情加载失败，请关闭后重试。'
  } finally {
    if (ticket === detailGeneration) detailLoading.value = false
  }
}
const beforeClose = (done: () => void) => {
  if (!submitting.value) done()
}
const submit = async (status: 1 | 2) => {
  if (
    !canReview.value ||
    submitting.value ||
    detailLoading.value ||
    detail.value?.approveStatus !== 0 ||
    !detail.value.id
  )
    return
  if (status === 2 && !opinion.value.trim()) {
    opinionError.value = '请填写驳回原因，便于维护人员修改。'
    document.getElementById('drug-review-opinion')?.focus()
    return
  }
  submitting.value = true
  try {
    try {
      await message.confirm(
        status === 1
          ? `确认通过「${detail.value.genericName}」的审核？`
          : `确认驳回「${detail.value.genericName}」？`,
        '确认审核结果'
      )
    } catch {
      return
    }
    const latest = await DrugApi.getDrug(detail.value.id)
    if (latest.approveStatus !== 0) {
      detail.value = latest
      message.warning('该药品已被审核，已更新最新结果。')
      await loadPage()
      window.dispatchEvent(new Event('pharmacy-drug-review-updated'))
      return
    }
    await DrugApi.approveDrug(detail.value.id, status, opinion.value.trim())
    message.success(status === 1 ? '审核已通过' : '已驳回并记录原因')
    detailVisible.value = false
    await loadPage()
    window.dispatchEvent(new Event('pharmacy-drug-review-updated'))
  } finally {
    submitting.value = false
  }
}
const openFromRoute = () => {
  const id = Number(route.query.id)
  if (Number.isSafeInteger(id) && id > 0) openDetail(id)
}
const applyPendingEntry = () => {
  if (route.query.status !== '0') return
  activeStatus.value = 0
  query.pageNo = 1
  query.genericName = ''
  query.drugCode = ''
}
let activatedOnce = false
onMounted(() => {
  applyPendingEntry()
  loadPage()
  openFromRoute()
  if (canReview.value)
    CategoryApi.getSimpleCategoryList()
      .then((data) => {
        categories.value = data
      })
      .catch(() => {})
})
onActivated(() => {
  if (activatedOnce) {
    applyPendingEntry()
    loadPage()
    openFromRoute()
  }
  activatedOnce = true
})
onDeactivated(() => {
  detailVisible.value = false
  detailGeneration++
})
</script>
<style scoped>
.review-heading,
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.review-heading {
  margin-bottom: 16px;
}
h1 {
  margin: 0;
  font-size: 22px;
}
.review-heading p,
.review-hint,
.review-secondary {
  color: var(--el-text-color-secondary);
}
.review-heading p {
  margin: 8px 0 0;
}
.review-hint {
  font-size: 13px;
}
.review-drug {
  display: flex;
  align-items: center;
  gap: 12px;
}
.review-drug strong {
  color: var(--el-text-color-primary);
}
.review-thumb {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.review-secondary {
  margin-top: 4px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.review-detail {
  max-height: 57vh;
  min-height: 140px;
  overflow: auto;
  padding-right: 8px;
}
.detail-heading h2 {
  font-size: 20px;
  margin: 0;
}
h3 {
  font-size: 15px;
  margin: 24px 0 12px;
}
.review-images {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.review-images :deep(.el-image) {
  width: 120px;
  height: 120px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.review-copy {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
}
.review-footer {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 16px;
  text-align: left;
}
.review-opinion label {
  display: block;
  margin-bottom: 8px;
}
.review-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  gap: 8px;
}
.review-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.review-error {
  color: var(--el-color-danger);
  font-size: 13px;
}
.review-detail :deep(.el-descriptions__cell) {
  overflow-wrap: anywhere;
}
</style>
