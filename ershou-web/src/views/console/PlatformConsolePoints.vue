<script setup lang="ts">
import { computed, inject, nextTick, onMounted, reactive, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  deleteAdminPoint,
  getAdminPoints,
  saveAdminPoint,
  toggleAdminPointStatus,
  updateAdminPoint,
} from '@/api/platform'
import type { PlatformPointSaveParams, PlatformPointStatus, PlatformPointVO } from '@/api/platform'
import { fetchCampuses } from '@/api/school'
import type { Campus } from '@/api/school'
import { DEFAULT_CAMPUS_ID, DEFAULT_SCHOOL_ID } from '@/utils/constants'
import { showTip } from '@/utils/feedback'

/**
 * 平台运营台 · 代卖点管理（第 3 轮，设计 4.4 Points）。
 *
 * 把原 `views/admin/AdminPlatformPointsView.vue` 的能力迁到独立运营台并放大排版：
 * 通栏表格（行高 56）、卡片圆角 16、前端分页 10/20/50。
 *
 * 口径（与后端一致，别当 bug）：
 * - `getAdminPoints(campusId)` 返回**含停用**的点位，状态筛选在前端做；校区由 Shell 顶栏统一控制
 *   （页面通过 `inject('campusId')` 读取，切换后自动重拉）。
 * - 删除是**软删（status=0）**，与「停用」落到同一状态：历史平台商品引用不破坏，可再启用。
 *
 * 接口复用 `@/api/platform` 的既有 5 个函数（设计 4.3 明确不重复实现）。
 */

const PAGE_SIZES = [10, 20, 50]

/* ------------------------------------------------------------------ *
 * 当前校区（Shell provide 的 Ref<number>；单独打开时兜底默认校区）
 * ------------------------------------------------------------------ */
const injectedCampus = inject<Ref<number> | number>('campusId', DEFAULT_CAMPUS_ID)
const campusId = computed(() =>
  typeof injectedCampus === 'number' ? injectedCampus : injectedCampus.value,
)

/* ------------------------------------------------------------------ *
 * 列表状态
 * ------------------------------------------------------------------ */
const loading = ref(false)
const rows = ref<PlatformPointVO[]>([])
const campuses = ref<Campus[]>([])
const statusFilter = ref<PlatformPointStatus | ''>('')
const keyword = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
/** 行内操作中的点位 id：只让那一行的按钮转圈 */
const busyId = ref(0)

const filteredRows = computed(() => {
  const status = statusFilter.value
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (status !== '' && row.status !== status) return false
    if (!kw) return true
    return (
      row.name.toLowerCase().includes(kw) ||
      (row.address ?? '').toLowerCase().includes(kw) ||
      (row.guideText ?? '').toLowerCase().includes(kw)
    )
  })
})

const maxPage = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value)))
const pageRows = computed(() => {
  const start = (pageNum.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

const enabledCount = computed(() => rows.value.filter((row) => row.status === 1).length)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await getAdminPoints(campusId.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
    clampPage()
  }
}

async function loadCampuses(): Promise<void> {
  try {
    campuses.value = await fetchCampuses(DEFAULT_SCHOOL_ID)
  } catch {
    campuses.value = []
  }
}

/** 筛选/删除后当前页可能越界，夹回最后一页，避免显示空表格 */
function clampPage(): void {
  if (pageNum.value > maxPage.value) pageNum.value = maxPage.value
}

function campusNameOf(row: PlatformPointVO): string {
  if (row.campusName) return row.campusName
  const hit = campuses.value.find((campus) => campus.id === row.campusId)
  return hit ? hit.name : `#${row.campusId}`
}

/** 坐标展示：6 位小数足够（GCJ-02 约 0.1m 精度），去掉无意义的尾零 */
function trimNumber(value: number): string {
  return String(Number(value.toFixed(6)))
}

watch([statusFilter, keyword], () => {
  pageNum.value = 1
})

watch(campusId, () => {
  pageNum.value = 1
  void load()
})

/* ------------------------------------------------------------------ *
 * 新建 / 编辑弹窗
 * ------------------------------------------------------------------ */
const formRef = ref<FormInstance>()
const dialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  loading: false,
})

const form = reactive({
  campusId: DEFAULT_CAMPUS_ID as number,
  name: '',
  address: '',
  lng: undefined as number | undefined,
  lat: undefined as number | undefined,
  openHours: '',
  contactPhone: '',
  guideText: '',
  sort: 0,
  status: 1 as PlatformPointStatus,
})

