<script setup lang="ts">
import { computed, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  Coin,
  DataLine,
  Goods,
  List,
  Moon,
  Place,
  Refresh,
  Shop,
  Sunny,
  SwitchButton,
  Ticket,
  Timer,
} from '@element-plus/icons-vue'
import { fetchCampuses } from '@/api/school'
import type { Campus } from '@/api/school'
import { useUserStore } from '@/stores/user'
import { useTheme } from '@/composables/useTheme'
import { DEFAULT_CAMPUS_ID, DEFAULT_SCHOOL_ID } from '@/utils/constants'
import { showTip } from '@/utils/feedback'

/**
 * 平台运营台 · 独立全屏 Shell（第 3 轮设计 4.2）。
 *
 * 与 `views/admin/AdminShell.vue`（内嵌在商店页布局里、内容限宽 1440）不同：
 * 这里是**独立顶层路由** `/console/platform`，不套 DefaultLayout、不套 AdminShell，
 * 整屏布局 = 左侧 248px 深色侧栏 + 右侧顶栏（64px，sticky）+ 通栏内容区（无 1440/1240 限制，
 * 内容 max-width 1760 居中兜底），目的就是"大屏下把表格铺开、不再被截断"。
 *
 * 父级 meta（requiresAuth/requiresAdmin）由路由继承，守卫不需要改。
 */

const MENU = [
  { path: '/console/platform', label: '运营概览', desc: '核心指标', icon: DataLine, exact: true },
  { path: '/console/platform/items', label: '平台商品', desc: '发布 / 上下架', icon: Goods },
  { path: '/console/platform/points', label: '代卖点', desc: '自提点维护', icon: Place },
  { path: '/console/platform/coupons', label: '优惠券', desc: '券模板 / 库存', icon: Ticket },
  { path: '/console/platform/flash-sales', label: '秒杀场次', desc: '场次 / 商品', icon: Timer },
  { path: '/console/platform/orders', label: '平台订单', desc: '自提单查询', icon: List },
  { path: '/console/platform/cache', label: '缓存运维', desc: 'Redis 预热', icon: Coin },
]

/** 顶栏标题/副标题：路由 meta 只有短标题，描述在这里按路径补 */
const PAGE_META: Record<string, { title: string; desc: string }> = {
  '/console/platform': { title: '运营概览', desc: '平台代卖核心指标与待办' },
  '/console/platform/items': { title: '平台商品', desc: '发布、编辑、上下架与库存管理' },
  '/console/platform/points': { title: '代卖点', desc: '代卖 / 自提点维护（校区、坐标、营业时间）' },
  '/console/platform/coupons': { title: '优惠券', desc: '券模板、库存与领取情况' },
  '/console/platform/flash-sales': { title: '秒杀场次', desc: '场次时间、商品与实时库存' },
  '/console/platform/orders': { title: '平台订单', desc: '平台代卖自提单查询' },
  '/console/platform/cache': { title: '缓存运维', desc: 'Redis 预热与库存快照' },
}

/** 校区选择持久化 key：顶栏「刷新当前页」是整页 reload，不存下来的话会退回默认校区 */
const CAMPUS_STORAGE_KEY = 'ershou:console:campusId'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { isDark, toggleTheme } = useTheme()

const campuses = ref<Campus[]>([])

function readStoredCampusId(): number {
  try {
    const raw = window.sessionStorage.getItem(CAMPUS_STORAGE_KEY)
    const parsed = raw === null ? NaN : Number(raw)
    return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_CAMPUS_ID
  } catch {
    return DEFAULT_CAMPUS_ID
  }
}

/** 当前校区：子页面通过 `inject('campusId')` 读取，切换后应 watch 重新拉数据 */
const campusId = ref<number>(readStoredCampusId())
provide('campusId', campusId)

const activeMenu = computed(() => {
  const path = route.path
  if (path === '/console/platform' || path === '/console/platform/') return '/console/platform'
  // 长的路径优先，避免 /console/platform/items 被更短的 /console/platform 抢走
  const hit = MENU.filter((item) => !item.exact)
    .sort((a, b) => b.path.length - a.path.length)
    .find((item) => path.startsWith(item.path))
  return hit ? hit.path : '/console/platform'
})

