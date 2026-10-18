<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Goods, Lightning, Location, Medal, Tickets, Timer } from '@element-plus/icons-vue'
import ItemCard from '@/components/ItemCard.vue'
import {
  claimPlatformCoupon,
  couponFaceValue,
  couponRuleText,
  fetchPlatformCoupons,
  fetchPlatformFlashSale,
  fetchPlatformItems,
  fetchPlatformOverview,
  fetchPlatformPoints,
  grabFlashSaleItem,
  stockPercent,
  toCardItem,
} from '@/api/platform'
import type {
  CouponVO,
  FlashSaleItemVO,
  FlashSaleVO,
  PlatformItemVO,
  PlatformOverviewVO,
  PlatformPointVO,
} from '@/api/platform'
import type { ItemVO } from '@/api/item'
import { useUserStore } from '@/stores/user'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { formatPrice } from '@/utils/format'
import { showTip } from '@/utils/feedback'

/**
 * 平台严选专区（设计第 5.2 节）。
 *
 * 页面分四块：hero 概览 → 官方代卖点 chips → 秒杀区 → 优惠券区 → 平台商品分页。
 * 所有写动作（领券 / 抢购）先过 `requireLogin()`：未登录跳 `/login?redirect=/platform`，
 * 不只是弹提示 —— 用户点完登录回来应该直接落回这个页面。
 */
const router = useRouter()
const userStore = useUserStore()

/** 当前校区：跟随登录用户；未登录时回落默认校区（与首页/地图页同一口径） */
const campusId = computed(() => userStore.profile?.campusId ?? DEFAULT_CAMPUS_ID)

/* ------------------------------------------------------------------ *
 * 状态
 * ------------------------------------------------------------------ */
const overview = ref<PlatformOverviewVO | null>(null)
const points = ref<PlatformPointVO[]>([])
const activePointId = ref<number | null>(null)

const flashSale = ref<FlashSaleVO | null>(null)
const flashRemain = ref(0)
const flashLoading = ref(false)

const coupons = ref<CouponVO[]>([])
const couponsLoading = ref(false)

const items = ref<PlatformItemVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(12)
const itemsLoading = ref(false)

/** 正在领取 / 正在抢购的 id（用于按钮 loading + 防连点） */
const claimingId = ref<number | null>(null)
const grabbingId = ref<number | null>(null)

let countdownTimer: number | undefined

/* ------------------------------------------------------------------ *
 * 派生数据
 * ------------------------------------------------------------------ */
const heroStats = computed(() => [
  { label: '在售平台商品', value: overview.value?.platformItemCount ?? items.value.length },
  { label: '官方代卖点', value: overview.value?.pointCount ?? points.value.length },
  {
    label: '可领优惠券',
    value: overview.value?.couponCount ?? coupons.value.filter((c) => !c.claimed && c.claimable).length,
  },
])

const activePoint = computed(
  () => points.value.find((point) => point.id === activePointId.value) ?? null,
)

/** 平台商品 → ItemCard 需要的 ItemVO（平台商品字段更少，适配一次） */
const cardItems = computed<ItemVO[]>(() =>
  items.value.map((item) => toCardItem(item, campusId.value)),
)

const flashStatusText = computed(() => {
  const status = flashSale.value?.status
  if (status === 2) return '进行中'
  if (status === 1) return '即将开始'
  if (status === 3) return '已结束'
  return '暂无场次'
})

/** 秒杀券/商品失败子码文案（设计 3.3 的 code 定义） */
const ACTIVITY_CODE_TEXT: Record<number, string> = {
  1: '操作成功',
  0: '已经抢完了',
  [-1]: '活动还没准备好，稍后再试',
  [-2]: '超出限购数量了',
  [-3]: '不在活动时间内',
  [-4]: '手速太快了，5 秒后再试',
}

/* ------------------------------------------------------------------ *
 * 加载
 * ------------------------------------------------------------------ */
async function loadOverview(): Promise<void> {
  try {
    overview.value = await fetchPlatformOverview(campusId.value)
  } catch {
    overview.value = null
  }
}

async function loadPoints(): Promise<void> {
  try {
    points.value = await fetchPlatformPoints(campusId.value)
  } catch {
    points.value = []
  }
}

async function loadCoupons(): Promise<void> {
  couponsLoading.value = true
  try {
    coupons.value = await fetchPlatformCoupons(campusId.value)
  } catch {
    coupons.value = []
  } finally {
    couponsLoading.value = false
  }
}