type ValidatorCallback = (error?: string | Error) => void

function isFilled(value: unknown): boolean {
  return value !== null && value !== undefined && value !== ''
}

/** 经纬度选填，但填了就要合法、且必须成对（只有经度没有纬度的点位在地图上没有意义） */
function validateLng(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (!isFilled(value)) {
    callback(isFilled(form.lat) ? '纬度已填，请同时填写经度' : undefined)
    return
  }
  const num = Number(value)
  if (!Number.isFinite(num) || num < -180 || num > 180) {
    callback('经度需在 -180 ~ 180 之间')
    return
  }
  callback(isFilled(form.lat) ? undefined : '经纬度需成对填写（都填或都留空）')
}

function validateLat(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (!isFilled(value)) {
    callback(isFilled(form.lng) ? '经度已填，请同时填写纬度' : undefined)
    return
  }
  const num = Number(value)
  if (!Number.isFinite(num) || num < -90 || num > 90) {
    callback('纬度需在 -90 ~ 90 之间')
    return
  }
  callback(isFilled(form.lng) ? undefined : '经纬度需成对填写（都填或都留空）')
}

/** 联系电话选填；只做「不像电话就拦住」的宽松校验（代卖点可能是固话/内部分机） */
function validatePhone(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (!isFilled(value)) {
    callback()
    return
  }
  if (!/^[0-9+\-() ]{5,20}$/.test(String(value).trim())) {
    callback('电话格式不正确（仅支持数字、+、-、括号、空格，5~20 位）')
    return
  }
  callback()
}

const rules: FormRules = {
  campusId: [{ required: true, message: '请选择校区', trigger: 'change' }],
  name: [
    { required: true, message: '请填写代卖点名称', trigger: 'blur' },
    { max: 64, message: '名称最多 64 个字', trigger: 'blur' },
  ],
  address: [
    { required: true, message: '请填写详细地址', trigger: 'blur' },
    { max: 120, message: '地址最多 120 个字', trigger: 'blur' },
  ],
  lng: [{ validator: validateLng, trigger: 'blur' }],
  lat: [{ validator: validateLat, trigger: 'blur' }],
  contactPhone: [{ validator: validatePhone, trigger: 'blur' }],
  openHours: [{ max: 64, message: '营业时间最多 64 个字', trigger: 'blur' }],
  guideText: [{ max: 200, message: '到点指引最多 200 个字', trigger: 'blur' }],
}

function nextSort(): number {
  return rows.value.reduce((max, row) => Math.max(max, row.sort ?? 0), 0) + 10
}

function openCreate(): void {
  dialog.mode = 'create'
  dialog.id = 0
  form.campusId = campusId.value
  form.name = ''
  form.address = ''
  form.lng = undefined
  form.lat = undefined
  form.openHours = ''
  form.contactPhone = ''
  form.guideText = ''
  form.sort = nextSort()
  form.status = 1
  dialog.visible = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: PlatformPointVO): void {
  dialog.mode = 'edit'
  dialog.id = row.id
  form.campusId = row.campusId
  form.name = row.name
  form.address = row.address
  form.lng = row.lng ?? undefined
  form.lat = row.lat ?? undefined
  form.openHours = row.openHours ?? ''
  form.contactPhone = row.contactPhone ?? ''
  form.guideText = row.guideText ?? ''
  form.sort = row.sort ?? 0
  form.status = row.status === 0 ? 0 : 1
  dialog.visible = true
  void nextTick(() => formRef.value?.clearValidate())
}

function textOrNull(value: string): string | null {
  const text = value.trim()
  return text ? text : null
}

async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const payload: PlatformPointSaveParams = {
    campusId: form.campusId,
    name: form.name.trim(),
    address: form.address.trim(),
    lng: form.lng ?? null,
    lat: form.lat ?? null,
    openHours: textOrNull(form.openHours),
    contactPhone: textOrNull(form.contactPhone),
    guideText: textOrNull(form.guideText),
    sort: form.sort ?? 0,
    status: form.status,
  }
  dialog.loading = true
  try {
    if (dialog.mode === 'create') {
      await saveAdminPoint(payload)
      showTip('已新建平台代卖点')
    } else {
      await updateAdminPoint(dialog.id, payload)
      showTip('已保存平台代卖点')
    }
    dialog.visible = false
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    dialog.loading = false
  }
}

