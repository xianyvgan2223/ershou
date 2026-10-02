<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Camera,
  Check,
  CircleClose,
  Edit,
  Goods,
  List,
  Location,
  Setting,
  ShoppingCart,
  Star,
  Timer,
} from '@element-plus/icons-vue'
import ProfileEditDialog from '@/components/ProfileEditDialog.vue'
import { fetchFavorites, fetchMyItems } from '@/api/item'
import { fetchMeetupTodoCount } from '@/api/order'
import { fetchCart } from '@/api/cart'
import { fetchBlockedUsers } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { ITEM_STATUS, formatPrice, formatRelativeTime, nameInitial } from '@/utils/format'
import type { TagType } from '@/utils/format'
import type { ItemVO } from '@/api/item'

/**
 * 个人中心（我的）。
 *
 * 改造前的问题：整页只有"头像 + 4 个配额数字 + 5 个功能入口 + 4 个按钮"，
 * **一个真实数据都没有** —— 用户进来想知道"我有没有面交要确认""我的东西卖出去了吗"，
 * 结果看到的是"可发布数量 300"这种和他没关系的东西，而入口在顶栏已经全有了，等于白来一趟。
 *
 * 现在首页面只回答两个问题：
 *   1. **有没有要我动手的事**（待确认的面交、购物车、在售商品）—— 没有就明说"暂无待办"；
 *   2. **我最近的东西怎么样了**（最近发布 + 最近收藏）。
 * 配额与用户编号这类"档案信息"降级到页面底部的小字，不再占据视觉中心。
 */
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const editVisible = ref(false)

/** 各接口自成一块：任一失败都不该让整页白掉，所以各自 try/catch 兜 0/空 */
/** 黑名单人数：入口文案上顺手显示，省得点进去才知道有没有 */
const blockedCount = ref(0)
const myOnSale = ref(0)
const mySold = ref(0)
const favoriteTotal = ref(0)
const cartCount = ref(0)
const todo = ref({ asSeller: 0, asBuyer: 0, total: 0 })
const recentItems = ref<ItemVO[]>([])
const recentFavorites = ref<ItemVO[]>([])

const profile = computed(() => userStore.profile)
const nickname = computed(() => userStore.nickname)
const creditScore = computed(() => userStore.creditScore)

/**
 * 信用等级文案。后端 creditLevel 是数字（1/2/3…），
 * 直接把它渲染出来会变成一个没头没尾的"2"（改造前就是这样，用户看不懂），所以在这里映射成中文。
 */
const CREDIT_LEVEL_TEXT: Record<number, string> = {
  1: '信用一般',
  2: '信用良好',
  3: '信用优秀',
  4: '信用极好',
}
const creditLevelText = computed(() => {
  const raw = profile.value?.creditLevel
  if (typeof raw === 'number') return CREDIT_LEVEL_TEXT[raw] ?? '信用良好'
  return (raw as string) || '信用良好'
})

/** 身份徽章：identity_flag 位标记（1=商家 / 2=代卖员 / 4=校内邮箱已验证） */
const badges = computed(() => {
  const flag = userStore.identityFlag ?? 0
  const list: { text: string; type: TagType }[] = []
  if (flag & 1) list.push({ text: '商家', type: 'warning' })
  if (flag & 2) list.push({ text: '代卖员', type: 'primary' })
  if (flag & 4) list.push({ text: '校内邮箱已验证', type: 'success' })
  return list
})

const joinedAt = computed(() => {
  const raw = profile.value?.createdAt
  if (!raw) return ''
  return String(raw).slice(0, 10).replace(/-/g, '/')
})

