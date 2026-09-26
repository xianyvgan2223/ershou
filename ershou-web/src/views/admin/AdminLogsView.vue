<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { fetchAuditLogs } from '@/api/admin'
import type { AuditLogItem } from '@/api/admin'
import { formatDateTime, formatRelativeTime } from '@/utils/format'
import { actionTagType, actionText, formatJson, moduleText, targetText } from '@/utils/adminDict'

const PAGE_SIZE = 20

const MODULE_OPTIONS = [
  { label: '商品', value: 'item' },
  { label: '点位', value: 'point' },
  { label: '用户', value: 'user' },
]

const moduleFilter = ref('')
const operatorId = ref('')
const loading = ref(false)
const list = ref<AuditLogItem[]>([])
const total = ref(0)
const pageNum = ref(1)

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await fetchAuditLogs({
      module: moduleFilter.value || undefined,
      operatorId: operatorId.value ? Number(operatorId.value) : undefined,
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
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

function search(): void {
  pageNum.value = 1
  load()
}

function changePage(page: number): void {
  pageNum.value = page
  load()
}

onMounted(load)
</script>

<template>
  <div class="admin-logs">
    <div class="panel">
      <div class="panel-title">操作审计日志</div>
      <p class="text-muted admin-logs__intro">
        记录运营的每一次写操作（操作人、模块、动作、目标、变更前后、IP），用于事后追溯。
      </p>
      <div class="admin-logs__toolbar">
        <el-select v-model="moduleFilter" filterable placeholder="全部模块" clearable style="width: 150px" @change="search">
          <el-option v-for="opt in MODULE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="operatorId"
          placeholder="操作人 ID"
          clearable
          style="width: 150px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <span class="text-muted admin-logs__count">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" stripe max-height="560" class="admin-logs__table">
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
        <el-table-column label="目标" width="130">
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
        <el-table-column label="变更前" min-width="210">
          <template #default="{ row }">
            <p class="admin-logs__json" :title="formatJson(row.beforeValue)">{{ formatJson(row.beforeValue) }}</p>
          </template>
        </el-table-column>
        <el-table-column label="变更后" min-width="210">
          <template #default="{ row }">
            <p class="admin-logs__json" :title="formatJson(row.afterValue)">{{ formatJson(row.afterValue) }}</p>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无操作记录" />
        </template>
      </el-table>

      <div v-if="total > 0" class="admin-logs__pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="PAGE_SIZE"
          :current-page="pageNum"
          @current-change="changePage"
        />
      </div>
    </div>
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
    margin-bottom: 14px;
    flex-wrap: wrap;
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
}
</style>
