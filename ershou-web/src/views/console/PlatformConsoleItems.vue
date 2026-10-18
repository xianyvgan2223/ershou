<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Picture, Plus, Refresh, Search } from '@element-plus/icons-vue'
import ImageUploader from '@/components/ImageUploader.vue'
import { fetchCategories } from '@/api/auth'
import { getAdminPoints, publishPlatformItem } from '@/api/platform'
import type { PlatformPointVO } from '@/api/platform'
import {
  fetchConsoleItems,
  offlineConsoleItem,
  onlineConsoleItem,
  updateConsoleItem,
} from '@/api/platform-console'
import type { AdminItemQuery, ConsoleAdminItemVO } from '@/api/platform-console'
import type { Category } from '@/api/types'
import { CONDITION_OPTIONS, DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { useQuerySync } from '@/composables/useQuerySync'
import { showTip } from '@/utils/feedback'
import { formatCount, formatDateTime, formatPrice, itemStatusLabel, itemStatusTagType } from '@/utils/format'

/**
 * 运营台 · 平台商品管理（第 3 轮设计 4.4 Items）。
 *
 * 能力：状态 tabs / 关键词 / 代卖点筛选 + 发布平台商品（复用 `publishPlatformItem`）
 * + 通栏表格 + 编辑（标题/价格/库存/代卖点）+ 下架 / 重新上架 + 分页。
 *
 * 发布走平台既有接口（`@/api/platform#publishPlatformItem`）：后端固定 `seller_id=1000`、
 * `seller_type=2`、免审核直接上架、有效期 30 天，代卖点必须属于同校区。
 */

const STATUS_TABS = [
  { label: '全部', value: 'all' },
  { label: '在售', value: '2' },
  { label: '已下架', value: '5' },
  { label: '已售出', value: '4' },
  { label: '已过期', value: '7' },
  { label: '审核中', value: '1' },
]

const PAGE_SIZES = [10, 20, 50]

const route = useRoute()
const router = useRouter()
const campusId = inject<Ref<number>>('campusId', ref(DEFAULT_CAMPUS_ID))

const loading = ref(false)
const list = ref<ConsoleAdminItemVO[]>([])
const total = ref(0)
const busyId = ref(0)

const statusTab = ref<string>('all')
const keyword = ref('')
const pointId = ref<number | ''>('')
const pageNum = ref(1)
const pageSize = ref(10)

const points = ref<PlatformPointVO[]>([])
const categories = ref<Category[]>([])

/** 可选的代卖点：发布/编辑时只能挂启用中的点位（停用的点位不展示给买家） */
const enabledPoints = computed(() => points.value.filter((point) => point.status === 1))

const hasFilter = computed(
  () => statusTab.value !== 'all' || !!keyword.value.trim() || pointId.value !== '',
)

/* ------------------------------------------------------------------ *
 * 列表
 * ------------------------------------------------------------------ */

async function load(): Promise<void> {
  loading.value = true
  try {
    const query: AdminItemQuery = {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      campusId: campusId.value,
      status: statusTab.value === 'all' ? undefined : Number(statusTab.value),
      keyword: keyword.value.trim() || undefined,
      pointId: pointId.value === '' ? undefined : pointId.value,
    }
    const res = await fetchConsoleItems(query)
    list.value = res.records ?? []
    total.value = res.total ?? 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadPoints(): Promise<void> {
  try {
    points.value = await getAdminPoints(campusId.value)
  } catch {
    points.value = []
  }
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await fetchCategories()
  } catch {
    categories.value = []
  }
}

function search(): void {
  pageNum.value = 1
  void load()
}

function reset(): void {
  statusTab.value = 'all'
  keyword.value = ''
  pointId.value = ''
  pageNum.value = 1
  void load()
}

function onStatusTab(): void {
  pageNum.value = 1
  void load()
}

/** 顶栏切校区：列表与点位选项都要跟着换 */
watch(campusId, () => {
  pageNum.value = 1
  void Promise.all([loadPoints(), load()])
})

/* ------------------------------------------------------------------ *
 * 发布平台商品
 * ------------------------------------------------------------------ */

const publishVisible = ref(false)
const publishSubmitting = ref(false)
const publishFormRef = ref<FormInstance>()

const publishForm = reactive({
  images: [] as string[],
  title: '',
  description: '',
  categoryId: undefined as number | undefined,
  conditionLevel: 3,
  price: undefined as number | undefined,
  originalPrice: undefined as number | undefined,
  stock: 10,
  platformPointId: undefined as number | undefined,
})

const publishRules: FormRules = {
  images: [
    {
      validator: (_rule: unknown, value: unknown, callback: (error?: string | Error) => void) => {
        if (!Array.isArray(value) || value.length === 0) callback('请至少上传一张商品图片')
        else callback()
      },
      trigger: 'change',
    },
  ],
  title: [
    { required: true, message: '请填写商品标题', trigger: 'blur' },
    { min: 2, max: 64, message: '标题 2~64 个字', trigger: 'blur' },
  ],
  categoryId: [{ required: true, message: '请选择商品分类', trigger: 'change' }],
  price: [{ required: true, message: '请填写售价', trigger: 'blur' }],
  platformPointId: [{ required: true, message: '请选择代卖点', trigger: 'change' }],
}

function resetPublishForm(): void {
  publishForm.images = []
  publishForm.title = ''
  publishForm.description = ''
  publishForm.categoryId = undefined
  publishForm.conditionLevel = 3
  publishForm.price = undefined
  publishForm.originalPrice = undefined
  publishForm.stock = 10
  publishForm.platformPointId = undefined
}

function openPublish(): void {
  resetPublishForm()
  publishVisible.value = true
}

async function submitPublish(): Promise<void> {
  const valid = await publishFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const categoryId = publishForm.categoryId
  const price = publishForm.price
  const pointIdValue = publishForm.platformPointId
  if (!categoryId || price === undefined || price <= 0 || !pointIdValue) {
    showTip('请把必填项填写完整', 'warning')
    return
  }
  if (publishForm.originalPrice !== undefined && publishForm.originalPrice < price) {
    showTip('原价不应低于售价', 'warning')
    return
  }
  publishSubmitting.value = true
  try {
    await publishPlatformItem({
      title: publishForm.title.trim(),
      description: publishForm.description.trim() || undefined,
      categoryId,
      conditionLevel: publishForm.conditionLevel,
      price,
      originalPrice: publishForm.originalPrice ?? null,
      stock: publishForm.stock,
      images: publishForm.images,
      platformPointId: pointIdValue,
      campusId: campusId.value,
    })
    showTip('平台商品已发布（免审核、有效期 30 天）', 'success')
    publishVisible.value = false
    await load()
  } catch {
    // 拦截器已提示（如代卖点不属于同校区）
  } finally {
    publishSubmitting.value = false
  }
}

/* ------------------------------------------------------------------ *
 * 编辑平台商品
 * ------------------------------------------------------------------ */

const editVisible = ref(false)
const editSubmitting = ref(false)
const editFormRef = ref<FormInstance>()
const editTarget = ref<ConsoleAdminItemVO | null>(null)

const editForm = reactive({
  title: '',
  price: undefined as number | undefined,
  stock: 0,
  platformPointId: undefined as number | undefined,
})

const editRules: FormRules = {
  title: [
    { required: true, message: '请填写商品标题', trigger: 'blur' },
    { min: 2, max: 64, message: '标题 2~64 个字', trigger: 'blur' },
  ],
  price: [{ required: true, message: '请填写售价', trigger: 'blur' }],
}

function openEdit(row: ConsoleAdminItemVO): void {
  editTarget.value = row
  editForm.title = row.title
  editForm.price = row.price
  editForm.stock = row.stock ?? 0
  editForm.platformPointId = row.pointId ?? undefined
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid || !editTarget.value) return
  const price = editForm.price
  if (price === undefined || price <= 0) {
    showTip('请填写正确的售价', 'warning')
    return
  }
  editSubmitting.value = true
  try {
    await updateConsoleItem(editTarget.value.id, {
      title: editForm.title.trim(),
      price,
      stock: Math.max(0, editForm.stock),
      platformPointId: editForm.platformPointId,
    })
    showTip('已保存，买家端立即生效', 'success')
    editVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    editSubmitting.value = false
  }
}

/* ------------------------------------------------------------------ *
 * 下架 / 重新上架
 * ------------------------------------------------------------------ */

async function handleOffline(row: ConsoleAdminItemVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `下架「${row.title}」后买家端不再展示（商品状态置为已下架），随时可以重新上架。`,
      '下架平台商品',
      { confirmButtonText: '确认下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // 用户取消
  }
  busyId.value = row.id
  try {
    await offlineConsoleItem(row.id)
    showTip('已下架', 'success')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    busyId.value = 0
  }
}

async function handleOnline(row: ConsoleAdminItemVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `重新上架「${row.title}」后买家端立即可见，有效期重新计算为 30 天。`,
      '重新上架平台商品',
      { confirmButtonText: '确认上架', cancelButtonText: '取消', type: 'success' },
    )
  } catch {
    return // 用户取消
  }
  busyId.value = row.id
  try {
    await onlineConsoleItem(row.id)
    showTip('已重新上架', 'success')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    busyId.value = 0
  }
}

