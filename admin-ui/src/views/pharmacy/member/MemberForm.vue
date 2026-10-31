<template>
  <el-drawer
    v-model="drawerVisible"
    :title="drawerTitle"
    direction="rtl"
    size="min(640px, 96vw)"
    :before-close="handleClose"
  >
    <el-form
      ref="formRef"
      v-loading="formLoading"
      :model="formData"
      :rules="formRules"
      label-width="100px"
    >
      <el-divider content-position="left">基本信息</el-divider>
      <el-form-item label="头像" prop="avatar">
        <el-avatar :src="formData.avatar" :size="80">
          <Icon icon="ep:user" />
        </el-avatar>
      </el-form-item>
      <el-form-item label="手机号" prop="mobile">
        <el-input v-model="formData.mobile" maxlength="11" placeholder="请输入手机号" :disabled="!canEdit" />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="formData.nickname" maxlength="30" placeholder="请输入昵称" :disabled="!canEdit" />
      </el-form-item>
      <el-form-item label="姓名" prop="name">
        <el-input v-model="formData.name" maxlength="30" placeholder="请输入姓名" :disabled="!canEdit" />
      </el-form-item>
      <el-form-item label="性别" prop="sex">
        <el-radio-group v-model="formData.sex" :disabled="!canEdit">
          <el-radio
            v-for="dict in getIntDictOptions(DICT_TYPE.SYSTEM_USER_SEX)"
            :key="dict.value"
            :value="dict.value"
          >
            {{ dict.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="生日" prop="birthday">
        <el-date-picker
          v-model="formData.birthday"
          type="date"
          placeholder="请选择生日"
          value-format="YYYY-MM-DD"
          class="!w-full"
          :disabled="!canEdit"
        />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="formData.email" maxlength="100" placeholder="请输入邮箱" :disabled="!canEdit" />
      </el-form-item>
      <el-form-item label="备注" prop="mark">
        <el-input
          v-model="formData.mark"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="请输入备注"
          :disabled="!canEdit"
        />
      </el-form-item>

      <el-divider content-position="left">账户信息</el-divider>
      <el-form-item label="会员等级" prop="levelId">
        <el-select v-model="formData.levelId" placeholder="请选择会员等级" class="!w-full" :disabled="!canEdit">
          <el-option
            v-for="item in levelList"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="积分" prop="point">
        <el-input-number
          v-model="formData.point"
          :min="0"
          controls-position="right"
          class="!w-200px"
          :disabled="!canEdit"
        />
      </el-form-item>
      <el-form-item label="经验值" prop="experience">
        <el-input-number
          v-model="formData.experience"
          :min="0"
          controls-position="right"
          class="!w-200px"
          :disabled="!canEdit"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="formData.status" :disabled="!canEdit">
          <el-radio v-for="item in MEMBER_STATUS_OPTIONS" :key="item.value" :value="item.value">
            {{ item.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>

      <el-divider v-if="!isCreate" content-position="left">注册与登录信息</el-divider>
      <el-form-item v-if="!isCreate" label="注册终端">
        <dict-tag :type="DICT_TYPE.TERMINAL" :value="formData.registerTerminal" />
      </el-form-item>
      <el-form-item v-if="!isCreate" label="最后登录">
        <span>{{ formatDate(formData.loginDate) || '-' }}</span>
      </el-form-item>
      <el-form-item v-if="!isCreate" label="注册时间">
        <span>{{ formatDate(formData.createTime) || '-' }}</span>
      </el-form-item>
      <el-form-item v-if="!isCreate && canViewPoints">
        <el-button @click="openPoints">查看该会员积分</el-button>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button v-if="canEdit" :disabled="formLoading" :loading="formLoading" type="primary" @click="submitForm">保 存</el-button>
      <el-button @click="drawerVisible = false">取 消</el-button>
    </template>
  </el-drawer>
</template>
<script lang="ts" setup>
import { DICT_TYPE, getIntDictOptions } from '@/utils/dict'
import { formatDate } from '@/utils/formatTime'
import { checkPermi } from '@/utils/permission'
import * as MemberApi from '@/api/pharmacy/member/user'
import * as LevelApi from '@/api/pharmacy/member/level'
import { MEMBER_ENABLED, MEMBER_STATUS_OPTIONS } from './member-status'

defineOptions({ name: 'PharmacyMemberUserForm' })

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗

const drawerVisible = ref(false) // 抽屉的是否展示
const drawerTitle = ref('') // 抽屉的标题
const formLoading = ref(false) // 表单的加载中
const formRef = ref() // 表单 Ref
const isCreate = ref(false) // 是否为新增模式

/** 统一默认值，避免隐式 any 与类型漂移 */
const createDefaultFormData = (): MemberApi.MemberUserVO => ({
  id: undefined,
  mobile: '',
  nickname: '',
  avatar: '',
  name: '',
  sex: 0,
  birthday: '',
  status: MEMBER_ENABLED,
  point: 0,
  levelId: 0,
  experience: 0,
  registerIp: '',
  registerTerminal: 0,
  loginIp: '',
  loginDate: new Date(),
  email: '',
  mark: '',
  createTime: undefined
})

const formData = ref<MemberApi.MemberUserVO>(createDefaultFormData())

const formRules = reactive({
  mobile: [{ required: true, message: '手机号不能为空', trigger: 'blur' }],
  nickname: [{ required: true, message: '昵称不能为空', trigger: 'blur' }],
  status: [{ required: true, message: '状态不能为空', trigger: 'change' }]
})
const loaded = ref(false)
const canEdit = computed(
  () => (isCreate.value && checkPermi(['pharmacy:member:user:create'])) || (!isCreate.value && loaded.value && checkPermi(['pharmacy:member:user:update']))
)
const canViewPoints = computed(() => checkPermi(['pharmacy:member:point-record:query']))
const router = useRouter()

/** 会员等级列表 */
const levelList = ref<LevelApi.MemberLevelSimpleVO[]>([])
const loadLevelList = async () => {
  const data = await LevelApi.getSimpleLevelList()
  levelList.value = data
}

/** 打开抽屉：编辑（查看 / 修改） */
const open = async (id: number) => {
  if (formLoading.value) return
  drawerVisible.value = true
  isCreate.value = false
  loaded.value = false
  drawerTitle.value = '会员详情'
  formLoading.value = true
  try {
    await loadLevelList()
    const data = await MemberApi.getMember(id)
    formData.value = { ...createDefaultFormData(), ...data }
    loaded.value = true
  } catch {
    message.error('会员资料读取失败，请关闭后重试')
    drawerVisible.value = false
  } finally {
    formLoading.value = false
  }
}

/** 打开抽屉：新增 */
const openCreate = async () => {
  drawerVisible.value = true
  isCreate.value = true
  drawerTitle.value = '新增会员'
  formData.value = createDefaultFormData()
  formLoading.value = true
  try {
    await loadLevelList()
  } finally {
    formLoading.value = false
  }
}
defineExpose({ open, openCreate }) // 提供 open / openCreate 方法，用于打开抽屉

/** 构造保存载荷，显式逐字段返回（只提交后端 SaveReqVO 支持的字段） */
const buildSaveData = (): MemberApi.MemberUserSaveVO => {
  const v = formData.value
  const payload: MemberApi.MemberUserSaveVO = {
    id: v.id,
    mobile: v.mobile,
    nickname: v.nickname,
    name: v.name,
    sex: v.sex,
    birthday: v.birthday,
    status: v.status,
    point: v.point,
    levelId: v.levelId,
    experience: v.experience,
    email: v.email,
    mark: v.mark,
    avatar: v.avatar
  }
  return payload
}

/** 提交表单 */
const emit = defineEmits(['success'])
const openPoints = () => {
  if (!formData.value.id) return
  router.push({ path: '/pharmacy-base/member/point-record', query: { userId: String(formData.value.id) } })
}

const submitForm = async () => {
  if (!formRef.value || formLoading.value || !canEdit.value) return
  formLoading.value = true
  try {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
    const data = buildSaveData()
    if (isCreate.value) {
      // 新增：注册 / 登录信息由服务端维护，不提交
      await MemberApi.createMember(data)
      message.success(t('common.createSuccess'))
    } else {
      await MemberApi.updateMember(data)
      message.success(t('common.updateSuccess'))
    }
    drawerVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}

/** 关闭前重置 */
const handleClose = (done: () => void) => {
  formData.value = createDefaultFormData()
  isCreate.value = false
  loaded.value = false
  formRef.value?.resetFields()
  done()
}
</script>
