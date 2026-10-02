<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { checkoutCart } from '@/api/cart'
import type { CheckoutResult } from '@/api/cart'
import { createMeetupOrder } from '@/api/order'
// 面交点 / 时间窗的校验、点位选择器都不再需要：买家不填这两项（见下方说明）

/**
 * 约面交弹窗：单品直接约（商品详情页）与购物车合并面交共用一套表单。
 *
 * <p>⚠️ **买家提交意向时不决定面交地点与时间**（2026-10-01 产品规则）：
 * 商品在卖家手上，见面的地点与时间由卖家定（他在「我的面交 → 我卖出的」里设置），
 * 买家想改就在私聊里商量。所以这里只留"留言"—— 以前让买家填面交点+时间窗，
 * 等于让买家替卖家做决定，和后来的权限规则自相矛盾。</p>
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 单品：商品 id */
    itemId?: number
    /** 购物车：勾选的购物车条目 id */
    cartIds?: number[]
    /** 购物车：件数，仅用于文案 */
    itemCount?: number
    /** 标题（不传按模式自动取） */
    title?: string
  }>(),
  {
    itemId: undefined,
    cartIds: () => [],
    itemCount: 0,
    title: '',
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'success', result: CheckoutResult): void
}>()

const submitting = ref(false)
const form = reactive({
  remark: '',
})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const isBatch = computed(() => props.cartIds.length > 0)
const dialogTitle = computed(() => props.title || (isBatch.value ? '合并面交' : '约面交'))

watch(visible, (open) => {
  if (open) form.remark = ''
})

async function submit(): Promise<void> {
  // 地点与时间都不传：由卖家确定（后端也接受留空 = 待协商）
  const payload = {
    pointId: null,
    timeSlotStart: null,
    timeSlotEnd: null,
    remark: form.remark.trim(),
  }
  submitting.value = true
  try {
    const result = isBatch.value
      ? await checkoutCart({ cartIds: props.cartIds, ...payload })
      : await createMeetupOrder({ itemId: props.itemId as number, quantity: 1, ...payload })
    ElMessage.success(
      isBatch.value ? `已生成 ${result.orders?.length ?? 0} 张面交单` : '面交单已提交，等卖家确认',
    )
    visible.value = false
    emit('success', result)
  } catch {
    // 响应拦截器已提示（40005 时间窗 / 30004 已被他人锁定 / 30002 已售出 等）
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="540px"
    append-to-body
    :close-on-click-modal="false"
  >
    <el-form label-width="86px">
      <!-- 面交点与面交时间**不由买家填**：商品在卖家手上，地点时间由他定
           （卖家在「我的面交 → 我卖出的」里设置），买家想改就在私聊里商量。 -->
      <el-form-item label="面交点">
        <p class="booking__fixed">由卖家确定，定好后会私聊通知你</p>
      </el-form-item>

      <el-form-item label="面交时间">
        <p class="booking__fixed">由卖家确定（也可以私聊里商量）</p>
      </el-form-item>

      <el-form-item label="留言">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          maxlength="100"
          show-word-limit
          placeholder="例如：我下午都有空，你定地点就行"
        />
      </el-form-item>
    </el-form>

    <el-alert
      v-if="isBatch"
      type="info"
      :closable="false"
      show-icon
      :title="`合并面交会把 ${itemCount || cartIds.length} 件商品按卖家拆成多张面交单，面交点与时间由各卖家分别确定。`"
    />
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="'提交后商品不会立刻锁定：卖家接受后才锁定，并会收到一条私聊提醒；见面验货后由卖家点「已卖出」完成交易。'"
    />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ isBatch ? '提交面交单' : '确认约面交' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.booking {
  /* 买家不填的字段：用一行灰字说明"谁来定"，避免留空看起来像没填 */
  &__fixed {
    margin: 0;
    color: var(--ershou-text-light);
    font-size: 13px;
  }

  &__option-name {
    margin-right: 6px;
  }

  &__option-type {
    color: var(--text-3);
    font-size: 12px;
  }

  &__empty {
    margin: 0;
    padding: 10px 0;
    color: var(--text-3);
    font-size: 13px;
    text-align: center;
  }

  &__point-more {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 8px 12px;
    border-top: 1px solid var(--line);
    color: var(--brand-600);
    font-size: 12.5px;
    cursor: pointer;

    &:hover {
      background: var(--brand-50);
    }
  }

  &__guide {
    display: flex;
    align-items: center;
    gap: 4px;
    margin: 0;
    color: var(--text-3);
    font-size: 12.5px;
  }

  &__time-tip {
    margin: 4px 0 0;
    color: var(--text-3);
    font-size: 12px;
    line-height: 1.5;
  }
}
</style>
