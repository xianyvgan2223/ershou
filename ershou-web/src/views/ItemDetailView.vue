<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ChatDotRound,
  Location,
  ShoppingCart,
  Star,
  StarFilled,
} from '@element-plus/icons-vue'
import { addFavorite, fetchItemDetail, removeFavorite } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { addToCart } from '@/api/cart'
import { openChatSession } from '@/api/chat'
import MeetupBookingDialog from '@/components/MeetupBookingDialog.vue'
import { useAuthGuard } from '@/composables/useAuthGuard'
import { useUserStore } from '@/stores/user'
import { conditionLabel, formatDateTime, formatPrice, nameInitial } from '@/utils/format'
import { showTip } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { ensureLogin } = useAuthGuard()

const itemId = computed(() => Number(route.params.id))
const item = ref<ItemVO | null>(null)
const loading = ref(false)
const favorited = ref(false)
const favLoading = ref(false)
const cartLoading = ref(false)
const chatLoading = ref(false)
const activeImage = ref(0)
const bookingVisible = ref(false)

const images = computed<string[]>(() => {
  const current = item.value
  if (!current) return []
  const list = (current.images ?? []).filter((url) => Boolean(url))
  if (list.length) return list
  return current.coverUrl ? [current.coverUrl] : []
})

const sellerName = computed(() => item.value?.seller?.nickname || '同学')
const pointName = computed(
  () => item.value?.preferPoint?.name || item.value?.preferPointName || '可协商',
)
const pointGuide = computed(() => item.value?.preferPoint?.guideText || item.value?.preferPointGuide || '')
const safeTags = computed(() => item.value?.preferPoint?.safeTags ?? [])
const onSale = computed(() => item.value?.status === 2 || item.value?.status === 3)
const creditScore = computed(() => item.value?.seller?.creditScore ?? 100)

async function loadItem(): Promise<void> {
  if (!Number.isFinite(itemId.value)) return
  loading.value = true
  try {
    const detail = await fetchItemDetail(itemId.value)
    item.value = detail
    // 刷新 / 直接进详情页时收藏态取自服务端，避免误判为未收藏
    favorited.value = Boolean(detail.favorited)
  } catch {
    item.value = null
  } finally {
    loading.value = false
  }
}

async function toggleFavorite(): Promise<void> {
  const current = item.value
  if (!current || !ensureLogin('登录后才能收藏商品')) return
  favLoading.value = true
  try {
    if (favorited.value) {
      await removeFavorite(current.id)
      favorited.value = false
      current.favCount = Math.max(0, (current.favCount ?? 0) - 1)
      showTip('已取消收藏')
    } else {
      await addFavorite(current.id)
      favorited.value = true
      current.favCount = (current.favCount ?? 0) + 1
      showTip('已加入收藏')
    }
  } catch {
    // 响应拦截器已提示
  } finally {
    favLoading.value = false
  }
}

async function handleAddCart(): Promise<void> {
  const current = item.value
  if (!current || !ensureLogin('登录后才能加入购物车')) return
  cartLoading.value = true
  try {
    await addToCart({ itemId: current.id, quantity: 1 })
    showTip('已加入购物车，可继续挑几件一起面交')
  } catch {
    // 响应拦截器已提示
  } finally {
    cartLoading.value = false
  }
}

/** 可面交：在售 + 不是自己发布的 */
const canBook = computed(() => item.value?.status === 2 && item.value?.seller?.id !== userStore.profile?.id)

/**
 * 「约面交」直接开预约弹窗（后端 POST /meetup-orders）。
 * 之前这里只是加购物车再跳转，用户还得自己勾选、再找结算入口，等于约不成。
 */
function openBooking(): void {
  const current = item.value
  if (!current || !ensureLogin('登录后才能约面交')) return
  if (current.seller?.id === userStore.profile?.id) {
    showTip('这是你自己发布的商品', 'warning')
    return
  }
  if (current.status !== 2) {
    showTip(`商品${current.statusText || '当前不可面交'}，先在"我的收藏"里等它回来吧`, 'warning')
    return
  }
  bookingVisible.value = true
}

function handleBooked(): void {
  showTip('面交单已提交，等卖家确认')
  router.push('/orders')
}