const pageTitle = computed(() => PAGE_META[activeMenu.value]?.title ?? route.meta.title ?? '平台运营台')
const pageDesc = computed(() => PAGE_META[activeMenu.value]?.desc ?? '平台代卖运营台')

async function loadCampuses(): Promise<void> {
  try {
    campuses.value = await fetchCampuses(DEFAULT_SCHOOL_ID)
    // 持久化下来的校区可能已停用/不存在：兜底回默认校区
    if (campuses.value.length && !campuses.value.some((campus) => campus.id === campusId.value)) {
      campusId.value = campuses.value[0]?.id ?? DEFAULT_CAMPUS_ID
    }
  } catch {
    campuses.value = []
  }
}

function onCampusChange(value: number): void {
  try {
    window.sessionStorage.setItem(CAMPUS_STORAGE_KEY, String(value))
  } catch {
    // 隐私模式等场景写不进去不影响本次使用
  }
  showTip('已切换校区，页面数据随之刷新', 'success')
}

/** 刷新当前页：整页 reload 最稳（子页由不同成员实现，统一走浏览器刷新，不依赖各自的事件约定） */
function refreshPage(): void {
  router.go(0)
}

function goShop(): void {
  void router.push('/')
}

function exitConsole(): void {
  void router.push('/admin')
}

onMounted(loadCampuses)
</script>

<template>
  <div class="pc">
    <!-- ================= 左侧：248px 深色侧栏 ================= -->
    <aside class="pc__side">
      <div class="pc__brand">
        <div class="pc__logo">严</div>
        <div class="pc__brand-text">
          <p class="pc__brand-title">平台运营台</p>
          <p class="pc__brand-badge">OFFICIAL SELECT</p>
        </div>
      </div>

      <nav class="pc__menu">
        <div
          v-for="item in MENU"
          :key="item.path"
          class="pc__menu-item"
          :class="{ 'is-active': activeMenu === item.path }"
          @click="router.push(item.path)"
        >
          <el-icon class="pc__menu-icon"><component :is="item.icon" /></el-icon>
          <span class="pc__menu-label">{{ item.label }}</span>
          <span class="pc__menu-desc">{{ item.desc }}</span>
        </div>
      </nav>

      <div class="pc__side-foot">
        <div class="pc__account">
          <el-avatar :size="34" :src="userStore.avatar || undefined" class="pc__account-avatar">
            {{ userStore.nickname.slice(0, 1) }}
          </el-avatar>
          <div class="pc__account-text">
            <p class="pc__account-name">{{ userStore.nickname }}</p>
            <p class="pc__account-role">平台管理员</p>
          </div>
        </div>
        <div class="pc__link" @click="goShop">
          <el-icon><Shop /></el-icon>
          <span class="pc__link-label">返回商城</span>
        </div>
        <div class="pc__link" @click="exitConsole">
          <el-icon><ArrowLeft /></el-icon>
          <span class="pc__link-label">退出运营台</span>
        </div>
      </div>
    </aside>

    <!-- ================= 右侧：顶栏 + 通栏内容 ================= -->
    <div class="pc__main">
      <header class="pc__top">
        <div class="pc__top-title">
          <h1 class="pc__title">{{ pageTitle }}</h1>
          <p class="pc__subtitle">{{ pageDesc }}</p>
        </div>
        <div class="pc__top-right">
          <el-tooltip :content="isDark ? '切换为日间模式' : '切换为夜间模式'" placement="bottom">
            <el-button
              circle
              :icon="isDark ? Sunny : Moon"
              :aria-label="isDark ? '切换为日间模式' : '切换为夜间模式'"
              @click="toggleTheme"
            />
          </el-tooltip>
          <el-select
            v-model="campusId"
            class="pc__campus"
            placeholder="选择校区"
            style="width: 148px"
            @change="onCampusChange"
          >
            <el-option
              v-for="campus in campuses"
              :key="campus.id"
              :label="campus.name"
              :value="campus.id"
            />
          </el-select>
          <el-tooltip content="刷新当前页" placement="bottom">
            <el-button :icon="Refresh" circle @click="refreshPage" />
          </el-tooltip>
          <el-divider direction="vertical" />
          <div class="pc__admin">
            <el-avatar :size="30" :src="userStore.avatar || undefined">
              {{ userStore.nickname.slice(0, 1) }}
            </el-avatar>
            <div class="pc__admin-text">
              <p class="pc__admin-name">{{ userStore.nickname }}</p>
              <p class="pc__admin-role">平台管理员</p>
            </div>
          </div>
          <el-button text :icon="SwitchButton" @click="exitConsole">退出</el-button>
        </div>
      </header>

      <div class="pc__content">
        <!--
          ⚠️ 这里**不要**再包 <transition mode="out-in">（T23，2026-10-18 踩过的坑）：
          包了之后第二次切页起 router-view 的组件 vnode 会丢，内容区只剩一个 <!----> 注释节点
          （侧栏/顶栏标题照常跟着路由变，也没有任何 console error），必须 F5 才恢复。
          已用 puppeteer 实测：修复前 40 次切换 47/48 次判定白屏且不可自愈；去掉 transition 后 0 白屏。
          切页稳定性 > 淡入动画，所以只保留 :key="route.path" 强制按路由重建子页面。
          route 用 router-view 插槽给的（比 useRoute() 少一层响应式依赖）。
        -->
        <router-view v-slot="{ Component, route: currentRoute }">
          <component :is="Component" :key="currentRoute.path" />
        </router-view>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