async function loadFlashSale(): Promise<void> {
  flashLoading.value = true
  try {
    const sale = await fetchPlatformFlashSale(campusId.value)
    flashSale.value = sale
    if (sale) startCountdown(sale.remainSeconds)
    else {
      stopCountdown()
      flashRemain.value = 0
    }
  } catch {
    flashSale.value = null
    stopCountdown()
    flashRemain.value = 0
  } finally {
    flashLoading.value = false
  }
}

async function loadItems(): Promise<void> {
  itemsLoading.value = true
  try {
    const page = await fetchPlatformItems({
      campusId: campusId.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      pointId: activePointId.value ?? undefined,
    })
    items.value = page.records ?? []
    total.value = page.total ?? 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    itemsLoading.value = false
  }
}

async function refreshAll(): Promise<void> {
  pageNum.value = 1
  await Promise.all([loadOverview(), loadPoints(), loadCoupons(), loadFlashSale()])
  await loadItems()
}

/* ------------------------------------------------------------------ *
 * 倒计时（服务端给 remainSeconds，本地每秒递减；卸载时清掉定时器）
 * ------------------------------------------------------------------ */
function stopCountdown(): void {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer)
    countdownTimer = undefined
  }
}

function startCountdown(seconds: number): void {
  stopCountdown()
  flashRemain.value = Math.max(0, Math.floor(seconds || 0))
  if (flashRemain.value <= 0) return
  countdownTimer = window.setInterval(() => {
    if (flashRemain.value > 0) flashRemain.value -= 1
    if (flashRemain.value <= 0) stopCountdown()
  }, 1000)
}

/** 秒数 → `08:01:02`（超过一天补「N天」） */
function formatCountdown(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const days = Math.floor(seconds / 86400)
  const pad = (value: number): string => String(value).padStart(2, '0')
  const clock = `${pad(Math.floor((seconds % 86400) / 3600))}:${pad(Math.floor((seconds % 3600) / 60))}:${pad(seconds % 60)}`
  return days > 0 ? `${days}天 ${clock}` : clock
}

/* ------------------------------------------------------------------ *
 * 交互
 * ------------------------------------------------------------------ */
function requireLogin(): boolean {
  if (userStore.isLogin) return true
  showTip('登录后才能领取优惠券 / 抢购', 'warning')
  void router.push({ path: '/login', query: { redirect: '/platform' } })
  return false
}

function pickPoint(id: number | null): void {
  if (activePointId.value === id) return
  activePointId.value = id
  pageNum.value = 1
  void loadItems()
}