/** 看卖家主页（公开页面，未登录也能看） */
function goSeller(): void {
  const sellerId = item.value?.seller?.id
  if (sellerId) router.push(`/user/${sellerId}`)
}

/** 私聊卖家：先建（或复用）会话，再带着 sessionId 跳聊天页 */
async function handleChat(): Promise<void> {
  if (!ensureLogin('登录后才能私聊卖家')) return
  if (!item.value) return
  chatLoading.value = true
  try {
    const session = await openChatSession(item.value.id)
    router.push({ path: '/chat', query: { session: session.id } })
  } catch {
    // 响应拦截器已提示（例如对自己的商品发起会话 → 70001 不能和自己私聊）
  } finally {
    chatLoading.value = false
  }
}

onMounted(loadItem)
</script>

<template>
  <div v-loading="loading" class="page detail">
    <button class="back" type="button" @click="router.back()">
      <el-icon><ArrowLeft /></el-icon>返回
    </button>

    <template v-if="item">
      <!-- 主信息 -->
      <section class="panel panel--flush main">
        <div class="gallery">
          <div class="gallery__stage">
            <el-image v-if="images.length" :src="images[activeImage]" fit="cover" class="gallery__img" />
            <div v-else class="gallery__empty">暂无图片</div>
            <span v-if="!onSale" class="gallery__mask">{{ item.statusText || '已下架' }}</span>
          </div>
          <div v-if="images.length > 1" class="gallery__thumbs">
            <button
              v-for="(url, index) in images"
              :key="index"
              class="thumb"
              :class="{ 'is-active': index === activeImage }"
              type="button"
              @click="activeImage = index"
            >
              <img :src="url" alt="" />
            </button>
          </div>
        </div>

        <div class="info">
          <h1 class="info__title">{{ item.title }}</h1>

          <div class="price-box">
            <span class="price price--xl"><span class="price__symbol">¥</span>{{ formatPrice(item.price) }}</span>
            <span v-if="item.originalPrice" class="price-origin">原价 ¥{{ formatPrice(item.originalPrice) }}</span>
            <span class="price-box__tags">
              <span v-if="item.negotiable" class="chip chip--accent">可议价</span>
              <span class="chip chip--brand">{{ conditionLabel(item.conditionLevel) }}</span>
            </span>
          </div>

          <ul class="meta">
            <li><span>分类</span>{{ item.categoryName || '未分类' }}</li>
            <li><span>发布</span>{{ formatDateTime(item.publishedAt) }}</li>
            <li><span>浏览</span>{{ item.viewCount ?? 0 }}</li>
            <li><span>收藏</span>{{ item.favCount ?? 0 }}</li>
          </ul>

          <div class="point">
            <span class="point__icon"><el-icon><Location /></el-icon></span>
            <div class="point__body">
              <p class="point__name">推荐面交点 · {{ pointName }}</p>
              <p v-if="pointGuide" class="point__guide">{{ pointGuide }}</p>
              <div v-if="safeTags.length" class="point__tags">
                <span v-for="tag in safeTags" :key="tag" class="chip chip--brand">{{ tag }}</span>
              </div>
            </div>
            <router-link class="point__link" to="/map">在地图上看 →</router-link>
          </div>

          <div class="actions">
            <el-button class="actions__primary" size="large" :disabled="!canBook" @click="openBooking">
              约面交
            </el-button>
            <el-button size="large" :icon="ShoppingCart" :loading="cartLoading" @click="handleAddCart">
              加入购物车
            </el-button>
            <el-button size="large" :icon="ChatDotRound" :loading="chatLoading" @click="handleChat">私聊卖家</el-button>
            <button class="fav-btn" :class="{ 'is-on': favorited }" type="button" :disabled="favLoading" @click="toggleFavorite">
              <el-icon><StarFilled v-if="favorited" /><Star v-else /></el-icon>
              <span>{{ favorited ? '已收藏' : '收藏' }}</span>
            </button>
          </div>
        </div>
      </section>

      <!-- 描述 + 卖家 -->
      <div class="lower">
        <section class="panel">
          <h2 class="panel-title">商品描述</h2>
          <p class="desc">{{ item.description || '卖家很懒，没有填写详细描述。可以点“私聊卖家”问问细节。' }}</p>
        </section>

        <aside class="side">
          <section class="panel">
            <h2 class="panel-title">卖家</h2>
            <!-- 卖家卡片可点：进卖家主页看信用、在售与拉黑入口 -->
            <div class="seller" :class="{ 'is-link': item.seller?.id }" @click="goSeller">
              <el-avatar :size="52" :src="item.seller?.avatar || undefined">
                {{ nameInitial(sellerName) }}
              </el-avatar>
              <div class="seller__main">
                <p class="seller__name">
                  {{ sellerName }}
                  <span v-if="item.seller?.identityFlag" class="seller__badge">商家</span>
                </p>
                <p class="seller__sub">{{ item.seller?.shopName || '个人卖家 · 同校学生' }}</p>
              </div>
              <span v-if="item.seller?.id" class="seller__more">主页 ›</span>
            </div>

            <div class="credit">
              <div class="credit__head">
                <span>信用分</span>
                <b>{{ creditScore }}</b>
              </div>
              <div class="credit__bar">
                <span :style="{ width: Math.min(creditScore, 100) + '%' }" />
              </div>
            </div>
          </section>

          <section class="tip">
            <p class="tip__title">安全提示</p>
            <p class="tip__text">
              请在校内公共区域面交，当面验货后再确认成交；<b>切勿提前转账</b>，平台不经手交易资金。
            </p>
          </section>
        </aside>
      </div>
    </template>

    <div v-else-if="!loading" class="empty-state">
      <p class="empty-state__title">商品不存在或已下架</p>
      <p class="empty-state__desc">它可能已经被别的同学带走啦</p>
      <el-button class="empty-state__btn" round @click="router.push('/')">回到首页</el-button>
    </div>

    <MeetupBookingDialog
      v-if="item"
      v-model="bookingVisible"
      :item-id="item.id"
      :prefer-point-id="item.preferPointId"
      @success="handleBooked"
    />
  </div>