/** 待办项：只放"需要我动手"的，且为 0 的不显示 */
const todoItems = computed(() => {
  const list: { key: string; label: string; hint: string; count: number; path: string; icon: unknown }[] = [
    {
      key: 'seller',
      label: '等你确认的面交',
      hint: '买家已下单，等你接受',
      count: todo.value.asSeller,
      path: '/orders?role=seller&status=1',
      icon: Timer,
    },
    {
      key: 'buyer',
      label: '等你确认收货',
      hint: '卖家已接受，面交后确认',
      count: todo.value.asBuyer,
      path: '/orders?role=buyer&status=2',
      icon: Check,
    },
    {
      key: 'cart',
      label: '购物车待结算',
      hint: '合并下单更省事',
      count: cartCount.value,
      path: '/cart',
      icon: ShoppingCart,
    },
  ]
  return list.filter((i) => i.count > 0)
})

/** 概览数字：有真实含义的四个数，不再放"可发布 300"这种配额 */
const summaryCards = computed(() => [
  { key: 'onsale', label: '在售中', value: myOnSale.value, path: '/publish-list' },
  { key: 'sold', label: '已卖出', value: mySold.value, path: '/publish-list' },
  { key: 'fav', label: '我的收藏', value: favoriteTotal.value, path: '/favorites' },
  { key: 'orders', label: '面交单', value: todo.value.total, path: '/orders' },
])

const entries = computed(() => {
  const base = [
    { label: '我的发布', desc: '在售 / 已售 / 下架', icon: Goods, path: '/publish-list' },
    { label: '我的收藏', desc: '心仪好物随时找', icon: Star, path: '/favorites' },
    { label: '我的面交', desc: '买家 / 卖家面交单', icon: List, path: '/orders' },
    { label: '购物车', desc: '合并面交更省事', icon: ShoppingCart, path: '/cart' },
    { label: '我的黑名单', desc: blockedCount.value > 0 ? `已拉黑 ${blockedCount.value} 人` : '拉黑的人在这里', icon: CircleClose, path: '/blocked' },
    { label: '校内地图', desc: '挑个安全的面交点', icon: Location, path: '/map' },
  ]
  // 运营人员额外展示后台入口
  if (userStore.isAdmin) {
    return [{ label: '运营后台', desc: '审核工作台 / 用户管理', icon: Setting, path: '/admin' }, ...base]
  }
  return base
})

/** 配额：只在"快用完"时才值得占用注意力 */
const publishLimit = computed(() => profile.value?.publishLimit ?? 0)
const chatLimit = computed(() => profile.value?.chatLimit ?? 0)

/**
 * 拉一次页面要用的全部数据。
 * 每块独立 catch：任何一个接口挂了，其余部分照常显示（个人中心挂掉最影响心情）。
 *
 * ⚠️ 「在售中 / 已卖出」是在**前端**按状态统计的，因为 `/items/mine` 只返回全部分页、
 * **不支持按 status 过滤**（后端签名只有 pageNum/pageSize）。所以取最近 100 件来数，
 * 页面上标了"按最近 100 件统计"，避免把它误读成精确总数。
 */
const MY_ITEMS_SCAN = 100
const myItemsScanned = ref(0)

async function loadAll(): Promise<void> {
  loading.value = true
  await Promise.all([
    userStore.fetchProfile().catch(() => null),
    fetchMyItems({ pageNum: 1, pageSize: MY_ITEMS_SCAN })
      .then((r) => {
        const all = r.records ?? []
        myItemsScanned.value = all.length
        myOnSale.value = all.filter((i) => i.status === ITEM_STATUS.ON_SALE || i.status === ITEM_STATUS.LOCKED).length
        mySold.value = all.filter((i) => i.status === ITEM_STATUS.SOLD).length
        // 最近发布：在售的排前面，其次按时间（接口本身已按 id 倒序）
        recentItems.value = [...all]
          .sort((a, b) => {
            const rank = (s: number) =>
              s === ITEM_STATUS.ON_SALE ? 0 : s === ITEM_STATUS.LOCKED ? 1 : s === ITEM_STATUS.REVIEWING ? 2 : 3
            return rank(a.status) - rank(b.status)
          })
          .slice(0, 4)
      })
      .catch(() => undefined),
    fetchFavorites({ pageNum: 1, pageSize: 3 })
      .then((r) => {
        favoriteTotal.value = r.total ?? 0
        recentFavorites.value = r.records ?? []
      })
      .catch(() => undefined),
    fetchMeetupTodoCount()
      .then((r) => (todo.value = r ?? { asSeller: 0, asBuyer: 0, total: 0 }))
      .catch(() => undefined),
    fetchCart()
      .then((groups) => (cartCount.value = (groups ?? []).reduce((sum, g) => sum + (g.items?.length ?? 0), 0)))
      .catch(() => undefined),
    fetchBlockedUsers()
      .then((list) => (blockedCount.value = (list ?? []).length))
      .catch(() => undefined),
  ])
  loading.value = false
}