function handlePageChange(page: number): void {
  pageNum.value = page
  void loadItems()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function handleClaim(coupon: CouponVO): Promise<void> {
  if (!requireLogin()) return
  if (coupon.claimed || !coupon.claimable || claimingId.value !== null) return
  claimingId.value = coupon.id
  try {
    const result = await claimPlatformCoupon(coupon.id)
    if (result.success) {
      showTip(`已领取「${coupon.name}」`)
      await Promise.all([loadCoupons(), loadOverview()])
    } else {
      showTip(result.message || ACTIVITY_CODE_TEXT[result.code] || '领取失败', 'warning')
      // 领完 / 超限时刷新一次，让按钮状态跟上真实库存
      if (result.code === 0 || result.code === -2) await loadCoupons()
    }
  } catch {
    // 响应拦截器已提示
  } finally {
    claimingId.value = null
  }
}

async function handleGrab(flashItem: FlashSaleItemVO): Promise<void> {
  if (!requireLogin()) return
  if (flashItem.grabbed || flashItem.leftStock <= 0 || grabbingId.value !== null) return
  grabbingId.value = flashItem.itemId
  try {
    const result = await grabFlashSaleItem(flashItem.itemId)
    if (result.success) {
      showTip(result.orderNo ? `抢购成功，单号 ${result.orderNo}` : '抢购成功')
      await Promise.all([loadFlashSale(), loadItems(), loadOverview()])
    } else {
      showTip(result.message || ACTIVITY_CODE_TEXT[result.code] || '抢购失败', 'warning')
      await loadFlashSale()
    }
  } catch {
    // 响应拦截器已提示
  } finally {
    grabbingId.value = null
  }
}

function scrollToSection(id: string): void {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/* ------------------------------------------------------------------ *
 * 展示辅助
 * ------------------------------------------------------------------ */
function couponButtonText(coupon: CouponVO): string {
  if (coupon.claimed) return '已领取'
  if (claimingId.value === coupon.id) return '领取中…'
  if (!coupon.claimable) {
    const now = Date.now()
    const start = new Date(coupon.startAt.replace(' ', 'T')).getTime()
    const end = new Date(coupon.endAt.replace(' ', 'T')).getTime()
    if (Number.isFinite(start) && now < start) return '未开始'
    if (Number.isFinite(end) && now > end) return '已结束'
    return coupon.leftStock > 0 ? '暂不可领' : '已领完'
  }
  return '立即领取'
}

function couponDisabled(coupon: CouponVO): boolean {
  return coupon.claimed || !coupon.claimable || claimingId.value !== null
}

/** 日期只取 `yyyy-MM-dd`，券的有效期展示够用了 */
function dateShort(value?: string | null): string {
  if (!value) return '--'
  return value.replace('T', ' ').slice(0, 10)
}

function flashButtonText(flashItem: FlashSaleItemVO): string {
  if (flashItem.grabbed) return '已抢到'
  if (grabbingId.value === flashItem.itemId) return '抢购中…'
  if (flashItem.leftStock <= 0) return '已抢光'
  return '立即抢购'
}

/* ------------------------------------------------------------------ *
 * 生命周期
 * ------------------------------------------------------------------ */
onMounted(async () => {
  // 直链进来时布局层的资料请求可能还没回来：这里补一次，确保 campusId 正确
  if (userStore.isLogin && !userStore.profile) {
    try {
      await userStore.fetchProfile()
    } catch {
      // 拦截器已提示；仍按默认校区渲染公开内容
    }
  }
  await refreshAll()
})

// 登录后（或资料里换了校区）重新按新校区加载
watch(campusId, () => {
  activePointId.value = null
  void refreshAll()
})

onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="platform">
    <!-- ============ hero ============ -->
    <section class="hero">
      <div class="hero__inner">
        <div class="hero__copy">
          <p class="hero__eyebrow">
            <el-icon><Medal /></el-icon>
            官方严选 · 平台自营
          </p>
          <h1 class="hero__title">校园二手 · 官方严选</h1>
          <p class="hero__desc">
            平台官方代卖：统一验货、统一自提点、售后有兜底；还有限时秒杀与优惠券
          </p>

          <!-- 平台管理员专属入口：进独立运营台（第 3 轮，设计 4.5） -->
          <div v-if="userStore.isAdmin" class="hero__admin">
            <el-button type="primary" round @click="router.push('/console/platform')">
              进入运营台<el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
            <span class="hero__admin-hint">平台管理员入口 · 代卖点 / 券 / 秒杀 / 订单 / 缓存</span>
          </div>

          <div class="hero__stats">
            <div v-for="stat in heroStats" :key="stat.label" class="stat">
              <b>{{ stat.value }}</b>
              <span>{{ stat.label }}</span>
            </div>
          </div>
        </div>

        <aside class="hero__panel">
          <p class="hero__panel-title">为什么在严选区买</p>
          <ul class="assurances">
            <li>
              <el-icon><Medal /></el-icon>
              <div><b>官方代卖</b><span>卖家是平台官方账号，不是个人卖家</span></div>
            </li>
            <li>
              <el-icon><Location /></el-icon>
              <div><b>固定自提点</b><span>到官方代卖点自提，当面验货更放心</span></div>
            </li>
            <li>
              <el-icon><Lightning /></el-icon>
              <div><b>限时秒杀</b><span>每天有限量特价，抢到即生成自提单</span></div>
            </li>
            <li>
              <el-icon><Tickets /></el-icon>
              <div><b>优惠券</b><span>先领券再下单，限量发放</span></div>
            </li>
          </ul>
          <div class="hero__panel-actions">
            <el-button round @click="scrollToSection('flash')">
              看秒杀<el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
            <el-button type="primary" round @click="scrollToSection('items')">逛严选好物</el-button>
          </div>
        </aside>
      </div>
    </section>

    <div class="page">
      <!-- ============ 代卖点 chips ============ -->
      <section id="points" class="panel points">
        <div class="panel-title">
          <el-icon><Location /></el-icon>
          官方代卖点
          <span class="text-muted points__hint">选一个代卖点，只看该点的好物</span>
        </div>
        <div class="points__chips">
          <button
            class="point-chip"
            :class="{ 'is-active': activePointId === null }"
            type="button"
            @click="pickPoint(null)"
          >
            全部代卖点
          </button>
          <button
            v-for="point in points"
            :key="point.id"
            class="point-chip"
            :class="{ 'is-active': activePointId === point.id }"
            type="button"
            @click="pickPoint(point.id)"
          >
            {{ point.name }}
          </button>
        </div>
        <p v-if="activePoint" class="points__detail">
          <span>{{ activePoint.address }}</span>
          <span v-if="activePoint.openHours">营业 {{ activePoint.openHours }}</span>
          <span v-if="activePoint.contactPhone">电话 {{ activePoint.contactPhone }}</span>
          <span v-if="activePoint.guideText">{{ activePoint.guideText }}</span>
        </p>
        <p v-else-if="!points.length" class="points__detail">当前校区还没有配置代卖点</p>
      </section>

      <!-- ============ 秒杀区 ============ -->
      <section id="flash" v-loading="flashLoading" class="panel flash">
        <div class="section-head">
          <div class="section-head__title">
            <el-icon class="section-head__icon section-head__icon--flash"><Lightning /></el-icon>
            <h2>限时秒杀</h2>
            <el-tag v-if="flashSale" size="small" :type="flashSale.status === 2 ? 'danger' : 'info'" round>
              {{ flashStatusText }}
            </el-tag>
          </div>
          <div v-if="flashSale" class="flash__countdown">
            <el-icon><Timer /></el-icon>
            <span class="flash__countdown-label">{{ flashSale.status === 2 ? '距结束' : '距开始' }}</span>
            <b class="flash__countdown-value">{{ formatCountdown(flashRemain) }}</b>
          </div>
          <span v-else class="text-muted">暂无进行中的场次</span>
        </div>

        <template v-if="flashSale && flashSale.title">
          <p class="flash__subtitle">{{ flashSale.title }}</p>
        </template>

        <div v-if="flashSale && flashSale.items.length" class="flash__grid">
          <article
            v-for="flashItem in flashSale.items"
            :key="flashItem.itemId"
            class="flash-card"
            @click="router.push(`/item/${flashItem.itemId}`)"
          >
            <div class="flash-card__cover">
              <el-image :src="flashItem.coverUrl" fit="cover" class="flash-card__img" lazy>
                <template #error>
                  <div class="flash-card__placeholder">暂无图片</div>
                </template>
              </el-image>
              <span class="flash-card__badge">秒杀</span>
            </div>
            <div class="flash-card__body">
              <h3 class="flash-card__title">{{ flashItem.title }}</h3>
              <div class="flash-card__price">
                <span class="price"><span class="price__symbol">¥</span>{{ formatPrice(flashItem.seckillPrice) }}</span>
                <span class="price-origin">¥{{ formatPrice(flashItem.originPrice) }}</span>
              </div>
              <div class="flash-card__bar">
                <div class="flash-card__bar-track">
                  <div
                    class="flash-card__bar-fill"
                    :style="{ width: stockPercent(flashItem.leftStock, flashItem.stock) + '%' }"
                  />
                </div>
                <span class="flash-card__bar-text">
                  剩余 {{ flashItem.leftStock }}/{{ flashItem.stock }}
                </span>
              </div>
              <button
                class="flash-card__btn"
                type="button"
                :disabled="flashItem.grabbed || flashItem.leftStock <= 0 || grabbingId !== null"
                @click.stop="handleGrab(flashItem)"
              >
                {{ flashButtonText(flashItem) }}
              </button>
            </div>
          </article>
        </div>
        <div v-else-if="!flashLoading" class="empty-block">
          <p class="empty-block__title">当前没有进行中的秒杀场次</p>
          <p class="empty-block__desc">先逛逛下面的严选好物，有场次时会第一时间出现在这里</p>
        </div>
      </section>

      <!-- ============ 优惠券区 ============ -->
      <section id="coupons" v-loading="couponsLoading" class="panel coupons">
        <div class="section-head">
          <div class="section-head__title">
            <el-icon class="section-head__icon"><Tickets /></el-icon>
            <h2>优惠券</h2>
            <span class="text-muted">先领券，再下单</span>
          </div>
          <span v-if="!userStore.isLogin" class="text-muted">登录后可领取</span>
        </div>

        <div v-if="coupons.length" class="coupons__grid">
          <article v-for="coupon in coupons" :key="coupon.id" class="coupon" :class="{ 'is-claimed': coupon.claimed }">
            <div class="coupon__face">
              <b>{{ couponFaceValue(coupon) }}</b>
              <span>{{ couponRuleText(coupon) }}</span>
            </div>
            <div class="coupon__body">
              <p class="coupon__name">{{ coupon.name }}</p>
              <p class="coupon__meta">
                有效期 {{ dateShort(coupon.startAt) }} 至 {{ dateShort(coupon.endAt) }}
              </p>
              <p class="coupon__meta">
                剩余 {{ coupon.leftStock }}/{{ coupon.totalStock }} · 每人限领 {{ coupon.perUserLimit }} 张
              </p>
            </div>
            <el-button
              class="coupon__btn"
              :type="coupon.claimed ? 'info' : 'primary'"
              :plain="coupon.claimed"
              round
              :disabled="couponDisabled(coupon)"
              @click="handleClaim(coupon)"
            >
              {{ couponButtonText(coupon) }}
            </el-button>
          </article>
        </div>
        <div v-else-if="!couponsLoading" class="empty-block">
          <p class="empty-block__title">当前没有可领的优惠券</p>
          <p class="empty-block__desc">活动上线后会在这里出现</p>
        </div>
      </section>

      <!-- ============ 平台商品 ============ -->
      <section id="items" class="items">
        <div class="section-head">
          <div class="section-head__title">
            <el-icon class="section-head__icon"><Goods /></el-icon>
            <h2>严选好物</h2>
            <span class="text-muted">共 {{ total }} 件</span>
          </div>
          <el-button
            v-if="activePointId !== null"
            text
            type="primary"
            @click="pickPoint(null)"
          >
            清除点位筛选
          </el-button>
        </div>

        <div v-if="itemsLoading && !cardItems.length" class="grid">
          <div v-for="n in 8" :key="n" class="skeleton-card">
            <div class="skeleton skeleton-card__cover" />
            <div class="skeleton-card__body">
              <div class="skeleton skeleton-card__line" />
              <div class="skeleton skeleton-card__line skeleton-card__line--short" />
            </div>
          </div>
        </div>

        <div v-else-if="cardItems.length" v-loading="itemsLoading" class="grid">
          <ItemCard
            v-for="(item, index) in cardItems"
            :key="item.id"
            :item="item"
            class="fade-up"
            :style="{ animationDelay: index * 24 + 'ms' }"
          />
        </div>

        <div v-else class="empty-block empty-block--panel">
          <p class="empty-block__title">这个筛选下还没有平台商品</p>
          <p class="empty-block__desc">换个代卖点，或等官方补货后再来看看</p>
          <el-button v-if="activePointId !== null" round @click="pickPoint(null)">查看全部代卖点</el-button>
        </div>

        <div v-if="total > pageSize" class="pager">
          <el-pagination
            background
            layout="prev, pager, next, jumper"
            :total="total"
            :page-size="pageSize"
            :current-page="pageNum"
            @current-change="handlePageChange"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
// 官方严选的「金」与站点的品牌绿/暖橙并存：金色只用在代卖相关标识上
$gold: #b8860b;
$gold-soft: #fbf3dc;

// ---------- hero ----------
.hero {
  position: relative;
  overflow: hidden;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(115deg, #fffdf6 0%, #fff 46%, var(--brand-50) 100%);

  &__inner {
    display: flex;
    align-items: center;
    gap: 40px;
    max-width: 1240px;
    margin: 0 auto;
    padding: 34px 20px 28px;
  }

  &__copy {
    flex: 1 1 560px;
    min-width: 0;
  }

  &__eyebrow {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 12px;
    border: 1px solid #f3e3b3;
    border-radius: var(--r-pill);
    background: $gold-soft;
    color: $gold;
    font-size: 12px;
    font-weight: 600;
  }

  &__title {
    margin: 12px 0 6px;
    font-size: 30px;
    font-weight: 800;
    letter-spacing: -0.4px;
    line-height: 1.28;
  }

  &__desc {
    max-width: 620px;
    color: var(--text-2);
    font-size: 15px;
  }

  // 平台管理员入口（仅 isAdmin 显示）
  &__admin {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 14px;
  }

  &__admin-hint {
    color: var(--text-3);
    font-size: 12px;
  }

  &__stats {
    display: flex;
    gap: 34px;
    margin-top: 22px;
  }

  &__panel {
    flex: 0 0 336px;
    padding: 18px 18px 16px;
    border: 1px solid #f3e3b3;
    border-radius: var(--r-lg);
    background: linear-gradient(180deg, $gold-soft, #fff 66%);
  }

  &__panel-title {
    margin-bottom: 12px;
    color: var(--text-1);
    font-size: 13px;
    font-weight: 700;
    letter-spacing: 0.2px;
  }

  &__panel-actions {
    display: flex;
    gap: 8px;
    margin-top: 14px;
  }
}

.stat {
  display: flex;
  flex-direction: column;

  b {
    font-size: 24px;
    font-weight: 800;
    letter-spacing: -0.6px;
  }

  span {
    color: var(--text-3);
    font-size: 12px;
  }
}

.assurances {
  display: flex;
  flex-direction: column;
  gap: 11px;
  margin: 0;
  padding: 0;
  list-style: none;

  li {
    display: flex;
    align-items: flex-start;
    gap: 9px;
  }

  .el-icon {
    margin-top: 2px;
    color: $gold;
  }

  b {
    display: block;
    color: var(--text-1);
    font-size: 13.5px;
    font-weight: 600;
  }

  span {
    color: var(--text-3);
    font-size: 12px;
    line-height: 1.5;
  }
}

// ---------- 通用区块头 ----------
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
  flex-wrap: wrap;

  &__title {
    display: flex;
    align-items: center;
    gap: 9px;

    h2 {
      font-size: 17px;
      font-weight: 700;
    }
  }

  &__icon {
    color: var(--brand-600);
    font-size: 18px;

    &--flash {
      color: var(--accent-500);
    }
  }
}

.panel {
  margin-bottom: 16px;
}

// ---------- 代卖点 ----------
.points {
  &__hint {
    font-size: 12px;
    font-weight: 400;
  }

  &__chips {
    display: flex;
    gap: 10px;
    padding: 2px;
    overflow-x: auto;
    scrollbar-width: none;

    &::-webkit-scrollbar {
      display: none;
    }
  }

  &__detail {
    display: flex;
    gap: 16px;
    margin-top: 12px;
    padding: 10px 12px;
    border-radius: var(--r-md);
    background: var(--surface-2);
    color: var(--text-2);
    font-size: 12.5px;
    flex-wrap: wrap;
  }
}

.point-chip {
  flex-shrink: 0;
  padding: 8px 16px;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-pill);
  background: #fff;
  color: var(--text-2);
  cursor: pointer;
  font-family: inherit;
  font-size: 13.5px;
  transition: all 0.18s ease;

  &:hover {
    border-color: $gold;
    color: $gold;
    transform: translateY(-1px);
  }

  &.is-active {
    border-color: $gold;
    background: $gold;
    color: #fff;
    font-weight: 600;
  }
}

// ---------- 秒杀 ----------
.flash {
  &__countdown {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--accent-600);
    font-size: 13px;
  }

  &__countdown-label {
    color: var(--text-3);
  }

  &__countdown-value {
    padding: 2px 8px;
    border-radius: 7px;
    background: #fff2ec;
    font-variant-numeric: tabular-nums;
    font-size: 14px;
    letter-spacing: 0.4px;
  }

  &__subtitle {
    margin: -4px 0 12px;
    color: var(--text-3);
    font-size: 12.5px;
  }

  &__grid {
    display: grid;
    gap: 14px;
    grid-template-columns: repeat(auto-fill, minmax(206px, 1fr));
  }
}

.flash-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: #fff;
  cursor: pointer;
  transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.2s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--shadow-sm);
  }

  &__cover {
    position: relative;
    aspect-ratio: 4 / 3;
    overflow: hidden;
    background: var(--surface-2);
  }

  &__img {
    width: 100%;
    height: 100%;
  }

  &__placeholder {
    display: grid;
    width: 100%;
    height: 100%;
    place-items: center;
    color: var(--text-3);
    font-size: 12px;
  }

  &__badge {
    position: absolute;
    top: 8px;
    left: 8px;
    padding: 2px 8px;
    border-radius: 999px;
    background: var(--accent-500);
    color: #fff;
    font-size: 11px;
    font-weight: 700;
  }

  &__body {
    padding: 10px 12px 12px;
  }

  &__title {
    display: -webkit-box;
    overflow: hidden;
    min-height: 40px;
    color: var(--text-1);
    font-size: 13.5px;
    font-weight: 600;
    line-height: 20px;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__price {
    display: flex;
    align-items: baseline;
    gap: 7px;
    margin-top: 6px;

    .price {
      font-size: 20px;
    }
  }

  &__bar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
  }

  &__bar-track {
    overflow: hidden;
    height: 6px;
    flex: 1;
    border-radius: 999px;
    background: #ffe8dd;
  }

  &__bar-fill {
    height: 100%;
    border-radius: 999px;
    background: linear-gradient(90deg, #ff9a68, var(--accent-600));
    transition: width 0.4s ease;
  }

  &__bar-text {
    flex-shrink: 0;
    color: var(--text-3);
    font-size: 11.5px;
  }

  &__btn {
    width: 100%;
    margin-top: 10px;
    padding: 8px 0;
    border: none;
    border-radius: var(--r-pill);
    background: var(--accent-500);
    color: #fff;
    cursor: pointer;
    font-family: inherit;
    font-size: 13px;
    font-weight: 600;
    transition: all 0.18s ease;

    &:hover:not(:disabled) {
      background: var(--accent-600);
    }

    &:disabled {
      background: #e6eaf0;
      color: var(--text-3);
      cursor: not-allowed;
    }
  }
}

