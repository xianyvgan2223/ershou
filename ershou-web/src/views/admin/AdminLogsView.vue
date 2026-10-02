<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { fetchAuditLogs } from '@/api/admin'
import type { AuditLogItem } from '@/api/admin'
import { formatDateTime, formatRelativeTime } from '@/utils/format'
import {
  ACTION_OPTIONS,
  MODULE_OPTIONS,
  actionTagType,
  actionText,
  formatJson,
  formatJsonPretty,
  moduleText,
  targetText,
} from '@/utils/adminDict'
import { useQuerySync } from '@/composables/useQuerySync'

/**
 * 操作审计日志。
 *
 * 以前只能按模块和操作人 ID 筛，追一件事时得靠翻页找。现在补了动作类型与时间范围
 * （后端 AdminLogService 支持 action / startTime / endTime），时间只给日期时
 * 后端会把结束日补到 23:59:59，所以"查今天"不会漏数据。
 */

const PAGE_SIZES = [20, 50, 100]

const moduleFilter = ref('')
const actionFilter = ref('')
const operatorId = ref('')
const startDate = ref('')
const endDate = ref('')
const pageNum = ref(1)
const pageSize = ref(20)

const loading = ref(false)
const list = ref<AuditLogItem[]>([])
const total = ref(0)

const detail = reactive({ visible: false, log: null as AuditLogItem | null })

const hasFilter = computed(
  () =>
    !!moduleFilter.value ||
    !!actionFilter.value ||
    !!operatorId.value.trim() ||
    !!startDate.value ||
    !!endDate.value,
)

