<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import { checkoutCart, fetchCart, removeCartItem, updateCartQuantity } from '@/api/cart'
import type { CartItem, CartSellerGroup, CheckoutResult } from '@/api/cart'
import { fetchTradePoints, pointTypeLabel } from '@/api/map'
import type { TradePoint } from '@/api/map'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { formatPrice } from '@/utils/format'

const router = useRouter()

const groups = ref<CartSellerGroup[]>([])
const tradePoints = ref<TradePoint[]>([])
const loading = ref(false)
const submitting = ref(false)
const selectedIds = ref<number[]>([])
const checkoutVisible = ref(false)
const resultVisible = ref(false)
const checkoutResult = ref<CheckoutResult | null>(null)

const checkoutForm = reactive({
  pointId: undefined as number | undefined,
  timeRange: [] as string[],
  remark: '',
})

const allItems = computed<CartItem[]>(() => groups.value.flatMap((group) => group.items))
const selectedItems = computed(() => allItems.value.filter((item) => selectedIds.value.includes(item.cartId)))
const totalPrice = computed(() =>
  selectedItems.value.reduce((sum, item) => sum + item.price * item.quantity, 0),
)
const allChecked = computed(
  () => allItems.value.length > 0 && selectedIds.value.length === allItems.value.length,
)
const indeterminate = computed(
  () => selectedIds.value.length > 0 && selectedIds.value.length < allItems.value.length,
)

async function loadCart(): Promise<void> {
  loading.value = true
  try {
    groups.value = await fetchCart()
    const validIds = new Set(allItems.value.map((item) => item.cartId))
    selectedIds.value = selectedIds.value.filter((id) => validIds.has(id))
  } catch {
    groups.value = []
  } finally {
    loading.value = false
  }
}

async function loadTradePoints(): Promise<void> {
  try {
    tradePoints.value = await fetchTradePoints(DEFAULT_CAMPUS_ID)
  } catch {
    tradePoints.value = []
  }
}

function toggleAll(checked: boolean): void {
  selectedIds.value = checked ? allItems.value.map((item) => item.cartId) : []
}

function sellerChecked(group: CartSellerGroup): boolean {
  return group.items.every((item) => selectedIds.value.includes(item.cartId))
}

function handleItemCheck(item: CartItem, checked: string | number | boolean): void {
  if (checked) {
    selectedIds.value = Array.from(new Set([...selectedIds.value, item.cartId]))
  } else {
    selectedIds.value = selectedIds.value.filter((id) => id !== item.cartId)
  }
}

function handleAllCheck(checked: string | number | boolean): void {
  toggleAll(Boolean(checked))
}

function handleSellerCheck(group: CartSellerGroup, checked: string | number | boolean): void {
  toggleSeller(group, Boolean(checked))
}

function toggleSeller(group: CartSellerGroup, checked: boolean): void {
  const ids = group.items.map((item) => item.cartId)
  if (checked) {
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...ids]))
  } else {
    selectedIds.value = selectedIds.value.filter((id) => !ids.includes(id))
  }
}

async function handleQuantityChange(item: CartItem, quantity: number | undefined): Promise<void> {
  if (!quantity || quantity < 1) return
  try {
    await updateCartQuantity(item.cartId, { quantity })
    item.quantity = quantity
    ElMessage.success('已更新数量')
  } catch {
    await loadCart()
  }
}

