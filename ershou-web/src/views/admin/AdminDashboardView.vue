<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import { Aim, CircleCheck, DocumentChecked, Goods, List, Refresh, TrendCharts, User, Warning } from '@element-plus/icons-vue'
import { fetchAdminDashboard, fetchAuditLogs } from '@/api/admin'
import type { AdminDashboard, AuditLogItem } from '@/api/admin'
import { formatRelativeTime } from '@/utils/format'
import { actionTagType, actionText, targetText } from '@/utils/adminDict'

const router = useRouter()
const loading = ref(false)
const data = ref<AdminDashboard | null>(null)
const recent = ref<AuditLogItem[]>([])

interface StatCard {
  key: string
  label: string
  value: number
  hint: string
  path: string
  tone: 'brand' | 'warning' | 'success' | 'danger'
  icon: Component
}

const todoCards = computed<StatCard[]>(() => {
  const d = data.value
  if (!d) return []
  return [
    { key: 'pendingItems', label: '待审商品', value: d.pendingItems, hint: 'AI 机审转人工，等待处置', path: '/admin/audit', tone: 'warning', icon: DocumentChecked },
    { key: 'pendingPoints', label: '待审点位', value: d.pendingPoints, hint: '同学提交的面交点', path: '/admin/audit?tab=point', tone: 'warning', icon: Aim },
  ]
})

const statusCards = computed<StatCard[]>(() => {
  const d = data.value
  if (!d) return []
  return [
    { key: 'onSaleItems', label: '在售商品', value: d.onSaleItems, hint: '当前可被检索到', path: '/admin/items?status=2', tone: 'success', icon: CircleCheck },
    { key: 'rejectedItems', label: '审核驳回', value: d.rejectedItems, hint: '卖家可修改后重新发布', path: '/admin/items?status=6', tone: 'danger', icon: Warning },
    { key: 'bannedUsers', label: '封禁用户', value: d.bannedUsers, hint: '状态为封禁的账号', path: '/admin/users?status=3', tone: 'danger', icon: User },
  ]
})

const totalCards = computed<StatCard[]>(() => {
  const d = data.value
  if (!d) return []
  return [
    { key: 'totalItems', label: '商品总数', value: d.totalItems, hint: '含各状态', path: '/admin/items', tone: 'brand', icon: Goods },
    { key: 'totalUsers', label: '用户总数', value: d.totalUsers, hint: '轻注册账号', path: '/admin/users', tone: 'brand', icon: List },
    { key: 'todayOperations', label: '今日操作', value: d.todayOperations, hint: '审核 / 封禁 / 信用调整', path: '/admin/logs', tone: 'brand', icon: TrendCharts },
  ]
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const [dash, logs] = await Promise.all([
      fetchAdminDashboard(),
      fetchAuditLogs({ pageNum: 1, pageSize: 6 }),
    ])
    data.value = dash
    recent.value = logs.records ?? []
  } catch {
    data.value = null
    recent.value = []
  } finally {
    loading.value = false
  }
}

