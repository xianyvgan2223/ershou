<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  Aim,
  CircleCheck,
  DocumentChecked,
  Goods,
  List,
  Refresh,
  TrendCharts,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { fetchAuditLogs } from '@/api/admin'
import type { AuditLogItem } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { formatRelativeTime } from '@/utils/format'
import { actionText, targetText } from '@/utils/adminDict'

const router = useRouter()
const adminStore = useAdminStore()
const recent = ref<AuditLogItem[]>([])
const recentLoading = ref(false)

interface StatCard {
  key: string
  label: string
  value: number
  hint: string
  path: string
  tone: 'brand' | 'warning' | 'success' | 'danger'
  icon?: Component
}

const dashboard = computed(() => adminStore.dashboard)

const todoCards = computed<StatCard[]>(() => {
  const d = dashboard.value
  if (!d) return []
  return [
    { key: 'pendingItems', label: '待审商品', value: d.pendingItems, hint: 'AI 机审转人工，等待处置', path: '/admin/audit', tone: 'warning', icon: DocumentChecked },
    { key: 'pendingPoints', label: '待审点位', value: d.pendingPoints, hint: '同学提交的面交点', path: '/admin/audit?tab=point&pointStatus=0', tone: 'warning', icon: Aim },
  ]
})

const statusCards = computed<StatCard[]>(() => {
  const d = dashboard.value
  if (!d) return []
  return [
    { key: 'onSaleItems', label: '在售商品', value: d.onSaleItems, hint: '当前可被检索到', path: '/admin/items?status=2', tone: 'success', icon: CircleCheck },
    { key: 'rejectedItems', label: '审核未通过', value: d.rejectedItems, hint: '卖家可修改后重新发布', path: '/admin/items?status=6', tone: 'danger', icon: Warning },
    { key: 'bannedUsers', label: '封禁用户', value: d.bannedUsers, hint: '状态为封禁的账号', path: '/admin/users?status=3', tone: 'danger', icon: User },
    { key: 'disabledPoints', label: '已下架点位', value: d.disabledPoints ?? 0, hint: '停用后不再出现在地图', path: '/admin/audit?tab=point&pointStatus=-1&pointMapStatus=0', tone: 'brand', icon: Aim },
  ]
})

const orderCards = computed<StatCard[]>(() => {
  const d = dashboard.value
  if (!d) return []
  return [
    { key: 'activeOrders', label: '进行中面交', value: d.activeOrders ?? 0, hint: '待卖家确认 + 待面交', path: '/admin/orders?status=1', tone: 'warning' },
    { key: 'todayOrders', label: '今日新增', value: d.todayOrders ?? 0, hint: '今天创建的面交单', path: '/admin/orders', tone: 'brand' },
    { key: 'riskOrders', label: '爽约 / 纠纷', value: d.riskOrders ?? 0, hint: '需要人工跟进的单', path: '/admin/orders?status=7', tone: 'danger' },
    { key: 'doneOrders', label: '已完成', value: d.doneOrders ?? 0, hint: '双方都确认成交', path: '/admin/orders?status=3', tone: 'success' },
  ]
})

const totalCards = computed<StatCard[]>(() => {
  const d = dashboard.value
  if (!d) return []
  return [
    { key: 'totalItems', label: '商品总数', value: d.totalItems, hint: '含各状态', path: '/admin/items', tone: 'brand', icon: Goods },
    { key: 'totalUsers', label: '用户总数', value: d.totalUsers, hint: '轻注册账号', path: '/admin/users', tone: 'brand', icon: List },
    { key: 'totalOrders', label: '面交单总数', value: d.totalOrders ?? 0, hint: '含已取消', path: '/admin/orders', tone: 'brand', icon: List },
    { key: 'todayOperations', label: '今日操作', value: d.todayOperations, hint: '审核 / 封禁 / 信用调整', path: '/admin/logs', tone: 'brand', icon: TrendCharts },
  ]
})

