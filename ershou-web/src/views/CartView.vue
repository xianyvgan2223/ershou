<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import { fetchCart, removeCartItem, updateCartQuantity } from '@/api/cart'
import type { CartItem, CartSellerGroup, CheckoutResult } from '@/api/cart'
import MeetupBookingDialog from '@/components/MeetupBookingDialog.vue'
import { formatPrice } from '@/utils/format'

const router = useRouter()

const groups = ref<CartSellerGroup[]>([])
const loading = ref(false)
const selectedIds = ref<number[]>([])
const checkoutVisible = ref(false)
const resultVisible = ref(false)
const checkoutResult = ref<CheckoutResult | null>(null)
/** 本次结算里因商品不可面交而被跳过的条目（后端返回 skipped） */
const skippedItems = computed(() => checkoutResult.value?.skipped ?? [])
/** 已经出现过的条目：新加入的自动勾选，老条目的勾选状态尊重用户操作 */
const knownIds = new Set<number>()

/** 只有「在售」能结算；已锁定 / 已售出 / 已下架的不能勾选 */
function sellable(item: CartItem): boolean {
  return item.status === 2
}

const allItems = computed<CartItem[]>(() => groups.value.flatMap((group) => group.items))
const sellableItems = computed(() => allItems.value.filter(sellable))
const selectedItems = computed(() => allItems.value.filter((item) => selectedIds.value.includes(item.cartId)))
const totalPrice = computed(() =>
  selectedItems.value.reduce((sum, item) => sum + item.price * item.quantity, 0),
)
const allChecked = computed(
  () => sellableItems.value.length > 0 && selectedItems.value.length === sellableItems.value.length,
)
const indeterminate = computed(
  () => selectedIds.value.length > 0 && selectedIds.value.length < sellableItems.value.length,
)

/**
 * 同步勾选状态。
 *
 * 之前购物车加载后 selectedIds 一直是空数组 → 点「一键合并面交」只弹一句
 * "请先勾选要一起面交的商品"，弹窗根本打不开，面交等于走不通。
 * 现在：从商品页「约面交」进来的、以及新加入购物车的商品默认就勾上。
 */
function syncSelection(): void {
  const sellableIds = sellableItems.value.map((item) => item.cartId)
  const kept = selectedIds.value.filter((id) => sellableIds.includes(id))
  const fresh = sellableIds.filter((id) => !knownIds.has(id))
  selectedIds.value = Array.from(new Set([...kept, ...fresh]))
  allItems.value.forEach((item) => knownIds.add(item.cartId))
}

async function loadCart(): Promise<void> {
  loading.value = true
  try {
    groups.value = await fetchCart()
    syncSelection()
  } catch {
    groups.value = []
  } finally {
    loading.value = false
  }
}

function toggleAll(checked: boolean): void {
  selectedIds.value = checked ? sellableItems.value.map((item) => item.cartId) : []
}

function sellerChecked(group: CartSellerGroup): boolean {
  const ids = group.items.filter(sellable).map((item) => item.cartId)
  return ids.length > 0 && ids.every((id) => selectedIds.value.includes(id))
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
  const ids = group.items.filter(sellable).map((item) => item.cartId)
  if (checked) {
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...ids]))
  } else {
    selectedIds.value = selectedIds.value.filter((id) => !ids.includes(id))
  }
}

/** 点整行也能勾选：只有一个小方框太难发现了 */
function toggleItem(item: CartItem): void {
  if (!sellable(item)) {
    ElMessage.info(`「${item.title}」${item.statusText || '当前不可面交'}，无法结算`)
    return
  }
  handleItemCheck(item, !selectedIds.value.includes(item.cartId))
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
  // 一件都没勾时先把可面交的都勾上：用户点了「合并面交」就是明确意图，
  // 不该只回一句"请先勾选"把他挡在门外
  if (!selectedIds.value.length) {
    selectedIds.value = sellableItems.value.map((item) => item.cartId)
  }
  if (!selectedIds.value.length) {
    ElMessage.warning('购物车里没有可面交的商品（已锁定 / 已售出 / 已下架）')
    return
  }
  checkoutVisible.value = true
}

