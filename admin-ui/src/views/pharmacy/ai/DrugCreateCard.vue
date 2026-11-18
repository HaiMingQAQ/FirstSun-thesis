<template>
  <el-card class="drug-create-card" shadow="never" aria-label="新增药品填写卡片">
    <template #header
      ><div class="card-heading"
        ><strong>新增药品</strong
        ><el-tag :type="created ? 'success' : 'info'">{{
          created ? '已创建 · 待审核' : '填写资料'
        }}</el-tag></div
      ></template
    >
    <template v-if="created">
      <el-result
        icon="success"
        title="药品档案已创建"
        sub-title="尚未审核，不会自动上架或增加库存。"
      />
      <el-button @click="router.push('/pharmacy-base/base/drug')">查看药品档案</el-button>
    </template>
    <template v-else>
      <p class="card-hint">按药品包装和说明书核对资料；带 * 的项目必须填写。点击提交即确认新增。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert
        v-if="categoryError"
        title="分类加载失败，请重试后再提交。"
        type="warning"
        :closable="false"
        ><el-button text @click="loadCategories">重新加载分类</el-button></el-alert
      >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        :disabled="locked"
        @submit.prevent="submit"
      >
        <h4>基本资料</h4>
        <div class="field-grid">
          <el-form-item
            v-for="field in textFields"
            :key="field.key"
            :label="field.label"
            :prop="field.key"
          >
            <el-input
              v-model="form[field.key]"
              :maxlength="field.max"
              :placeholder="field.placeholder"
              clearable
            />
          </el-form-item>
          <el-form-item label="所属分类" prop="categoryId">
            <el-tree-select
              v-model="form.categoryId"
              :data="categoryTree"
              :props="{ label: 'catName', value: 'id', children: 'children' }"
              node-key="id"
              check-strictly
              filterable
              placeholder="选择药品分类"
              class="full-width"
            />
          </el-form-item>
          <el-form-item label="零售价（元）" prop="retailPrice"
            ><el-input-number
              v-model="form.retailPrice"
              :min="0"
              :precision="2"
              controls-position="right"
              class="full-width"
          /></el-form-item>
        </div>
        <h4>药品属性与销售设置</h4>
        <p class="card-hint">请逐项核对。是否处方药、特殊管理及储存条件均由你确认。</p>
        <div class="field-grid">
          <el-form-item
            v-for="field in selectFields"
            :key="field.key"
            :label="field.label"
            :prop="field.key"
          >
            <el-select v-model="form[field.key]" placeholder="请选择" class="full-width"
              ><el-option
                v-for="option in getIntDictOptions(field.dict)"
                :key="option.value"
                :label="option.label"
                :value="option.value"
            /></el-select>
          </el-form-item>
          <el-form-item
            v-for="field in numberFields"
            :key="field.key"
            :label="field.label"
            :prop="field.key"
            ><el-input-number
              v-model="form[field.key]"
              :min="0"
              :max="field.max"
              :precision="field.precision"
              controls-position="right"
              class="full-width"
          /></el-form-item>
        </div>
        <el-form-item label="备注" prop="remark"
          ><el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="可填写需要审核人员留意的事项"
        /></el-form-item>
        <p class="card-hint">创建后进入待审核状态；包装图、说明书附件等可在药品档案中继续维护。</p>
        <div class="card-actions">
          <el-button
            v-if="!submission"
            type="primary"
            :loading="busy"
            :disabled="!allowed || !categoriesReady"
            @click="submit"
            >提交新增药品</el-button
          >
        </div>
      </el-form>
      <div v-if="submission" class="submission-state" role="status">
        <p
          >提交记录 #{{ submission.commandId }}：{{
            submission.status === 'PENDING' ? '等待确认结果' : '结果待核对'
          }}。请先核对本次结果，避免重复新增。</p
        >
        <el-button :loading="busy" @click="checkSubmission">核对提交结果</el-button>
        <el-button
          v-if="pendingPreview?.confirmationToken && submission.status === 'PENDING'"
          :disabled="busy"
          @click="confirmPending"
          >继续本次提交</el-button
        >
        <el-button
          v-if="['PENDING', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(submission.status)"
          :disabled="busy"
          @click="resetSubmission"
          >{{ submission.status === 'PENDING' ? '取消本次提交并修改' : '返回修改' }}</el-button
        >
      </div>
      <p v-if="!allowed" class="card-hint">当前账号没有新增药品权限。</p>
    </template>
  </el-card>
</template>
<script setup lang="ts">
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import * as CategoryApi from '@/api/pharmacy/base/category'
import { handleTree } from '@/utils/tree'
import { hasPermission } from '@/directives/permission/hasPermi'
import { PharmacyAiApi, type PharmacyAiCommand } from '@/api/pharmacy/ai'

