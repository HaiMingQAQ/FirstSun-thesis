<template>
  <div class="pharmacy-page pharmacy-modern-page">
    <div class="drug-list-heading">
      <h1>药品档案</h1>
      <span>筛选结果 {{ total }} 条</span>
    </div>
    <ContentWrap class="pharmacy-panel">
      <!-- 搜索 -->
      <el-form
        class="-mb-15px"
        :model="queryParams"
        ref="queryFormRef"
        :inline="true"
        label-width="68px"
      >
        <el-form-item label="药品编码" prop="drugCode">
          <el-input
            v-model="queryParams.drugCode"
            placeholder="请输入编码"
            clearable
            @keyup.enter="handleQuery"
            class="!w-180px"
          />
        </el-form-item>
        <el-form-item label="通用名" prop="genericName">
          <el-input
            v-model="queryParams.genericName"
            placeholder="请输入通用名"
            clearable
            @keyup.enter="handleQuery"
            class="!w-180px"
          />
        </el-form-item>
        <el-form-item v-show="expandQuery" label="商品名" prop="tradeName">
          <el-input
            v-model="queryParams.tradeName"
            placeholder="请输入商品名"
            clearable
            @keyup.enter="handleQuery"
            class="!w-180px"
          />
        </el-form-item>
        <el-form-item v-show="expandQuery" label="分类" prop="categoryId">
          <el-tree-select
            v-model="queryParams.categoryId"
            :data="categoryTree"
            :props="treeProps"
            check-strictly
            placeholder="请选择分类"
            clearable
            node-key="id"
            class="!w-180px"
          />
        </el-form-item>
        <el-form-item v-show="expandQuery" label="药品类型" prop="drugType">
          <el-select v-model="queryParams.drugType" placeholder="请选择" clearable class="!w-180px">
            <el-option
              v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_DRUG_TYPE)"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态" prop="status">
          <el-select v-model="queryParams.status" placeholder="全部" clearable class="!w-180px">
            <el-option
              v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_STATUS)"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-show="expandQuery" label="线上可售" prop="saleableOnline">
          <el-select v-model="queryParams.saleableOnline" placeholder="请选择" clearable class="!w-180px">
            <el-option
              v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_YES_NO)"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-show="expandQuery" label="审核状态" prop="approveStatus">
          <el-select
            v-model="queryParams.approveStatus"
            placeholder="请选择"
            clearable
            class="!w-180px"
          >
            <el-option
              v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_DRUG_APPROVE_STATUS)"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
          <el-button text @click="expandQuery = !expandQuery">
            {{ expandQuery ? '收起' : '更多筛选' }}
            <Icon :icon="expandQuery ? 'ep:arrow-up' : 'ep:arrow-down'" class="ml-5px" />
          </el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>

    <!-- 列表 -->
    <ContentWrap class="pharmacy-panel">
      <el-button
        type="primary"
        :icon="Plus"
        plain
        @click="openForm('create')"
        v-hasPermi="['pharmacy:base:drug:create']"
      >
        新增
      </el-button>
      <el-button
        type="success"
        :icon="Download"
        plain
        @click="handleExport"
        v-hasPermi="['pharmacy:base:drug:export']"
        class="ml-10px drug-export"
      >
        导出
      </el-button>

      <el-popover placement="bottom" trigger="click" width="220"><template #reference><el-button class="ml-10px">列设置</el-button></template><el-checkbox-group v-model="extraColumns"><el-checkbox v-for="column in ['商品名', '分类', '药品类型', 'memberPrice']" :key="column" :label="column">{{ column === 'memberPrice' ? '会员价' : column }}</el-checkbox></el-checkbox-group></el-popover>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="mt-10px" />
      <el-table v-loading="loading" :data="list" :show-overflow-tooltip="true" class="mt-10px">
        <el-table-column label="图片" align="center" width="76">
          <template #default="scope">
            <el-image
              v-if="scope.row.imageUrl"
              :src="scope.row.imageUrl"
              :preview-src-list="[scope.row.imageUrl]"
              preview-teleported
              fit="contain"
              class="h-44px w-44px rounded-4px bg-#f5f7fa"
            />
            <span v-else class="text-12px text-#909399">暂无</span>
          </template>
        </el-table-column>
        <el-table-column label="药品编码" align="left" min-width="240" :show-overflow-tooltip="false">
          <template #default="{ row }">
            <div class="drug-code-cell">
              <el-tooltip :content="row.drugCode" placement="top">
                <span class="drug-code">{{ row.drugCode }}</span>
              </el-tooltip>
              <el-button link @click="copyDrugCode(row.drugCode)">复制</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="通用名" align="left" prop="genericName" min-width="160" fixed="left" />
        <el-table-column v-if="extraColumns.includes('商品名')" label="商品名" align="left" prop="tradeName" min-width="120" />
        <el-table-column label="规格" align="left" min-width="150" :show-overflow-tooltip="false"><template #default="{ row }"><span class="drug-spec">{{ row.specification }}</span></template></el-table-column>
        <el-table-column v-if="extraColumns.includes('分类')" label="分类" align="left" prop="categoryName" min-width="100" />
        <el-table-column v-if="extraColumns.includes('药品类型')" label="药品类型" align="center" prop="drugType" width="150">
          <template #default="scope">
            <dict-tag :type="DICT_TYPE.PHARMACY_DRUG_TYPE" :value="scope.row.drugType" />
            <el-tag
              v-if="isDrugTypeInconsistent(scope.row)"
              type="warning"
              size="small"
              class="ml-5px"
              >属性不一致</el-tag
            >
            <el-tag
              v-else
              :type="scope.row.isRx === 1 ? 'danger' : 'info'"
              size="small"
              class="ml-5px"
            >
              {{ scope.row.isRx === 1 ? 'Rx' : '非处方' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="零售价" align="right" width="100"><template #default="{ row }">¥{{ Number(row.retailPrice || 0).toFixed(2) }}</template></el-table-column>
        <el-table-column v-if="extraColumns.includes('memberPrice')" label="会员价" align="right" width="100">
          <template #default="scope">{{ scope.row.memberPrice == null ? '-' : '¥' + Number(scope.row.memberPrice).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="线上可售" align="center" prop="saleableOnline" width="100">
          <template #default="scope">
            <dict-tag :type="DICT_TYPE.PHARMACY_YES_NO" :value="scope.row.saleableOnline" />
          </template>
        </el-table-column>
        <el-table-column label="审核状态" align="center" prop="approveStatus" width="100">
          <template #default="scope">
            <dict-tag
              :type="DICT_TYPE.PHARMACY_DRUG_APPROVE_STATUS"
              :value="scope.row.approveStatus"
            />
          </template>
        </el-table-column>
        <el-table-column label="启用" align="center" prop="status" width="80">
          <template #default="scope">
            <dict-tag :type="DICT_TYPE.PHARMACY_STATUS" :value="scope.row.status" />
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="210" fixed="right">
          <template #default="scope">
            <el-button
              link
              type="primary"
              @click="openForm('update', scope.row.id)"
              v-hasPermi="['pharmacy:base:drug:update']"
              >编辑</el-button
            >
            <el-button v-if="scope.row.approveStatus === 0 && canReview" type="primary" size="small" @click="goReview(scope.row.id)">去审核</el-button>
            <el-dropdown v-hasPermi="['pharmacy:base:drug:delete']" @command="() => handleDelete(scope.row.id)">
              <el-button link type="primary">更多<Icon icon="ep:arrow-down" class="ml-5px" /></el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item command="delete">删除</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <Pagination
        :total="total"
        v-model:page="queryParams.pageNo"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </ContentWrap>

    <DrugForm ref="formRef" @success="getList" />
  </div>
</template>
<script setup lang="ts">
const extraColumns = ref<string[]>([])
const copyDrugCode = async (code: string) => {
  try { await navigator.clipboard.writeText(code); message.success('编码已复制') }
  catch { message.error('复制失败，请选择编码手动复制') }
}
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import { Search, Refresh, Plus, Download } from '@element-plus/icons-vue'
import { checkPermi } from '@/utils/permission'
import * as DrugApi from '@/api/pharmacy/base/drug'
import * as CategoryApi from '@/api/pharmacy/base/category'
import { handleTree } from '@/utils/tree'
import DrugForm from './DrugForm.vue'

defineOptions({ name: 'PharmacyBaseDrug' })

const message = useMessage() // 消息弹窗
const { t } = useI18n() // 国际化

const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<any[]>([])
let listGeneration = 0
/** 查询区「更多筛选」展开状态：默认仅展示高频条件，审核状态收入展开区 */
const expandQuery = ref(false)
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  drugCode: undefined,
  genericName: undefined,
  tradeName: undefined,
  categoryId: undefined,
  drugType: undefined,
  approveStatus: undefined,
  saleableOnline: undefined,
  status: 1 as number | undefined
})
const queryFormRef = ref()