async function handleToggle(row: PlatformPointVO): Promise<void> {
  const next: PlatformPointStatus = row.status === 1 ? 0 : 1
  if (next === 0) {
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」不会展示在买家端「平台严选」页，可随时重新启用。`,
        '停用平台代卖点',
        { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return // 用户取消
    }
  }
  busyId.value = row.id
  try {
    await toggleAdminPointStatus(row.id, next)
    showTip(next === 1 ? '已启用' : '已停用')
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    busyId.value = 0
  }
}

async function handleDelete(row: PlatformPointVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除代卖点「${row.name}」吗？这是软删除：点位会停用（status=0）并不再展示给买家，` +
        '历史平台商品对它的引用不受影响，之后还可以重新启用。',
      '删除平台代卖点',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // 用户取消
  }
  busyId.value = row.id
  try {
    await deleteAdminPoint(row.id)
    showTip('已删除（软删，可重新启用恢复）')
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    busyId.value = 0
  }
}

onMounted(() => {
  void loadCampuses()
  void load()
})
</script>

<template>
  <div class="console-page">
    <!-- 工具条 -->
    <div class="cp-bar">
      <div class="cp-bar__title">代卖点</div>
      <span class="cp-bar__count">
        共 {{ filteredRows.length }} 个 · 启用 {{ enabledCount }} 个
      </span>
      <el-select
        v-model="statusFilter"
        placeholder="全部状态"
        clearable
        :value-on-clear="''"
        style="width: 132px"
      >
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索名称 / 地址 / 指引"
        clearable
        :prefix-icon="Search"
        style="width: 240px"
      />
      <div class="cp-bar__spacer" />
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建代卖点</el-button>
    </div>

    <!-- 表格 -->
    <div class="cp-card">
      <div class="cp-card__head">
        <div>
          <p class="cp-card__title">代卖点列表</p>
          <p class="cp-sub">停用与删除都是 status=0（软删）：历史平台商品引用不破坏，可随时重新启用</p>
        </div>
        <span class="cp-sub">第 {{ pageNum }} / {{ maxPage }} 页</span>
      </div>

      <el-skeleton v-if="loading && !rows.length" :rows="8" animated class="cp-skeleton" />

      <el-table v-else :data="pageRows" row-key="id" class="cp-table">
        <el-table-column label="名称" min-width="170">
          <template #default="{ row }">
            <p class="cp-name">{{ row.name }}</p>
            <p v-if="row.guideText" class="cp-sub cp-ellipsis">{{ row.guideText }}</p>
            <p v-else-if="row.itemCount != null" class="cp-sub">挂着 {{ row.itemCount }} 件平台商品</p>
          </template>
        </el-table-column>
        <el-table-column label="校区" width="96">
          <template #default="{ row }">{{ campusNameOf(row) }}</template>
        </el-table-column>
        <el-table-column label="地址" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address }}</template>
        </el-table-column>
        <el-table-column label="经纬度（GCJ-02）" width="146">
          <template #default="{ row }">
            <template v-if="row.lng != null && row.lat != null">
              <p class="cp-coord">{{ trimNumber(row.lng) }}</p>
              <p class="cp-coord">{{ trimNumber(row.lat) }}</p>
            </template>
            <span v-else class="cp-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="营业时间" width="104">
          <template #default="{ row }">{{ row.openHours || '—' }}</template>
        </el-table-column>
        <el-table-column label="联系电话" width="110">
          <template #default="{ row }">{{ row.contactPhone || '—' }}</template>
        </el-table-column>
        <el-table-column label="排序" width="68" align="center">
          <template #default="{ row }">{{ row.sort }}</template>
        </el-table-column>
        <el-table-column label="状态" width="82" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light" round size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="156" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :loading="busyId === row.id" @click="handleToggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" :loading="busyId === row.id" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="cp-empty">
            <div class="cp-empty__icon">📍</div>
            <p>{{ rows.length ? '没有符合筛选条件的代卖点' : '当前校区还没有代卖点，点右上角「新建代卖点」创建' }}</p>
          </div>
        </template>
      </el-table>

      <div v-if="filteredRows.length" class="cp-card__foot">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="PAGE_SIZES"
          :total="filteredRows.length"
        />
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'create' ? '新建平台代卖点' : '编辑平台代卖点'"
      width="780px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-row :gutter="18">
          <el-col :span="12">
            <el-form-item label="名称" prop="name">
              <el-input
                v-model="form.name"
                maxlength="64"
                show-word-limit
                placeholder="如：花江校区 · 官方严选代卖点"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="校区" prop="campusId">
              <el-select v-model="form.campusId" placeholder="请选择校区" style="width: 100%">
                <el-option
                  v-for="campus in campuses"
                  :key="campus.id"
                  :label="campus.name"
                  :value="campus.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="详细地址" prop="address">
              <el-input
                v-model="form.address"
                maxlength="120"
                show-word-limit
                placeholder="如：花江校区商业街 12 号（买家自提点）"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="经度" prop="lng">
              <el-input-number
                v-model="form.lng"
                :min="-180"
                :max="180"
                :precision="6"
                :step="0.000001"
                :controls="false"
                placeholder="如：110.416819"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="纬度" prop="lat">
              <el-input-number
                v-model="form.lat"
                :min="-90"
                :max="90"
                :precision="6"
                :step="0.000001"
                :controls="false"
                placeholder="如：25.311724"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="营业时间" prop="openHours">
              <el-input v-model="form.openHours" maxlength="64" placeholder="如：09:00-21:00（选填）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="联系电话" prop="contactPhone">
              <el-input v-model="form.contactPhone" maxlength="20" placeholder="选填，买家可电话咨询" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序">
              <el-input-number
                v-model="form.sort"
                :min="0"
                :max="9999"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio-button :value="1">启用</el-radio-button>
                <el-radio-button :value="0">停用</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="到点指引" prop="guideText">
              <el-input
                v-model="form.guideText"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                placeholder="选填，如：进商业街后左转 30 米，认准橙色「官方严选」灯箱"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <p class="cp-sub cp-dialog-tip">
          经纬度选填，但需成对填写；排序数字越小越靠前；停用后买家端不展示该点位。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
