<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  approveAuditItem,
  approveAuditPoint,
  batchAuditItems,
  fetchAuditItems,
  fetchAuditPoints,
  fetchRejectReasons,
  rejectAuditItem,
  rejectAuditPoint,
} from '@/api/admin'
import type { AdminItem, AdminPoint, AuditScope, RejectReason } from '@/api/admin'
import { formatDateTime, formatPrice, formatRelativeTime } from '@/utils/format'
import type { TagType } from '@/utils/format'
import { pointTypeLabel } from '@/api/map'

const route = useRoute()

type TabName = 'item' | 'point'
const activeTab = ref<TabName>(route.query.tab === 'point' ? 'point' : 'item')
const PAGE_SIZE = 10

const ITEM_STATUS_OPTIONS = [
  { label: '待审核', value: 1 },
  { label: '已驳回', value: 6 },
  { label: '在售', value: 2 },
  { label: '违规下架', value: 8 },
]

// ---------------- 商品审核 ----------------
const itemStatus = ref<number>(1)
const itemKeyword = ref('')
const itemLoading = ref(false)
const itemList = ref<AdminItem[]>([])
const itemTotal = ref(0)
const itemPage = ref(1)
const selectedItems = ref<AdminItem[]>([])
const acting = ref(false)

// ---------------- 点位审核 ----------------
const pointStatus = ref<number>(0)
const pointKeyword = ref('')
const pointLoading = ref(false)
const pointList = ref<AdminPoint[]>([])
const pointTotal = ref(0)
const pointPage = ref(1)

// ---------------- 驳回原因模板 ----------------
const reasonOptions = ref<Record<AuditScope, RejectReason[]>>({ item: [], point: [] })

const rejectDialog = reactive({
  visible: false,
  scope: 'item' as AuditScope,
  mode: 'single' as 'single' | 'batch',
  targetId: 0,
  targetTitle: '',
  reasonCode: '',
  reason: '',
  loading: false,
})

const pointDialog = reactive({
  visible: false,
  target: null as AdminPoint | null,
  safetyScore: 60,
  guideText: '',
  loading: false,
})

async function loadReasons(scope: AuditScope): Promise<void> {
  if (reasonOptions.value[scope].length) return
  try {
    reasonOptions.value[scope] = await fetchRejectReasons(scope)
  } catch {
    // 拦截器已提示
  }
}

async function loadItems(): Promise<void> {
  itemLoading.value = true
  try {
    const res = await fetchAuditItems({
      status: itemStatus.value,
      keyword: itemKeyword.value.trim() || undefined,
      pageNum: itemPage.value,
      pageSize: PAGE_SIZE,
    })
    itemList.value = res.records ?? []
    itemTotal.value = res.total ?? 0
    selectedItems.value = []
  } catch {
    itemList.value = []
    itemTotal.value = 0
  } finally {
    itemLoading.value = false
  }
}

async function loadPoints(): Promise<void> {
  pointLoading.value = true
  try {
    const res = await fetchAuditPoints({
      auditStatus: pointStatus.value,
      keyword: pointKeyword.value.trim() || undefined,
      pageNum: pointPage.value,
      pageSize: PAGE_SIZE,
    })
    pointList.value = res.records ?? []
    pointTotal.value = res.total ?? 0
  } catch {
    pointList.value = []
    pointTotal.value = 0
  } finally {
    pointLoading.value = false
  }
}

function searchItems(): void {
  itemPage.value = 1
  loadItems()
}

function searchPoints(): void {
  pointPage.value = 1
  loadPoints()
}

function handleItemSelection(rows: AdminItem[]): void {
  selectedItems.value = rows
}

/** 审核时能放大看图：优先整组图，没有则退回封面 */
function previewList(item: AdminItem): string[] {
  if (item.images?.length) return item.images
  return item.coverUrl ? [item.coverUrl] : []
}

/** 驳回原因模板：点一下选中，再点一下取消 */
function toggleReason(code: string): void {
  rejectDialog.reasonCode = rejectDialog.reasonCode === code ? '' : code
}

