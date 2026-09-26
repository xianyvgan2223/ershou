<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Location, Plus } from '@element-plus/icons-vue'
import { checkoutCart } from '@/api/cart'
import type { CheckoutResult } from '@/api/cart'
import { createMeetupOrder } from '@/api/order'
import { pointTypeLabel } from '@/api/map'
import { useTradePoints } from '@/composables/useTradePoints'
import {
  MEETUP_MAX_DAYS,
  MEETUP_MAX_HOURS,
  defaultMeetupRange,
  disabledMeetupDate,
  formatMeetupDuration,
  validateMeetupRange,
} from '@/utils/meetup'

/**
 * 约面交弹窗：单品直接约（商品详情页）与购物车合并面交共用一套表单。
 * 时间窗默认 1 小时后 ~ 2 小时后，并做与后端一致的本地校验，避免填完才报 40005。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 单品：商品 id */
    itemId?: number
    /** 单品：卖家推荐点位，作为默认值 */
    preferPointId?: number | null
    /** 购物车：勾选的购物车条目 id */
    cartIds?: number[]
    /** 购物车：件数，仅用于文案 */
    itemCount?: number
    /** 标题（不传按模式自动取） */
    title?: string
  }>(),
  {
    itemId: undefined,
    preferPointId: null,
    cartIds: () => [],
    itemCount: 0,
    title: '',
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'success', result: CheckoutResult): void
}>()

const router = useRouter()
const { points, loading: pointsLoading, load: loadPoints, find: findPoint } = useTradePoints()

const submitting = ref(false)
const form = reactive({
  pointId: undefined as number | undefined,
  timeRange: [] as string[],
  remark: '',
})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const isBatch = computed(() => props.cartIds.length > 0)
const dialogTitle = computed(() => props.title || (isBatch.value ? '合并面交' : '约面交'))
const selectedPoint = computed(() => findPoint(form.pointId))
const durationText = computed(() => formatMeetupDuration(form.timeRange))

/** 重新选日期时的时间默认值：现在 ~ 2 小时后，避免选完两个日期得到 0 长度时间窗 */
const defaultTimes = computed<[Date, Date]>(() => {
  const start = new Date()
  start.setSeconds(0, 0)
  return [start, new Date(start.getTime() + 2 * 3600 * 1000)]
})

watch(visible, async (open) => {
  if (!open) return
  form.remark = ''
  form.timeRange = defaultMeetupRange()
  const list = await loadPoints(true)
  if (!list.length) {
    form.pointId = undefined
    return
  }
  // 默认优先卖家推荐的点位，其次上次选过的；都没有就不预选（留空=待协商）
  form.pointId = [props.preferPointId, form.pointId].find(
    (id) => id !== null && id !== undefined && list.some((point) => point.id === id),
  ) as number | undefined
})

function goMap(): void {
  visible.value = false
  router.push('/map')
}

async function submit(): Promise<void> {
  // 面交点 / 时间窗都可以留空：留空 = 待协商，双方在私聊里定
  const invalid = validateMeetupRange(form.timeRange)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  const [start, end] = form.timeRange
  const payload = {
    pointId: form.pointId ?? null,
    timeSlotStart: start ?? null,
    timeSlotEnd: end ?? null,
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
      <el-form-item label="面交点">
        <el-select
          v-model="form.pointId"
          filterable
          clearable
          :loading="pointsLoading"
          placeholder="可选：输入地点名搜索，留空=私聊里商量"
          style="width: 100%"
        >
          <el-option
            v-for="point in points"
            :key="point.id"
            :label="`${point.name}（${pointTypeLabel(point.type)}）`"
            :value="point.id"
          >
            <span class="booking__option-name">{{ point.name }}</span>
            <span class="booking__option-type">{{ pointTypeLabel(point.type) }}</span>
          </el-option>
          <template #empty>
            <p class="booking__empty">当前校区还没有可选点位</p>
          </template>
          <template #footer>
            <div class="booking__point-more" @click="goMap">
              <el-icon><Plus /></el-icon>
              找不到合适的地点？去地图上推荐一个新点位
            </div>
          </template>
        </el-select>
        <p v-if="selectedPoint?.guideText" class="booking__guide">
          <el-icon><Location /></el-icon>
          {{ selectedPoint.guideText }}
        </p>
      </el-form-item>

      <el-form-item label="面交时间">
        <el-date-picker
          v-model="form.timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间（可留空）"
          end-placeholder="结束时间（可留空）"
          value-format="YYYY-MM-DD HH:mm:ss"
          :default-time="defaultTimes"
          :disabled-date="disabledMeetupDate"
          style="width: 100%"
        />
        <p class="booking__time-tip">
          <template v-if="durationText">已选 {{ durationText }}，</template>
          想先约个时间就填，还没定就留空（私聊里商量）。填了的话需在 {{ MEETUP_MAX_DAYS }} 天内、单次不超过 {{ MEETUP_MAX_HOURS }} 小时
        </p>
      </el-form-item>

      <el-form-item label="留言">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          maxlength="100"
          show-word-limit
          placeholder="例如：我在图书馆三楼，可以提前十分钟到"
        />
      </el-form-item>
    </el-form>

    <el-alert
      v-if="isBatch"
      type="info"
      :closable="false"
      show-icon
      :title="`合并面交会把 ${itemCount || cartIds.length} 件商品按卖家拆成多张面交单，共用同一个面交点与时间窗。`"
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