const props = defineProps<{
  draft: Record<string, any>
  submission?: { commandId: number; status: string; drugId?: number }
}>()
const emit = defineEmits(['update:draft', 'update:submission', 'command', 'busy'])
const router = useRouter()
const allowed = computed(
  () => hasPermission(['pharmacy:ai:chat']) && hasPermission(['pharmacy:base:drug:create'])
)
const busy = ref(false)
watch(busy, (value) => emit('busy', value))
const created = computed(() => props.submission?.status === 'EXECUTED')
const locked = computed(() => busy.value || !!props.submission || !allowed.value)
const error = ref('')
const categoryError = ref(false)
const categoriesReady = ref(false)
const categoryTree = ref<any[]>([])
const formRef = ref()
const pendingPreview = ref<PharmacyAiCommand>()
const textFields = [
  {
    key: 'genericName',
    label: '通用名称',
    max: 64,
    placeholder: '例如：感冒灵颗粒',
    required: true
  },
  { key: 'drugCode', label: '药品编码', max: 32, placeholder: '填写本店唯一编码', required: true },
  { key: 'specification', label: '规格', max: 64, placeholder: '例如：10g×9袋/盒', required: true },
  { key: 'unit', label: '销售单位', max: 8, placeholder: '例如：盒、瓶、台', required: true },
  { key: 'tradeName', label: '商品名（选填）', max: 64, placeholder: '填写商品名' },
  { key: 'manufacturer', label: '生产厂家（选填）', max: 128, placeholder: '按包装填写' },
  { key: 'approvalNo', label: '批准文号／注册证号（选填）', max: 64, placeholder: '按包装填写' }
]
const selectFields = [
  { key: 'drugType', label: '药品类型', dict: DICT_TYPE.PHARMACY_DRUG_TYPE },
  { key: 'isRx', label: '是否处方药', dict: DICT_TYPE.PHARMACY_YES_NO },
  { key: 'isSpecial', label: '是否特殊管理药品', dict: DICT_TYPE.PHARMACY_YES_NO },
  { key: 'isPseudoephedrine', label: '是否含麻黄碱类成分', dict: DICT_TYPE.PHARMACY_YES_NO },
  { key: 'isColdChain', label: '是否需要冷链', dict: DICT_TYPE.PHARMACY_YES_NO },
  { key: 'storageCond', label: '储存条件', dict: DICT_TYPE.PHARMACY_STORAGE_COND },
  { key: 'insuranceType', label: '医保类别', dict: DICT_TYPE.PHARMACY_INSURANCE_TYPE },
  { key: 'needExpiry', label: '是否管理有效期', dict: DICT_TYPE.PHARMACY_YES_NO },
  { key: 'saleableOnline', label: '审核后是否允许线上销售', dict: DICT_TYPE.PHARMACY_YES_NO },
  { key: 'status', label: '启用状态', dict: DICT_TYPE.PHARMACY_STATUS }
]
const numberFields = [
  { key: 'taxRate', label: '税率（%）', max: 100, precision: 2 },
  { key: 'minStock', label: '库存下限', max: 2147483647, precision: 0 },
  { key: 'maxStock', label: '库存上限（0 表示不设上限）', max: 2147483647, precision: 0 }
]
const editableKeys = [...textFields, ...selectFields, ...numberFields]
  .map((field) => field.key)
  .concat(['categoryId', 'retailPrice', 'remark'])
