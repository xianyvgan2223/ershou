<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, QuestionFilled, Refresh, Search, Select } from '@element-plus/icons-vue'
import {
  backfillReviews,
  batchReview,
  fetchReviewDetail,
  fetchReviewFeed,
  fetchReviewStats,
  markItemReviewed,
  offlineReviewItem,
} from '@/api/admin'
import type { ItemReview, ItemReviewStats, ReviewScope } from '@/api/admin'
import { useAdminReviewSocket } from '@/composables/useAdminReviewSocket'
import { useQuerySync } from '@/composables/useQuerySync'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatPrice, formatRelativeTime } from '@/utils/format'
import { showLongTip, showTip } from '@/utils/feedback'

/**
 * 上架审核：让审核员确认"每一条上架到在售的商品都被人工看过"。
 *
 * 产品口径（与后端 ItemReviewService 一致）：
 * - 商品正常上架【不拦截】：发布成功就在售，这个页面只是把它排进来等确认；
 * - 点「已审核」→ 这条从待审列表消失（归档 24 小时，还能在"已处置"里回看自己刚做了什么）；
 * - 觉得不合适 → 「下架」直接撤下来（卖家改好可以重新上架，重新上架会再进这个页面）；
 * - 卖家在售期间改名/改描述 → 重新回到最前面，并打上「已改名」标记；
 * - 违规下架的商品二次申请上架【必须审核】，那条走「审核工作台」，这里只做提醒。
 *
 * 实时性：WS 推送新上架/被处置。设计上刻意不"重推整个列表"——
 * 新条目 push 里带完整 item 直接插到顶部，被别人处置的事件只带 itemId 本地摘掉一行，
 * 只有条数对不上时才静默重拉一次，避免多个审核员同时在线时互相触发刷新风暴。
 */

const PAGE_SIZES = [10, 20, 50, 100]

const scope = ref<ReviewScope>('pending')
const keyword = ref('')
const sellerId = ref('')
const pageNum = ref(1)
const pageSize = ref(20)

const loading = ref(false)
const list = ref<ItemReview[]>([])
const total = ref(0)
const stats = ref<ItemReviewStats | null>(null)
const busyItems = ref<number[]>([])
const selected = ref<ItemReview[]>([])

/** 新条目提醒：页面滚下去了或正在看详情时，不硬插行打断，先飘一条可点的提示 */
const newArrivals = ref<ItemReview[]>([])
const flashItemId = ref<number | null>(null)
/** 使用说明默认收起：常驻会一直抢注意力，但删掉审核员又不知道这页在干什么 */
const showHelp = ref(false)

const detail = reactive({ visible: false, item: null as ItemReview | null, loading: false })
const offlineDialog = reactive({
  visible: false,
  target: null as ItemReview | null,
  reason: '',
  loading: false,
})

/** 待审条数：优先用推送里的权威值，避免前端自己加减算错 */
const pendingCount = computed(() => stats.value?.pending ?? 0)
const hasFilter = computed(() => !!keyword.value.trim() || !!sellerId.value.trim())
const isPendingScope = computed(() => scope.value === 'pending')

const socket = useAdminReviewSocket({
  nickname: useUserStore().nickname,
  onQueued: (data) => {
    if (typeof data.pending === 'number' && stats.value) stats.value.pending = data.pending
    // 只关心待审视图：在"已处置/全部"里插新行没有意义
    if (scope.value !== 'pending' || pageNum.value !== 1) return
    // 已经不在列表里才补：改名重新入队的商品本来就在列表里，只需要把它顶到最前
    void upsertArrival(data.itemId, data.reviewTypeText)
  },
  onHandled: (data) => {
    if (typeof data.pending === 'number' && stats.value) stats.value.pending = data.pending
    if (data.itemId) {
      const existed = list.value.some((row) => row.itemId === data.itemId)
      list.value = list.value.filter((row) => row.itemId !== data.itemId)
      selected.value = selected.value.filter((row) => row.itemId !== data.itemId)
      if (existed) total.value = Math.max(0, total.value - 1)
    }
    // 别人处置后本页可能空了，把下一批补上来
    if (list.value.length === 0 && pageNum.value > 1) {
      pageNum.value -= 1
      void load()
    } else if (list.value.length < pageSize.value && total.value > list.value.length) {
      void load()
    }
  },
  onViolationApplied: (data) => {
    if (typeof data.pending === 'number' && stats.value) stats.value.pending = data.pending
    showLongTip(`有违规商品申请重新上架：${data.title || '#' + data.itemId}，请到「审核工作台」处理`, 'warning')
  },
  // 首次连接与每次重连都会走这里：断线期间的上架事件补不回来，必须靠 REST 兜底
  onReconnected: () => {
    void load(true)
    void loadStats()
  },
})

