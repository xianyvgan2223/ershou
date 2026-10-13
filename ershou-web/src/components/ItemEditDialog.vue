<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateItem } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { fetchCategories } from '@/api/auth'
import { tradePointLabel, useTradePoints } from '@/composables/useTradePoints'
import { CONDITION_OPTIONS } from '@/utils/constants'
import ImageUploader from '@/components/ImageUploader.vue'
import type { Category } from '@/api/types'

/**
 * 编辑已发布的商品。
 *
 * 抽成独立组件的原因：**发布列表页和商品详情页都要能改**。
 * 之前只有「我的发布」里有这个弹窗，卖家在自己商品的详情页看完想改价得绕回去，
 * 多一次跳转（用户的诉求就是"商品页面增加修改按钮"）。
 * 复制一份表单到详情页是最糟的做法 —— 两边字段一改就会不一致，所以抽出来共用。
 *
 * 字段与后端 `ItemUpdateDTO` 对齐（全字段可选，只提交真正改过的项）。
 *
 * ⚠️ 改标题 / 描述 / 图片会**重新过内容审核**：命中禁售词被直接拦下，
 * 可疑词打回「审核中」（商品暂时从在售列表消失）。这一点必须在弹窗里讲清楚，
 * 否则卖家会以为"改个名字怎么东西没了"。
 */
const props = defineProps<{
  modelValue: boolean
  item: ItemVO | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 保存成功：把最新状态回给调用方（列表刷新 / 详情页局部更新） */
  (e: 'saved', payload: { status: number }): void
}>()

// 与发布页同一口径：下拉用合并后的 options，回显/查找用原始 points
const { options: pointOptions, load: loadTradePoints } = useTradePoints()
const categories = ref<Category[]>([])
const saving = ref(false)

interface EditForm {
  id: number
  title: string
  price: number
  originalPrice?: number
  negotiable: 0 | 1
  conditionLevel: number
  categoryId?: number
  description: string
  images: string[]
  /** 未选=undefined（el-select 的 modelValue 不接受 null） */
  preferPointId?: number
  /** 已售出/违规下架等不可编辑状态：只读展示，不给保存 */
  readOnlyReason: string
}

const form = ref<EditForm>({
  id: 0,
  title: '',
  price: 0,
  originalPrice: undefined,
  negotiable: 1,
  conditionLevel: 3,
  categoryId: undefined,
  description: '',
  images: [],
  preferPointId: undefined,
  readOnlyReason: '',
})

/**
 * 后端 ItemService.update 只拦一种状态：已售出(4)。
 * 其余（含违规下架 8）都允许改 —— 所以这里也只声明这一条，
 * 别自己发明限制（发明了就会和"按钮到底出不出来"对不上）。
 */
const UNEDITABLE: Record<number, string> = {
  4: '商品已售出，不能再改价格和标题（要保留成交记录）',
}

const editable = computed(() => !form.value.readOnlyReason)


watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    const it = props.item
    if (!it) return
    form.value = {
      id: it.id,
      title: it.title ?? '',
      price: it.price ?? 0,
      originalPrice: it.originalPrice ?? undefined,
      negotiable: (it.negotiable ?? 1) as 0 | 1,
      conditionLevel: it.conditionLevel ?? 3,
      categoryId: it.categoryId,
      description: it.description ?? '',
      images: [...(it.images ?? (it.coverUrl ? [it.coverUrl] : []))],
      preferPointId: it.preferPointId ?? undefined,
      readOnlyReason: UNEDITABLE[it.status] ?? '',
    }
    // 分类与面交点列表：只在真的需要时拉一次（composable 内部有模块级缓存）
    if (!categories.value.length) {
      categories.value = await fetchCategories().catch(() => [])
    }
    await loadTradePoints().catch(() => undefined)
  },
)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  const f = form.value
  if (!f.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!f.price || f.price <= 0) {
    ElMessage.warning('价格要大于 0')
    return
  }
  if (f.originalPrice && f.originalPrice > 0 && f.originalPrice < f.price) {
    ElMessage.warning('原价不能低于售价')
    return
  }
  if (!f.images.length) {
    ElMessage.warning('至少保留一张图片')
    return
  }

  saving.value = true
  try {
    const result = await updateItem(f.id, {
      title: f.title.trim(),
      price: f.price,
      originalPrice: f.originalPrice ?? null,
      negotiable: f.negotiable,
      conditionLevel: f.conditionLevel,
      categoryId: f.categoryId,
      description: f.description,
      images: f.images,
      preferPointId: f.preferPointId ?? null,
    })
    // 后端把「需要复审」体现在 status=1 上，文案要跟着变，别让卖家以为改坏了
    ElMessage.success(result.status === 1 ? '已保存，改动需重新审核（商品暂时显示为「审核中」）' : '已保存')
    emit('saved', { status: result.status })
    close()
  } catch {
    // 响应拦截器已提示（禁售词等后端文案会直接显示出来）
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="编辑商品"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="!editable"
      type="warning"
      :closable="false"
      show-icon
      :title="form.readOnlyReason"
      class="edit-item__blocked"
    />

    <el-form label-width="72px" label-position="left" :disabled="!editable">
      <el-form-item label="标题">
        <el-input
          v-model="form.title"
          maxlength="80"
          show-word-limit
          placeholder="同学搜的时候看到的就是它"
        />
      </el-form-item>

      <el-form-item label="售价">
        <el-input-number v-model="form.price" :min="0.01" :max="99999.99" :precision="2" :step="1" />
        <span class="text-muted edit-item__tip">改价格对已收藏的同学也立即生效</span>
      </el-form-item>

      <el-form-item label="原价">
        <el-input-number
          v-model="form.originalPrice"
          :min="0"
          :max="99999.99"
          :precision="2"
          placeholder="选填"
        />
      </el-form-item>

      <el-form-item label="可议价">
        <el-radio-group v-model="form.negotiable">
          <el-radio :value="1">可小刀</el-radio>
          <el-radio :value="0">一口价</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="分类">
        <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="成色">
        <el-select v-model="form.conditionLevel" style="width: 100%">
          <el-option
            v-for="opt in CONDITION_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="面交点">
        <el-select
          v-model="form.preferPointId"
          clearable
          filterable
          placeholder="选填 · 校内面交更安全"
          style="width: 100%"
        >
          <el-option
            v-for="point in pointOptions"
            :key="point.id"
            :label="tradePointLabel(point)"
            :value="point.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit />
      </el-form-item>

      <el-form-item label="商品图片">
        <ImageUploader v-model="form.images" :limit="6" biz="item" />
      </el-form-item>
    </el-form>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="改标题 / 描述 / 图片会重新过内容审核：命中禁售词会被直接拦下，可疑词会转人工（商品暂时变成「审核中」）"
    />

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!editable" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.edit-item {
  &__tip {
    margin-left: 10px;
    font-size: 12px;
  }

  &__blocked {
    margin-bottom: 12px;
  }
}
</style>