/* ------------------------------------------------------------------ *
 * URL 状态同步（刷新/分享/后退不丢筛选，与后台其他列表页同款）
 * ------------------------------------------------------------------ */

useQuerySync(
  { statusTab, keyword, pointId, pageNum, pageSize },
  { statusTab: 'all', keyword: '', pointId: '' as number | '', pageNum: 1, pageSize: 10 },
  {
    onChange: () => {
      pageNum.value = 1
      void load()
    },
    aliases: { statusTab: 'status', keyword: 'keyword', pointId: 'point', pageNum: 'page', pageSize: 'size' },
    numeric: ['pointId'],
  },
)

onMounted(async () => {
  await Promise.all([loadPoints(), loadCategories()])
  await load()
  // 概览页「发布平台商品」快捷入口带 ?publish=1 进来时自动打开弹窗，并清掉该参数
  if (route.query.publish === '1') {
    openPublish()
    const query = { ...route.query }
    delete query.publish
    void router.replace({ query })
  }
})
</script>

<template>
  <!-- 根节点保留运营台约定类 console-page（QA 用它断言页面已挂载，与其余 6 页一致）；.items 只负责本页布局 -->
  <div class="items console-page">
    <!-- ================= 筛选条 ================= -->
    <section class="items__toolbar">
      <el-tabs v-model="statusTab" class="items__tabs" @tab-change="onStatusTab">
        <el-tab-pane
          v-for="tab in STATUS_TABS"
          :key="tab.value"
          :label="tab.label"
          :name="tab.value"
        />
      </el-tabs>
      <div class="items__filter">
        <el-input
          v-model="keyword"
          placeholder="搜索商品标题"
          clearable
          :prefix-icon="Search"
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select
          v-model="pointId"
          placeholder="全部代卖点"
          clearable
          :value-on-clear="''"
          style="width: 180px"
        >
          <el-option
            v-for="point in points"
            :key="point.id"
            :label="point.name"
            :value="point.id"
          />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="hasFilter" text @click="reset">重置</el-button>
        <el-button type="primary" :icon="Plus" class="items__publish-btn" @click="openPublish">
          发布平台商品
        </el-button>
      </div>
    </section>

    <!-- ================= 通栏表格 ================= -->
    <section class="card">
      <el-table v-loading="loading" :data="list" row-key="id" class="pc-table items__table">
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div class="items__goods">
              <el-image v-if="row.coverUrl" :src="row.coverUrl" fit="cover" class="items__cover" />
              <span v-else class="items__cover items__cover--empty">
                <el-icon><Picture /></el-icon>
              </span>
              <div class="items__goods-text">
                <p class="items__title">{{ row.title }}</p>
                <p class="items__sub">#{{ row.id }} · 平台代卖</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="100" align="right">
          <template #default="{ row }">
            <p class="items__price">¥{{ formatPrice(row.price) }}</p>
            <p v-if="row.originalPrice" class="items__origin">¥{{ formatPrice(row.originalPrice) }}</p>
          </template>
        </el-table-column>
        <el-table-column label="库存" width="80" align="center">
          <template #default="{ row }">
            <span class="items__stock" :class="{ 'is-low': (row.stock ?? 0) <= 2 }">
              {{ row.stock ?? 0 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="itemStatusTagType(row.status)" effect="plain" size="small">
              {{ row.statusText || itemStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="代卖点" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.pointName || '—' }}</template>
        </el-table-column>
        <el-table-column label="浏览" width="80" align="right">
          <template #default="{ row }">
            <span class="items__sub">{{ formatCount(row.viewCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="130">
          <template #default="{ row }">
            <span class="items__sub">{{ formatDateTime(row.publishedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="right" fixed="right">
          <template #default="{ row }">
            <div class="items__ops">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="row.status === 2"
                link
                type="warning"
                size="small"
                :loading="busyId === row.id"
                @click="handleOffline(row)"
              >
                下架
              </el-button>
              <el-button
                v-else-if="row.status === 5 || row.status === 7"
                link
                type="success"
                size="small"
                :loading="busyId === row.id"
                @click="handleOnline(row)"
              >
                重新上架
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="hasFilter ? '没有符合条件的平台商品' : '还没有平台商品，点右上角「发布平台商品」'"
          />
        </template>
      </el-table>

      <div v-if="total > 0" class="items__pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-sizes="PAGE_SIZES"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </section>

    <!-- ================= 发布弹窗 ================= -->
    <el-dialog v-model="publishVisible" title="发布平台商品" width="700px" :close-on-click-modal="false">
      <el-form ref="publishFormRef" :model="publishForm" :rules="publishRules" label-width="96px">
        <el-form-item label="商品图片" prop="images">
          <ImageUploader v-model="publishForm.images" :limit="6" biz="item" />
        </el-form-item>
        <el-form-item label="商品标题" prop="title">
          <el-input
            v-model="publishForm.title"
            maxlength="64"
            show-word-limit
            placeholder="如：[官方严选] 全新蓝牙耳机"
          />
        </el-form-item>
        <el-form-item label="商品描述">
          <el-input
            v-model="publishForm.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="成色、配件、自提说明等（选填）"
          />
        </el-form-item>
        <div class="items__form-row">
          <el-form-item label="商品分类" prop="categoryId">
            <el-select v-model="publishForm.categoryId" placeholder="请选择分类" style="width: 220px">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="成色">
            <el-select v-model="publishForm.conditionLevel" style="width: 180px">
              <el-option
                v-for="option in CONDITION_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </div>
        <div class="items__form-row">
          <el-form-item label="售价" prop="price">
            <el-input-number v-model="publishForm.price" :min="0.01" :precision="2" :controls="false" style="width: 160px" />
          </el-form-item>
          <el-form-item label="原价">
            <el-input-number
              v-model="publishForm.originalPrice"
              :min="0"
              :precision="2"
              :controls="false"
              style="width: 160px"
            />
          </el-form-item>
          <el-form-item label="库存">
            <el-input-number v-model="publishForm.stock" :min="1" :max="9999" :controls="false" style="width: 130px" />
          </el-form-item>
        </div>
        <el-form-item label="代卖点" prop="platformPointId">
          <el-select
            v-model="publishForm.platformPointId"
            placeholder="选择买家自提的代卖点（限当前校区）"
            style="width: 360px"
          >
            <el-option
              v-for="point in enabledPoints"
              :key="point.id"
              :label="`${point.name}（${point.address}）`"
              :value="point.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button type="primary" :loading="publishSubmitting" @click="submitPublish">发布</el-button>
      </template>
    </el-dialog>

    <!-- ================= 编辑弹窗 ================= -->
    <el-dialog v-model="editVisible" title="编辑平台商品" width="560px" :close-on-click-modal="false">
      <p class="items__dialog-target">#{{ editTarget?.id }} · {{ editTarget?.title }}</p>
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="96px">
        <el-form-item label="商品标题" prop="title">
          <el-input v-model="editForm.title" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="售价" prop="price">
          <el-input-number v-model="editForm.price" :min="0.01" :precision="2" :controls="false" style="width: 160px" />
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="editForm.stock" :min="0" :max="9999" :controls="false" style="width: 130px" />
        </el-form-item>
        <el-form-item label="代卖点">
          <el-select v-model="editForm.platformPointId" placeholder="选择代卖点" style="width: 340px" clearable>
            <el-option
              v-for="point in enabledPoints"
              :key="point.id"
              :label="point.name"
              :value="point.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
// =============================================================
// 运营台 · 平台商品：tabs 筛选条 + 通栏表格（行高 56）+ 两个弹窗
// =============================================================

.items {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.items__toolbar {
  padding: 6px 22px 18px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%), 0 12px 32px rgb(15 23 42 / 6%);
}

.items__tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 10px;
  }

  :deep(.el-tabs__item) {
    height: 46px;
    color: var(--text-2);
    font-size: 14px;
    font-weight: 600;
  }

  :deep(.el-tabs__item.is-active) {
    color: var(--brand-600);
  }

  :deep(.el-tabs__active-bar) {
    background: var(--brand-500);
  }
}

.items__filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.items__publish-btn {
  margin-left: auto;
}

/* ---------------- 卡片 + 表格 ---------------- */
.card {
  padding: 18px 22px 20px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%), 0 12px 32px rgb(15 23 42 / 6%);
}

.items__table {
  width: 100%;

  :deep(.el-table__header th.el-table__cell) {
    padding: 10px 0;
    background: #f8fafc;
    color: var(--text-3);
    font-size: 12px;
    font-weight: 500;
  }

  :deep(.el-table__body td.el-table__cell) {
    height: 56px;
    padding: 0;
    font-size: 13px;
  }
}

.items__goods {
  display: flex;
  align-items: center;
  gap: 12px;
}

.items__cover {
  flex: 0 0 44px;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: var(--surface-2);
}

.items__cover--empty {
  display: grid;
  place-items: center;
  color: var(--text-3);
  font-size: 18px;
}

.items__goods-text {
  min-width: 0;
}

.items__title {
  overflow: hidden;
  color: var(--text-1);
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.items__sub {
  color: var(--text-3);
  font-size: 12px;
}

.items__price {
  color: var(--text-1);
  font-size: 13.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.items__origin {
  color: var(--text-3);
  font-size: 11.5px;
  text-decoration: line-through;
}

.items__stock {
  color: var(--text-1);
  font-size: 13px;
  font-weight: 600;

  &.is-low {
    color: var(--accent-600);
  }
}

.items__ops {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}

.items__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.items__form-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;

  :deep(.el-form-item) {
    margin-bottom: 18px;
  }
}

.items__dialog-target {
  margin-bottom: 14px;
  color: var(--text-3);
  font-size: 12.5px;
}

@media (max-width: 900px) {
  .items__publish-btn {
    margin-left: 0;
  }

  .items__form-row {
    display: block;
  }
}
</style>