function handleBooked(result: CheckoutResult): void {
  checkoutResult.value = result
  resultVisible.value = true
  selectedIds.value = []
  allItems.value.forEach((item) => knownIds.delete(item.cartId))
  loadCart()
}

function goOrders(): void {
  resultVisible.value = false
  router.push('/orders')
}

onMounted(loadCart)
</script>

<template>
  <div class="page cart">
    <div class="page-head">
      <div>
        <h2 class="page-title">购物车</h2>
        <p class="page-subtitle">勾选同一面交点、同一时间窗的商品，可一键合并成面交单</p>
      </div>
      <!-- 只是取消勾选，不是删商品：原来用 Delete 图标 + danger 样式，看着像"清空购物车"，很容易误点 -->
      <el-button text @click="toggleAll(false)">取消全选</el-button>
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

          <div
            v-for="item in group.items"
            :key="item.cartId"
            class="cart__item"
            :class="{ 'is-disabled': !sellable(item) }"
            @click="toggleItem(item)"
          >
            <el-checkbox
              :model-value="selectedIds.includes(item.cartId)"
              :disabled="!sellable(item)"
              @click.stop
              @change="(checked: string | number | boolean) => handleItemCheck(item, checked)"
            />
            <el-image
              :src="item.coverUrl"
              fit="cover"
              class="cart__cover"
              @click.stop="router.push(`/item/${item.itemId}`)"
            />
            <div class="cart__info">
              <p class="cart__title" @click.stop="router.push(`/item/${item.itemId}`)">{{ item.title }}</p>
              <p class="text-muted">
                单价 ¥{{ formatPrice(item.price) }}
                <el-tag v-if="!sellable(item)" size="small" type="info" effect="plain" class="cart__status">
                  {{ item.statusText || '不可面交' }}
                </el-tag>
              </p>
            </div>
            <el-input-number
              :model-value="item.quantity"
              :min="1"
              :max="99"
              size="small"
              @click.stop
              @change="(value: number | undefined) => handleQuantityChange(item, value)"
            />
            <span class="cart__subtotal">¥{{ formatPrice(item.price * item.quantity) }}</span>
            <el-button text type="danger" :icon="Delete" @click.stop="handleRemove(item)">移除</el-button>
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
      <span class="cart__bar-count">已选 {{ selectedItems.length }} / {{ sellableItems.length }} 件可面交</span>
      <span class="cart__bar-total">
        合计：<strong>¥{{ formatPrice(totalPrice) }}</strong>
      </span>
      <el-button type="primary" size="large" @click="openCheckout">一键合并面交</el-button>
    </div>

    <MeetupBookingDialog
      v-model="checkoutVisible"
      :cart-ids="selectedIds"
      :item-count="selectedItems.length"
      @success="handleBooked"
    />

    <el-dialog v-model="resultVisible" title="面交单已提交" width="520px">
      <p class="cart__result-tip">
        批次号：<strong>{{ checkoutResult?.batchNo }}</strong>，共生成
        {{ checkoutResult?.orders?.length ?? 0 }} 张面交单，等待卖家确认。
      </p>
      <!-- 被跳过的商品要说清楚：以前是一条坏条目让整批结算失败，
           用户只看到一句"商品已被其他同学约走"，不知道是哪件、也不知道别的能不能买 -->
      <el-alert
        v-if="skippedItems.length"
        type="warning"
        show-icon
        :closable="false"
        class="cart__skipped"
        title="以下商品已不可面交，本次没有一起提交"
      >
        <ul class="cart__skipped-list">
          <li v-for="row in skippedItems" :key="row.cartId">
            {{ row.title }} —— {{ row.reason }}
          </li>
        </ul>
      </el-alert>
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
    cursor: pointer;

    &:last-child {
      border-bottom: none;
    }

    &.is-disabled {
      opacity: 0.66;
      cursor: not-allowed;
    }
  }

  &__status {
    margin-left: 6px;
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

  &__skipped {
    margin-bottom: 12px;

    ul {
      margin: 4px 0 0;
      padding-left: 18px;
    }

    li {
      line-height: 22px;
    }
  }
}
</style>
