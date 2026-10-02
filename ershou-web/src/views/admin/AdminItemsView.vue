<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  approveAuditItem,
  fetchAdminItems,
  fetchRejectReasons,
  forceOfflineItem,
  rejectAuditItem,
} from '@/api/admin'
import type { AdminItem, RejectReason } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import {
  ITEM_STATUS,
  ITEM_STATUS_OPTIONS,
  conditionLabel,
  formatDateTime,
  formatPrice,
  formatRelativeTime,
  itemStatusTagType,
} from '@/utils/format'
import { useQuerySync } from '@/composables/useQuerySync'
import { showTip } from '@/utils/feedback'

/**
 * 商品管理：全状态检索 + 处置。
 *
 * 相比以前补了三件事：
 * - 详情抽屉：以前点「查看」会跳到学生端商品详情页，等于离开了后台，筛选条件也丢了
 * - 卖家筛选：后端一直支持 sellerId，但页面没给入口（排查某个卖家时只能翻页找）
 * - 待审商品直接审核：审到一半发现要处置的商品还压在队列里时，不用再切回审核工作台
 */

const PAGE_SIZES = [10, 20, 50]

const statusFilter = ref<number | ''>('')
const keyword = ref('')
const sellerId = ref('')
const pageNum = ref(1)
const pageSize = ref(10)

const adminStore = useAdminStore()
const loading = ref(false)
const list = ref<AdminItem[]>([])
const total = ref(0)
/** 行内提交中的商品 id，只让那一行的按钮转圈 */
const busyItems = ref<number[]>([])

const detail = reactive({ visible: false, item: null as AdminItem | null })

const offlineDialog = reactive({
  visible: false,
  target: null as AdminItem | null,
  reason: '',
  loading: false,
})

const rejectDialog = reactive({
  visible: false,
  target: null as AdminItem | null,
  reasonCode: '',
  reason: '',
  loading: false,
})

const itemReasons = ref<RejectReason[]>([])

