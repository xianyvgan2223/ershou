<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  approveAuditItem,
  approveAuditPoint,
  batchAuditItems,
  fetchAuditItems,
  fetchAuditPoints,
  fetchRejectReasons,
  rejectAuditItem,
  rejectAuditPoint,
  updatePointStatus,
} from '@/api/admin'
import type { AdminItem, AdminPoint, AuditScope, RejectReason } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { formatDateTime, formatPrice, formatRelativeTime, ITEM_STATUS_OPTIONS } from '@/utils/format'
import type { TagType } from '@/utils/format'
import { pointTypeLabel } from '@/api/map'
import { useQuerySync } from '@/composables/useQuerySync'
import { showTip } from '@/utils/feedback'

/**
 * 审核工作台：商品审核 + 面交点处置。
 *
 * 两处历史问题在这里修掉：
 * 1. 所有行的「通过」按钮共用一个 loading 标志，点一行整屏按钮一起转圈 —— 改成按行记录。
 * 2. 点位审核通过后没有任何处置出口，页面文案却写着「用于事后追溯与下架」；
 *    现在补了「下架 / 恢复」，下架会让点位立刻从地图上消失。
 */

type TabName = 'item' | 'point'

const adminStore = useAdminStore()
const activeTab = ref<TabName>('item')

// ---------------- 商品审核 ----------------
const itemStatus = ref<number>(1)
const itemKeyword = ref('')
const itemPage = ref(1)
const itemLoading = ref(false)
const itemList = ref<AdminItem[]>([])
const itemTotal = ref(0)
const selectedItems = ref<AdminItem[]>([])
/** 正在提交的单行商品 id：只让那一行的按钮转圈 */
const busyItems = ref<number[]>([])
const batchLoading = ref(false)

// ---------------- 点位 ----------------
// 面交点已改为免审核（创建即生效），所以「待审核」队列平时是空的。
// 这个 tab 的主要用途是事后处置，默认就把全部审核状态都列出来。
const POINT_AUDIT_OPTIONS = [
  { label: '不限审核状态', value: -1 },
  { label: '待审核', value: 0 },
  { label: '已通过', value: 1 },
  { label: '已驳回', value: 2 },
]
const POINT_MAP_OPTIONS = [
  { label: '不限地图状态', value: -1 },
  { label: '启用中', value: 1 },
  { label: '已下架', value: 0 },
]
const pointStatus = ref<number>(-1)
const pointMapStatus = ref<number>(-1)
const pointKeyword = ref('')
const pointPage = ref(1)
const pointLoading = ref(false)
const pointList = ref<AdminPoint[]>([])
const pointTotal = ref(0)
const busyPoints = ref<number[]>([])

// ---------------- 驳回 / 下架原因模板 ----------------
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

/** 下架 / 恢复点位弹窗；下架时复用点位的驳回原因模板 */
const pointStatusDialog = reactive({
  visible: false,
  target: null as AdminPoint | null,
  status: 0,
  reasonCode: '',
  reason: '',
  loading: false,
})

const PAGE_SIZE = 10

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
      status: pointMapStatus.value < 0 ? undefined : pointMapStatus.value,
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