// =============================================================
// 平台运营台 Shell：全屏独立布局（第 3 轮设计第二节视觉规范）
// - .pc 占满视口，侧栏 248px 固定，右侧 flex:1 自己滚动
// - 顶栏 64px sticky + 半透明白底 blur
// - 内容 padding 28/32/48，max-width 1760 居中（大屏铺开、超宽屏不无限拉长）
// =============================================================

.pc {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg);
  color: var(--text-1);
}

/* ---------------- 侧栏 ---------------- */
.pc__side {
  display: flex;
  flex: 0 0 248px;
  flex-direction: column;
  width: 248px;
  background: linear-gradient(180deg, #0b1220 0%, #111c33 100%);
  color: #cbd5e1;
}

.pc__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 18px 16px;
}

.pc__logo {
  display: grid;
  flex: 0 0 38px;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  color: #052e21;
  font-size: 17px;
  font-weight: 800;
}

.pc__brand-title {
  color: #f8fafc;
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.pc__brand-badge {
  margin-top: 2px;
  color: #64748b;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.16em;
}

.pc__menu {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  padding: 6px 12px 12px;
  overflow-y: auto;
}

.pc__menu-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 48px;
  padding: 0 12px;
  border-radius: 10px;
  color: #94a3b8;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;

  &:hover {
    background: rgb(255 255 255 / 6%);
    color: #e2e8f0;
  }

  &.is-active {
    background: linear-gradient(90deg, rgb(18 185 129 / 24%) 0%, rgb(18 185 129 / 6%) 100%);
    color: #fff;

    &::before {
      position: absolute;
      top: 10px;
      bottom: 10px;
      left: 0;
      width: 3px;
      border-radius: 0 3px 3px 0;
      background: var(--brand-400);
      content: '';
    }

    .pc__menu-icon {
      color: var(--brand-300);
    }
  }
}

.pc__menu-icon {
  flex: 0 0 18px;
  font-size: 18px;
}

.pc__menu-label {
  font-size: 14px;
  font-weight: 600;
}

.pc__menu-desc {
  margin-left: auto;
  color: #64748b;
  font-size: 11px;
}

.pc__side-foot {
  display: grid;
  gap: 4px;
  padding: 12px;
  border-top: 1px solid rgb(255 255 255 / 8%);
}

.pc__account {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px 10px;
}

