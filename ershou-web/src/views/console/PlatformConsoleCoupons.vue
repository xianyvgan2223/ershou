<script setup lang="ts">
import { computed, inject, nextTick, onMounted, reactive, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh, Tickets } from '@element-plus/icons-vue'
import {
  fetchConsoleCoupons,
  saveConsoleCoupon,
  toggleConsoleCoupon,
  updateConsoleCoupon,
} from '@/api/platform-console'
import type { ConsoleAdminCouponVO, ConsoleCouponSaveParams } from '@/api/platform-console'
import { couponFaceValue, couponRuleText } from '@/api/platform'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { formatPrice } from '@/utils/format'
import { showTip } from '@/utils/feedback'

/**
 * 平台运营台 · 优惠券管理（第 3 轮，设计 4.4 Coupons）。
 *
 * 能力：券列表（类型/门槛/面额/总量/已领/剩余/有效期/状态）+ 领取进度条 +
 * 新建/编辑弹窗 + 启停。
 *
 * 口径（与后端/DB 一致，别按自己的直觉改）：
 * - `discount_rate` 在 DB 里是 **0.90 = 9 折**（`sql/migration-platform.sql:81`），
 *   所以弹窗里让运营按「几折」输入（9 / 8.5），提交时除以 10。
 * - `leftStock` 优先 Redis、未预热回退 `totalStock - claimedCount`，已领进度用 `claimedCount`。
 * - 编辑时把 `totalStock` 改到小于已领数会被后端拒绝（400），前端先挡一次并给明确文案。
 */

const PAGE_SIZES = [10, 20, 50]

/** 当前校区（Shell provide 的 Ref<number>；单独打开时兜底默认校区） */
const injectedCampus = inject<Ref<number> | number>('campusId', DEFAULT_CAMPUS_ID)
const campusId = computed(() =>
  typeof injectedCampus === 'number' ? injectedCampus : injectedCampus.value,
)

/* ------------------------------------------------------------------ *
 * 列表
 * ------------------------------------------------------------------ */
const loading = ref(false)
const rows = ref<ConsoleAdminCouponVO[]>([])
const typeFilter = ref<number | ''>('')
const keyword = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const busyId = ref(0)

const filteredRows = computed(() => {
  const type = typeFilter.value
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (type !== '' && row.type !== type) return false
    if (!kw) return true
    return row.name.toLowerCase().includes(kw)
  })
})

const maxPage = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value)))
const pageRows = computed(() => {
  const start = (pageNum.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

/** 顶部汇总：总库存 / 已领 / 整体领取率 */
const summary = computed(() => {
  const totalStock = rows.value.reduce((sum, row) => sum + (row.totalStock || 0), 0)
  const claimed = rows.value.reduce((sum, row) => sum + (row.claimedCount || 0), 0)
  return {
    totalStock,
    claimed,
    percent: totalStock > 0 ? Math.min(100, Math.round((claimed / totalStock) * 100)) : 0,
  }
})

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await fetchConsoleCoupons(campusId.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
    clampPage()
  }
}

function clampPage(): void {
  if (pageNum.value > maxPage.value) pageNum.value = maxPage.value
}

watch([typeFilter, keyword], () => {
  pageNum.value = 1
})

watch(campusId, () => {
  pageNum.value = 1
  void load()
})

/* ------------------------------------------------------------------ *
 * 展示辅助
 * ------------------------------------------------------------------ */
function typeText(type: number): string {
  return type === 2 ? '折扣券' : '满减券'
}

function thresholdText(row: ConsoleAdminCouponVO): string {
  const threshold = Number(row.thresholdAmount ?? 0)
  return threshold > 0 ? `满 ¥${formatPrice(threshold)}` : '无门槛'
}

function dateShort(value?: string | null): string {
  if (!value) return '--'
  return value.replace('T', ' ').slice(0, 10)
}

/** 已领进度（0~100），totalStock 为 0 时按 0 处理 */
function claimPercent(row: ConsoleAdminCouponVO): number {
  const total = Number(row.totalStock ?? 0)
  if (total <= 0) return 0
  return Math.min(100, Math.round((Number(row.claimedCount ?? 0) / total) * 100))
}

function statusTagType(status: number): 'success' | 'info' {
  return status === 1 ? 'success' : 'info'
}

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
  name: '',
  type: 1,
  thresholdAmount: 0 as number | undefined,
  discountAmount: 0 as number | undefined,
  /** 折扣（几折）：9 表示 9 折；提交时 /10 转成后端要求的 0.90 */
  discountRate: 9 as number | undefined,
  totalStock: 100,
  perUserLimit: 1,
  // el-date-picker 的 model 类型不接受 null（清空时运行时会给 null，提交前统一做非空校验）
  dateRange: undefined as string[] | undefined,
  status: 1,
})

