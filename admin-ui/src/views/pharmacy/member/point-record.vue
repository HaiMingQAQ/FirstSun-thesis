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
      <el-form-item label="会员编号" prop="userId">
        <el-input
          v-model="queryParams.userId"
          placeholder="请输入会员编号"
          clearable
          class="!w-240px"
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="标题" prop="title">
        <el-input
          v-model="queryParams.title"
          placeholder="积分标题"
          clearable
          maxlength="64"
          class="!w-240px"
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="创建时间" prop="createTime">
        <el-date-picker
          v-model="queryParams.createTime"
          value-format="YYYY-MM-DD HH:mm:ss"
          type="datetimerange"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          class="!w-360px"
        />
      </el-form-item>
      <el-form-item label="业务类型" prop="bizType">
        <el-select
          v-model="queryParams.bizType"
          placeholder="请选择业务类型"
          clearable
          class="!w-240px"
        >
          <el-option
            v-for="dict in getIntDictOptions(DICT_TYPE.PHARMACY_MEMBER_POINT_BIZ_TYPE)"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
        <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
        <el-button
          type="success"
          plain
          @click="handleExport"
          :loading="exportLoading"
          v-hasPermi="['pharmacy:member:point-record:export']"
        >
          <Icon icon="ep:download" class="mr-5px" /> 导出
        </el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <!-- 列表 -->
  <ContentWrap class="pharmacy-panel">
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="mb-12px" />
    <el-table v-loading="loading" :data="list" empty-text="没有符合条件的积分记录" :show-overflow-tooltip="true">
      <el-table-column label="记录编号" align="center" prop="id" width="110" />
      <el-table-column label="会员编号" align="center" prop="userId" width="110" />
      <el-table-column label="业务编码" align="center" prop="bizId" min-width="140" />
      <el-table-column label="业务类型" align="center" prop="bizType" width="150">
        <template #default="scope">
          <dict-tag :type="DICT_TYPE.PHARMACY_MEMBER_POINT_BIZ_TYPE" :value="scope.row.bizType" />
        </template>
      </el-table-column>
      <el-table-column label="标题" align="center" prop="title" />
      <el-table-column label="描述" align="center" prop="description" show-overflow-tooltip />
      <el-table-column label="积分变动" align="center" prop="point" width="120">
        <template #default="scope">
          <span :class="scope.row.point > 0 ? 'text-green-500' : 'text-red-500'">
            {{ scope.row.point > 0 ? '+' : '' }}{{ scope.row.point }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="变动后总积分" align="center" prop="totalPoint" width="140" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="180">
        <template #default="scope">
          {{ formatDate(scope.row.createTime) }}
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
</template>
<script lang="ts" setup>
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import download from '@/utils/download'
import { formatDate } from '@/utils/formatTime'
import { checkPermi } from '@/utils/permission'
import * as PointRecordApi from '@/api/pharmacy/member/point-record'
import { POINT_EXPORT_NOTICE, parseMemberId } from './member-status'

defineOptions({ name: 'PharmacyMemberPointRecord' })

const message = useMessage() // 消息弹窗

const loading = ref(true) // 列表的加载中
const listError = ref('')
const total = ref(0) // 列表的总页数
const list = ref<PointRecordApi.PointRecordVO[]>([]) // 列表的数据
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  userId: '' as string | undefined,
  bizType: undefined as number | undefined,
  title: '',
  createTime: [] as string[]
})
let listGeneration = 0
const canQuery = computed(() => checkPermi(['pharmacy:member:point-record:query']))
const route = useRoute()
const queryFormRef = ref() // 搜索的表单
const exportLoading = ref(false) // 导出的加载中

/** 查询积分明细列表 */
const getList = async () => {
  if (!canQuery.value) {
    loading.value = false
    listError.value = '没有积分查询权限，不能读取积分记录。'
    return
  }
  const memberId = parseMemberId(queryParams.userId)
  if (memberId === undefined) {
    loading.value = false
    listError.value = '会员编号需要是正整数。'
    return
  }
  const ticket = ++listGeneration
  loading.value = true
  listError.value = ''
  try {
    const data = await PointRecordApi.getPointRecordPage({
      ...queryParams,
      userId: memberId ?? undefined,
      title: queryParams.title || undefined,
      createTime: queryParams.createTime?.length === 2 ? queryParams.createTime : undefined
    })
    if (ticket !== listGeneration) return
    list.value = data.list || []
    total.value = data.total
  } catch {
    if (ticket !== listGeneration) return
    listError.value = '积分记录加载失败，请重试。表格仍保留上次成功的结果。'
  } finally {
    if (ticket === listGeneration) loading.value = false
  }
}

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value.resetFields()
  handleQuery()
}

/** 导出按钮操作 */
const handleExport = async () => {
  try {
    const memberId = parseMemberId(queryParams.userId)
    if (memberId === undefined) {
      message.warning('会员编号需要是正整数。')
      return
    }
    await message.confirm(POINT_EXPORT_NOTICE, '导出积分明细')
    exportLoading.value = true
    const data = await PointRecordApi.exportPointRecord({
      ...queryParams,
      userId: memberId ?? undefined,
      title: queryParams.title || undefined,
      createTime: queryParams.createTime?.length === 2 ? queryParams.createTime : undefined
    })
    download.excel(data, '积分明细.xls')
  } catch {
  } finally {
    exportLoading.value = false
  }
}

/** 初始化 **/
onMounted(() => {
  const memberId = parseMemberId(route.query.userId)
  if (typeof memberId === 'number') queryParams.userId = String(memberId)
  if (!canQuery.value) {
    loading.value = false
    listError.value = '没有积分查询权限，不能读取积分记录。'
    return
  }
  getList()
})
</script>