</template>

<style scoped lang="scss">
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 12px;
  padding: 6px 12px 6px 8px;
  border: none;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  transition: all 0.18s ease;

  &:hover {
    background: #fff;
    color: var(--brand-700);
    box-shadow: var(--shadow-xs);
  }
}

.main {
  display: grid;
  gap: 0;
  grid-template-columns: minmax(0, 480px) minmax(0, 1fr);
}

// ---------- 图集 ----------
.gallery {
  padding: 20px;
  border-right: 1px solid var(--line);
  background: var(--surface-2);

  &__stage {
    position: relative;
    overflow: hidden;
    border-radius: var(--r-md);
    aspect-ratio: 1 / 1;
    background: #fff;
  }

  &__img {
    width: 100%;
    height: 100%;
  }

  &__empty {
    display: grid;
    height: 100%;
    place-items: center;
    color: var(--text-3);
    font-size: 13px;
  }

  &__mask {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgb(15 23 42 / 45%);
    color: #fff;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 1px;
  }

  &__thumbs {
    display: flex;
    gap: 10px;
    margin-top: 12px;
  }
}

.thumb {
  overflow: hidden;
  width: 62px;
  height: 62px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: all 0.18s ease;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &:hover {
    border-color: var(--brand-300);
  }

  &.is-active {
    border-color: var(--brand-500);
    box-shadow: 0 0 0 3px var(--brand-50);
  }
}

// ---------- 信息区 ----------
.info {
  padding: 24px 26px;

  &__title {
    font-size: 22px;
    font-weight: 700;
    letter-spacing: -0.3px;
    line-height: 1.45;
  }
}

.price-box {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 14px;
  padding: 16px 18px;
  border-radius: var(--r-md);
  background: #fff7f3;

  &__tags {
    display: flex;
    gap: 6px;
    margin-left: auto;
  }

  .price--xl {
    font-size: 32px;
  }
}

.meta {
  display: flex;
  gap: 22px;
  margin: 16px 0;
  padding: 0;
  list-style: none;
  color: var(--text-2);
  font-size: 13px;
  flex-wrap: wrap;

  li {
    display: flex;
    gap: 6px;
  }

  span {
    color: var(--text-3);
  }
}