/** 编辑态下该券已领数量：totalStock 不能改到比它小（后端也会拒） */
const editingClaimed = ref(0)

type ValidatorCallback = (error?: string | Error) => void

function validateThreshold(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (form.type !== 1) {
    callback()
    return
  }
  const num = Number(value ?? 0)
  if (!Number.isFinite(num) || num < 0) {
    callback('门槛金额不能为负')
    return
  }
  callback()
}

function validateDiscountAmount(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (form.type !== 1) {
    callback()
    return
  }
  const num = Number(value ?? 0)
  if (!Number.isFinite(num) || num <= 0) {
    callback('满减券需要填写大于 0 的减免金额')
    return
  }
  const threshold = Number(form.thresholdAmount ?? 0)
  if (threshold > 0 && num >= threshold) {
    callback('减免金额需小于门槛金额')
    return
  }
  callback()
}

function validateDiscountRate(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (form.type !== 2) {
    callback()
    return
  }
  const num = Number(value ?? 0)
  if (!Number.isFinite(num) || num <= 0 || num > 10) {
    callback('折扣需在 0 ~ 10 之间（如 9 表示 9 折）')
    return
  }
  callback()
}

function validateDateRange(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
    callback('请选择券的生效起止时间')
    return
  }
  callback()
}

function validateTotalStock(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  const num = Number(value ?? 0)
  if (!Number.isInteger(num) || num < 1) {
    callback('总库存需为大于 0 的整数')
    return
  }
  if (editingClaimed.value > num) {
    callback(`已发放 ${editingClaimed.value} 张，总库存不能小于已发放数`)
    return
  }
  callback()
}

const rules: FormRules = {
  name: [
    { required: true, message: '请填写券名称', trigger: 'blur' },
    { max: 64, message: '名称最多 64 个字', trigger: 'blur' },
  ],
  thresholdAmount: [{ validator: validateThreshold, trigger: 'blur' }],
  discountAmount: [{ validator: validateDiscountAmount, trigger: 'blur' }],
  discountRate: [{ validator: validateDiscountRate, trigger: 'blur' }],
  totalStock: [{ validator: validateTotalStock, trigger: 'blur' }],
  dateRange: [{ validator: validateDateRange, trigger: 'change' }],
}