async function handleRemove(item: CartItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定从购物车移除「${item.title}」吗？`, '移除商品', {
      confirmButtonText: '移除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await removeCartItem(item.cartId)
    ElMessage.success('已移除')
    await loadCart()
  } catch {
    // 响应拦截器已提示
  }
}

function openCheckout(): void {
  if (!selectedIds.value.length) {
    ElMessage.warning('请先勾选要一起面交的商品')
    return
  }
  checkoutForm.pointId = checkoutForm.pointId ?? tradePoints.value[0]?.id ?? undefined
  checkoutVisible.value = true
}

async function submitCheckout(): Promise<void> {
  const pointId = checkoutForm.pointId
  const [start, end] = checkoutForm.timeRange
  if (!pointId) {
    ElMessage.warning('请选择面交点')
    return
  }
  if (!start || !end) {
    ElMessage.warning('请选择面交时间窗')
    return
  }
  submitting.value = true
  try {
    const result = await checkoutCart({
      cartIds: selectedIds.value,
      pointId,
      timeSlotStart: start,
      timeSlotEnd: end,
      remark: checkoutForm.remark.trim(),
    })
    checkoutResult.value = result
    checkoutVisible.value = false
    resultVisible.value = true
    selectedIds.value = []
    await loadCart()
  } catch {
    // 响应拦截器已提示
  } finally {
    submitting.value = false
  }
}

function goOrders(): void {
  resultVisible.value = false
  router.push('/orders')
}

onMounted(() => {
  loadCart()
  loadTradePoints()
})
</script>

<template>
  <div class="page cart">
    <div class="page-head">
      <div>
        <h2 class="page-title">购物车</h2>
        <p class="page-subtitle">勾选同一面交点、同一时间窗的商品，可一键合并成面交单</p>
      </div>
      <el-button :icon="Delete" text type="danger" @click="toggleAll(false)">清空选择</el-button>
    </div>

    <div v-loading="loading" class="cart__body">
      <template v-if="groups.length">
        <el-card v-for="group in groups" :key="group.sellerId" class="section-card" shadow="never">
          <template #header>
            <div class="flex-between">
              <el-checkbox
                :model-value="sellerChecked(group)"
                @change="(checked: string | number | boolean) => handleSellerCheck(group, checked)"
              >
                卖家：{{ group.sellerName }}
              </el-checkbox>
              <span class="text-muted">{{ group.items.length }} 件商品</span>
            </div>
          </template>

          <div v-for="item in group.items" :key="item.cartId" class="cart__item">
            <el-checkbox
              :model-value="selectedIds.includes(item.cartId)"
              @change="(checked: string | number | boolean) => handleItemCheck(item, checked)"
            />
            <el-image :src="item.coverUrl" fit="cover" class="cart__cover" @click="router.push(`/item/${item.itemId}`)" />
            <div class="cart__info">
              <p class="cart__title" @click="router.push(`/item/${item.itemId}`)">{{ item.title }}</p>
              <p class="text-muted">单价 ¥{{ formatPrice(item.price) }}</p>
            </div>
            <el-input-number
              :model-value="item.quantity"
              :min="1"
              :max="99"
              size="small"
              @change="(value: number | undefined) => handleQuantityChange(item, value)"
            />
            <span class="cart__subtotal">¥{{ formatPrice(item.price * item.quantity) }}</span>
            <el-button text type="danger" :icon="Delete" @click="handleRemove(item)">移除</el-button>
          </div>
        </el-card>
      </template>

      <el-empty v-else-if="!loading" class="empty-block" description="购物车还是空的，去挑几件好物吧">
        <el-button type="primary" @click="router.push('/')">去逛逛</el-button>
      </el-empty>
    </div>

    <div v-if="groups.length" class="cart__bar">
      <el-checkbox
        :model-value="allChecked"
        :indeterminate="indeterminate"
        @change="(checked: string | number | boolean) => handleAllCheck(checked)"
      >
        全选
      </el-checkbox>
      <span class="cart__bar-count">已选 {{ selectedItems.length }} 件</span>
      <span class="cart__bar-total">
        合计：<strong>¥{{ formatPrice(totalPrice) }}</strong>
      </span>
      <el-button type="primary" size="large" @click="openCheckout">一键合并面交</el-button>
    </div>

    <el-dialog v-model="checkoutVisible" title="合并面交" width="520px">
      <el-form label-width="90px">
        <el-form-item label="面交点" required>
          <el-select v-model="checkoutForm.pointId" placeholder="请选择面交点" style="width: 100%">
            <el-option
              v-for="point in tradePoints"
              :key="point.id"
              :label="`${point.name}（${pointTypeLabel(point.type)}）`"
              :value="point.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="面交时间" required>
          <el-date-picker
            v-model="checkoutForm.timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="留言">
          <el-input v-model="checkoutForm.remark" type="textarea" :rows="2" maxlength="100" show-word-limit placeholder="例如：我在图书馆三楼，可以提前十分钟到" />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="合并面交会把不同卖家的商品拆成多张面交单，但共用同一个面交点与时间窗。"
      />
      <template #footer>
        <el-button @click="checkoutVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCheckout">提交面交单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resultVisible" title="面交单已提交" width="520px">
      <p class="cart__result-tip">
        批次号：<strong>{{ checkoutResult?.batchNo }}</strong>，共生成
        {{ checkoutResult?.orders?.length ?? 0 }} 张面交单，等待卖家确认。
      </p>
      <el-table :data="checkoutResult?.orders ?? []" size="small" border>
        <el-table-column prop="orderNo" label="面交单号" min-width="160" />
        <el-table-column prop="pointName" label="面交点" min-width="120" />
      </el-table>
      <template #footer>
        <el-button @click="resultVisible = false">继续逛逛</el-button>
        <el-button type="primary" @click="goOrders">查看我的面交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.cart {
  padding-bottom: 90px;

  &__body {
    min-height: 200px;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 0;
    border-bottom: 1px dashed #ebeef5;

    &:last-child {
      border-bottom: none;
    }
  }

  &__cover {
    width: 72px;
    height: 72px;
    border-radius: 8px;
    cursor: pointer;
  }

  &__info {
    flex: 1;
    min-width: 0;
  }

  &__title {
    margin: 0 0 6px;
    overflow: hidden;
    font-size: 15px;
    text-overflow: ellipsis;
    white-space: nowrap;
    cursor: pointer;

    &:hover {
      color: var(--ershou-primary);
    }
  }

  &__subtotal {
    width: 96px;
    color: #f56c6c;
    font-weight: 600;
    text-align: right;
  }

  &__bar {
    position: fixed;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 90;
    display: flex;
    align-items: center;
    gap: 20px;
    max-width: 1200px;
    margin: 0 auto 16px;
    padding: 12px 20px;
    border-radius: 10px;
    background: #fff;
    box-shadow: 0 -2px 12px rgb(0 0 0 / 8%);
  }

  &__bar-count {
    color: var(--ershou-text-light);
  }

  &__bar-total {
    margin-left: auto;

    strong {
      color: #f56c6c;
      font-size: 20px;
    }
  }

  &__result-tip {
    margin: 0 0 12px;
    line-height: 22px;
  }
}
</style>