/**
 * 药品类型与处方药一致性判断（不静默隐藏不一致数据）：
 * drugType=0(处方) 期望 isRx=1；drugType=1/2(OTC) 期望 isRx=0；其余类型不强制。
 */
const isDrugTypeInconsistent = (row: any): boolean => {
  if (row.drugType === 0 && row.isRx !== 1) return true
  if ((row.drugType === 1 || row.drugType === 2) && row.isRx === 1) return true
  return false
}

const router = useRouter()
const canReview = computed(() => checkPermi(['pharmacy:base:drug:query']) && checkPermi(['pharmacy:base:drug:approve']))
const goReview = (id: number) => router.push({ name: 'PharmacyDrugReview', query: { id: String(id) } })

/** 分类树 */
const categoryTree = ref<any[]>([])
const treeProps = { label: 'catName', value: 'id', children: 'children' }
const getCategoryTree = async () => {
  const list = await CategoryApi.getSimpleCategoryList()
  categoryTree.value = handleTree(list, 'id', 'parentId')
}

/** 查询列表 */
const getList = async () => {
  const ticket = ++listGeneration
  loading.value = true
  listError.value = ''
  try {
    const data = await DrugApi.getDrugPage(queryParams)
    if (ticket !== listGeneration) return
    list.value = data.list
    total.value = data.total
  } catch {
    if (ticket !== listGeneration) return
    listError.value = '药品列表加载失败，请重试。当前表格仍是上次成功的结果。'
  } finally {
    if (ticket === listGeneration) loading.value = false
  }
}