const form = reactive<Record<string, any>>({})
for (const field of [...textFields, { key: 'remark', max: 500 }]) {
  const value = props.draft[field.key]
  if (typeof value !== 'string') continue
  const trimmed = field.key === 'unit' ? value.split(/[（(]/, 1)[0].trim() : value.trim()
  if (trimmed.length <= field.max) form[field.key] = trimmed
}
for (const field of selectFields) {
  const value = props.draft[field.key]
  if (typeof value !== 'number' && typeof value !== 'string') continue
  const input = String(value).trim()
  const labelled = input.match(/^(.+?)\s*[（(]\s*(\d+)\s*[）)]$/)
  const normalizeLabel = (label: string) => label.replace(/^(OTC[甲乙])类$/i, '$1')
  const option = getIntDictOptions(field.dict).find(({ label, value }) =>
    labelled
      ? normalizeLabel(label) === normalizeLabel(labelled[1].trim()) &&
        value === Number(labelled[2])
      : normalizeLabel(label) === normalizeLabel(input) || String(value) === input
  )
  if (option) form[field.key] = option.value
}
for (const field of [
  ...numberFields,
  { key: 'retailPrice', max: Number.MAX_SAFE_INTEGER, precision: 2 }
]) {
  const input = String(props.draft[field.key] ?? '')
    .trim()
    .replace(/\s*[元%％]$/, '')
  if (!/^\d+(?:\.\d{1,2})?$/.test(input)) continue
  const value = Number(input)
  if (value <= field.max && (field.precision !== 0 || Number.isInteger(value)))
    form[field.key] = value
}
const rules: Record<string, any[]> = {}
for (const field of [
  ...textFields.filter((field) => field.required),
  ...selectFields,
  ...numberFields,
  { key: 'categoryId', label: '所属分类' },
  { key: 'retailPrice', label: '零售价' }
]) {
  rules[field.key] = [
    { required: true, message: `请填写或选择${field.label}`, trigger: ['blur', 'change'] }
  ]
}
watch(form, () => emit('update:draft', { ...form }), { deep: true })
const loadCategories = async () => {
  categoryError.value = false
  try {
    const categories: CategoryApi.CategorySimpleVO[] = await CategoryApi.getSimpleCategoryList()
    const requested = String(props.draft.categoryId ?? '').trim()
    const exactNameMatches = categories.filter((category) => category.catName === requested)
    const match =
      categories.find((category) => String(category.id) === requested) ??
      (exactNameMatches.length === 1 ? exactNameMatches[0] : undefined)
    form.categoryId = match?.id
    categoryTree.value = handleTree(categories, 'id', 'parentId')
    categoriesReady.value = true
  } catch {
    categoryError.value = true
  }
}
const submit = async () => {
  if (busy.value || !allowed.value || props.submission || !categoriesReady.value) return
  busy.value = true
  try {
    if (!(await formRef.value?.validate().catch(() => false))) return
    if (form.maxStock !== 0 && form.maxStock < form.minStock) {
      error.value = '库存上限不能小于库存下限。'
      return
    }
    if (
      (form.drugType === 0 && form.isRx !== 1) ||
      ([1, 2].includes(form.drugType) && form.isRx !== 0)
    ) {
      error.value = '药品类型与是否处方药不一致，请核对。'
      return
    }
    error.value = ''
    const payload = Object.fromEntries(
      editableKeys
        .filter((key) => form[key] !== undefined && form[key] !== '')
        .map((key) => [key, typeof form[key] === 'string' ? form[key].trim() : form[key]])
    )
    const preview = await PharmacyAiApi.drugPreview(payload)
    pendingPreview.value = preview
    emit('update:submission', { commandId: preview.commandId, status: preview.status })
    emit('command', preview)
    await executeConfirmation(preview)
  } catch {
    error.value = pendingPreview.value
      ? '提交结果尚未确认，请核对本次提交记录。'
      : '无法生成新增操作，请检查资料后重试。'
  } finally {
    busy.value = false
  }
}
const executeConfirmation = async (preview: PharmacyAiCommand) => {
  if (!preview.confirmationToken) throw new Error('缺少确认凭证')
  const result = await PharmacyAiApi.confirm(preview.commandId, preview.confirmationToken)
  preview.status = 'EXECUTED'
  preview.result = result
  preview.confirmationToken = undefined
  emit('update:submission', {
    commandId: preview.commandId,
    status: 'EXECUTED',
    drugId: result.drugId
  })
  emit('command', preview)
  window.dispatchEvent(new Event('pharmacy-drug-review-updated'))
}
const confirmPending = async () => {
  if (
    busy.value ||
    !allowed.value ||
    !pendingPreview.value?.confirmationToken ||
    props.submission?.status !== 'PENDING'
  )
    return
  busy.value = true
  error.value = ''
  try {
    await executeConfirmation(pendingPreview.value)
  } catch {
    error.value = '提交结果尚未确认，请先核对结果。'
  } finally {
    busy.value = false
  }
}
const checkSubmission = async () => {
  if (busy.value || !props.submission) return
  busy.value = true
  try {
    const commands: PharmacyAiCommand[] = await PharmacyAiApi.recentCommands()
    const command = commands.find((item) => item.commandId === props.submission?.commandId)
    if (!command) {
      error.value = '最近记录中未找到本次操作，请在药品档案核对，勿重复提交。'
      return
    }
    emit('update:submission', { ...props.submission, status: command.status })
    emit('command', command)
    error.value =
      command.status === 'EXECUTED'
        ? ''
        : command.errorMessage || '本次操作尚未完成，请查看状态后处理。'
  } catch {
    error.value = '暂时无法核对，请稍后重试，勿重复新增。'
  } finally {
    busy.value = false
  }
}
const resetSubmission = async () => {
  if (busy.value || !allowed.value || !props.submission) return
  busy.value = true
  try {
    if (props.submission.status === 'PENDING')
      await PharmacyAiApi.cancel(props.submission.commandId)
    else if (!['FAILED', 'CANCELLED', 'EXPIRED'].includes(props.submission.status)) return
    if (pendingPreview.value) {
      pendingPreview.value.status = 'CANCELLED'
      pendingPreview.value.confirmationToken = undefined
      emit('command', pendingPreview.value)
    }
    pendingPreview.value = undefined
    emit('update:submission', undefined)
    error.value = ''
  } catch {
    error.value = '无法返回修改，请先核对提交结果。'
  } finally {
    busy.value = false
  }
}
onMounted(() => {
  if (!created.value) loadCategories()
})
</script>
<style scoped>
.drug-create-card {
  width: 100%;
  min-width: 0;
  border-color: var(--el-color-primary-light-7);
}
.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-heading strong {
  font-size: 17px;
}
.card-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}
h4 {
  margin: 20px 0 12px;
  font-size: 15px;
}
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.full-width {
  width: 100%;
}
.card-actions {
  display: flex;
  justify-content: flex-end;
}
.submission-state {
  margin-top: 12px;
  font-size: 13px;
}
@media (width <= 600px) {
  .field-grid {
    grid-template-columns: 1fr;
  }
}
</style>
