<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Camera, Delete } from '@element-plus/icons-vue'
import { buildUploadUrl } from '@/api/file'
import { fetchCampuses, fetchSchools } from '@/api/school'
import type { Campus, School } from '@/api/school'
import { useUserStore } from '@/stores/user'
import { getToken } from '@/utils/request'
import type { ApiResult } from '@/utils/request'
import { nameInitial } from '@/utils/format'

const props = defineProps<{ modelValue: boolean }>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const userStore = useUserStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const schools = ref<School[]>([])
const campuses = ref<Campus[]>([])
const loadingSchools = ref(false)
const loadingCampuses = ref(false)
let schoolsLoaded = false

const form = reactive({
  nickname: '',
  avatar: '',
  schoolId: undefined as number | undefined,
  campusId: undefined as number | undefined,
  shopName: '',
  shopIntro: '',
})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const uploadUrl = buildUploadUrl('avatar')
const uploadHeaders = computed(() => (getToken() ? { Authorization: `Bearer ${getToken()}` } : {}))

const rules = computed<FormRules>(() => ({
  nickname: [
    { required: true, message: '请填写昵称', trigger: 'blur' },
    { min: 1, max: 32, message: '昵称 1-32 个字', trigger: 'blur' },
  ],
  // 选了学校就必须选校区：只换学校不换校区会让 school/campus 对不上（后端也会拦）
  campusId: form.schoolId ? [{ required: true, message: '请选择校区', trigger: 'change' }] : [],
}))

/** 用当前资料回填表单（打开弹窗时调用，避免上次改了一半的残留） */
function fillForm(): void {
  const profile = userStore.profile
  form.nickname = profile?.nickname ?? ''
  form.avatar = profile?.avatar ?? ''
  form.schoolId = profile?.schoolId ?? undefined
  form.campusId = profile?.campusId ?? undefined
  form.shopName = profile?.shopName ?? ''
  form.shopIntro = profile?.shopIntro ?? ''
}

async function ensureSchools(): Promise<void> {
  if (schoolsLoaded || loadingSchools.value) return
  loadingSchools.value = true
  try {
    schools.value = await fetchSchools()
    schoolsLoaded = true
  } catch {
    // 响应拦截器已提示
  } finally {
    loadingSchools.value = false
  }
}

async function loadCampuses(schoolId?: number): Promise<void> {
  if (!schoolId) {
    campuses.value = []
    return
  }
  loadingCampuses.value = true
  try {
    campuses.value = await fetchCampuses(schoolId)
  } catch {
    campuses.value = []
  } finally {
    loadingCampuses.value = false
  }
}

/** 换学校必须清空校区：旧校区不属于新学校，提交会被后端打回 */
async function handleSchoolChange(schoolId: number): Promise<void> {
  form.campusId = undefined
  await loadCampuses(schoolId)
}

function beforeAvatarUpload(rawFile: File): boolean {
  if (!rawFile.type.startsWith('image/')) {
    ElMessage.error('头像只能是图片文件')
    return false
  }
  if (rawFile.size / 1024 / 1024 > 5) {
    ElMessage.error('头像大小不能超过 5MB')
    return false
  }
  return true
}

function onAvatarSuccess(response: ApiResult<{ url: string }>): void {
  if (response && response.code === 0 && response.data?.url) {
    form.avatar = response.data.url
    ElMessage.success('头像已上传，记得点保存')
    return
  }
  ElMessage.error(response?.message || '头像上传失败')
}

function onAvatarError(): void {
  ElMessage.error('头像上传失败，请稍后重试')
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await userStore.updateProfile({
      nickname: form.nickname.trim(),
      avatar: form.avatar,
      schoolId: form.schoolId,
      campusId: form.campusId,
      shopName: form.shopName.trim(),
      shopIntro: form.shopIntro.trim(),
    })
    ElMessage.success('资料已更新')
    visible.value = false
  } catch {
    // 内容违规等业务错误由响应拦截器提示
  } finally {
    submitting.value = false
  }
}