async function approveOne(item: AdminItem): Promise<void> {
  acting.value = true
  try {
    await approveAuditItem(item.id)
    ElMessage.success('已通过：' + item.title)
    await loadItems()
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}

async function batchApprove(): Promise<void> {
  if (!selectedItems.value.length) {
    ElMessage.warning('请先勾选要通过的商品')
    return
  }
  try {
    await ElMessageBox.confirm('确定批量通过选中的 ' + selectedItems.value.length + ' 件商品吗？', '批量通过', {
      confirmButtonText: '确认通过',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  acting.value = true
  try {
    const res = await batchAuditItems({
      ids: selectedItems.value.map((i) => i.id),
      action: 'approve',
    })
    ElMessage.success('通过 ' + res.success + ' 件' + (res.failed ? '，' + res.failed + ' 件失败' : ''))
    await loadItems()
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}

function openRejectItem(item: AdminItem): void {
  loadReasons('item')
  Object.assign(rejectDialog, {
    visible: true,
    scope: 'item',
    mode: 'single',
    targetId: item.id,
    targetTitle: item.title,
    reasonCode: '',
    reason: '',
  })
}

function openRejectBatch(): void {
  if (!selectedItems.value.length) {
    ElMessage.warning('请先勾选要驳回的商品')
    return
  }
  loadReasons('item')
  Object.assign(rejectDialog, {
    visible: true,
    scope: 'item',
    mode: 'batch',
    targetId: 0,
    targetTitle: '已勾选 ' + selectedItems.value.length + ' 件商品',
    reasonCode: '',
    reason: '',
  })
}

function openRejectPoint(point: AdminPoint): void {
  loadReasons('point')
  Object.assign(rejectDialog, {
    visible: true,
    scope: 'point',
    mode: 'single',
    targetId: point.id,
    targetTitle: point.name,
    reasonCode: '',
    reason: '',
  })
}

async function submitReject(): Promise<void> {
  if (!rejectDialog.reasonCode && !rejectDialog.reason.trim()) {
    ElMessage.warning('请选择驳回原因或填写补充说明')
    return
  }
  const payload = {
    reasonCode: rejectDialog.reasonCode || undefined,
    reason: rejectDialog.reason.trim() || undefined,
  }
  rejectDialog.loading = true
  try {
    if (rejectDialog.scope === 'point') {
      await rejectAuditPoint(rejectDialog.targetId, payload)
      ElMessage.success('点位已驳回')
      await loadPoints()
    } else if (rejectDialog.mode === 'batch') {
      const res = await batchAuditItems({
        ids: selectedItems.value.map((i) => i.id),
        action: 'reject',
        ...payload,
      })
      ElMessage.success('驳回 ' + res.success + ' 件' + (res.failed ? '，' + res.failed + ' 件失败' : ''))
      await loadItems()
    } else {
      await rejectAuditItem(rejectDialog.targetId, payload)
      ElMessage.success('商品已驳回')
      await loadItems()
    }
    rejectDialog.visible = false
  } catch {
    // 拦截器已提示
  } finally {
    rejectDialog.loading = false
  }
}

function openPointApprove(point: AdminPoint): void {
  pointDialog.target = point
  pointDialog.safetyScore = point.safetyScore ?? 60
  pointDialog.guideText = point.guideText ?? ''
  pointDialog.visible = true
}

async function submitPointApprove(): Promise<void> {
  if (!pointDialog.target) return
  pointDialog.loading = true
  try {
    await approveAuditPoint(pointDialog.target.id, {
      safetyScore: pointDialog.safetyScore,
      guideText: pointDialog.guideText.trim() || undefined,
    })
    ElMessage.success('点位已通过：' + pointDialog.target.name)
    pointDialog.visible = false
    await loadPoints()
  } catch {
    // 拦截器已提示
  } finally {
    pointDialog.loading = false
  }
}

function itemStatusType(status?: number): TagType {
  switch (status) {
    case 1:
      return 'warning'
    case 2:
      return 'success'
    case 6:
    case 8:
      return 'danger'
    default:
      return 'info'
  }
}

function pointAuditType(status?: number): TagType {
  if (status === 0) return 'warning'
  return status === 1 ? 'success' : 'danger'
}

function pointAuditText(status?: number): string {
  if (status === 0) return '待审核'
  return status === 1 ? '已通过' : '已驳回'
}

function changeItemPage(page: number): void {
  itemPage.value = page
  loadItems()
}

function changePointPage(page: number): void {
  pointPage.value = page
  loadPoints()
}

onMounted(() => {
  loadItems()
  loadPoints()
})
</script>

<template>
  <div class="audit">
    <div class="panel audit__intro">
      <div class="panel-title">审核工作台</div>
      <p class="text-muted">
        商品发布时先过 AI 机审：命中禁售词直接拦截，可疑内容进入"审核中"等待人工处置；
        用户提交的面交点同样需要人工审核后才会出现在地图上。
      </p>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="商品审核" name="item" />
      <el-tab-pane label="点位审核" name="point" />
    </el-tabs>

    <!-- ---------------- 商品 ---------------- -->
    <div v-show="activeTab === 'item'" class="panel">
      <div class="audit__toolbar">
        <el-select v-model="itemStatus" filterable style="width: 140px" @change="searchItems">
          <el-option v-for="opt in ITEM_STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="itemKeyword"
          placeholder="搜索标题 / 描述"
          clearable
          style="width: 240px"
          @keyup.enter="searchItems"
          @clear="searchItems"
        />
        <el-button @click="searchItems">查询</el-button>
        <el-button :icon="Refresh" :loading="itemLoading" @click="loadItems">刷新</el-button>
        <div class="audit__toolbar-right">
          <span class="audit__count">共 {{ itemTotal }} 件</span>
          <span v-if="selectedItems.length" class="audit__chip">已选 {{ selectedItems.length }} 件</span>
          <el-button type="primary" :disabled="!selectedItems.length" :loading="acting" @click="batchApprove">
            批量通过
          </el-button>
          <el-button type="danger" plain :disabled="!selectedItems.length" @click="openRejectBatch">
            批量驳回
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="itemLoading"
        :data="itemList"
        row-key="id"
        stripe
        max-height="560"
        class="audit__table"
        @selection-change="handleItemSelection"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column label="商品" min-width="290">
          <template #default="{ row }">
            <div class="audit__item">
              <el-image
                :src="row.coverUrl || row.images?.[0]"
                :preview-src-list="previewList(row)"
                :initial-index="0"
                preview-teleported
                hide-on-click-modal
                fit="cover"
                class="audit__cover"
              />
              <div class="audit__item-info">
                <a
                  class="audit__item-title"
                  :title="row.title"
                  :href="'/item/' + row.id"
                  target="_blank"
                  rel="noopener"
                >
                  #{{ row.id }} {{ row.title }}
                </a>
                <p class="text-muted audit__sub">
                  ¥{{ formatPrice(row.price) }} · {{ row.categoryName || '未分类' }} ·
                  {{ row.conditionText || '成色未知' }}
                </p>
                <p class="text-muted audit__sub">面交点：{{ row.preferPointName || '未选' }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="卖家" min-width="150">
          <template #default="{ row }">
            <p class="audit__strong">{{ row.sellerNickname || '—' }}</p>
            <p class="text-muted audit__sub">#{{ row.sellerId }} {{ row.sellerPhone || '' }}</p>
            <p class="text-muted audit__sub">信用 {{ row.sellerCreditScore ?? '—' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="审核原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.auditReason }">{{ row.auditReason || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="145">
          <template #default="{ row }">
            <span class="audit__sub" :title="formatDateTime(row.createdAt)">
              {{ formatRelativeTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="itemStatusType(row.status)" effect="plain" class="audit__tag">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" align="center">
          <template #default="{ row }">
            <div v-if="row.status === 1" class="audit__ops">
              <el-button size="small" type="primary" :loading="acting" @click="approveOne(row)">通过</el-button>
              <el-button size="small" type="danger" plain @click="openRejectItem(row)">驳回</el-button>
            </div>
            <span v-else class="text-muted">已处置</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="itemStatus === 1 ? '待审队列是空的，目前没有需要人工复核的商品' : '没有符合条件的商品'"
          />
        </template>
      </el-table>

      <div v-if="itemTotal > 0" class="audit__pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="itemTotal"
          :page-size="PAGE_SIZE"
          :current-page="itemPage"
          @current-change="changeItemPage"
        />
      </div>
    </div>

    <!-- ---------------- 点位 ---------------- -->
    <div v-show="activeTab === 'point'" class="panel">
      <div class="audit__toolbar">
        <el-select v-model="pointStatus" filterable style="width: 140px" @change="searchPoints">
          <el-option label="待审核" :value="0" />
          <el-option label="已通过" :value="1" />
          <el-option label="已驳回" :value="2" />
        </el-select>
        <el-input
          v-model="pointKeyword"
          placeholder="搜索点位名称"
          clearable
          style="width: 220px"
          @keyup.enter="searchPoints"
          @clear="searchPoints"
        />
        <el-button @click="searchPoints">查询</el-button>
        <el-button :icon="Refresh" :loading="pointLoading" @click="loadPoints">刷新</el-button>
        <span class="audit__count audit__count--right">共 {{ pointTotal }} 个</span>
      </div>

      <el-table v-loading="pointLoading" :data="pointList" row-key="id" stripe max-height="560" class="audit__table">
        <el-table-column label="点位" min-width="190">
          <template #default="{ row }">
            <p class="audit__strong">{{ row.name }}</p>
            <p class="text-muted audit__sub">
              {{ pointTypeLabel(row.pointType) }} · {{ row.campusName || '校区未知' }}
            </p>
          </template>
        </el-table-column>
        <el-table-column label="坐标" width="120">
          <template #default="{ row }">
            <span class="text-muted audit__sub">{{ row.coordX }}, {{ row.coordY }}</span>
          </template>
        </el-table-column>
        <el-table-column label="安全分" width="84" align="center">
          <template #default="{ row }">{{ row.safetyScore ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="图文指引" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.guideText }">{{ row.guideText || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交人" min-width="110">
          <template #default="{ row }">
            {{ row.creatorNickname || (row.creatorId ? '#' + row.creatorId : '官方') }}
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="145">
          <template #default="{ row }">
            <span class="audit__sub">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="pointAuditType(row.auditStatus)" effect="plain" class="audit__tag">
              {{ pointAuditText(row.auditStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" align="center">
          <template #default="{ row }">
            <div v-if="row.auditStatus === 0" class="audit__ops">
              <el-button size="small" type="primary" @click="openPointApprove(row)">通过</el-button>
              <el-button size="small" type="danger" plain @click="openRejectPoint(row)">驳回</el-button>
            </div>
            <span v-else class="text-muted">已处置</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的点位" />
        </template>
      </el-table>

      <div v-if="pointTotal > 0" class="audit__pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="pointTotal"
          :page-size="PAGE_SIZE"
          :current-page="pointPage"
          @current-change="changePointPage"
        />
      </div>
    </div>

    <!-- ---------------- 驳回弹窗 ---------------- -->
    <el-dialog v-model="rejectDialog.visible" title="驳回" width="520px">
      <p class="text-muted audit__dialog-target">目标：{{ rejectDialog.targetTitle }}</p>
      <el-form label-width="90px">
        <el-form-item label="驳回原因">
          <div class="audit__reasons">
            <el-tag
              v-for="r in reasonOptions[rejectDialog.scope]"
              :key="r.code"
              class="audit__reason"
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

    <!-- ---------------- 点位通过弹窗 ---------------- -->
    <el-dialog v-model="pointDialog.visible" title="通过点位" width="520px">
      <el-form label-width="90px">
        <el-form-item label="点位名称">
          <span class="text-strong">{{ pointDialog.target?.name }}</span>
        </el-form-item>
        <el-form-item label="安全分">
          <el-input-number v-model="pointDialog.safetyScore" :min="0" :max="100" />
          <span class="text-muted audit__hint">夜间只推荐安全分 ≥85 的点位</span>
        </el-form-item>
        <el-form-item label="图文指引">
          <el-input
            v-model="pointDialog.guideText"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="例：从东门进直走 100 米，快递柜旁"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pointDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="pointDialog.loading" @click="submitPointApprove">确认通过</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.audit {
  &__intro {
    margin-bottom: 12px;
  }

  &__toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    flex-wrap: wrap;
  }

  &__toolbar-right {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-left: auto;
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
    width: 48px;
    height: 48px;
    border-radius: 8px;
    flex-shrink: 0;
  }

  &__item-info {
    min-width: 0;

    p {
      margin: 0;
      font-size: 12px;
    }
  }

  &__sub {
    font-size: 12px;
    line-height: 1.5;
  }

  &__strong {
    font-size: 14px;
    font-weight: 600;
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

  &__item-title {
    display: block;
    overflow: hidden;
    color: var(--text-1);
    font-size: 14px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;

    &:hover {
      color: var(--brand-600);
    }
  }

  &__count {
    color: var(--text-3);
    font-size: 13px;

    &--right {
      margin-left: auto;
    }
  }

  &__chip {
    padding: 2px 10px;
    border-radius: var(--r-pill);
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 12px;
    line-height: 20px;
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

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 14px;
  }

  &__dialog-target {
    margin-bottom: 12px;
    font-size: 13px;
  }

  &__hint {
    margin-left: 10px;
    font-size: 12px;
  }
}
</style>