.pc__account-avatar {
  background: linear-gradient(135deg, var(--brand-500), var(--brand-700));
  color: #fff;
  font-weight: 700;
}

.pc__account-name {
  color: #e2e8f0;
  font-size: 13.5px;
  font-weight: 600;
}

.pc__account-role {
  color: #64748b;
  font-size: 11px;
}

.pc__link {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 38px;
  padding: 0 10px;
  border-radius: 10px;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;

  &:hover {
    background: rgb(255 255 255 / 6%);
    color: #e2e8f0;
  }
}

/* ---------------- 主区 ---------------- */
.pc__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  overflow: auto;
}

.pc__top {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  // 关键：.pc__main 是 flex 列容器，默认 flex-shrink:1 会把顶栏压到 min-content
  // （实测长页面 overview/cache 上被压成 43px）。顶栏必须钉死 64px，不参与收缩。
  flex: none;
  align-items: center;
  gap: 16px;
  height: 64px;
  padding: 0 32px;
  border-bottom: 1px solid var(--line);
  background: var(--header-bg);
  backdrop-filter: blur(8px);
}

.pc__top-title {
  min-width: 0;
}

.pc__title {
  color: var(--text-1);
  font-size: 17px;
  font-weight: 700;
  line-height: 1.2;
}

.pc__subtitle {
  margin-top: 2px;
  color: var(--text-3);
  font-size: 12px;
}

.pc__top-right {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.pc__admin {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--surface);
}

.pc__admin-name {
  color: var(--text-1);
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.2;
}

.pc__admin-role {
  color: var(--text-3);
  font-size: 10.5px;
}

.pc__content {
  width: 100%;
  max-width: 1760px;
  margin: 0 auto;
  padding: 28px 32px 48px;
}

/* 页面切换过渡的样式曾配合 <transition mode="out-in"> 使用，T23 因白屏已去掉 transition，
   对应 .pc-fade-* 规则一并删除（不要再加回来，见 template 里 router-view 上方的说明）。 */

/* ---------------- 响应式：<1200 侧栏只留图标；<900 侧栏顶部横排 ---------------- */
@media (max-width: 1200px) {
  .pc__side {
    flex-basis: 72px;
    width: 72px;
  }

  .pc__brand {
    justify-content: center;
    padding: 16px 0;
  }

  .pc__brand-text,
  .pc__menu-label,
  .pc__menu-desc,
  .pc__account-text,
  .pc__link-label {
    display: none;
  }

  .pc__menu {
    align-items: center;
    padding: 6px 8px;
  }

  .pc__menu-item {
    justify-content: center;
    width: 48px;
    padding: 0;
  }

  .pc__side-foot {
    justify-items: center;
  }

  .pc__link {
    justify-content: center;
    width: 48px;
    padding: 0;
  }

  .pc__top {
    padding: 0 20px;
  }

  .pc__content {
    padding: 20px 20px 40px;
  }

  .pc__admin-text {
    display: none;
  }
}

@media (max-width: 900px) {
  .pc {
    flex-direction: column;
    height: auto;
    min-height: 100vh;
  }

  .pc__side {
    flex: none;
    flex-direction: row;
    align-items: center;
    width: 100%;
    overflow-x: auto;
  }

  .pc__brand {
    padding: 10px 12px;
  }

  .pc__menu {
    flex-direction: row;
    flex: 1;
    padding: 8px 4px;
    overflow-x: auto;
  }

  .pc__side-foot {
    display: flex;
    gap: 4px;
    padding: 8px;
    border-top: none;
    border-left: 1px solid rgb(255 255 255 / 8%);
  }

  .pc__account {
    display: none;
  }

  .pc__main {
    overflow: visible;
  }

  .pc__title {
    font-size: 15px;
  }

  .pc__subtitle {
    display: none;
  }

  .pc__top {
    height: 56px;
    padding: 0 14px;
  }

  .pc__content {
    padding: 14px 14px 32px;
  }

  .pc__admin {
    display: none;
  }
}
</style>