// ---------- 优惠券 ----------
.coupons {
  &__grid {
    display: grid;
    gap: 14px;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  }
}

.coupon {
  display: flex;
  align-items: center;
  gap: 14px;
  overflow: hidden;
  padding: 14px;
  border: 1px dashed #f3d2c2;
  border-radius: var(--r-md);
  background: linear-gradient(100deg, #fff8f4, #fff 62%);

  &.is-claimed {
    border-color: var(--line-strong);
    background: var(--surface-2);
    opacity: 0.82;
  }

  &__face {
    display: flex;
    width: 98px;
    flex-shrink: 0;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding-right: 12px;
    border-right: 1px dashed #f3d2c2;

    b {
      color: var(--accent-600);
      font-size: 24px;
      font-weight: 800;
      letter-spacing: -0.5px;
    }

    span {
      color: var(--text-3);
      font-size: 11.5px;
      text-align: center;
    }
  }

  &__body {
    min-width: 0;
    flex: 1;
  }

  &__name {
    overflow: hidden;
    color: var(--text-1);
    font-size: 14px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__meta {
    margin-top: 3px;
    color: var(--text-3);
    font-size: 11.5px;
  }

  &__btn {
    flex-shrink: 0;
  }
}

// ---------- 商品区 ----------
.items {
  margin-bottom: 8px;
}

.grid {
  display: grid;
  gap: 18px;
  grid-template-columns: repeat(auto-fill, minmax(226px, 1fr));
  min-height: 200px;
}

.skeleton-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;

  &__cover {
    aspect-ratio: 4 / 3;
    width: 100%;
  }

  &__body {
    padding: 14px;
  }

  &__line {
    height: 12px;
    border-radius: 6px;

    &--short {
      width: 55%;
      margin-top: 10px;
    }
  }
}

.empty-block {
  padding: 44px 16px;
  text-align: center;

  &--panel {
    border: 1px dashed var(--line-strong);
    border-radius: var(--r-lg);
    background: #fff;
  }

  &__title {
    color: var(--text-1);
    font-size: 15px;
    font-weight: 600;
  }

  &__desc {
    margin-top: 4px;
    margin-bottom: 14px;
    color: var(--text-3);
    font-size: 13px;
  }
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 26px;
}

@media (max-width: 1080px) {
  .hero__panel {
    display: none;
  }
}

@media (max-width: 560px) {
  .hero__inner {
    padding: 22px 16px 20px;
  }

  .hero__title {
    font-size: 24px;
  }

  .hero__stats {
    gap: 22px;
  }

  .coupon {
    flex-wrap: wrap;

    &__face {
      width: 100%;
      border-right: none;
      border-bottom: 1px dashed #f3d2c2;
      padding: 0 0 8px;
      flex-direction: row;
      justify-content: center;
      gap: 8px;
    }
  }
}
</style>
