<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { buildUploadUrl } from '@/api/file'
import type { UploadBiz } from '@/api/file'
import { getToken } from '@/utils/request'
import type { ApiResult } from '@/utils/request'

/** el-upload 内部文件对象的最小子集，避免额外依赖 */
interface UploadFileItem {
  name: string
  url?: string
  uid?: number
  status?: 'ready' | 'uploading' | 'success' | 'fail'
  percentage?: number
  size?: number
}

interface UploadData {
  url: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: string[]
    limit?: number
    biz?: UploadBiz
    tip?: string
  }>(),
  { modelValue: () => [], limit: 6, biz: 'item', tip: '' },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
}>()

const fileList = ref<UploadFileItem[]>([])
const headers = computed(() => (getToken() ? { Authorization: `Bearer ${getToken()}` } : {}))
const action = computed(() => buildUploadUrl(props.biz))
const tipText = computed(
  () => props.tip || `最多 ${props.limit} 张，支持 jpg / png / webp，建议单张不超过 5MB`,
)

// 外部回填（例如编辑已有商品）
watch(
  () => props.modelValue,
  (value) => {
    const urls = value ?? []
    const current = fileList.value.map((file) => file.url).filter(Boolean) as string[]
    if (urls.length === current.length && urls.every((url, index) => url === current[index])) return
    fileList.value = urls.map((url, index): UploadFileItem => ({
      name: `图片${index + 1}`,
      url,
      uid: Date.now() + index,
      status: 'success',
    }))
  },
  { immediate: true },
)

function sync(): void {
  emit(
    'update:modelValue',
    fileList.value.map((file) => file.url ?? '').filter((url) => url !== ''),
  )
}

function beforeUpload(rawFile: File): boolean {
  const isImage = rawFile.type.startsWith('image/')
  if (!isImage) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (rawFile.size / 1024 / 1024 > 5) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

function handleSuccess(response: ApiResult<UploadData>, file: UploadFileItem): void {
  if (response && response.code === 0 && response.data?.url) {
    file.url = response.data.url
    file.status = 'success'
    sync()
    ElMessage.success('图片上传成功')
    return
  }
  ElMessage.error(response?.message || '图片上传失败')
  removeFile(file)
}

function handleError(_error: Error, file: UploadFileItem): void {
  ElMessage.error('图片上传失败，请稍后重试')
  removeFile(file)
}

function handleRemove(file: UploadFileItem): void {
  removeFile(file)
}

function handleExceed(): void {
  ElMessage.warning(`最多只能上传 ${props.limit} 张图片`)
}

function removeFile(file: UploadFileItem): void {
  fileList.value = fileList.value.filter((item) => item.uid !== file.uid)
  sync()
}
</script>

<template>
  <div class="uploader">
    <el-upload
      v-model:file-list="fileList"
      :action="action"
      :headers="headers"
      :limit="limit"
      name="file"
      list-type="picture-card"
      accept="image/*"
      :before-upload="beforeUpload"
      :on-success="handleSuccess"
      :on-error="handleError"
      :on-remove="handleRemove"
      :on-exceed="handleExceed"
    >
      <el-icon><Plus /></el-icon>
    </el-upload>
    <p class="uploader__tip">{{ tipText }}</p>
  </div>
</template>

<style scoped lang="scss">
.uploader {
  &__tip {
    margin: 4px 0 0;
    color: var(--ershou-text-light);
    font-size: 12px;
    line-height: 18px;
  }
}
</style>