/** 收到新上架：能直接拿到 VO（后端只推了 itemId 时为 null）就插到顶部并闪一下 */
async function upsertArrival(itemId?: number, reviewTypeText?: string): Promise<void> {
  if (!itemId) {
    newArrivals.value = [
      { itemId: 0, title: reviewTypeText || '有新商品上架' } as unknown as ItemReview,
      ...newArrivals.value,
    ].slice(0, 5)
    return
  }
  try {
    const fresh = await fetchReviewDetail(itemId)
    if (!fresh) return
    const index = list.value.findIndex((row) => row.itemId === itemId)
    if (index >= 0) {
      // 改名重新入队：原地更新并顶到最前（不重复插一条，否则审核员会以为有两件商品）
      list.value.splice(index, 1)
    } else {
      total.value += 1
    }
    list.value = [fresh, ...list.value].slice(0, pageSize.value)
    flashItemId.value = itemId
    window.setTimeout(() => {
      if (flashItemId.value === itemId) flashItemId.value = null
    }, 3000)
  } catch {
    // 拉详情失败就退回"有新条目"提示条，别让实时性变成静默失败
    newArrivals.value = [
      { itemId, title: reviewTypeText || '有新商品上架' } as unknown as ItemReview,
      ...newArrivals.value,
    ].slice(0, 5)
  }
}

