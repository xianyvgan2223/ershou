<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { fetchAdminItems, forceOfflineItem } from '@/api/admin'
import type { AdminItem } from '@/api/admin'
import { formatDateTime, formatPrice } from '@/utils/format'
import type { TagType } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const PAGE_SIZE = 10

const STATUS_OPTIONS = [
  { label: '草稿', value: 0 },
  { label: '审核中', value: 1 },
  { label: '在售', value: 2 },
  { label: '已锁定', value: 3 },
  { label: '已售出', value: 4 },
  { label: '已下架', value: 5 },
  { label: '审核未通过', value: 6 },
  { label: '已过期', value: 7 },
  { label: '违规下架', value: 8 },
]

const statusFilter = ref<number | ''>('')
const keyword = ref('')
const loading = ref(false)
const list = ref<AdminItem[]>([])
const total = ref(0)
const pageNum = ref(1)

const offlineDialog = reactive({
  visible: false,
  target: null as AdminItem | null,
  reason: '',
  loading: false,
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await fetchAdminItems({
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      keyword: keyword.value.trim() || undefined,
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

function openDetail(item: AdminItem): void {
  router.push('/item/' + item.id)
}

function openOffline(item: AdminItem): void {
  offlineDialog.target = item
  offlineDialog.reason = ''
  offlineDialog.visible = true
}

async function submitOffline(): Promise<void> {
  if (!offlineDialog.target) return
  if (!offlineDialog.reason.trim()) {
    ElMessage.warning('请填写强制下架的原因')
    return
  }
  offlineDialog.loading = true
  try {
    await forceOfflineItem(offlineDialog.target.id, offlineDialog.reason.trim())
    ElMessage.success('已强制下架')
    offlineDialog.visible = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    offlineDialog.loading = false
  }
}

function statusType(status?: number): TagType {
  switch (status) {
    case 1:
      return 'warning'
    case 2:
      return 'success'
    case 3:
      return 'primary'
    case 4:
      return 'info'
    case 6:
    case 8:
      return 'danger'
    default:
      return 'info'
  }
}

onMounted(() => {
  const qs = route.query.status
  if (typeof qs === 'string' && qs !== '') {
    statusFilter.value = Number(qs)
  }
  const qk = route.query.keyword
  if (typeof qk === 'string') {
    keyword.value = qk
  }
  load()
})
</script>

<template>
  <div class="admin-items">
    <div class="panel">
      <div class="panel-title">商品管理</div>
      <div class="admin-items__toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 160px" @change="search">
          <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索标题 / 描述"
          clearable
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <span class="text-muted admin-items__count">共 {{ total }} 件</span>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" stripe max-height="560" class="admin-items__table">
        <el-table-column label="商品" min-width="300">
          <template #default="{ row }">
            <div class="admin-items__item">
              <el-image :src="row.coverUrl || row.images?.[0]" fit="cover" class="admin-items__cover" />
              <div class="admin-items__info">
                <p class="admin-items__title" :title="row.title">#{{ row.id }} {{ row.title }}</p>
                <p class="text-muted admin-items__sub">
                  ¥{{ formatPrice(row.price) }} · {{ row.categoryName || '未分类' }} ·
                  {{ row.preferPointName || '未选点' }}
                </p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="卖家" min-width="140">
          <template #default="{ row }">
            <p class="admin-items__sub">#{{ row.sellerId }} {{ row.sellerNickname || '—' }}</p>
            <p class="text-muted admin-items__sub">{{ row.sellerPhone || '手机号不可用' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="审核原因" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.auditReason }">{{ row.auditReason || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="浏览/收藏" width="104" align="center">
          <template #default="{ row }">
            <span class="text-muted admin-items__sub">{{ row.viewCount ?? 0 }} / {{ row.favCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain" class="admin-items__tag">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="140">
          <template #default="{ row }">
            <span class="admin-items__sub">{{ formatDateTime(row.publishedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" align="center">
          <template #default="{ row }">
            <div class="admin-items__ops">
              <el-button size="small" @click="openDetail(row)">查看</el-button>
              <el-button
                v-if="row.status === 1 || row.status === 2 || row.status === 3 || row.status === 6"
                size="small"
                type="danger"
                plain
                @click="openOffline(row)"
              >
                下架
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的商品" />
        </template>
      </el-table>

      <div v-if="total > 0" class="admin-items__pager">
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

    <el-dialog v-model="offlineDialog.visible" title="强制下架商品" width="500px">
      <p class="text-muted admin-items__dialog-target">商品：{{ offlineDialog.target?.title }}</p>
      <el-input
        v-model="offlineDialog.reason"
        type="textarea"
        :rows="3"
        maxlength="120"
        show-word-limit
        placeholder="请填写违规原因，会记录到 audit_reason 与操作日志"
      />
      <template #footer>
        <el-button @click="offlineDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="offlineDialog.loading" @click="submitOffline">确认下架</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.admin-items {
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

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__cover {
    width: 44px;
    height: 44px;
    border-radius: 8px;
    flex-shrink: 0;
  }

  &__info {
    min-width: 0;

    p {
      margin: 0;
      font-size: 12px;
    }
  }

  &__title {
    overflow: hidden;
    font-size: 14px !important;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__sub {
    font-size: 12px;
    line-height: 1.5;
  }

  &__tag {
    white-space: nowrap;
  }

  &__ops {
    display: flex;
    gap: 6px;
    justify-content: center;
    white-space: nowrap;
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 14px;
  }

  &__dialog-target {
    margin-bottom: 12px;
    font-size: 13px;
  }
}
</style>
