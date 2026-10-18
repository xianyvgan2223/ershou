<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowDown, ArrowUp, Plus, Refresh, Timer } from '@element-plus/icons-vue'
import {
  addConsoleFlashItem,
  fetchConsoleFlashSales,
  removeConsoleFlashItem,
  saveConsoleFlashSale,
  toggleConsoleFlashStatus,
  updateConsoleFlashSale,
} from '@/api/platform-console'
import type {
  ConsoleAdminFlashItemVO,
  ConsoleAdminFlashSaleVO,
  ConsoleFlashItemSaveParams,
  ConsoleFlashSaleSaveParams,
} from '@/api/platform-console'
import { fetchPlatformItems } from '@/api/platform'
import type { PlatformItemVO } from '@/api/platform'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { formatPrice } from '@/utils/format'
import { showTip } from '@/utils/feedback'

/**
 * 平台运营台 · 秒杀场次管理（第 3 轮，设计 4.4 Flash）。
 *
 * 能力：场次卡片（状态/时间/倒计时/商品数）+ 新建/编辑场次 + 展开商品明细 +
 * 添加商品（从平台在售商品里选，填秒杀价/库存/限购）+ 移除商品 + 场次状态切换。
 *
 * 口径：
 * - 场次状态 1 预告 / 2 进行中 / 3 结束；切状态后端会重建该场次的 Redis 秒杀库存。
 * - 明细里的 `leftStock` 是 Redis 实时剩余，`soldCount` 是 DB 已售；两者不必相等（预热差）。
 * - 倒计时在本地每秒递减（服务端只给起止时间，场次卡是纯展示，不占用高频接口）。
 */

const PAGE_STATUS = {
  PREVIEW: 1,
  RUNNING: 2,
  ENDED: 3,
} as const

/** 当前校区（Shell provide 的 Ref<number>；单独打开时兜底默认校区） */
const injectedCampus = inject<Ref<number> | number>('campusId', DEFAULT_CAMPUS_ID)
const campusId = computed(() =>
  typeof injectedCampus === 'number' ? injectedCampus : injectedCampus.value,
)

/* ------------------------------------------------------------------ *
 * 列表 + 倒计时
 * ------------------------------------------------------------------ */
const loading = ref(false)
const rows = ref<ConsoleAdminFlashSaleVO[]>([])
/** 展开的场次 id 集合 */
const expanded = ref<Record<number, boolean>>({})
const busyId = ref(0)
const now = ref(Date.now())
let tickTimer: number | undefined

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await fetchConsoleFlashSales(campusId.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(campusId, () => {
  expanded.value = {}
  void load()
})

onMounted(() => {
  void load()
  tickTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer !== undefined) {
    window.clearInterval(tickTimer)
    tickTimer = undefined
  }
})

/* ------------------------------------------------------------------ *
 * 展示辅助
 * ------------------------------------------------------------------ */
/** 兼容 'yyyy-MM-dd HH:mm:ss' 与 ISO（都按本地时间解析） */
function parseTime(value?: string | null): number {
  if (!value) return 0
  const time = new Date(value.replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? 0 : time
}

function statusText(scene: ConsoleAdminFlashSaleVO): string {
  if (scene.statusText) return scene.statusText
  if (scene.status === PAGE_STATUS.RUNNING) return '进行中'
  if (scene.status === PAGE_STATUS.PREVIEW) return '预告'
  return '已结束'
}

function statusTagType(status: number): 'success' | 'warning' | 'info' {
  if (status === PAGE_STATUS.RUNNING) return 'success'
  if (status === PAGE_STATUS.PREVIEW) return 'warning'
  return 'info'
}

function dateTimeShort(value?: string | null): string {
  if (!value) return '--'
  return value.replace('T', ' ').slice(5, 16)
}

function rangeText(scene: ConsoleAdminFlashSaleVO): string {
  return `${dateTimeShort(scene.startAt)} ~ ${dateTimeShort(scene.endAt)}`
}

/** 倒计时目标：进行中算到结束，预告算到开始，结束为 0 */
function remainSeconds(scene: ConsoleAdminFlashSaleVO): number {
  const target =
    scene.status === PAGE_STATUS.RUNNING
      ? parseTime(scene.endAt)
      : scene.status === PAGE_STATUS.PREVIEW
        ? parseTime(scene.startAt)
        : 0
  if (!target) return 0
  return Math.max(0, Math.floor((target - now.value) / 1000))
}

function countdownLabel(scene: ConsoleAdminFlashSaleVO): string {
  if (scene.status === PAGE_STATUS.RUNNING) return '距结束'
  if (scene.status === PAGE_STATUS.PREVIEW) return '距开始'
  return '已结束'
}

function formatCountdown(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const days = Math.floor(seconds / 86400)
  const pad = (value: number): string => String(value).padStart(2, '0')
  const clock = `${pad(Math.floor((seconds % 86400) / 3600))}:${pad(Math.floor((seconds % 3600) / 60))}:${pad(seconds % 60)}`
  return days > 0 ? `${days}天 ${clock}` : clock
}

/** 已售百分比（0~100，供明细行进度条） */
function soldPercent(item: ConsoleAdminFlashItemVO): number {
  const total = Number(item.seckillStock ?? 0)
  if (total <= 0) return 0
  return Math.min(100, Math.round((Number(item.soldCount ?? 0) / total) * 100))
}

function totalItems(scene: ConsoleAdminFlashSaleVO): number {
  return scene.items?.length ?? 0
}

function toggleExpand(id: number): void {
  expanded.value = { ...expanded.value, [id]: !expanded.value[id] }
}

/* ------------------------------------------------------------------ *
 * 新建 / 编辑场次
 * ------------------------------------------------------------------ */
const formRef = ref<FormInstance>()
const dialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  loading: false,
})