/** 把 Date 按本地时区格式化成 yyyy-MM-dd（不能用 toISOString，那是 UTC，会差一天） */
function toDateText(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function applyQuickRange(days: number): void {
  const today = new Date()
  const from = new Date(today)
  from.setDate(from.getDate() - (days - 1))
  startDate.value = toDateText(from)
  endDate.value = toDateText(today)
  search()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await fetchAuditLogs({
      module: moduleFilter.value || undefined,
      action: actionFilter.value || undefined,
      operatorId: toId(operatorId.value),
      startTime: startDate.value || undefined,
      endTime: endDate.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    })
    list.value = res.records ?? []
    total.value = res.total ?? 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function toId(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const num = Number(trimmed)
  return Number.isInteger(num) && num > 0 ? num : undefined
}

function search(): void {
  pageNum.value = 1
  load()
}

function reset(): void {
  moduleFilter.value = ''
  actionFilter.value = ''
  operatorId.value = ''
  startDate.value = ''
  endDate.value = ''
  pageNum.value = 1
  load()
}

function openDetail(log: AuditLogItem): void {
  detail.log = log
  detail.visible = true
}

useQuerySync(
  { moduleFilter, actionFilter, operatorId, startDate, endDate, pageNum, pageSize },
  {
    moduleFilter: '',
    actionFilter: '',
    operatorId: '',
    startDate: '',
    endDate: '',
    pageNum: 1,
    pageSize: 20,
  },
  {
    onChange: load,
    aliases: {
      moduleFilter: 'module',
      actionFilter: 'action',
      startDate: 'from',
      endDate: 'to',
      pageNum: 'page',
      pageSize: 'size',
    },
  },
)

onMounted(load)
</script>

<template>
  <div class="admin-page admin-logs">
    <div class="panel">
      <div class="panel-title">操作审计日志</div>
      <p class="text-muted admin-logs__intro">
        记录运营的每一次写操作（操作人、模块、动作、目标、变更前后、IP），用于事后追溯。
      </p>

      <div class="admin-logs__toolbar">
        <el-select v-model="moduleFilter" placeholder="全部模块" clearable style="width: 130px" @change="search">
          <el-option v-for="opt in MODULE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select v-model="actionFilter" placeholder="全部动作" clearable style="width: 150px" @change="search">
          <el-option v-for="opt in ACTION_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="operatorId"
          placeholder="操作人 ID"
          clearable
          style="width: 120px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-date-picker
          v-model="startDate"
          type="date"
          placeholder="开始日期"
          value-format="YYYY-MM-DD"
          clearable
          style="width: 150px"
          @change="search"
        />
        <span class="admin-logs__tilde">~</span>
        <el-date-picker
          v-model="endDate"
          type="date"
          placeholder="结束日期"
          value-format="YYYY-MM-DD"
          clearable
          style="width: 150px"
          @change="search"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="hasFilter" text @click="reset">重置</el-button>
      </div>

      <div class="admin-logs__quick">
        <span class="text-muted admin-logs__quick-label">快捷区间</span>
        <el-button size="small" text @click="applyQuickRange(1)">今天</el-button>
        <el-button size="small" text @click="applyQuickRange(7)">近 7 天</el-button>
        <el-button size="small" text @click="applyQuickRange(30)">近 30 天</el-button>
        <span class="text-muted admin-logs__count">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" max-height="560" class="admin-logs__table admin-table">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="admin-logs__sub" :title="formatDateTime(row.createdAt)">
              {{ formatRelativeTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" min-width="130">
          <template #default="{ row }">
            <p class="admin-logs__sub">{{ row.operatorNickname || ('#' + row.operatorId) }}</p>
            <p class="text-muted admin-logs__sub">IP {{ row.ip || '—' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="模块 / 动作" width="160">
          <template #default="{ row }">
            <p>
              <el-tag size="small" effect="plain">{{ moduleText(row.module) }}</el-tag>
            </p>
            <p class="admin-logs__sub">
              <el-tag size="small" :type="actionTagType(row.action)" effect="plain">
                {{ actionText(row.action) }}
              </el-tag>
            </p>
          </template>
        </el-table-column>
        <el-table-column label="目标" width="120">
          <template #default="{ row }">
            <a
              v-if="row.targetType === 'item' && row.targetId"
              class="admin-logs__link"
              :href="'/item/' + row.targetId"
              target="_blank"
              rel="noopener"
            >
              {{ targetText(row) }}
            </a>
            <span v-else class="text-muted admin-logs__sub">{{ targetText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变更前" min-width="200">
          <template #default="{ row }">
            <p class="admin-logs__json" :title="formatJson(row.beforeValue)">{{ formatJson(row.beforeValue) }}</p>
          </template>
        </el-table-column>
        <el-table-column label="变更后" min-width="200">
          <template #default="{ row }">
            <p class="admin-logs__json" :title="formatJson(row.afterValue)">{{ formatJson(row.afterValue) }}</p>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="82" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="hasFilter ? '这段时间没有匹配的操作记录' : '暂无操作记录'" />
        </template>
      </el-table>

      <div v-if="total > 0" class="admin-logs__pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          layout="prev, pager, next, sizes, total"
          :total="total"
          :page-sizes="PAGE_SIZES"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>

    <el-dialog v-model="detail.visible" title="操作详情" width="640px">
      <template v-if="detail.log">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="时间">{{ formatDateTime(detail.log.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">
            {{ detail.log.operatorNickname || '—' }}（#{{ detail.log.operatorId }}）
          </el-descriptions-item>
          <el-descriptions-item label="模块">{{ moduleText(detail.log.module) }}</el-descriptions-item>
          <el-descriptions-item label="动作">{{ actionText(detail.log.action) }}</el-descriptions-item>
          <el-descriptions-item label="目标">{{ targetText(detail.log) }}</el-descriptions-item>
          <el-descriptions-item label="IP">{{ detail.log.ip || '—' }}</el-descriptions-item>
        </el-descriptions>

        <p class="admin-logs__detail-title">变更前</p>
        <pre class="admin-logs__pre">{{ formatJsonPretty(detail.log.beforeValue) }}</pre>
        <p class="admin-logs__detail-title">变更后</p>
        <pre class="admin-logs__pre">{{ formatJsonPretty(detail.log.afterValue) }}</pre>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.admin-logs {
  &__intro {
    margin-bottom: 12px;
    font-size: 13px;
  }

  &__toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__tilde {
    color: var(--text-3);
  }

  &__quick {
    display: flex;
    align-items: center;
    gap: 4px;
    margin: 4px 0 14px;
  }

  &__quick-label {
    margin-right: 4px;
    font-size: 12px;
  }

  &__count {
    margin-left: auto;
    font-size: 13px;
  }

  &__table {
    width: 100%;
  }

  &__sub {
    font-size: 12px;
    line-height: 1.5;
  }

  &__json {
    display: -webkit-box;
    overflow: hidden;
    margin: 0;
    color: var(--text-2);
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
    font-size: 12px;
    line-height: 1.5;
    word-break: break-all;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__link {
    color: var(--brand-600);
    font-size: 12px;

    &:hover {
      text-decoration: underline;
    }
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 14px;
  }

  &__detail-title {
    margin: 16px 0 6px;
    font-size: 13px;
    font-weight: 600;
  }

  &__pre {
    max-height: 220px;
    overflow: auto;
    margin: 0;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: var(--surface-2);
    color: var(--text-2);
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