$radius-lg: 16px;
$card-shadow: 0 1px 2px rgb(15 23 42 / 4%), 0 12px 32px rgb(15 23 42 / 6%);

.console-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

// ---------- 工具条 ----------
.cp-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 22px;
  border-radius: $radius-lg;
  background: #fff;
  box-shadow: $card-shadow;

  &__title {
    margin-right: 4px;
    color: var(--text-1);
    font-size: 19px;
    font-weight: 700;
    letter-spacing: -0.2px;
  }

  &__count {
    color: var(--text-3);
    font-size: 13px;
  }

  &__spacer {
    flex: 1;
  }
}

// ---------- 卡片 ----------
.cp-card {
  overflow: hidden;
  border-radius: $radius-lg;
  background: #fff;
  box-shadow: $card-shadow;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 18px 22px;
    border-bottom: 1px solid var(--line);
  }

  &__title {
    color: var(--text-1);
    font-size: 17px;
    font-weight: 700;
  }

  &__foot {
    display: flex;
    justify-content: flex-end;
    padding: 14px 22px;
    border-top: 1px solid var(--line);
  }
}

.cp-skeleton {
  padding: 22px;
}

// ---------- 表格（通栏 + 行高 56）----------
.cp-table {
  width: 100%;

  :deep(.el-table) {
    --el-table-border-color: var(--line);
    --el-table-header-bg-color: #f8fafc;
    --el-table-row-hover-bg-color: #f8fafc;
    color: var(--text-2);
    font-size: 13.5px;
  }

  :deep(th.el-table__cell) {
    height: 48px;
    padding: 8px 0;
    background: #f8fafc;
    color: var(--text-2);
    font-weight: 600;
  }

  :deep(td.el-table__cell) {
    height: 56px;
    padding: 8px 0;
  }

  :deep(.cell) {
    line-height: 1.5;
  }
}

// ---------- 文本 ----------
.cp-name {
  color: var(--text-1);
  font-size: 14px;
  font-weight: 600;
}

.cp-sub {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cp-ellipsis {
  overflow: hidden;
  max-width: 210px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-coord {
  color: var(--text-2);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  line-height: 1.4;
}

.cp-dialog-tip {
  margin-top: 2px;
  padding-left: 96px;
}

// ---------- 空态 ----------
.cp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--text-3);
  font-size: 13px;

  &__icon {
    display: grid;
    width: 56px;
    height: 56px;
    place-items: center;
    border-radius: 50%;
    background: var(--surface-2);
    font-size: 24px;
  }
}

@media (max-width: 1200px) {
  .cp-bar {
    &__spacer {
      display: none;
    }
  }
}
</style>