function resetPointFilter(): void {
  pointStatus.value = -1
  pointMapStatus.value = -1
  pointKeyword.value = ''
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

function togglePointReason(code: string): void {
  pointStatusDialog.reasonCode = pointStatusDialog.reasonCode === code ? '' : code
}

async function approveOne(item: AdminItem): Promise<void> {
  busyItems.value = [...busyItems.value, item.id]
  try {
    await approveAuditItem(item.id)
    showTip('已通过：' + item.title, 'success')
    await Promise.all([loadItems(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    busyItems.value = busyItems.value.filter((id) => id !== item.id)
  }
}

async function batchApprove(): Promise<void> {
  if (!selectedItems.value.length) {
    showTip('请先勾选要通过的商品', 'warning')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确定批量通过选中的 ' + selectedItems.value.length + ' 件商品吗？',
      '批量通过',
      { confirmButtonText: '确认通过', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  batchLoading.value = true
  try {
    const res = await batchAuditItems({
      ids: selectedItems.value.map((i) => i.id),
      action: 'approve',
    })
    showTip('通过 ' + res.success + ' 件' + (res.failed ? '，' + res.failed + ' 件失败' : ''), 'success')
    await Promise.all([loadItems(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    batchLoading.value = false
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
    showTip('请先勾选要驳回的商品', 'warning')
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
    showTip('请选择驳回原因或填写补充说明', 'warning')
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
      showTip('点位已驳回', 'success')
      await Promise.all([loadPoints(), adminStore.refresh()])
    } else if (rejectDialog.mode === 'batch') {
      const res = await batchAuditItems({
        ids: selectedItems.value.map((i) => i.id),
        action: 'reject',
        ...payload,
      })
      showTip('驳回 ' + res.success + ' 件' + (res.failed ? '，' + res.failed + ' 件失败' : ''), 'success')
      await Promise.all([loadItems(), adminStore.refresh()])
    } else {
      await rejectAuditItem(rejectDialog.targetId, payload)
      showTip('商品已驳回', 'success')
      await Promise.all([loadItems(), adminStore.refresh()])
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
    showTip('点位已通过：' + pointDialog.target.name, 'success')
    pointDialog.visible = false
    await Promise.all([loadPoints(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    pointDialog.loading = false
  }
}

/** 打开下架 / 恢复弹窗：恢复不需要填原因，下架必须填 */
function openPointStatus(point: AdminPoint, status: 0 | 1): void {
  if (status === 0) loadReasons('point')
  Object.assign(pointStatusDialog, {
    visible: true,
    target: point,
    status,
    reasonCode: '',
    reason: '',
  })
}

async function submitPointStatus(): Promise<void> {
  const target = pointStatusDialog.target
  if (!target) return
  const reason = pointStatusDialog.reason.trim()
  if (pointStatusDialog.status === 0 && !pointStatusDialog.reasonCode && !reason) {
    showTip('请选择下架原因或填写补充说明', 'warning')
    return
  }
  // 模板原因 + 补充说明拼成一句话存进审计日志
  const label = reasonOptions.value.point.find((r) => r.code === pointStatusDialog.reasonCode)?.label
  const composed = [label, reason].filter(Boolean).join('：')

  pointStatusDialog.loading = true
  busyPoints.value = [...busyPoints.value, target.id]
  try {
    await updatePointStatus(target.id, {
      status: pointStatusDialog.status,
      reason: composed || undefined,
    })
    showTip(pointStatusDialog.status === 0 ? '点位已下架' : '点位已恢复', 'success')
    pointStatusDialog.visible = false
    await Promise.all([loadPoints(), adminStore.refresh()])
  } catch {
    // 拦截器已提示
  } finally {
    pointStatusDialog.loading = false
    busyPoints.value = busyPoints.value.filter((id) => id !== target.id)
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

/** 只有「审核通过且地图启用」的点位才是真正在地图上生效的 */
function pointLive(point: AdminPoint): boolean {
  return point.auditStatus === 1 && point.status === 1
}

/**
 * 坐标展示：用户提交的点位基本只有经纬度（第三方地图），官方点位才有自绘底图的像素坐标。
 * 以前不管有没有都直接拼 `coordX, coordY`，结果是一列「., .」。
 */
function pointCoordText(point: AdminPoint): string {
  if (point.lng != null && point.lat != null) {
    return `${Number(point.lng).toFixed(5)}, ${Number(point.lat).toFixed(5)}`
  }
  if (point.coordX != null && point.coordY != null) {
    return `${Math.round(Number(point.coordX))}, ${Math.round(Number(point.coordY))}`
  }
  return '—'
}

const itemEmptyText = computed(() =>
  itemStatus.value === 1 ? '待审队列是空的，目前没有需要人工复核的商品' : '没有符合条件的商品',
)

function changeItemPage(page: number): void {
  itemPage.value = page
  loadItems()
}

function changePointPage(page: number): void {
  pointPage.value = page
  loadPoints()
}

/** tab 与筛选状态都进 URL：概览页卡片可以直接跳到「已下架点位」这种视图 */
useQuerySync(
  { activeTab, itemStatus, itemKeyword, itemPage, pointStatus, pointMapStatus, pointKeyword, pointPage },
  {
    activeTab: 'item' as TabName,
    itemStatus: 1,
    itemKeyword: '',
    itemPage: 1,
    pointStatus: -1,
    pointMapStatus: -1,
    pointKeyword: '',
    pointPage: 1,
  },
  {
    onChange: () => {
      // 外部改的是哪一半就刷哪一半，避免白打一次接口
      loadItems()
      loadPoints()
    },
    // 内部字段名 -> 短 URL 参数名，方便概览页卡片直接深链过来
    aliases: { activeTab: 'tab', itemStatus: 'status', itemKeyword: 'keyword', itemPage: 'page' },
  },
)

onMounted(() => {
  loadItems()
  loadPoints()
})
</script>

<template>
  <div class="admin-page audit">
    <div class="panel audit__intro">
      <div class="panel-title">审核工作台</div>
      <p class="text-muted">
        商品发布时先过 AI 机审：命中禁售词直接拦截，可疑内容进入「审核中」等待人工处置。
        面交点已改为<b>免审核</b>（用户自定义地点创建即生效），「点位处置」tab 用于<b>事后追溯</b>：
        发现不合适的点位可以直接<b>下架</b>（立刻从地图消失），误伤了也能恢复。
      </p>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="商品审核" name="item" />
      <el-tab-pane label="点位处置" name="point" />
    </el-tabs>

    <!-- ---------------- 商品 ---------------- -->
    <div v-show="activeTab === 'item'" class="panel audit__panel audit__panel--item">
      <div class="audit__toolbar">
        <el-select v-model="itemStatus" style="width: 160px" @change="searchItems">
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
        <el-button type="primary" :icon="Search" @click="searchItems">查询</el-button>
        <el-button :icon="Refresh" :loading="itemLoading" @click="loadItems">刷新</el-button>
        <div class="audit__toolbar-right">
          <span class="audit__count">共 {{ itemTotal }} 件</span>
          <span v-if="selectedItems.length" class="audit__chip">已选 {{ selectedItems.length }} 件</span>
          <el-button
            type="primary"
            :disabled="!selectedItems.length"
            :loading="batchLoading"
            @click="batchApprove"
          >
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
        max-height="560"
        class="audit__table admin-table"
        @selection-change="handleItemSelection"
      >
        <el-table-column type="selection" width="46" :selectable="(row: AdminItem) => row.status === 1" />
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
              >
                <template #error>
                  <div class="audit__cover-empty">无图</div>
                </template>
              </el-image>
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
        <el-table-column label="状态" width="104" align="center">
          <template #default="{ row }">
            <el-tag :type="itemStatusType(row.status)" effect="plain" class="audit__tag">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" align="center" fixed="right">
          <template #default="{ row }">
            <div v-if="row.status === 1" class="audit__ops">
              <el-button
                size="small"
                type="primary"
                :loading="busyItems.includes(row.id)"
                @click="approveOne(row)"
              >
                通过
              </el-button>
              <el-button link size="small" type="danger" @click="openRejectItem(row)">驳回</el-button>
            </div>
            <span v-else class="text-muted">已处置</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="itemEmptyText" />
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
    <div v-show="activeTab === 'point'" class="panel audit__panel audit__panel--point">
      <div class="audit__toolbar">
        <el-select v-model="pointStatus" style="width: 160px" @change="searchPoints">
          <el-option v-for="opt in POINT_AUDIT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select v-model="pointMapStatus" style="width: 150px" @change="searchPoints">
          <el-option v-for="opt in POINT_MAP_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="pointKeyword"
          placeholder="搜索点位名称"
          clearable
          style="width: 200px"
          @keyup.enter="searchPoints"
          @clear="searchPoints"
        />
        <el-button type="primary" :icon="Search" @click="searchPoints">查询</el-button>
        <el-button :icon="Refresh" :loading="pointLoading" @click="loadPoints">刷新</el-button>
        <el-button text @click="resetPointFilter">重置</el-button>
        <span class="audit__count audit__count--right">共 {{ pointTotal }} 个</span>
      </div>

      <el-table v-loading="pointLoading" :data="pointList" row-key="id" max-height="560" class="audit__table admin-table">
        <el-table-column label="点位" min-width="190">
          <template #default="{ row }">
            <p class="audit__strong">{{ row.name }}</p>
            <p class="text-muted audit__sub">
              {{ pointTypeLabel(row.pointType) }} · {{ row.campusName || '校区未知' }}
            </p>
          </template>
        </el-table-column>
        <el-table-column label="坐标" width="132">
          <template #default="{ row }">
            <span class="text-muted audit__sub">{{ pointCoordText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="安全分" width="80" align="center">
          <template #default="{ row }">{{ row.safetyScore ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="图文指引" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.guideText }">{{ row.guideText || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交人" min-width="100">
          <template #default="{ row }">
            {{ row.creatorNickname || (row.creatorId ? '#' + row.creatorId : '官方') }}
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="130">
          <template #default="{ row }">
            <span class="audit__sub">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="审核" width="84" align="center">
          <template #default="{ row }">
            <el-tag :type="pointAuditType(row.auditStatus)" effect="plain" class="audit__tag">
              {{ pointAuditText(row.auditStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地图" width="84" align="center">
          <template #default="{ row }">
            <el-tag :type="pointLive(row) ? 'success' : 'info'" effect="plain" class="audit__tag">
              {{ pointLive(row) ? '生效中' : '未上架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <div v-if="row.auditStatus === 0" class="audit__ops">
              <el-button link size="small" type="primary" @click="openPointApprove(row)">通过</el-button>
              <el-button link size="small" type="danger" @click="openRejectPoint(row)">驳回</el-button>
            </div>
            <div v-else-if="row.status === 1" class="audit__ops">
              <el-button
                size="small"
                type="danger"
                plain
                :loading="busyPoints.includes(row.id)"
                @click="openPointStatus(row, 0)"
              >
                下架
              </el-button>
            </div>
            <div v-else class="audit__ops">
              <el-button
                size="small"
                type="primary"
                plain
                :loading="busyPoints.includes(row.id)"
                @click="openPointStatus(row, 1)"
              >
                恢复
              </el-button>
            </div>
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

    <!-- ---------------- 点位下架 / 恢复弹窗 ---------------- -->
    <el-dialog
      v-model="pointStatusDialog.visible"
      :title="pointStatusDialog.status === 0 ? '下架点位' : '恢复点位'"
      width="520px"
    >
      <p class="text-muted audit__dialog-target">
        点位：{{ pointStatusDialog.target?.name }}
        <template v-if="pointStatusDialog.status === 0">
          · 下架后立刻从地图上消失，已约好面交的订单不受影响
        </template>
      </p>
      <el-form v-if="pointStatusDialog.status === 0" label-width="90px">
        <el-form-item label="下架原因">
          <div class="audit__reasons">
            <el-tag
              v-for="r in reasonOptions.point"
              :key="r.code"
              class="audit__reason"
              :type="pointStatusDialog.reasonCode === r.code ? 'danger' : 'info'"
              :effect="pointStatusDialog.reasonCode === r.code ? 'dark' : 'plain'"
              @click="togglePointReason(r.code)"
            >
              {{ r.label }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input
            v-model="pointStatusDialog.reason"
            type="textarea"
            :rows="3"
            maxlength="120"
            show-word-limit
            placeholder="选填，与上面选中的原因一起记入操作日志"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        title="恢复后点位会重新出现在地图上，同学可以继续把它选为面交地点"
      />
      <template #footer>
        <el-button @click="pointStatusDialog.visible = false">取消</el-button>
        <el-button
          :type="pointStatusDialog.status === 0 ? 'danger' : 'primary'"
          :loading="pointStatusDialog.loading"
          @click="submitPointStatus"
        >
          {{ pointStatusDialog.status === 0 ? '确认下架' : '确认恢复' }}
        </el-button>
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

  &__cover-empty {
    display: grid;
    width: 100%;
    height: 100%;
    place-items: center;
    background: var(--surface-2);
    color: var(--text-3);
    font-size: 10px;
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