const form = reactive({
  title: '',
  // el-date-picker 的 model 类型不接受 null（清空时运行时会给 null，提交前统一做非空校验）
  dateRange: undefined as string[] | undefined,
  status: PAGE_STATUS.PREVIEW as number,
  sort: 0,
})

type ValidatorCallback = (error?: string | Error) => void

function validateDateRange(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
    callback('请选择场次起止时间')
    return
  }
  if (parseTime(String(value[0])) >= parseTime(String(value[1]))) {
    callback('结束时间必须晚于开始时间')
    return
  }
  callback()
}

const rules: FormRules = {
  title: [
    { required: true, message: '请填写场次标题', trigger: 'blur' },
    { max: 64, message: '标题最多 64 个字', trigger: 'blur' },
  ],
  dateRange: [{ validator: validateDateRange, trigger: 'change' }],
}

function openCreate(): void {
  dialog.mode = 'create'
  dialog.id = 0
  form.title = ''
  form.dateRange = undefined
  form.status = PAGE_STATUS.PREVIEW
  form.sort = 0
  dialog.visible = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(scene: ConsoleAdminFlashSaleVO): void {
  dialog.mode = 'edit'
  dialog.id = scene.id
  form.title = scene.title
  form.dateRange = [scene.startAt, scene.endAt]
  form.status = scene.status
  form.sort = scene.sort ?? 0
  dialog.visible = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function submitScene(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const range = form.dateRange
  if (!range || range.length !== 2) return
  const payload: ConsoleFlashSaleSaveParams = {
    title: form.title.trim(),
    startAt: range[0],
    endAt: range[1],
    status: form.status,
    campusId: campusId.value,
    sort: Number(form.sort ?? 0),
  }
  dialog.loading = true
  try {
    if (dialog.mode === 'create') {
      await saveConsoleFlashSale(payload)
      showTip('已新建秒杀场次')
    } else {
      await updateConsoleFlashSale(dialog.id, payload)
      showTip('已保存秒杀场次')
    }
    dialog.visible = false
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    dialog.loading = false
  }
}

async function changeStatus(scene: ConsoleAdminFlashSaleVO, status: 1 | 2 | 3): Promise<void> {
  if (scene.status === status) return
  const label = status === 1 ? '预告' : status === 2 ? '进行中' : '结束'
  try {
    await ElMessageBox.confirm(
      status === PAGE_STATUS.ENDED
        ? `确定把「${scene.title}」切到「已结束」吗？结束后买家端不再展示该场次，秒杀缓存会重建。`
        : `确定把「${scene.title}」切到「${label}」吗？场次状态会立即影响买家端展示，并重建秒杀缓存。`,
      '切换场次状态',
      { confirmButtonText: `切到${label}`, cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busyId.value = scene.id
  try {
    await toggleConsoleFlashStatus(scene.id, status)
    showTip(`已切换为「${label}」`)
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    busyId.value = 0
  }
}

async function handleRemoveItem(scene: ConsoleAdminFlashSaleVO, item: ConsoleAdminFlashItemVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定把「${item.title}」从「${scene.title}」移除吗？已抢购成功的订单不受影响。`,
      '移除秒杀商品',
      { confirmButtonText: '确认移除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busyId.value = scene.id
  try {
    await removeConsoleFlashItem(scene.id, item.itemId)
    showTip('已移除该秒杀商品')
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    busyId.value = 0
  }
}

/* ------------------------------------------------------------------ *
 * 添加商品（从平台在售商品里选）
 * ------------------------------------------------------------------ */
const itemDialog = reactive({
  visible: false,
  sceneId: 0,
  sceneTitle: '',
  loading: false,
  optionsLoading: false,
})

const platformItems = ref<PlatformItemVO[]>([])

const addFormRef = ref<FormInstance>()
const addForm = reactive({
  itemId: undefined as number | undefined,
  seckillPrice: undefined as number | undefined,
  seckillStock: 5,
  perUserLimit: 1,
  sort: 0,
})

const currentScene = computed(() => rows.value.find((scene) => scene.id === itemDialog.sceneId) ?? null)

/** 已在当前场次里的商品 id：选择器里禁选，避免重复添加报错 */
const addedItemIds = computed(
  () => new Set((currentScene.value?.items ?? []).map((item) => item.itemId)),
)

const availableItems = computed(() =>
  platformItems.value.filter((item) => !addedItemIds.value.has(item.id)),
)

const selectedItem = computed(
  () => platformItems.value.find((item) => item.id === addForm.itemId) ?? null,
)

async function loadPlatformItems(): Promise<void> {
  itemDialog.optionsLoading = true
  try {
    const page = await fetchPlatformItems({
      campusId: campusId.value,
      pageNum: 1,
      pageSize: 50,
      sort: 'latest',
    })
    platformItems.value = page.records ?? []
  } catch {
    platformItems.value = []
  } finally {
    itemDialog.optionsLoading = false
  }
}

async function openAddItem(scene: ConsoleAdminFlashSaleVO): Promise<void> {
  itemDialog.sceneId = scene.id
  itemDialog.sceneTitle = scene.title
  itemDialog.visible = true
  addForm.itemId = undefined
  addForm.seckillPrice = undefined
  addForm.seckillStock = 5
  addForm.perUserLimit = 1
  addForm.sort = 0
  await loadPlatformItems()
  void nextTick(() => addFormRef.value?.clearValidate())
}

/** 选商品后带出建议值：默认 6 折、限购 1（运营可改） */
function onPickItem(itemId: number): void {
  const hit = platformItems.value.find((item) => item.id === itemId)
  if (!hit) return
  addForm.seckillPrice = Number((Number(hit.price) * 0.6).toFixed(2))
  addForm.seckillStock = 5
  addForm.perUserLimit = 1
}

function validateSeckillPrice(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  const price = Number(value ?? 0)
  if (!Number.isFinite(price) || price <= 0) {
    callback('请填写大于 0 的秒杀价')
    return
  }
  const origin = Number(selectedItem.value?.price ?? 0)
  if (origin > 0 && price >= origin) {
    callback(`秒杀价需低于原价 ¥${formatPrice(origin)}`)
    return
  }
  callback()
}

function validatePositiveInt(_rule: unknown, value: unknown, callback: ValidatorCallback): void {
  const num = Number(value ?? 0)
  if (!Number.isInteger(num) || num < 1) {
    callback('请填写大于 0 的整数')
    return
  }
  callback()
}

const addRules: FormRules = {
  itemId: [{ required: true, message: '请选择平台商品', trigger: 'change' }],
  seckillPrice: [{ validator: validateSeckillPrice, trigger: 'blur' }],
  seckillStock: [{ validator: validatePositiveInt, trigger: 'blur' }],
  perUserLimit: [{ validator: validatePositiveInt, trigger: 'blur' }],
}

async function submitAddItem(): Promise<void> {
  const valid = await addFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const itemId = addForm.itemId
  if (!itemId) return
  const payload: ConsoleFlashItemSaveParams = {
    itemId,
    seckillPrice: Number(addForm.seckillPrice ?? 0),
    seckillStock: Number(addForm.seckillStock ?? 1),
    perUserLimit: Number(addForm.perUserLimit ?? 1),
    sort: Number(addForm.sort ?? 0),
  }
  itemDialog.loading = true
  try {
    await addConsoleFlashItem(itemDialog.sceneId, payload)
    showTip('已添加到秒杀场次')
    itemDialog.visible = false
    await load()
  } catch {
    // 响应拦截器已统一提示
  } finally {
    itemDialog.loading = false
  }
}
</script>

<template>
  <div class="console-page">
    <!-- 工具条 -->
    <div class="cp-bar">
      <div class="cp-bar__title">秒杀场次</div>
      <span class="cp-bar__count">共 {{ rows.length }} 个场次</span>
      <div class="cp-bar__spacer" />
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建场次</el-button>
    </div>

    <el-skeleton v-if="loading && !rows.length" :rows="6" animated class="cp-card cp-skeleton" />

    <div v-else-if="rows.length" class="flash-list">
      <section v-for="scene in rows" :key="scene.id" class="flash-scene">
        <header class="flash-scene__head">
          <div class="flash-scene__main">
            <span class="flash-scene__id">#{{ scene.id }}</span>
            <h3 class="flash-scene__title">{{ scene.title }}</h3>
            <el-tag :type="statusTagType(scene.status)" effect="light" round size="small">
              {{ statusText(scene) }}
            </el-tag>
            <span class="cp-sub">{{ rangeText(scene) }}</span>
            <span class="cp-sub">· {{ totalItems(scene) }} 个商品</span>
          </div>

          <div class="flash-scene__side">
            <div class="flash-count">
              <el-icon><Timer /></el-icon>
              <span class="flash-count__label">{{ countdownLabel(scene) }}</span>
              <b class="flash-count__value">{{ formatCountdown(remainSeconds(scene)) }}</b>
            </div>
            <el-button size="small" :icon="Plus" type="primary" plain @click="openAddItem(scene)">
              添加商品
            </el-button>
            <el-button size="small" @click="openEdit(scene)">编辑场次</el-button>
            <div class="flash-status">
              <span class="cp-sub">状态</span>
              <el-button
                v-if="scene.status !== PAGE_STATUS.PREVIEW"
                link
                size="small"
                :loading="busyId === scene.id"
                @click="changeStatus(scene, 1)"
              >
                预告
              </el-button>
              <el-button
                v-if="scene.status !== PAGE_STATUS.RUNNING"
                link
                size="small"
                type="success"
                :loading="busyId === scene.id"
                @click="changeStatus(scene, 2)"
              >
                进行中
              </el-button>
              <el-button
                v-if="scene.status !== PAGE_STATUS.ENDED"
                link
                size="small"
                type="warning"
                :loading="busyId === scene.id"
                @click="changeStatus(scene, 3)"
              >
                结束
              </el-button>
            </div>
            <el-button link size="small" @click="toggleExpand(scene.id)">
              {{ expanded[scene.id] ? '收起明细' : '展开明细' }}
              <el-icon class="flash-scene__arrow">
                <ArrowUp v-if="expanded[scene.id]" />
                <ArrowDown v-else />
              </el-icon>
            </el-button>
          </div>
        </header>

        <div v-if="expanded[scene.id]" class="flash-scene__body">
          <el-table :data="scene.items" row-key="itemId" class="cp-table">
            <el-table-column label="商品" min-width="240">
              <template #default="{ row }">
                <div class="cp-goods">
                  <el-image :src="row.coverUrl || ''" fit="cover" class="cp-goods__img" lazy>
                    <template #error>
                      <div class="cp-goods__placeholder">无图</div>
                    </template>
                  </el-image>
                  <div class="cp-goods__main">
                    <p class="cp-name">{{ row.title }}</p>
                    <p class="cp-sub">商品 #{{ row.itemId }}</p>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="原价" width="100" align="right">
              <template #default="{ row }">¥{{ formatPrice(row.originPrice) }}</template>
            </el-table-column>
            <el-table-column label="秒杀价" width="110" align="right">
              <template #default="{ row }">
                <span class="cp-seckill">¥{{ formatPrice(row.seckillPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="秒杀库存" width="96" align="right">
              <template #default="{ row }">{{ row.seckillStock }}</template>
            </el-table-column>
            <el-table-column label="已售" width="90" align="right">
              <template #default="{ row }">{{ row.soldCount }}</template>
            </el-table-column>
            <el-table-column label="实时剩余" width="130">
              <template #default="{ row }">
                <div class="cp-claim">
                  <span>{{ row.leftStock }} 件</span>
                  <el-progress
                    :percentage="soldPercent(row)"
                    :stroke-width="6"
                    :show-text="false"
                    color="#ff6b35"
                  />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="限购" width="80" align="center">
              <template #default="{ row }">{{ row.perUserLimit }}</template>
            </el-table-column>
            <el-table-column label="操作" width="96" align="right" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="danger"
                  :loading="busyId === scene.id"
                  @click="handleRemoveItem(scene, row)"
                >
                  移除
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="cp-empty cp-empty--inline">
                <p>这个场次还没有秒杀商品，点右上角「添加商品」从平台在售商品里选</p>
              </div>
            </template>
          </el-table>
        </div>
      </section>
    </div>

    <div v-else class="cp-card">
      <div class="cp-empty cp-empty--card">
        <div class="cp-empty__icon">⚡</div>
        <p>当前校区还没有秒杀场次</p>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建场次</el-button>
      </div>
    </div>

    <!-- 新建 / 编辑场次 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'create' ? '新建秒杀场次' : '编辑秒杀场次'"
      width="680px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="场次标题" prop="title">
          <el-input v-model="form.title" maxlength="64" show-word-limit placeholder="如：官方严选 · 限时秒杀" />
        </el-form-item>
        <el-form-item label="起止时间" prop="dateRange">
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
        <el-form-item label="场次状态">
          <el-radio-group v-model="form.status">
            <el-radio-button :value="1">预告</el-radio-button>
            <el-radio-button :value="2">进行中</el-radio-button>
            <el-radio-button :value="3">结束</el-radio-button>
          </el-radio-group>
          <span class="cp-sub cp-form-hint">切到「进行中」后买家端才会展示并可抢购</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" :controls="false" style="width: 140px" />
          <span class="cp-sub cp-form-hint">数字越小越靠前</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.loading" @click="submitScene">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加商品 -->
    <el-dialog
      v-model="itemDialog.visible"
      :title="`添加秒杀商品 · ${itemDialog.sceneTitle}`"
      width="700px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form ref="addFormRef" :model="addForm" :rules="addRules" label-width="100px">
        <el-form-item label="平台商品" prop="itemId">
          <el-select
            v-model="addForm.itemId"
            filterable
            placeholder="从平台在售商品里选择"
            :loading="itemDialog.optionsLoading"
            style="width: 100%"
            @change="onPickItem"
          >
            <el-option
              v-for="item in availableItems"
              :key="item.id"
              :label="`${item.title}（¥${formatPrice(item.price)}，库存 ${item.stock ?? 0}）`"
              :value="item.id"
            />
          </el-select>
          <p v-if="!itemDialog.optionsLoading && !availableItems.length" class="cp-sub cp-form-hint">
            没有可选商品：平台在售商品为空，或都已加入本场次
          </p>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="秒杀价" prop="seckillPrice">
              <el-input-number
                v-model="addForm.seckillPrice"
                :min="0.01"
                :precision="2"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="秒杀库存" prop="seckillStock">
              <el-input-number
                v-model="addForm.seckillStock"
                :min="1"
                :max="100000"
                :precision="0"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="每人限购" prop="perUserLimit">
              <el-input-number
                v-model="addForm.perUserLimit"
                :min="1"
                :max="99"
                :precision="0"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序">
              <el-input-number v-model="addForm.sort" :min="0" :max="9999" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <p class="cp-sub cp-form-hint cp-form-hint--block">
          选中商品后会按 6 折带出建议秒杀价，可手动修改；秒杀价必须低于原价
          <template v-if="selectedItem">（当前原价 ¥{{ formatPrice(selectedItem.price) }}）</template>。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="itemDialog.loading" @click="submitAddItem">添加</el-button>
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
}

.cp-skeleton {
  padding: 22px;
}

// ---------- 场次卡片 ----------
.flash-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.flash-scene {
  overflow: hidden;
  border-radius: $radius-lg;
  background: #fff;
  box-shadow: $card-shadow;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 18px 22px;
    flex-wrap: wrap;
  }

  &__main {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    flex-wrap: wrap;
  }

  &__id {
    color: var(--text-3);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
  }

  &__title {
    color: var(--text-1);
    font-size: 18px;
    font-weight: 700;
    letter-spacing: -0.2px;
  }

  &__side {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__body {
    border-top: 1px solid var(--line);
  }

  &__arrow {
    margin-left: 2px;
  }
}

.flash-count {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--r-pill);
  background: #fff3ed;
  color: var(--accent-600);
  font-size: 12.5px;

  &__label {
    color: var(--text-3);
  }

  &__value {
    font-variant-numeric: tabular-nums;
    font-size: 14px;
    letter-spacing: 0.4px;
  }
}

.flash-status {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding-left: 8px;
  border-left: 1px solid var(--line);
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

.cp-goods {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;

  &__img {
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    border-radius: 8px;
    background: var(--surface-2);
  }

  &__placeholder {
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border-radius: 8px;
    background: var(--surface-2);
    color: var(--text-3);
    font-size: 11px;
  }

  &__main {
    min-width: 0;
  }
}

.cp-name {
  overflow: hidden;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-sub {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cp-seckill {
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

.cp-form-hint {
  margin-left: 10px;
  font-size: 12px;

  &--block {
    display: block;
    margin: 2px 0 0;
    padding-left: 100px;
  }
}

.cp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 56px 0;
  color: var(--text-3);
  font-size: 13px;

  &--card {
    padding: 72px 0;
  }

  &--inline {
    padding: 28px 0;
  }

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