function openEdit(): void {
  editVisible.value = true
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '再想想',
      type: 'warning',
    })
  } catch {
    return
  }
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading" class="page profile">
    <!-- ---------------- 身份卡 ---------------- -->
    <section class="profile__hero">
      <div class="profile__avatar" title="点击修改头像" @click="openEdit">
        <el-avatar :size="72" :src="userStore.avatar || undefined">{{ nameInitial(nickname) }}</el-avatar>
        <span class="profile__avatar-mask"><el-icon><Camera /></el-icon></span>
      </div>

      <div class="profile__hero-info">
        <h2 class="profile__name">
          {{ nickname }}
          <el-tag v-for="badge in badges" :key="badge.text" size="small" :type="badge.type" effect="plain">
            {{ badge.text }}
          </el-tag>
          <el-tag v-if="userStore.isAdmin" size="small" type="danger" effect="plain">运营</el-tag>
        </h2>
        <p class="profile__meta">
          {{ profile?.schoolName || '校园' }} · {{ profile?.campusName || userStore.campusName || '本校校区' }}
          <template v-if="joinedAt"> · {{ joinedAt }} 加入</template>
          <template v-if="profile?.phone"> · {{ profile.phone }}</template>
        </p>
        <p v-if="profile?.shopIntro" class="profile__intro">小铺：{{ profile.shopIntro }}</p>
      </div>

      <!-- 信用：分数 + 中文等级。
           星级已去掉 —— 信用分满分 100、星级是「分数÷20 四舍五入」，同一个信息说两遍；
           而且星级只在整 20 分时才准（80 分和 89 分都是 4 星），反而容易让人误解。 -->
      <div class="profile__credit" :title="`信用分 ${creditScore}（满分 100）`">
        <div class="profile__credit-row">
          <span class="profile__credit-score">{{ creditScore }}</span><span class="profile__credit-unit">分</span>
        </div>
        <p class="profile__credit-level">{{ creditLevelText }}</p>
      </div>

      <el-button :icon="Edit" @click="openEdit">编辑资料</el-button>
    </section>

    <!-- ---------------- 待办：只列"需要我动手"的；一件没有就明说 ---------------- -->
    <section v-if="todoItems.length" class="profile__todo">
      <div class="profile__todo-head">
        <span class="profile__todo-title">需要你处理</span>
        <span class="text-muted">共 {{ todoItems.length }} 类</span>
      </div>
      <div class="profile__todo-list">
        <button
          v-for="item in todoItems"
          :key="item.key"
          type="button"
          class="profile__todo-item"
          @click="router.push(item.path)"
        >
          <el-icon class="profile__todo-icon"><component :is="item.icon" /></el-icon>
          <span class="profile__todo-text">
            <span class="profile__todo-label">{{ item.label }}</span>
            <span class="profile__todo-hint">{{ item.hint }}</span>
          </span>
          <span class="profile__todo-count">{{ item.count }}</span>
        </button>
      </div>
    </section>
    <section v-else-if="!loading" class="profile__todo profile__todo--clear">
      <el-icon><Check /></el-icon>
      <span>暂无待处理的事，逛逛校园好物吧</span>
    </section>

    <!-- ---------------- 概览数字（都是真实数据，不再是配额） ---------------- -->
    <div class="profile__stats">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="profile__stat"
        @click="router.push(card.path)"
      >
        <span class="profile__stat-value">{{ card.value }}</span>
        <span class="profile__stat-label">{{ card.label }}</span>
      </button>
    </div>

    <!-- ---------------- 最近发布 / 最近收藏 ---------------- -->
    <section class="profile__cols">
      <div class="panel">
        <div class="profile__section-head">
          <span class="profile__section-title">我发布的</span>
          <el-button text size="small" @click="router.push('/publish-list')">全部</el-button>
        </div>
        <ul v-if="recentItems.length" class="profile__items">
          <li
            v-for="item in recentItems"
            :key="item.id"
            class="profile__item"
            @click="router.push(`/item/${item.id}`)"
          >
            <img v-if="item.coverUrl" :src="item.coverUrl" class="profile__item-img" alt="" />
            <span v-else class="profile__item-img profile__item-img--empty">
              <el-icon><Goods /></el-icon>
            </span>
            <span class="profile__item-main">
              <span class="profile__item-title">{{ item.title }}</span>
              <span class="profile__item-sub">
                <span class="profile__item-price">¥{{ formatPrice(item.price) }}</span>
                <span class="text-muted">{{ item.statusText || '在售' }}</span>
                <span v-if="item.publishedAt" class="text-muted">
                  {{ formatRelativeTime(item.publishedAt) }}
                </span>
              </span>
            </span>
          </li>
        </ul>
        <div v-else class="profile__empty">
          <p>还没发布过东西</p>
          <el-button type="primary" size="small" @click="router.push('/publish')">去发布</el-button>
        </div>
      </div>

      <div class="panel">
        <div class="profile__section-head">
          <span class="profile__section-title">我收藏的</span>
          <el-button text size="small" @click="router.push('/favorites')">全部</el-button>
        </div>
        <ul v-if="recentFavorites.length" class="profile__items">
          <li
            v-for="item in recentFavorites"
            :key="item.id"
            class="profile__item"
            @click="router.push(`/item/${item.id}`)"
          >
            <img v-if="item.coverUrl" :src="item.coverUrl" class="profile__item-img" alt="" />
            <span v-else class="profile__item-img profile__item-img--empty">
              <el-icon><Star /></el-icon>
            </span>
            <span class="profile__item-main">
              <span class="profile__item-title">{{ item.title }}</span>
              <span class="profile__item-sub">
                <span class="profile__item-price">¥{{ formatPrice(item.price) }}</span>
                <span class="text-muted">{{ item.seller?.nickname || '同学' }}</span>
              </span>
            </span>
          </li>
        </ul>
        <div v-else class="profile__empty">
          <p>还没有收藏</p>
          <el-button size="small" @click="router.push('/')">去逛逛</el-button>
        </div>
      </div>
    </section>

    <!-- ---------------- 入口 + 账号操作（原来的主体，现在收到底部） ---------------- -->
    <section class="panel">
      <div class="profile__section-head">
        <span class="profile__section-title">常用入口</span>
      </div>
      <div class="profile__entries">
        <button
          v-for="entry in entries"
          :key="entry.path"
          type="button"
          class="profile__entry"
          @click="router.push(entry.path)"
        >
          <el-icon class="profile__entry-icon"><component :is="entry.icon" /></el-icon>
          <span class="profile__entry-text">
            <span class="profile__entry-label">{{ entry.label }}</span>
            <span class="profile__entry-desc">{{ entry.desc }}</span>
          </span>
          <el-icon class="profile__entry-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </section>

    <!-- ---------------- 档案信息 + 账号操作：都是低优先信息，放最底部小字 ---------------- -->
    <section class="panel profile__foot">
      <p class="profile__archive">
        用户编号 #{{ profile?.id ?? '--' }}
        <span class="profile__dot">·</span>
        每日可发布 {{ publishLimit }} 件、可发起 {{ chatLimit }} 次会话
        <span class="profile__dot">·</span>
        <el-button text size="small" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button text size="small" @click="openEdit">编辑资料</el-button>
        <el-button text size="small" class="profile__logout" @click="handleLogout">退出登录</el-button>
      </p>
      <p class="profile__note">
        交易过程中请保持在校内公共区域面交；遇到问题可联系校园二手客服（学生服务号）。
      </p>
    </section>

    <ProfileEditDialog v-model="editVisible" />
  </div>