const hasFilter = computed(
  () => statusFilter.value !== '' || !!keyword.value.trim() || !!sellerId.value.trim(),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await fetchAdminItems({
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      keyword: keyword.value.trim() || undefined,
      sellerId: toId(sellerId.value),
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

/** 卖家 ID 只接受正整数，其它一律当作「不筛」 */
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
  statusFilter.value = ''
  keyword.value = ''
  sellerId.value = ''
  pageNum.value = 1
  load()
}

function openDetail(item: AdminItem): void {
  detail.item = item
  detail.visible = true
}

function previewList(item: AdminItem): string[] {
  if (item.images?.length) return item.images
  return item.coverUrl ? [item.coverUrl] : []
}

// ---------------- 强制下架 ----------------
function openOffline(item: AdminItem): void {
  offlineDialog.target = item
  offlineDialog.reason = ''
  offlineDialog.visible = true
}

async function submitOffline(): Promise<void> {
  if (!offlineDialog.target) return
  if (!offlineDialog.reason.trim()) {
    showTip('请填写强制下架的原因', 'warning')
    return
  }
  offlineDialog.loading = true
  try {
    await forceOfflineItem(offlineDialog.target.id, offlineDialog.reason.trim())
    showTip('已强制下架', 'success')
    offlineDialog.visible = false
    detail.visible = false
    await Promise.all([load(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    offlineDialog.loading = false
  }
}

// ---------------- 待审商品直接审核 ----------------
async function approveOne(item: AdminItem): Promise<void> {
  busyItems.value = [...busyItems.value, item.id]
  try {
    await approveAuditItem(item.id)
    showTip('已通过：' + item.title, 'success')
    detail.visible = false
    await Promise.all([load(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    busyItems.value = busyItems.value.filter((id) => id !== item.id)
  }
}

async function openReject(item: AdminItem): Promise<void> {
  rejectDialog.target = item
  rejectDialog.reasonCode = ''
  rejectDialog.reason = ''
  rejectDialog.visible = true
  if (!itemReasons.value.length) {
    try {
      itemReasons.value = await fetchRejectReasons('item')
    } catch {
      // 拦截器已提示
    }
  }
}

function toggleReason(code: string): void {
  rejectDialog.reasonCode = rejectDialog.reasonCode === code ? '' : code
}

async function submitReject(): Promise<void> {
  const target = rejectDialog.target
  if (!target) return
  if (!rejectDialog.reasonCode && !rejectDialog.reason.trim()) {
    showTip('请选择驳回原因或填写补充说明', 'warning')
    return
  }
  rejectDialog.loading = true
  busyItems.value = [...busyItems.value, target.id]
  try {
    await rejectAuditItem(target.id, {
      reasonCode: rejectDialog.reasonCode || undefined,
      reason: rejectDialog.reason.trim() || undefined,
    })
    showTip('商品已驳回', 'success')
    rejectDialog.visible = false
    detail.visible = false
    await Promise.all([load(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    rejectDialog.loading = false
    busyItems.value = busyItems.value.filter((id) => id !== target.id)
  }
}

/** 能在列表里直接下架的状态：审核中 / 在售 / 已锁定 / 审核未通过 */
const OFFLINEABLE = [ITEM_STATUS.REVIEWING, ITEM_STATUS.ON_SALE, ITEM_STATUS.LOCKED, ITEM_STATUS.REJECTED]

function canOffline(item: AdminItem): boolean {
  return OFFLINEABLE.includes(item.status as (typeof OFFLINEABLE)[number])
}

useQuerySync(
  { statusFilter, keyword, sellerId, pageNum, pageSize },
  { statusFilter: '' as number | '', keyword: '', sellerId: '', pageNum: 1, pageSize: 10 },
  {
    onChange: load,
    aliases: { statusFilter: 'status', pageNum: 'page', pageSize: 'size' },
    // statusFilter 的默认值是空串（联合类型），必须显式声明成数字
    numeric: ['statusFilter'],
  },
)

onMounted(load)
</script>

<template>
  <div class="admin-page admin-items">
    <div class="panel">
      <div class="panel-title">商品管理</div>
      <div class="admin-items__toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 160px" @change="search">
          <el-option v-for="opt in ITEM_STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索标题 / 描述"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="sellerId"
          placeholder="卖家 ID"
          clearable
          style="width: 120px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="hasFilter" text @click="reset">重置</el-button>
        <span class="text-muted admin-items__count">共 {{ total }} 件</span>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" max-height="560" class="admin-items__table admin-table">
        <el-table-column label="商品" min-width="300">
          <template #default="{ row }">
            <div class="admin-items__item">
              <el-image
                :src="row.coverUrl || row.images?.[0]"
                :preview-src-list="previewList(row)"
                preview-teleported
                hide-on-click-modal
                fit="cover"
                class="admin-items__cover"
              >
                <template #error>
                  <div class="admin-items__cover-empty">无图</div>
                </template>
              </el-image>
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
        <el-table-column label="审核原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.auditReason }">{{ row.auditReason || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="浏览/收藏" width="104" align="center">
          <template #default="{ row }">
            <span class="text-muted admin-items__sub">{{ row.viewCount ?? 0 }} / {{ row.favCount ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="104" align="center">
          <template #default="{ row }">
            <el-tag :type="itemStatusTagType(row.status)" effect="plain" class="admin-items__tag">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="140">
          <template #default="{ row }">
            <span class="admin-items__sub">{{ formatDateTime(row.publishedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="164" align="center" fixed="right">
          <template #default="{ row }">
            <div class="admin-items__ops">
              <el-button
                v-if="row.status === ITEM_STATUS.REVIEWING"
                link
                size="small"
                type="primary"
                :loading="busyItems.includes(row.id)"
                @click="approveOne(row)"
              >
                通过
              </el-button>
              <el-button v-if="row.status === ITEM_STATUS.REVIEWING" link size="small" type="danger" @click="openReject(row)">
                驳回
              </el-button>
              <el-button link size="small" @click="openDetail(row)">详情</el-button>
              <el-button v-if="canOffline(row)" link size="small" type="danger" @click="openOffline(row)">
                下架
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="hasFilter ? '没有符合条件的商品' : '还没有任何商品'" />
        </template>
      </el-table>

      <div v-if="total > 0" class="admin-items__pager">
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

    <!-- ---------------- 商品详情抽屉 ---------------- -->
    <el-drawer v-model="detail.visible" title="商品详情" size="520px">
      <template v-if="detail.item">
        <div class="admin-items__gallery">
          <el-image
            v-for="(url, index) in previewList(detail.item)"
            :key="url + index"
            :src="url"
            :preview-src-list="previewList(detail.item)"
            :initial-index="index"
            preview-teleported
            hide-on-click-modal
            fit="cover"
            class="admin-items__gallery-img"
          >
            <template #error>
              <div class="admin-items__cover-empty">加载失败</div>
            </template>
          </el-image>
          <el-empty v-if="!previewList(detail.item).length" description="没有图片" :image-size="60" />
        </div>

        <p class="admin-items__detail-title">{{ detail.item.title }}</p>
        <p class="admin-items__detail-price">
          ¥{{ formatPrice(detail.item.price) }}
          <span v-if="detail.item.originalPrice" class="price-origin">¥{{ formatPrice(detail.item.originalPrice) }}</span>
        </p>

        <el-descriptions :column="1" border class="admin-items__detail-desc">
          <el-descriptions-item label="状态">
            <el-tag :type="itemStatusTagType(detail.item.status)" effect="plain">
              {{ detail.item.statusText }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            {{ detail.item.categoryName || '未分类' }}
          </el-descriptions-item>
          <el-descriptions-item label="成色">
            {{ detail.item.conditionText || conditionLabel(detail.item.conditionLevel) }}
          </el-descriptions-item>
          <el-descriptions-item label="校区 / 面交点">
            {{ detail.item.campusName || '—' }} · {{ detail.item.preferPointName || '未选点' }}
          </el-descriptions-item>
          <el-descriptions-item label="卖家">
            {{ detail.item.sellerNickname || '—' }}（#{{ detail.item.sellerId }}）
            · 信用 {{ detail.item.sellerCreditScore ?? '—' }}
            · {{ detail.item.sellerPhone || '手机号不可用' }}
          </el-descriptions-item>
          <el-descriptions-item label="浏览 / 收藏">
            {{ detail.item.viewCount ?? 0 }} / {{ detail.item.favCount ?? 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="发布时间">
            <span :title="formatDateTime(detail.item.publishedAt)">
              {{ formatRelativeTime(detail.item.publishedAt) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.item.auditReason" label="审核原因">
            {{ detail.item.auditReason }}
            <span v-if="detail.item.rejectCode" class="text-muted">（{{ detail.item.rejectCode }}）</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.item.expireAt" label="过期时间">
            {{ formatDateTime(detail.item.expireAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <p class="admin-items__detail-sub">商品描述</p>
        <p class="admin-items__detail-body">{{ detail.item.description || '（卖家没有填写描述）' }}</p>

        <div class="admin-items__detail-actions">
          <a class="admin-items__link" :href="'/item/' + detail.item.id" target="_blank" rel="noopener">
            在新标签打开学生端详情页
          </a>
          <div class="admin-items__detail-btns">
            <template v-if="detail.item.status === ITEM_STATUS.REVIEWING">
              <el-button
                type="primary"
                :loading="busyItems.includes(detail.item.id)"
                @click="approveOne(detail.item)"
              >
                审核通过
              </el-button>
              <el-button type="danger" plain @click="openReject(detail.item)">驳回</el-button>
            </template>
            <el-button v-if="canOffline(detail.item)" type="danger" plain @click="openOffline(detail.item)">
              强制下架
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- ---------------- 强制下架弹窗 ---------------- -->
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

    <!-- ---------------- 驳回弹窗 ---------------- -->
    <el-dialog v-model="rejectDialog.visible" title="驳回商品" width="520px">
      <p class="text-muted admin-items__dialog-target">商品：{{ rejectDialog.target?.title }}</p>
      <el-form label-width="90px">
        <el-form-item label="驳回原因">
          <div class="admin-items__reasons">
            <el-tag
              v-for="r in itemReasons"
              :key="r.code"
              class="admin-items__reason"
              :type="rejectDialog.reasonCode === r.code ? 'danger' : 'info'"
              :effect="rejectDialog.reasonCode === r.code ? 'dark' : 'plain'"
              @click="toggleReason(r.code)"
            >
              {{ r.label }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input
            v-model="rejectDialog.reason"
            type="textarea"
            :rows="3"
            maxlength="120"
            show-word-limit
            placeholder="选填，会拼在模板原因后展示给发布者"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="rejectDialog.loading" @click="submitReject">确认驳回</el-button>
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
    flex-wrap: wrap;
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

  // ---------- 详情抽屉 ----------
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
    border-radius: var(--r-md);
  }

  &__detail-title {
    font-size: 17px;
    font-weight: 700;
    line-height: 1.4;
  }

  &__detail-price {
    margin-top: 4px;
    color: var(--accent-500);
    font-size: 20px;
    font-weight: 800;

    .price-origin {
      margin-left: 8px;
      font-size: 12px;
      font-weight: 400;
    }
  }

  &__detail-desc {
    margin-top: 14px;
  }

  &__detail-sub {
    margin: 18px 0 6px;
    font-size: 14px;
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

  &__reasons {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }

  &__reason {
    cursor: pointer;
    user-select: none;
  }
}
</style>