function openCreate(): void {
  dialog.mode = 'create'
  dialog.id = 0
  editingClaimed.value = 0
  form.name = ''
  form.type = 1
  form.thresholdAmount = 0
  form.discountAmount = 0
  form.discountRate = 9
  form.totalStock = 100
  form.perUserLimit = 1
  form.dateRange = undefined
  form.status = 1
  dialog.visible = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: ConsoleAdminCouponVO): void {
  dialog.mode = 'edit'
  dialog.id = row.id
  editingClaimed.value = Number(row.claimedCount ?? 0)
  form.name = row.name
  form.type = row.type === 2 ? 2 : 1
  form.thresholdAmount = row.thresholdAmount ?? 0
  form.discountAmount = row.discountAmount ?? 0
  // 后端可能下发 0.9 或 90，统一换算成「几折」展示
  const rate = Number(row.discountRate ?? 0)
  form.discountRate = rate > 1 ? Number((rate / 10).toFixed(2)) : Number((rate * 10).toFixed(2))
  form.totalStock = row.totalStock
  form.perUserLimit = row.perUserLimit
  form.dateRange = [row.startAt, row.endAt]
  form.status = row.status
  dialog.visible = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const range = form.dateRange
  if (!range || range.length !== 2) return

  const isDiscount = form.type === 2
  const discountRate = isDiscount
    ? Number((Number(form.discountRate ?? 0) / 10).toFixed(2))
    : null
  const payload: ConsoleCouponSaveParams = {
    name: form.name.trim(),
    type: form.type,
    thresholdAmount: Number(form.thresholdAmount ?? 0),
    discountAmount: isDiscount ? null : Number(form.discountAmount ?? 0),
    discountRate,
    totalStock: Number(form.totalStock),
    perUserLimit: Number(form.perUserLimit),
    startAt: range[0],
    endAt: range[1],
    campusId: campusId.value,
    status: form.status,
  }

  dialog.loading = true
  try {
    if (dialog.mode === 'create') {
      await saveConsoleCoupon(payload)
      showTip('已新建优惠券')
    } else {
      await updateConsoleCoupon(dialog.id, payload)
      showTip('已保存优惠券')
    }
    dialog.visible = false
    await load()
  } catch {
    // 响应拦截器已统一提示（含「已发放数 > 新库存」这类后端校验）
  } finally {
    dialog.loading = false
  }
}