/** 搜索 */
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}

/** 重置 */
const resetQuery = () => {
  queryFormRef.value.resetFields()
  queryParams.status = 1
  handleQuery()
}

/** 新增/修改弹窗 */
const formRef = ref()
const openForm = (type: string, id?: number) => {
  formRef.value.open(type, id)
}

/** 删除 */
const handleDelete = async (id: number) => {
  try {
    await message.delConfirm()
    await DrugApi.deleteDrug(id)
    message.success(t('common.delSuccess'))
    await getList()
  } catch {}
}

/** 导出 */
const handleExport = async () => {
  try {
    await message.exportConfirm()
    await DrugApi.exportDrug(queryParams)
    message.success(t('common.exportSuccess'))
  } catch {}
}

/** 初始化 */
let activatedOnce = false
onMounted(async () => {
  getList()
  await getCategoryTree()
})
onActivated(() => {
  if (activatedOnce) getList()
  activatedOnce = true
})
</script>

<style scoped>
.drug-export:not(.is-disabled) {
  --el-button-text-color: #27643c;
  --el-button-bg-color: #f0f9eb;
  --el-button-border-color: #b9d7ae;
  --el-button-hover-text-color: #27643c;
  --el-button-hover-bg-color: #e3f0dc;
  --el-button-hover-border-color: #27643c;
  --el-button-active-text-color: #27643c;
  --el-button-active-bg-color: #e3f0dc;
  --el-button-active-border-color: #27643c;
}
.drug-export:not(.is-disabled):focus-visible {
  outline: 2px solid #27643c;
  outline-offset: 2px;
}

.drug-list-heading { display: flex; align-items: baseline; gap: 16px; margin-bottom: 12px; }
.drug-list-heading h1 { margin: 0; font-size: 20px; line-height: 28px; }
.drug-list-heading span { color: var(--el-text-color-secondary); }
.drug-code-cell { display: flex; align-items: center; gap: 8px; }
.drug-code { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; user-select: all; }
.drug-code-cell :deep(.el-button) { flex-shrink: 0; }
.drug-spec { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: normal; }
</style>