</template>

<style scoped lang="scss">
// 个人中心：先回答"有没有要我动手的事""我的东西怎么样了"，
// 档案信息（编号/配额）压到最底部小字。
.profile {
  /* ---------------- 身份卡 ---------------- */
  &__hero {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 18px 20px;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: var(--surface);
    box-shadow: var(--shadow-xs);
  }

  &__avatar {
    position: relative;
    flex-shrink: 0;
    cursor: pointer;
    line-height: 0;

    &:hover .profile__avatar-mask {
      opacity: 1;
    }
  }

  &__avatar-mask {
    position: absolute;
    inset: 0;
    display: grid;
    border-radius: 50%;
    background: rgb(15 23 42 / 46%);
    color: #fff;
    font-size: 18px;
    opacity: 0;
    place-items: center;
    transition: opacity 0.2s ease;
  }

  &__hero-info {
    min-width: 0;
    flex: 1;
  }

  &__name {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin: 0 0 6px;
    font-size: 19px;
    font-weight: 700;
  }

  &__meta {
    margin: 0;
    color: var(--text-3);
    font-size: 12.5px;
  }

  &__intro {
    overflow: hidden;
    margin: 4px 0 0;
    max-width: 520px;
    color: var(--text-2);
    font-size: 12.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__credit {
    flex-shrink: 0;
    padding: 0 4px 0 18px;
    border-left: 1px solid var(--line);
    // 两行居中：分数 / 等级。（原来还有一行星级，已去掉）
    text-align: center;
  }

  // 分数与"分"字要在同一基线上对齐，所以用 inline 拼而不是 flex 列
  &__credit-score {
    color: var(--text-1);
    font-size: 24px;
    font-weight: 700;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }

  &__credit-unit {
    margin-left: 2px;
    color: var(--text-3);
    font-size: 12px;
  }

  &__credit-level {
    margin: 4px 0 0;
    color: var(--text-3);
    font-size: 11.5px;
  }

  /* ---------------- 待办 ---------------- */
  &__todo {
    margin-top: 12px;
    padding: 12px 16px;
    border: 1px solid var(--brand-200);
    border-radius: var(--r-md);
    background: var(--brand-50);
  }

  &__todo--clear {
    display: flex;
    align-items: center;
    gap: 8px;
    border-color: var(--line);
    background: var(--surface);
    color: var(--text-3);
    font-size: 13px;
  }

  &__todo-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 8px;
  }

  &__todo-title {
    color: var(--brand-700);
    font-size: 13.5px;
    font-weight: 700;
  }

  &__todo-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 8px;
  }

  &__todo-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 12px;
    border: 1px solid var(--brand-200);
    border-radius: var(--r-sm);
    background: var(--surface);
    cursor: pointer;
    font-family: inherit;
    text-align: left;

    &:hover {
      border-color: var(--brand-400);
    }
  }

  &__todo-icon {
    color: var(--brand-600);
    font-size: 17px;
  }

  &__todo-text {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
  }

  &__todo-label {
    color: var(--text-1);
    font-size: 13px;
    font-weight: 600;
  }

  &__todo-hint {
    color: var(--text-3);
    font-size: 11px;
  }

  &__todo-count {
    flex-shrink: 0;
    color: var(--brand-700);
    font-size: 17px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  /* ---------------- 概览数字 ---------------- */
  &__stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 10px;
    margin-top: 12px;
  }

  &__stat {
    padding: 12px 14px;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: var(--surface);
    cursor: pointer;
    font-family: inherit;
    text-align: left;

    &:hover {
      border-color: var(--line-strong);
      background: var(--surface-2);
    }
  }

  &__stat-value {
    display: block;
    color: var(--text-1);
    font-size: 21px;
    font-weight: 700;
    line-height: 1.15;
    font-variant-numeric: tabular-nums;
  }

  &__stat-label {
    display: block;
    margin-top: 2px;
    color: var(--text-3);
    font-size: 11.5px;
  }

  /* ---------------- 两栏：我发布的 / 我收藏的 ---------------- */
  &__cols {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
    gap: 12px;
    margin-top: 12px;
    align-items: start;
  }

  &__section-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 8px;
  }

  &__section-title {
    color: var(--text-1);
    font-size: 14px;
    font-weight: 600;
  }

  &__items {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 0;
    border-bottom: 1px solid var(--line);
    cursor: pointer;

    &:last-child {
      border-bottom: none;
    }

    &:hover .profile__item-title {
      color: var(--brand-600);
    }
  }

  &__item-img {
    width: 44px;
    height: 44px;
    border-radius: 6px;
    background: var(--surface-2);
    flex-shrink: 0;
    object-fit: cover;
  }

  &__item-img--empty {
    display: grid;
    color: var(--text-3);
    place-items: center;
  }

  &__item-main {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 2px;
  }

  &__item-title {
    overflow: hidden;
    color: var(--text-1);
    font-size: 13px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__item-sub {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 11.5px;
  }

  &__item-price {
    color: var(--accent-600);
    font-weight: 700;
  }

  &__empty {
    padding: 18px 0;
    color: var(--text-3);
    font-size: 13px;
    text-align: center;

    p {
      margin: 0 0 10px;
    }
  }

  /* ---------------- 入口 ---------------- */
  &__entries {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
    gap: 8px;
  }

  &__entry {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    background: var(--surface);
    cursor: pointer;
    font-family: inherit;
    text-align: left;

    &:hover {
      border-color: var(--line-strong);
      background: var(--surface-2);
    }

    &:hover .profile__entry-arrow {
      opacity: 1;
      transform: translateX(2px);
    }
  }

  &__entry-icon {
    color: var(--text-3);
    font-size: 17px;
  }

  &__entry-text {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
  }

  &__entry-label {
    color: var(--text-1);
    font-size: 13px;
    font-weight: 600;
  }

  &__entry-desc {
    color: var(--text-3);
    font-size: 11px;
  }

  &__entry-arrow {
    color: var(--text-3);
    font-size: 13px;
    opacity: 0;
    transition: all 0.15s;
  }

  /* ---------------- 底部档案 ---------------- */
  &__foot {
    margin-top: 12px;
  }

  // 档案行：编号 / 配额 / 操作都在这一行小字里。
  // 用 inline 排版而不是 flex —— flex 会让"·"分隔符被当成独立块，和两侧文字挤在一起（实测成了"300 次会话 · 刷新"这种黏连感）。
  &__archive {
    margin: 0;
    color: var(--text-3);
    font-size: 12px;
    line-height: 2;

    .el-button {
      height: auto;
      padding: 0 4px;
      font-size: 12px;
    }
  }

  &__dot {
    margin: 0 4px;
    color: var(--line-strong);
  }

  &__logout {
    color: var(--el-color-danger);

    &:hover {
      color: var(--el-color-danger);
      text-decoration: underline;
    }
  }

  &__note {
    margin: 8px 0 0;
    color: var(--text-3);
    font-size: 11.5px;
  }
}

@media (max-width: 900px) {
  .profile__hero {
    flex-wrap: wrap;
  }

  .profile__credit {
    padding: 0;
    border-left: none;
    text-align: left;
  }

  .profile__stats {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>