async function handleToggle(row: ConsoleAdminCouponVO): Promise<void> {
  const next: 0 | 1 = row.status === 1 ? 0 : 1
  if (next === 0) {
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」不再出现在买家端可领券列表，已领走的券不受影响。`,
        '停用优惠券',
        { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  busyId.value = row.id
  try {
    await toggleConsoleCoupon(row.id, next)
    showTip(next === 1 ? '已启用' : '已停用')
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    busyId.value = 0
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="console-page">
    <!-- 工具条 -->
    <div class="cp-bar">
      <div class="cp-bar__title">优惠券</div>
      <span class="cp-bar__count">
        共 {{ filteredRows.length }} 张 · 总库存 {{ summary.totalStock }} 张 · 已领
        {{ summary.claimed }} 张（{{ summary.percent }}%）
      </span>
      <el-select
        v-model="typeFilter"
        placeholder="全部类型"
        clearable
        :value-on-clear="''"
        style="width: 132px"
      >
        <el-option label="满减券" :value="1" />
        <el-option label="折扣券" :value="2" />
      </el-select>
      <el-input v-model="keyword" placeholder="搜索券名称" clearable style="width: 220px" />
      <div class="cp-bar__spacer" />
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建优惠券</el-button>
    </div>

    <!-- 整体领取进度 -->
    <div class="cp-card cp-progress">
      <div class="cp-progress__head">
        <div class="cp-progress__title">
          <el-icon><Tickets /></el-icon>
          全部券领取进度
        </div>
        <span class="cp-sub">已领 {{ summary.claimed }} / 总库存 {{ summary.totalStock }}</span>
      </div>
      <el-progress
        :percentage="summary.percent"
        :stroke-width="14"
        :show-text="false"
        color="#12b981"
      />
    </div>

    <!-- 表格 -->
    <div class="cp-card">
      <div class="cp-card__head">
        <div>
          <p class="cp-card__title">优惠券列表</p>
          <p class="cp-sub">折扣券的「折扣率」按 9 折 = 0.90 存库；停用不影响已领取的券</p>
        </div>
        <span class="cp-sub">第 {{ pageNum }} / {{ maxPage }} 页</span>
      </div>

      <el-skeleton v-if="loading && !rows.length" :rows="7" animated class="cp-skeleton" />

      <el-table v-else :data="pageRows" row-key="id" class="cp-table">
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }">
            <p class="cp-name">{{ row.name }}</p>
            <p class="cp-sub">{{ couponRuleText(row) }}</p>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="86">
          <template #default="{ row }">
            <el-tag :type="row.type === 2 ? 'warning' : 'primary'" effect="light" round size="small">
              {{ typeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="门槛" width="96" align="right">
          <template #default="{ row }">{{ thresholdText(row) }}</template>
        </el-table-column>
        <el-table-column label="面额" width="92" align="right">
          <template #default="{ row }">
            <span class="cp-face">{{ couponFaceValue(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="总量" width="80" align="right">
          <template #default="{ row }">{{ row.totalStock }}</template>
        </el-table-column>
        <el-table-column label="已领" width="128">
          <template #default="{ row }">
            <div class="cp-claim">
              <span>{{ row.claimedCount }} 张</span>
              <el-progress
                :percentage="claimPercent(row)"
                :stroke-width="6"
                :show-text="false"
                :color="claimPercent(row) >= 100 ? '#f59e0b' : '#12b981'"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="剩余" width="80" align="right">
          <template #default="{ row }">
            <span :class="{ 'cp-warn': row.leftStock <= 0 }">{{ row.leftStock }}</span>
          </template>
        </el-table-column>
        <el-table-column label="有效期" width="168">
          <template #default="{ row }">
            <p class="cp-sub cp-date">{{ dateShort(row.startAt) }}</p>
            <p class="cp-sub cp-date">至 {{ dateShort(row.endAt) }}</p>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" round size="small">
              {{ row.statusText || (row.status === 1 ? '启用' : '停用') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :loading="busyId === row.id" @click="handleToggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="cp-empty">
            <div class="cp-empty__icon">🎟️</div>
            <p>{{ rows.length ? '没有符合筛选条件的优惠券' : '当前校区还没有优惠券，点右上角「新建优惠券」创建' }}</p>
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
      :title="dialog.mode === 'create' ? '新建优惠券' : '编辑优惠券'"
      width="740px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px">
        <el-row :gutter="18">
          <el-col :span="12">
            <el-form-item label="券名称" prop="name">
              <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如：满 50 减 8" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="券类型">
              <el-radio-group v-model="form.type">
                <el-radio-button :value="1">满减券</el-radio-button>
                <el-radio-button :value="2">折扣券</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="门槛金额" prop="thresholdAmount">
              <el-input-number
                v-model="form.thresholdAmount"
                :min="0"
                :precision="2"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col v-if="form.type === 1" :span="12">
            <el-form-item label="减免金额" prop="discountAmount">
              <el-input-number
                v-model="form.discountAmount"
                :min="0"
                :precision="2"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col v-else :span="12">
            <el-form-item label="折扣（几折）" prop="discountRate">
              <el-input-number
                v-model="form.discountRate"
                :min="0.1"
                :max="10"
                :precision="2"
                :step="0.5"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="总库存" prop="totalStock">
              <el-input-number
                v-model="form.totalStock"
                :min="1"
                :max="1000000"
                :precision="0"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="每人限领">
              <el-input-number
                v-model="form.perUserLimit"
                :min="1"
                :max="99"
                :precision="0"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="有效期" prop="dateRange">
              <el-date-picker
                v-model="form.dateRange"
                type="datetimerange"
                value-format="YYYY-MM-DD HH:mm:ss"
                range-separator="至"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
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
          <el-col v-if="dialog.mode === 'edit'" :span="12">
            <el-form-item label=" ">
              <span class="cp-sub">该券已发放 {{ editingClaimed }} 张</span>
            </el-form-item>
          </el-col>
        </el-row>
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

// 全部券领取进度卡
.cp-progress {
  padding: 18px 22px;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--text-1);
    font-size: 15px;
    font-weight: 700;
  }
}

.cp-skeleton {
  padding: 22px;
}

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

.cp-date {
  font-variant-numeric: tabular-nums;
}

.cp-face {
  color: var(--accent-600);
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.cp-claim {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text-2);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}

.cp-warn {
  color: #f59e0b;
  font-weight: 600;
}

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
</style>