function open(path: string): void {
  router.push(path)
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="dash">
    <div class="panel dash__head">
      <div class="panel-title">今日待办</div>
      <p class="text-muted dash__lead">
        审核工作台是运营主入口：AI 机审负责拦截违禁内容，可疑内容转人工后在这里处置。
      </p>
      <div class="dash__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" @click="open('/admin/audit')">进入审核工作台</el-button>
      </div>
    </div>

    <div class="dash__grid dash__grid--todo">
      <button
        v-for="card in todoCards"
        :key="card.key"
        type="button"
        class="dash__card dash__card--lg"
        :class="'dash__card--' + card.tone"
        @click="open(card.path)"
      >
        <span class="dash__icon"><el-icon><component :is="card.icon" /></el-icon></span>
        <span class="dash__value">{{ card.value }}</span>
        <span class="dash__label">{{ card.label }}</span>
        <span class="dash__hint">{{ card.hint }}</span>
      </button>
    </div>

    <div class="panel dash__section">
      <div class="panel-title">平台状态</div>
      <div class="dash__grid">
        <button
          v-for="card in statusCards"
          :key="card.key"
          type="button"
          class="dash__card"
          :class="'dash__card--' + card.tone"
          @click="open(card.path)"
        >
          <span class="dash__icon"><el-icon><component :is="card.icon" /></el-icon></span>
          <span class="dash__value dash__value--sm">{{ card.value }}</span>
          <span class="dash__label">{{ card.label }}</span>
          <span class="dash__hint">{{ card.hint }}</span>
        </button>
      </div>
    </div>

    <div class="dash__cols">
      <div class="panel dash__section">
        <div class="panel-title">累计数据</div>
        <div class="dash__grid dash__grid--mini">
          <button
            v-for="card in totalCards"
            :key="card.key"
            type="button"
            class="dash__card dash__card--mini"
            :class="'dash__card--' + card.tone"
            @click="open(card.path)"
          >
            <span class="dash__value dash__value--sm">{{ card.value }}</span>
            <span class="dash__label">{{ card.label }}</span>
          </button>
        </div>
      </div>

      <div class="panel dash__section">
        <div class="dash__recent-head">
          <div class="panel-title">最近操作</div>
          <el-button text type="primary" @click="open('/admin/logs')">查看全部</el-button>
        </div>
        <ul v-if="recent.length" class="dash__recent">
          <li v-for="log in recent" :key="log.id" class="dash__recent-item">
            <el-tag size="small" :type="actionTagType(log.action)" effect="plain">
              {{ actionText(log.action) }}
            </el-tag>
            <span class="dash__recent-target">{{ targetText(log) }}</span>
            <span class="dash__recent-by">{{ log.operatorNickname || ('#' + log.operatorId) }}</span>
            <span class="dash__recent-time">{{ formatRelativeTime(log.createdAt) }}</span>
          </li>
        </ul>
        <el-empty v-else description="还没有操作记录" :image-size="70" />
      </div>
    </div>

    <div class="panel dash__section">
      <div class="panel-title">运营职责速览</div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="审核工作台">
          商品审核（通过 / 驳回 / 批量处置）与用户自定义面交点审核；驳回使用模板化原因。
        </el-descriptions-item>
        <el-descriptions-item label="商品管理">
          按状态检索全部商品，对违规在售商品执行强制下架（状态置为"违规下架"）。
        </el-descriptions-item>
        <el-descriptions-item label="用户管理">
          查询用户、封禁 / 解封 / 限权、人工调整信用分（写入 credit_log 流水）。
        </el-descriptions-item>
        <el-descriptions-item label="操作日志">
          每次后台写操作都会落库（操作人、动作、目标、变更前后、IP），可回溯。
        </el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<style scoped lang="scss">
.dash {
  &__head {
    margin-bottom: 16px;
  }

  &__lead {
    max-width: 720px;
  }

  &__actions {
    display: flex;
    gap: 10px;
    margin-top: 14px;
  }

  &__section {
    margin-bottom: 16px;
  }

  &__cols {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
    gap: 16px;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 12px;

    &--todo {
      grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
      margin-bottom: 16px;
    }

    &--mini {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }

  &__card {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 14px 16px;
    border: 1px solid var(--line);
    border-left: 4px solid var(--brand-500);
    border-radius: var(--r-lg);
    background: var(--surface);
    box-shadow: var(--shadow-xs);
    text-align: left;
    cursor: pointer;
    transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;

    &:hover {
      transform: translateY(-2px);
      box-shadow: var(--shadow-md);
    }

    &--lg {
      padding: 18px 20px;
    }

    &--mini {
      padding: 12px 14px;
    }

    &--warning {
      border-left-color: #f59e0b;
    }

    &--success {
      border-left-color: var(--brand-500);
    }

    &--danger {
      border-left-color: #f5455c;
    }

    &--brand {
      border-left-color: #6366f1;
    }
  }

  &__icon {
    position: absolute;
    top: 14px;
    right: 14px;
    display: grid;
    width: 30px;
    height: 30px;
    place-items: center;
    border-radius: 9px;
    background: var(--surface-2);
    color: var(--text-3);
    font-size: 16px;
  }

  &__value {
    font-size: 30px;
    font-weight: 800;
    line-height: 1.1;

    &--sm {
      font-size: 22px;
    }
  }

  &__label {
    font-size: 14px;
    font-weight: 600;
  }

  &__hint {
    color: var(--text-3);
    font-size: 12px;
  }

  &__recent-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;

    .panel-title {
      margin-bottom: 0;
    }
  }

  &__recent {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__recent-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 0;
    border-bottom: 1px dashed var(--line);
    font-size: 13px;

    &:last-child {
      border-bottom: none;
    }
  }

  &__recent-target {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    color: var(--text-2);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__recent-by {
    color: var(--text-3);
    font-size: 12px;
  }

  &__recent-time {
    width: 76px;
    color: var(--text-3);
    font-size: 12px;
    text-align: right;
  }
}

@media (max-width: 1000px) {
  .dash__cols {
    grid-template-columns: 1fr;
  }
}
</style>