async function loadRecent(): Promise<void> {
  recentLoading.value = true
  try {
    const logs = await fetchAuditLogs({ pageNum: 1, pageSize: 6 })
    recent.value = logs.records ?? []
  } catch {
    recent.value = []
  } finally {
    recentLoading.value = false
  }
}

/** 只重拉最近操作；概览统计走 store 的强制刷新 */
async function reload(): Promise<void> {
  await Promise.all([adminStore.refresh(), loadRecent()])
}

/**
 * 是否为"破坏性/负面"操作 —— 只有这些在「最近操作」里标红。
 * 一列里红绿相间是最花的排版之一，而且"审核通过"用绿色、"撤回"用红色
 * 并不能帮运营更快看懂，反而让真正需要注意的"强制下架"淹没在颜色里。
 */
function isDestructive(action?: string | null): boolean {
  if (!action) return false
  return /offline|delete|ban|reject|force/.test(action)
}

function open(path: string): void {
  router.push(path)
}

onMounted(() => {
  // Shell 也调 load()，store 内部有 2 秒复用窗口，同时挂载只打一次概览接口
  adminStore.load()
  loadRecent()
})
</script>

<template>
  <div v-loading="adminStore.loading" class="admin-page dash">
    <!-- ---------------- 待办：需要我动手的数字（品牌色）---------------- -->
    <div class="admin-stats">
      <button
        v-for="card in todoCards"
        :key="card.key"
        type="button"
        class="admin-stat dash__card"
        :class="{ 'is-primary': card.value > 0 }"
        @click="open(card.path)"
      >
        <p class="admin-stat__num">{{ card.value }}</p>
        <p class="admin-stat__label">{{ card.label }}</p>
      </button>
      <button
        v-for="card in orderCards"
        :key="card.key"
        type="button"
        class="admin-stat dash__card"
        @click="open(card.path)"
      >
        <p class="admin-stat__num">{{ card.value }}</p>
        <p class="admin-stat__label">{{ card.label }}</p>
      </button>
    </div>

    <div class="panel">
      <div class="dash__recent-head">
        <div class="panel-title">平台状态</div>
        <!-- 刷新入口：原来挂在"今日待办"那块大面板上，面板去掉后挪到这里 -->
        <el-button :icon="Refresh" text size="small" :loading="adminStore.loading" @click="reload">
          刷新
        </el-button>
      </div>
      <div class="dash__row">
        <button
          v-for="card in statusCards"
          :key="card.key"
          type="button"
          class="dash__row-item"
          :class="{ 'is-alert': card.tone === 'danger' && card.value > 0 }"
          @click="open(card.path)"
        >
          <span class="dash__row-value">{{ card.value }}</span>
          <span class="dash__row-label">{{ card.label }}</span>
          <span class="dash__row-hint">{{ card.hint }}</span>
        </button>
      </div>
    </div>

    <div class="dash__cols">
      <div class="panel">
        <div class="panel-title">累计数据</div>
        <div class="dash__row dash__row--wrap">
          <button
            v-for="card in totalCards"
            :key="card.key"
            type="button"
            class="dash__row-item"
            @click="open(card.path)"
          >
            <span class="dash__row-value">{{ card.value }}</span>
            <span class="dash__row-label">{{ card.label }}</span>
          </button>
        </div>
      </div>

      <div class="panel">
        <div class="dash__recent-head">
          <div class="panel-title">最近操作</div>
          <el-button text type="primary" @click="open('/admin/logs')">查看全部</el-button>
        </div>
        <ul v-if="recent.length" class="dash__recent">
          <li v-for="log in recent" :key="log.id" class="dash__recent-item">
            <!-- 操作名一律中性：一列里红绿相间最花，只有真"破坏性"的才标红 -->
            <span class="dash__recent-action" :class="{ 'is-danger': isDestructive(log.action) }">
              {{ actionText(log.action) }}
            </span>
            <span class="dash__recent-target">{{ targetText(log) }}</span>
            <span class="dash__recent-by">{{ log.operatorNickname || ('#' + log.operatorId) }}</span>
            <span class="dash__recent-time">{{ formatRelativeTime(log.createdAt) }}</span>
          </li>
        </ul>
        <el-empty v-else :description="recentLoading ? '加载中…' : '还没有操作记录'" :image-size="70" />
      </div>
    </div>

    <div class="panel dash__section">
      <div class="panel-title">运营职责速览</div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="审核工作台">
          商品审核（通过 / 驳回 / 批量处置）与面交点审核；驳回使用模板化原因。
        </el-descriptions-item>
        <el-descriptions-item label="商品管理">
          按状态 / 卖家检索全部商品，查看完整图文详情，对违规商品强制下架。
        </el-descriptions-item>
        <el-descriptions-item label="面交单管理">
          按状态 / 单号 / 买卖双方检索成交记录，展开时间线排查爽约与纠纷。
        </el-descriptions-item>
        <el-descriptions-item label="用户管理">
          查询用户、封禁 / 解封 / 限权、人工调整信用分（写入 credit_log 流水）。
        </el-descriptions-item>
        <el-descriptions-item label="操作日志">
          每次后台写操作都会落库（操作人、动作、目标、变更前后、IP），可按动作与时间范围回溯。
        </el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<style scoped lang="scss">