async function load(withLoading = true): Promise<void> {
  if (withLoading) loading.value = true
  try {
    const res = await fetchReviewFeed({
      scope: scope.value,
      keyword: keyword.value.trim() || undefined,
      sellerId: toId(sellerId.value),
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    })
    list.value = res.records ?? []
    total.value = res.total ?? 0
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadStats(): Promise<void> {
  try {
    stats.value = await fetchReviewStats()
  } catch {
    // 顶部数字拉不到不影响列表使用
  }
}

/** 卖家 ID 只接受正整数，其它一律当作「不筛」 */
function toId(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const num = Number(trimmed)
  return Number.isInteger(num) && num > 0 ? num : undefined
}

function search(): void {
  pageNum.value = 1
  void load()
  void loadStats()
}

function reset(): void {
  keyword.value = ''
  sellerId.value = ''
  pageNum.value = 1
  void load()
}

function changeScope(next: ReviewScope): void {
  scope.value = next
  pageNum.value = 1
  selected.value = []
  void load()
}

/** 顶部「新条目」提示：点一下回到第一页看最新的（不自动跳，避免打断正在看的详情） */
function goTop(): void {
  newArrivals.value = []
  pageNum.value = 1
  void load()
}

// ---------------- 已审核 ----------------
async function markOne(row: ItemReview, silent = false): Promise<void> {
  busyItems.value = [...busyItems.value, row.itemId]
  try {
    const next = await markItemReviewed(row.itemId)
    applyStats(next)
    removeLocally(row.itemId)
    if (!silent) showTip('已审核：' + row.title, 'success')
  } catch (e) {
    handleConflict(e, row.itemId)
  } finally {
    busyItems.value = busyItems.value.filter((id) => id !== row.itemId)
  }
}

/** 本地摘掉一行，并把下一批补上来（否则处置几条后列表会越来越空） */
function removeLocally(itemId: number): void {
  const existed = list.value.some((row) => row.itemId === itemId)
  list.value = list.value.filter((row) => row.itemId !== itemId)
  selected.value = selected.value.filter((row) => row.itemId !== itemId)
  if (detail.item?.itemId === itemId) detail.visible = false
  if (existed) total.value = Math.max(0, total.value - 1)
  if (list.value.length === 0 && pageNum.value > 1) {
    pageNum.value -= 1
    void load(false)
  } else if (list.value.length < pageSize.value && total.value > list.value.length) {
    void load(false)
  }
}

function applyStats(next?: ItemReviewStats | null): void {
  if (next) stats.value = next
}

/**
 * 并发冲突（61001/61002）：另一个审核员刚处置过这条。
 * 这不是错误操作，所以不弹红色报错，把它移出列表并给一句轻提示即可。
 */
function handleConflict(error: unknown, itemId: number): void {
  const code = (error as { code?: number } | null)?.code
  if (code === 61001 || code === 61002) {
    removeLocally(itemId)
    showTip('这条已被其他审核员处置，已从列表移除', 'info')
    void loadStats()
    return
  }
  // 其它错误：响应拦截器已经弹过提示
}

// ---------------- 下架 ----------------
function openOffline(row: ItemReview): void {
  offlineDialog.target = row
  offlineDialog.reason = ''
  offlineDialog.visible = true
}

async function submitOffline(): Promise<void> {
  const target = offlineDialog.target
  if (!target) return
  offlineDialog.loading = true
  try {
    const next = await offlineReviewItem(target.itemId, offlineDialog.reason.trim() || undefined)
    applyStats(next)
    removeLocally(target.itemId)
    offlineDialog.visible = false
    showTip('已下架：' + target.title, 'success')
  } catch (e) {
    handleConflict(e, target.itemId)
    offlineDialog.visible = false
  } finally {
    offlineDialog.loading = false
  }
}

// ---------------- 批量 ----------------
function onSelectionChange(rows: ItemReview[]): void {
  selected.value = rows
}

async function batchAction(action: 'review' | 'offline'): Promise<void> {
  const rows = selected.value.filter((row) => row.itemId)
  if (!rows.length) {
    showTip('请先勾选要处置的商品', 'warning')
    return
  }
  let reason = ''
  if (action === 'offline') {
    try {
      const res = await ElMessageBox.prompt(
        `将下架 ${rows.length} 件商品，请填写原因（会记入审核留痕）`,
        '批量下架',
        { inputPlaceholder: '例如：标题含联系方式 / 疑似违禁品', inputValidator: (v) => !!v?.trim() || '必须填写原因' },
      )
      reason = res.value.trim()
    } catch {
      return // 用户取消
    }
  } else {
    try {
      await ElMessageBox.confirm(`确认这 ${rows.length} 件商品都看过且没有问题？`, '批量已审核', {
        type: 'warning',
      })
    } catch {
      return
    }
  }
  try {
    const result = await batchReview(action, { ids: rows.map((r) => r.itemId), reason: reason || undefined })
    if (result.failed) {
      showTip(`成功 ${result.success} 条，失败 ${result.failed} 条（可能已被别人处置）`, 'warning')
    } else {
      showTip(`已处理 ${result.success} 条`, 'success')
    }
    selected.value = []
    await Promise.all([load(false), loadStats()])
  } catch {
    // 拦截器已提示
  }
}

// ---------------- 详情 ----------------
async function openDetail(row: ItemReview): Promise<void> {
  detail.item = row
  detail.visible = true
  detail.loading = true
  try {
    // 列表里不带完整描述（可能很长），展开时按需拉一次
    detail.item = await fetchReviewDetail(row.itemId)
  } catch {
    // 拉不到就展示列表里已有的字段
  } finally {
    detail.loading = false
  }
}

function previewList(row: ItemReview | null): string[] {
  if (!row) return []
  if (row.images?.length) return row.images
  return row.coverUrl ? [row.coverUrl] : []
}

/** 补登记：兜底用，把"在售却没有审核记录"的商品补进队列 */
async function backfill(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '把「在售但没有审核记录」的商品补进待审队列（正常情况下不会用到，用于排查遗漏）',
      '补登记',
      { type: 'info' },
    )
  } catch {
    return
  }
  try {
    const added = await backfillReviews()
    showTip(added > 0 ? `补登记 ${added} 条` : '没有需要补登记的商品', 'success')
    await Promise.all([load(), loadStats()])
  } catch {
    // 拦截器已提示
  }
}

/** 已经在待审队列里等了多久（小时）。列表按最新在前，老条目靠这个提示"别忘了我" */
function waitHours(queuedAt?: string | null): number {
  if (!queuedAt) return 0
  const ts = new Date(queuedAt.replace(' ', 'T')).getTime()
  if (!Number.isFinite(ts)) return 0
  return Math.max(0, (Date.now() - ts) / 3_600_000)
}