.point {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  background: var(--brand-50);

  &__icon {
    display: grid;
    width: 34px;
    height: 34px;
    flex-shrink: 0;
    place-items: center;
    border-radius: 11px;
    background: #fff;
    color: var(--brand-600);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    color: var(--brand-700);
    font-size: 14px;
    font-weight: 600;
  }

  &__guide {
    margin-top: 2px;
    color: var(--text-2);
    font-size: 12.5px;
  }

  &__tags {
    display: flex;
    gap: 6px;
    margin-top: 8px;
    flex-wrap: wrap;
  }

  &__link {
    flex-shrink: 0;
    color: var(--brand-700);
    font-size: 12.5px;
    font-weight: 600;

    &:hover {
      text-decoration: underline;
    }
  }
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 20px;
  flex-wrap: wrap;

  &__primary {
    border: none;
    padding: 0 26px;
    background: var(--brand-500);
    color: #fff;
    font-weight: 600;

    &:hover,
    &:focus {
      background: var(--brand-600);
      color: #fff;
    }
  }
}

.fav-btn {
  display: inline-flex;
  height: 42px;
  align-items: center;
  gap: 6px;
  padding: 0 16px;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-pill);
  background: #fff;
  color: var(--text-2);
  cursor: pointer;
  font-family: inherit;
  font-size: 14px;
  transition: all 0.18s ease;

  &:hover {
    border-color: var(--accent-500);
    color: var(--accent-500);
  }

  &.is-on {
    border-color: var(--accent-500);
    background: #fff3ed;
    color: var(--accent-600);
  }
}

// ---------- 下半部分 ----------
.lower {
  display: grid;
  gap: 18px;
  margin-top: 18px;
  grid-template-columns: minmax(0, 1fr) 320px;
}

.desc {
  color: var(--text-2);
  font-size: 14px;
  line-height: 1.9;
  white-space: pre-wrap;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.seller {
  display: flex;
  align-items: center;
  gap: 12px;

  // 可点击进主页时给一点反馈（其余场景保持静态卡片）
  &.is-link {
    cursor: pointer;

    &:hover .seller__more {
      color: var(--brand-600);
    }
  }

  &__more {
    margin-left: auto;
    flex-shrink: 0;
    color: var(--text-3);
    font-size: 12px;
  }

  &__name {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 15px;
    font-weight: 600;
  }

  &__badge {
    padding: 1px 6px;
    border-radius: 5px;
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 11px;
    font-weight: 600;
  }

  &__sub {
    color: var(--text-3);
    font-size: 12.5px;
  }
}

.credit {
  margin-top: 16px;

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 6px;
    color: var(--text-3);
    font-size: 12.5px;

    b {
      color: var(--brand-600);
      font-size: 16px;
      font-weight: 700;
    }
  }

  &__bar {
    overflow: hidden;
    height: 7px;
    border-radius: 999px;
    background: var(--line);

    span {
      display: block;
      height: 100%;
      border-radius: 999px;
      background: var(--brand-500);
    }
  }
}

.tip {
  padding: 16px 18px;
  border: 1px solid #fde8dc;
  border-radius: var(--r-lg);
  background: #fff8f4;

  &__title {
    color: var(--accent-600);
    font-size: 13.5px;
    font-weight: 700;
  }

  &__text {
    margin-top: 6px;
    color: var(--text-2);
    font-size: 12.5px;
    line-height: 1.8;

    b {
      color: var(--accent-600);
    }
  }
}

.empty-state {
  padding: 80px 20px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--r-lg);
  background: #fff;
  text-align: center;

  &__icon {
    font-size: 40px;
  }

  &__title {
    margin-top: 12px;
    font-size: 17px;
    font-weight: 600;
  }

  &__desc {
    margin: 4px 0 18px;
    color: var(--text-3);
    font-size: 13px;
  }
}

@media (max-width: 980px) {
  .main {
    grid-template-columns: 1fr;
  }

  .gallery {
    border-right: none;
    border-bottom: 1px solid var(--line);
  }

  .lower {
    grid-template-columns: 1fr;
  }
}
</style>