// 视觉规则统一在 styles/admin.scss。这里只留本页独有的东西。
//
// 整改前的问题：每个统计卡左侧一根彩色竖条（橙/紫/红/蓝/绿），
// 一屏七八种颜色 —— 而这些颜色**不承载信息**（数字本身已经说明了一切）
// 只会让人眼花。现在统一：中性卡 + 待办数字用品牌色 + 只有"真需要注意"的标红。
.dash {
  &__card {
    display: block;
    width: 100%;
    text-align: left;
    cursor: pointer;
    font-family: inherit;

    &:hover {
      border-color: var(--line-strong);
    }
  }

  // 待办区点击后跳到对应列表
  &__row {
    display: flex;
    gap: 8px;
    flex-wrap: nowrap;

    &--wrap {
      flex-wrap: wrap;
    }
  }

  &__row-item {
    flex: 1;
    min-width: 120px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    background: var(--surface);
    cursor: pointer;
    text-align: left;
    font-family: inherit;

    &:hover {
      border-color: var(--line-strong);
      background: var(--surface-2);
    }

    // 只有"违规/封禁"这类需要跟进的数字标红，其余保持中性
    &.is-alert .dash__row-value {
      color: var(--el-color-danger);
    }
  }

  &__row-value {
    display: block;
    color: var(--text-1);
    font-size: 18px;
    font-weight: 700;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
  }

  &__row-label {
    display: block;
    color: var(--text-2);
    font-size: 12.5px;
  }

  &__row-hint {
    display: block;
    color: var(--text-3);
    font-size: 11px;
    line-height: 1.5;
  }

  &__cols {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
    gap: 12px;
    margin-top: 12px;
    align-items: start;
  }

  &__recent-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;

    .panel-title {
      margin-bottom: 0;
    }
  }

  &__recent {
    margin: 8px 0 0;
    padding: 0;
    list-style: none;
  }

  &__recent-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 0;
    border-bottom: 1px solid var(--line);
    font-size: 12.5px;

    &:last-child {
      border-bottom: none;
    }
  }

  // 操作名：中性小标签，破坏性操作才标红
  &__recent-action {
    flex-shrink: 0;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--surface-2);
    color: var(--text-2);
    font-size: 11.5px;

    &.is-danger {
      background: #fef2f4;
      color: var(--el-color-danger);
    }
  }

  &__recent-target {
    overflow: hidden;
    min-width: 0;
    flex: 1;
    color: var(--text-2);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__recent-by,
  &__recent-time {
    flex-shrink: 0;
    color: var(--text-3);
    font-size: 11.5px;
  }
}

@media (max-width: 1100px) {
  .dash__cols {
    grid-template-columns: 1fr;
  }
}
</style>