watch(visible, async (open) => {
  if (!open) return
  if (!userStore.profile) {
    try {
      await userStore.fetchProfile()
    } catch {
      // 拉不到就按空表单展示，保存时后端仍会校验
    }
  }
  fillForm()
  formRef.value?.clearValidate()
  await ensureSchools()
  // 学校列表可能还没回来，但资料里已有 schoolId，直接按它拉校区即可
  await loadCampuses(form.schoolId)
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="编辑资料"
    width="560px"
    append-to-body
    :close-on-click-modal="false"
    class="profile-edit"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="86px">
      <el-form-item label="头像">
        <div class="avatar-field">
          <el-upload
            class="avatar-field__upload"
            :action="uploadUrl"
            :headers="uploadHeaders"
            name="file"
            :show-file-list="false"
            accept="image/*"
            :before-upload="beforeAvatarUpload"
            :on-success="onAvatarSuccess"
            :on-error="onAvatarError"
          >
            <div class="avatar-field__preview">
              <el-avatar :size="76" :src="form.avatar || undefined">
                {{ nameInitial(form.nickname) }}
              </el-avatar>
              <span class="avatar-field__mask">
                <el-icon><Camera /></el-icon>
              </span>
            </div>
          </el-upload>
          <div class="avatar-field__tips">
            <p>点击头像上传新图片，支持 jpg / png / webp，不超过 5MB</p>
            <el-button v-if="form.avatar" link type="danger" :icon="Delete" @click="form.avatar = ''">
              恢复默认头像
            </el-button>
          </div>
        </div>
      </el-form-item>

      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" maxlength="32" show-word-limit placeholder="同学们怎么称呼你" />
      </el-form-item>

      <el-form-item label="学校">
        <el-select
          v-model="form.schoolId"
          class="profile-edit__select"
          filterable
          placeholder="输入学校名搜索"
          :loading="loadingSchools"
          @change="handleSchoolChange"
        >
          <el-option v-for="school in schools" :key="school.id" :label="school.name" :value="school.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="校区" prop="campusId">
        <el-select
          v-model="form.campusId"
          class="profile-edit__select"
          filterable
          placeholder="输入校区名搜索"
          :loading="loadingCampuses"
          :disabled="!form.schoolId"
        >
          <el-option v-for="campus in campuses" :key="campus.id" :label="campus.name" :value="campus.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="小铺名称">
        <el-input v-model="form.shopName" maxlength="64" show-word-limit placeholder="选填，商家可填小铺名" />
      </el-form-item>

      <el-form-item label="小铺简介">
        <el-input
          v-model="form.shopIntro"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="选填，写点主营品类或售后说明"
        />
      </el-form-item>
    </el-form>

    <p class="profile-edit__note">
      手机号是登录凭证，暂不支持修改；平台不收集真实姓名，昵称就是对外展示的名字。
    </p>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.avatar-field {
  display: flex;
  align-items: center;
  gap: 14px;

  &__preview {
    position: relative;
    cursor: pointer;
    line-height: 0;
  }

  &__mask {
    position: absolute;
    inset: 0;
    display: grid;
    border-radius: 50%;
    background: rgb(15 23 42 / 46%);
    color: #fff;
    font-size: 20px;
    opacity: 0;
    place-items: center;
    transition: opacity 0.2s ease;
  }

  &__preview:hover &__mask {
    opacity: 1;
  }

  &__tips {
    min-width: 0;

    p {
      margin: 0 0 4px;
      color: var(--text-3);
      font-size: 12.5px;
      line-height: 1.5;
    }
  }
}

.profile-edit {
  &__select {
    width: 100%;
  }

  &__note {
    margin: 4px 0 0 86px;
    color: var(--text-3);
    font-size: 12px;
    line-height: 1.6;
  }
}
</style>