useQuerySync(
  { scope, keyword, sellerId, pageNum, pageSize },
  { scope: 'pending' as ReviewScope, keyword: '', sellerId: '', pageNum: 1, pageSize: 20 },
  { onChange: () => void load(), aliases: { pageNum: 'page', pageSize: 'size' } },
)

onMounted(async () => {
  await Promise.all([load(), loadStats()])
  socket.connect()
})
</script>

<template>
  <div class="admin-page admin-review">
    <!-- ---------------- 顶部：实时状态 + 待审数字（一行看完）---------------- -->
    <div class="admin-stats">
      <div class="admin-stat is-primary">
        <p class="admin-stat__num">{{ pendingCount }}</p>
        <p class="admin-stat__label">待审核（每条都要看）</p>
      </div>
      <div class="admin-stat">
        <p class="admin-stat__num">{{ stats?.queuedToday ?? 0 }}</p>
        <p class="admin-stat__label">今日上架 / 改名</p>
      </div>
      <div class="admin-stat">
        <p class="admin-stat__num">{{ stats?.reviewedToday ?? 0 }}</p>
        <p class="admin-stat__label">今日已审核</p>
      </div>
      <div class="admin-stat">
        <p class="admin-stat__num">{{ stats?.onSaleTotal ?? 0 }}</p>
        <p class="admin-stat__label">在售商品总数</p>
      </div>
      <div class="admin-stat" title="商品在审核期间被卖家下架/卖出而退出列表的条数">
        <p class="admin-stat__num">{{ stats?.stalePending ?? 0 }}</p>
        <p class="admin-stat__label">已失效待审</p>
      </div>
    </div>

    <div class="panel">
      <div class="admin-toolbar">
        <!-- 实时连接状态：一个小圆点就够，不用整条彩色横幅 -->
        <span class="admin-review__live" :class="{ 'is-off': !socket.connected.value }">
          <i class="admin-review__dot" />
          {{ socket.connected.value ? '实时接收中' : '实时断开，重连中' }}
        </span>
        <el-tag v-if="stats && stats.onlineReviewers > 1" size="small" effect="plain" class="is-brand">
          {{ stats.onlineReviewers }} 人在线
        </el-tag>
        <transition name="el-fade-in">
          <el-tag
            v-if="newArrivals.length"
            size="small"
            class="is-warn admin-review__bell"
            @click="goTop"
          >
            {{ newArrivals.length }} 条新上架，点此查看
          </el-tag>
        </transition>
        <span class="admin-toolbar__spacer" />
        <span class="admin-toolbar__count">共 {{ total }} 条</span>
      </div>

      <div class="admin-toolbar">
        <el-radio-group :model-value="scope" @change="(v: any) => changeScope(v)">
          <el-radio-button value="pending">待审核</el-radio-button>
          <el-radio-button value="done">已处置</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="keyword"
          placeholder="搜索标题 / 描述"
          clearable
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="sellerId"
          placeholder="卖家 ID"
          clearable
          style="width: 104px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" text @click="search">刷新</el-button>
        <el-button v-if="hasFilter" text @click="reset">重置</el-button>
        <span class="admin-toolbar__spacer" />
        <el-button text size="small" class="admin-review__backfill" @click="backfill">
          补登记漏掉的商品
        </el-button>
      </div>

      <!-- 说明按需展开：常驻会一直抢注意力，但完全删掉审核员又不知道这页在干什么 -->
      <div class="admin-help">
        <span class="admin-help__toggle" @click="showHelp = !showHelp">
          <el-icon><QuestionFilled /></el-icon>
          这一页怎么用
          <el-icon><component :is="showHelp ? ArrowUp : ArrowDown" /></el-icon>
        </span>
        <div v-if="showHelp" class="admin-help__body">
          商品上架<strong>不拦截</strong>（发布即在售），这一页用来确认<strong>每条上架都被人工看过</strong>：
          点「已审核」后本条消失并归档；觉得不合适直接「下架」。
          卖家在售期间<strong>改名/改描述</strong>会重新回到这里。
          违规商品二次申请上架属于审核，请到「审核工作台」处理。
        </div>
      </div>

      <!-- ---------------- 批量操作条 ---------------- -->
      <div v-if="selected.length" class="admin-review__bulk">
        <span class="admin-review__bulk-text">已选 {{ selected.length }} 条</span>
        <el-button type="primary" size="small" :icon="Select" @click="batchAction('review')">
          批量已审核
        </el-button>
        <el-button size="small" @click="batchAction('offline')">批量下架</el-button>
        <el-button text size="small" @click="selected = []">取消选择</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        row-key="itemId"
        max-height="620"
        class="admin-table"
        :row-class-name="({ row }: any) => (row.itemId === flashItemId ? 'admin-review__row-flash' : '')"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="42" :selectable="(row: ItemReview) => isPendingScope && row.status === 0" />
        <el-table-column label="商品" min-width="320">
          <template #default="{ row }">
            <div class="admin-review__item">
              <el-image
                :src="row.coverUrl || row.images?.[0]"
                :preview-src-list="previewList(row)"
                preview-teleported
                hide-on-click-modal
                fit="cover"
                class="admin-review__cover"
              >
                <template #error>
                  <div class="admin-review__cover-empty">无图</div>
                </template>
              </el-image>
              <div class="admin-review__info">
                <p class="admin-review__title" :title="row.title">
                  #{{ row.itemId }} {{ row.title }}
                </p>
                <p class="text-muted admin-review__sub">
                  ¥{{ formatPrice(row.price) }} · {{ row.categoryName || '未分类' }} ·
                  {{ row.preferPointName || '未选点' }}
                </p>
                <p
                  v-if="row.contentChanged && row.titleSnapshot && row.titleSnapshot !== row.title"
                  class="admin-review__renamed"
                >
                  原名称：{{ row.titleSnapshot }}
                </p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="进入原因" width="146">
          <template #default="{ row }">
            <div class="admin-review__reason-cell">
              <!-- 标注一律中性灰，只有"需要警惕"的才是彩色（原来 4 种颜色叠一列最花） -->
              <span class="admin-review__label">{{ row.reviewTypeText }}</span>
              <span v-if="row.contentChanged" class="admin-review__label is-warn">已改名</span>
              <span v-if="row.violationBefore" class="admin-review__label is-danger">曾违规</span>
              <span v-if="row.reviewRound && row.reviewRound > 1" class="text-muted admin-review__round">
                第 {{ row.reviewRound }} 次
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="卖家" min-width="126">
          <template #default="{ row }">
            <p class="admin-review__sub">{{ row.sellerNickname || '—' }}</p>
            <p class="text-muted admin-review__sub">
              #{{ row.sellerId }} · 信用 {{ row.sellerCreditScore ?? '—' }}
              <span v-if="row.rejectCount" class="admin-review__label is-danger">
                历史驳回 {{ row.rejectCount }}
              </span>
            </p>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <span class="admin-review__sub">{{ row.itemStatusText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进入时间" width="150">
          <template #default="{ row }">
            <span class="admin-review__sub" :title="formatDateTime(row.queuedAt)">
              {{ formatRelativeTime(row.queuedAt) }}
            </span>
            <!-- 等待时长：列表按最新在前，老条目会被不断顶下去，靠这个看"压了多久"。
                 只在真超时才提醒（6 小时以内不用占用注意力） -->
            <span
              v-if="row.status === 0 && waitHours(row.queuedAt) >= 24"
              class="admin-review__label is-danger"
            >
              已等 {{ Math.floor(waitHours(row.queuedAt) / 24) }} 天
            </span>
            <span
              v-else-if="row.status === 0 && waitHours(row.queuedAt) >= 6"
              class="admin-review__label is-warn"
            >
              已等 {{ Math.floor(waitHours(row.queuedAt)) }} 小时
            </span>
            <p v-if="row.status === 1" class="text-muted admin-review__sub">
              {{ row.reviewActionText }} · {{ formatRelativeTime(row.reviewedAt) }}
            </p>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <div class="admin-ops">
              <template v-if="row.status === 0 && row.actionable">
                <el-button
                  type="primary"
                  link
                  size="small"
                  :loading="busyItems.includes(row.itemId)"
                  @click="markOne(row)"
                >
                  已审核
                </el-button>
                <el-button type="danger" link size="small" @click="openOffline(row)">下架</el-button>
              </template>
              <span v-else-if="row.status === 1" class="admin-review__label">{{ row.reviewActionText }}</span>
              <span v-else class="text-muted admin-review__sub">已不在售</span>
              <el-button link size="small" @click="openDetail(row)">详情</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="
              isPendingScope && !hasFilter
                ? '没有待审核的商品 —— 每条上架都已经看过了'
                : '没有符合条件的记录'
            "
          />
        </template>
      </el-table>

      <div class="admin-review__footer">
        <el-button text size="small" class="admin-review__backfill" @click="backfill">
          补登记在售但漏掉的商品
        </el-button>
        <el-pagination
          v-if="total > 0"
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          layout="prev, pager, next, sizes, total"
          :total="total"
          :page-sizes="PAGE_SIZES"
          @current-change="() => load()"
          @size-change="search"
        />
      </div>
    </div>

    <!-- ---------------- 详情抽屉 ---------------- -->
    <el-drawer v-model="detail.visible" title="上架审核 · 商品详情" size="540px">
      <template v-if="detail.item">
        <div v-loading="detail.loading">
          <div class="admin-review__gallery">
            <el-image
              v-for="(url, index) in previewList(detail.item)"
              :key="url + index"
              :src="url"
              :preview-src-list="previewList(detail.item)"
              :initial-index="index"
              preview-teleported
              hide-on-click-modal
              fit="cover"
              class="admin-review__gallery-img"
            >
              <template #error>
                <div class="admin-review__cover-empty">加载失败</div>
              </template>
            </el-image>
            <el-empty v-if="!previewList(detail.item).length" description="没有图片" :image-size="60" />
          </div>

          <p class="admin-review__detail-title">{{ detail.item.title }}</p>
          <p v-if="detail.item.titleSnapshot && detail.item.titleSnapshot !== detail.item.title" class="admin-review__renamed">
            入队时名称：{{ detail.item.titleSnapshot }}
          </p>
          <p class="admin-review__detail-price">¥{{ formatPrice(detail.item.price) }}</p>

          <el-descriptions :column="1" border class="admin-review__detail-desc">
            <el-descriptions-item label="进入原因">
              <span class="admin-review__label">{{ detail.item.reviewTypeText }}</span>
              <span v-if="detail.item.contentChanged" class="admin-review__label is-warn admin-review__ml">
                已改名
              </span>
              <span v-if="detail.item.violationBefore" class="admin-review__label is-danger admin-review__ml">
                曾违规
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="商品状态">
              {{ detail.item.itemStatusText }}
            </el-descriptions-item>
            <el-descriptions-item label="审核状态">
              {{ detail.item.statusText }}
              <span v-if="detail.item.reviewerNickname" class="text-muted">
                · {{ detail.item.reviewerNickname }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="分类 / 成色">
              {{ detail.item.categoryName || '未分类' }}
            </el-descriptions-item>
            <el-descriptions-item label="校区 / 面交点">
              {{ detail.item.campusName || '—' }} · {{ detail.item.preferPointName || '未选点' }}
            </el-descriptions-item>
            <el-descriptions-item label="卖家">
              {{ detail.item.sellerNickname || '—' }}（#{{ detail.item.sellerId }}）
              · 信用 {{ detail.item.sellerCreditScore ?? '—' }}
              · {{ detail.item.sellerPhone || '手机号不可用' }}
            </el-descriptions-item>
            <el-descriptions-item label="上架时间">
              {{ formatDateTime(detail.item.publishedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="进入审核">
              {{ formatDateTime(detail.item.queuedAt) }}
              <span v-if="detail.item.reviewRound && detail.item.reviewRound > 1" class="text-muted">
                （第 {{ detail.item.reviewRound }} 次）
              </span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.item.expireAt" label="到期时间">
              {{ formatDateTime(detail.item.expireAt) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.item.note" label="处置备注">
              {{ detail.item.note }}
            </el-descriptions-item>
          </el-descriptions>

          <p class="admin-review__detail-sub">商品描述</p>
          <p class="admin-review__detail-body">
            {{ detail.item.description || '（卖家没有填写描述）' }}
          </p>

          <div class="admin-review__detail-actions">
            <a class="admin-review__link" :href="'/item/' + detail.item.itemId" target="_blank" rel="noopener">
              在新标签打开学生端详情页
            </a>
            <div v-if="detail.item.status === 0 && detail.item.actionable" class="admin-review__detail-btns">
              <el-button
                type="primary"
                :loading="busyItems.includes(detail.item.itemId)"
                @click="markOne(detail.item)"
              >
                已审核
              </el-button>
              <el-button type="danger" plain @click="openOffline(detail.item)">下架</el-button>
            </div>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- ---------------- 下架弹窗 ---------------- -->
    <el-dialog v-model="offlineDialog.visible" title="下架商品" width="500px">
      <p class="text-muted admin-review__dialog-target">商品：{{ offlineDialog.target?.title }}</p>
      <el-input
        v-model="offlineDialog.reason"
        type="textarea"
        :rows="3"
        maxlength="120"
        show-word-limit
        placeholder="选填。留空按「上架审核：内容不符合规范，已下架」记录，会写入审核留痕"
      />
      <template #footer>
        <el-button @click="offlineDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="offlineDialog.loading" @click="submitOffline">确认下架</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
// 视觉规则统一在 styles/admin.scss（.admin-page / .admin-stats / .admin-table / .admin-help）。
// 这里只留本页独有的东西。
.admin-review {
  /* ---------------- 实时状态：一个小圆点 ---------------- */
  &__live {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--text-3);
    font-size: 12px;

    &.is-off {
      color: var(--accent-600);
    }
  }

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--brand-500);
  }

  &__live.is-off &__dot {
    background: var(--accent-500);
  }

  &__bell {
    cursor: pointer;
  }

  /* ---------------- 批量操作条 ---------------- */
  &__bulk {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
    padding: 8px 12px;
    border: 1px solid var(--brand-200);
    border-radius: var(--r-md);
    background: var(--brand-50);
  }

  &__bulk-text {
    font-size: 13px;
    font-weight: 600;
  }

  /* ---------------- 行内标注：中性默认，危险/提醒才上色 ---------------- */
  &__label {
    display: inline-block;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--surface-2);
    color: var(--text-2);
    font-size: 11.5px;
    line-height: 18px;
    white-space: nowrap;

    &.is-danger {
      background: #fef2f4;
      color: var(--el-color-danger);
    }

    &.is-warn {
      background: #fff8eb;
      color: #b45309;
    }
  }

  /* ---------------- 商品单元 ---------------- */
  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__cover {
    width: 44px;
    height: 44px;
    border-radius: 6px;
    flex-shrink: 0;
  }

  &__cover-empty {
    display: grid;
    width: 100%;
    height: 100%;
    place-items: center;
    background: var(--surface-2);
    color: var(--text-3);
    font-size: 10px;
  }

  &__info {
    min-width: 0;

    p {
      margin: 0;
    }
  }

  &__title {
    overflow: hidden;
    font-size: 13.5px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__sub {
    font-size: 12px;
    line-height: 1.55;
  }

  &__renamed {
    color: var(--text-3);
    font-size: 11.5px;
    line-height: 1.5;
    text-decoration: line-through;
  }

  &__reason-cell {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-wrap: wrap;
  }

  &__round {
    font-size: 11.5px;
  }

  /* ---------------- 底部 ---------------- */
  &__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 12px;
    flex-wrap: wrap;
  }

  &__backfill {
    color: var(--text-3);
    font-size: 12px;

    &:hover {
      color: var(--brand-600);
    }
  }

  /* ---------------- 详情抽屉 ---------------- */
  &__dialog-target {
    margin-bottom: 12px;
    font-size: 13px;
  }

  &__gallery {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    margin-bottom: 16px;
  }

  &__gallery-img {
    width: 96px;
    height: 96px;
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
  }

  &__detail-title {
    font-size: 16px;
    font-weight: 700;
    line-height: 1.4;
  }

  &__detail-price {
    margin-top: 4px;
    color: var(--accent-600);
    font-size: 19px;
    font-weight: 700;
  }

  &__detail-desc {
    margin-top: 14px;
  }

  &__detail-sub {
    margin: 18px 0 6px;
    font-size: 13.5px;
    font-weight: 600;
  }

  &__detail-body {
    color: var(--text-2);
    font-size: 13px;
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__detail-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 20px;
    padding-top: 14px;
    border-top: 1px solid var(--line);
    flex-wrap: wrap;
  }

  &__detail-btns {
    display: flex;
    gap: 8px;
  }

  &__link {
    color: var(--brand-600);
    font-size: 13px;

    &:hover {
      text-decoration: underline;
    }
  }

  &__ml {
    margin-left: 6px;
  }
}

// 新条目到达时闪一下（弱化过：只留一行淡黄底，不再整体缩放动画）
:deep(.admin-review__row-flash) td {
  background: #fff8eb !important;
  transition: background 0.4s;
}
</style>