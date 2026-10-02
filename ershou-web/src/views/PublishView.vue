<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Clock, Location, Plus, PriceTag, UploadFilled } from '@element-plus/icons-vue'
import ImageUploader from '@/components/ImageUploader.vue'
import PointPickerDialog from '@/components/PointPickerDialog.vue'
import { fetchCategories } from '@/api/auth'
import { publishItem } from '@/api/item'
import { fetchMapPoints, pointTypeLabel } from '@/api/map'
import type { MapConfig, TradePoint } from '@/api/map'
import { tradePointLabel, useTradePoints } from '@/composables/useTradePoints'
import type { Category } from '@/api/types'
import { CONDITION_OPTIONS } from '@/utils/constants'

/**
 * 上架闲置。
 *
 * 版式刻意做成「单列 + 分节 + 无边框输入」：商品图是第一眼要看到的东西，
 * 所以图片区放最前面；文字类字段不要右侧标签（label-position=right 那种老表单观感），
 * 改成字段名在上、说明在下的窄行距排布，整体留白多一点。
 */

/** 商品可挂天数 —— 与后端 `ershou.item.valid-days` 保持一致 */
const VALID_DAYS = 7

const router = useRouter()

const {
  options: tradePoints,
  loading: pointsLoading,
  campusId,
  load: loadTradePoints,
  add: addTradePoint,
} = useTradePoints()
const categories = ref<Category[]>([])
const submitting = ref(false)
const pickerVisible = ref(false)
const mapConfig = ref<MapConfig | null>(null)

const form = reactive({
  title: '',
  description: '',
  categoryId: undefined as number | undefined,
  conditionLevel: 3,
  price: undefined as number | undefined,
  originalPrice: undefined as number | undefined,
  negotiable: true,
  isUnique: true,
  stock: 1,
  images: [] as string[],
  preferPointId: undefined as number | undefined,
})

const selectedPoint = computed(
  () => tradePoints.value.find((point) => point.id === form.preferPointId) ?? null,
)

/** 原价相对售价的折扣，顺手给个「省 xx%」的正反馈（填了原价才有） */
const discountText = computed(() => {
  const price = form.price
  const origin = form.originalPrice
  if (!price || !origin || origin <= price) return ''
  return `比原价省 ${Math.round(((origin - price) / origin) * 100)}%`
})

async function loadBaseData(): Promise<void> {
  try {
    categories.value = await fetchCategories()
  } catch {
    categories.value = []
  }
  await loadTradePoints()
  try {
    // 必须传用户所在校区：漏传会拿到默认校区的底图，选址对话框就会显示到别的校区去
    const mapData = await fetchMapPoints(campusId.value)
    mapConfig.value = mapData.mapConfig ?? null
  } catch {
    mapConfig.value = null
  }
}

/** 地图选点新建地点后：立刻插入下拉选项并选中（PointPickerDialog 已把点位写进共享列表） */
function handlePointCreated(point: TradePoint): void {
  addTradePoint(point, true)
  form.preferPointId = point.id
}

function resetForm(): void {
  form.title = ''
  form.description = ''
  form.categoryId = undefined
  form.conditionLevel = 3
  form.price = undefined
  form.originalPrice = undefined
  form.negotiable = true
  form.isUnique = true
  form.stock = 1
  form.images = []
  form.preferPointId = undefined
}

async function handleSubmit(): Promise<void> {
  const title = form.title.trim()
  const categoryId = form.categoryId
  const price = form.price

  if (title.length < 2) {
    ElMessage.warning('商品标题至少 2 个字')
    return
  }
  if (!categoryId) {
    ElMessage.warning('请选择商品分类')
    return
  }
  if (price === undefined || price <= 0) {
    ElMessage.warning('请填写正确的出售价格')
    return
  }
  if (form.originalPrice !== undefined && form.originalPrice < price) {
    ElMessage.warning('原价不应低于出售价')
    return
  }
  if (!form.images.length) {
    ElMessage.warning('请至少上传一张商品图片')
    return
  }

  submitting.value = true
  try {
    const result = await publishItem({
      title,
      description: form.description.trim(),
      categoryId,
      conditionLevel: form.conditionLevel,
      price,
      originalPrice: form.originalPrice,
      negotiable: form.negotiable ? 1 : 0,
      stock: form.isUnique ? 1 : form.stock,
      isUnique: form.isUnique ? 1 : 0,
      images: form.images,
      preferPointId: form.preferPointId,
    })
    ElMessage.success(result.statusText || '发布成功，等待同学下单')
    resetForm()
    router.push(`/item/${result.itemId}`)
  } catch {
    // 违规内容等业务错误由响应拦截器统一提示（如 code=50001）
  } finally {
    submitting.value = false
  }
}

onMounted(loadBaseData)
</script>

<template>
  <div class="page publish">
    <div class="page-head">
      <div>
        <h2 class="page-title">上架闲置</h2>
        <p class="page-subtitle">照片拍清楚、成色写实在，成交会快很多</p>
      </div>
      <el-button text type="primary" @click="router.push('/publish-list')">我的发布</el-button>
    </div>

    <!-- 有效期提示：这是卖家最该先知道的一条规则，所以放在表单之前 -->
    <section class="notice">
      <span class="notice__icon"><el-icon><Clock /></el-icon></span>
      <div class="notice__body">
        <p class="notice__title">每件商品可挂 {{ VALID_DAYS }} 天</p>
        <p class="notice__text">
          到期还没卖出会自动下架（<b>不扣信用分</b>）。想继续卖，在「我的发布」里点一下
          <b>重新上架</b> 就能再挂 {{ VALID_DAYS }} 天，图片和描述都不用重填。
          中途卖出或自己下架，计时随时结束。
        </p>
      </div>
    </section>

    <div class="sheet">
      <!-- 1. 图片：第一眼看到的就是它 -->
      <section class="block">
        <header class="block__head">
          <h3 class="block__title">商品图片</h3>
          <span class="block__hint">第一张作为封面 · 最多 6 张</span>
        </header>
        <ImageUploader v-model="form.images" :limit="6" biz="item" />
        <p class="block__foot">
          <el-icon><UploadFilled /></el-icon>
          建议拍整体、细节、瑕疵各一张 —— 写清楚瑕疵，面交时少扯皮。
        </p>
      </section>

      <!-- 2. 基本信息 -->
      <section class="block">
        <header class="block__head">
          <h3 class="block__title">基本信息</h3>
        </header>

        <div class="field">
          <label class="field__label" for="pub-title">标题 <em>*</em></label>
          <el-input
            id="pub-title"
            v-model="form.title"
            class="field__input field__input--lg"
            maxlength="40"
            show-word-limit
            placeholder="例如：九成新《数据结构》教材（C 语言版）"
          />
        </div>

        <div class="field">
          <label class="field__label" for="pub-desc">描述</label>
          <el-input
            id="pub-desc"
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            resize="none"
            placeholder="写清楚成色、使用时长、有无笔记或配件、有没有磕碰 —— 越具体越省事"
          />
        </div>

        <div class="grid grid--2">
          <div class="field">
            <label class="field__label">分类 <em>*</em></label>
            <el-select v-model="form.categoryId" filterable placeholder="输入关键字搜索" class="field__full">
              <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </div>
          <div class="field">
            <label class="field__label">成色</label>
            <el-select v-model="form.conditionLevel" class="field__full">
              <el-option v-for="item in CONDITION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </div>
        </div>
      </section>

      <!-- 3. 价格 -->
      <section class="block">
        <header class="block__head">
          <h3 class="block__title">价格</h3>
          <span v-if="discountText" class="block__hint block__hint--accent">{{ discountText }}</span>
        </header>

        <div class="grid grid--2">
          <div class="field">
            <label class="field__label">出售价 <em>*</em></label>
            <el-input-number
              v-model="form.price"
              class="field__full"
              :min="0"
              :max="99999"
              :precision="2"
              :step="1"
              :controls="false"
              placeholder="0.00"
            />
          </div>
          <div class="field">
            <label class="field__label">原价 <span class="field__optional">选填</span></label>
            <el-input-number
              v-model="form.originalPrice"
              class="field__full"
              :min="0"
              :max="99999"
              :precision="2"
              :step="1"
              :controls="false"
              placeholder="填了会显示划线价"
            />
          </div>
        </div>

        <div class="field field--row">
          <el-switch v-model="form.negotiable" />
          <span class="field__hint">
            {{ form.negotiable ? '可小刀 —— 同学可以还价，通常问的人更多' : '一口价 —— 不接受还价' }}
          </span>
        </div>
      </section>

      <!-- 4. 库存 -->
      <section class="block">
        <header class="block__head">
          <h3 class="block__title">库存</h3>
        </header>
        <div class="field field--row">
          <el-radio-group v-model="form.isUnique">
            <el-radio-button :value="true">孤品（就一件）</el-radio-button>
            <el-radio-button :value="false">多件同款</el-radio-button>
          </el-radio-group>
          <template v-if="!form.isUnique">
            <span class="field__label field__label--inline">库存件数</span>
            <el-input-number v-model="form.stock" :min="1" :max="999" />
          </template>
        </div>
      </section>

      <!-- 5. 面交点 -->
      <section class="block block--last">
        <header class="block__head">
          <h3 class="block__title">常用面交点</h3>
          <span class="block__hint">选填 · 具体地点时间由你在私聊里定</span>
        </header>

        <div class="field field--row">
          <el-select
            v-model="form.preferPointId"
            filterable
            clearable
            :loading="pointsLoading"
            placeholder="输入地点名搜索，例如「食堂」「快递」"
            class="publish__point-select"
          >
            <el-option
              v-for="point in tradePoints"
              :key="point.id"
              :label="tradePointLabel(point)"
              :value="point.id"
            >
              <span class="publish__point-option">{{ point.name }}</span>
              <span class="publish__point-option-type">{{ pointTypeLabel(point.type) }}</span>
            </el-option>
            <template #empty>
              <p class="publish__point-empty">当前校区还没有可选点位</p>
            </template>
            <template #footer>
              <div class="publish__point-more" @click="pickerVisible = true">
                <el-icon><Plus /></el-icon>
                没有合适的地点？在地图上选一个真实地址
              </div>
            </template>
          </el-select>
          <el-button :icon="Location" @click="pickerVisible = true">地图上新增</el-button>
        </div>

        <div v-if="selectedPoint" class="picked">
          <span class="picked__icon"><el-icon><Location /></el-icon></span>
          <div class="picked__body">
            <p class="picked__name">{{ selectedPoint.name }}</p>
            <p v-if="selectedPoint.guideText" class="picked__guide">{{ selectedPoint.guideText }}</p>
          </div>
          <span v-for="tag in selectedPoint.safeTags" :key="tag" class="chip chip--brand">{{ tag }}</span>
        </div>
      </section>
    </div>

    <!-- 底部操作条：长表单滚到底不用再往回找按钮 -->
    <div class="bar">
      <p class="bar__tip">
        <el-icon><Clock /></el-icon>
        发布后可挂 {{ VALID_DAYS }} 天，到期未售出自动下架
      </p>
      <div class="bar__actions">
        <el-button size="large" @click="resetForm">重置</el-button>
        <el-button size="large" type="primary" :loading="submitting" @click="handleSubmit">
          <el-icon class="bar__icon"><PriceTag /></el-icon>
          立即发布
        </el-button>
      </div>
    </div>

    <p class="legal">
      禁止发布违规电器、代写代考、烟酒、药品、账号类虚拟物品等 —— 一经发现将下架并扣减信用分。
    </p>

    <PointPickerDialog
      v-model="pickerVisible"
      :campus-id="campusId"
      :map-config="mapConfig"
      @created="handlePointCreated"
    />
  </div>
</template>

<style scoped lang="scss">
.publish {
  // 单列窄版，比学生端默认的 1240px 收一档，长表单读起来更聚焦
  max-width: 940px;

  // ---------- 有效期提示 ----------
  .notice {
    display: flex;
    gap: 12px;
    padding: 14px 18px;
    margin-bottom: 16px;
    border: 1px solid #fde3c8;
    border-radius: var(--r-lg);
    background: linear-gradient(180deg, #fffaf3, #fff6ea);
  }

  .notice__icon {
    display: grid;
    width: 32px;
    height: 32px;
    flex-shrink: 0;
    place-items: center;
    border-radius: 10px;
    background: #fff;
    color: var(--accent-600);
    font-size: 17px;
    box-shadow: var(--shadow-xs);
  }

  .notice__title {
    color: #9a3412;
    font-size: 14px;
    font-weight: 700;
  }

  .notice__text {
    margin-top: 2px;
    color: #b45309;
    font-size: 12.5px;
    line-height: 1.7;

    b {
      color: #9a3412;
    }
  }

  // ---------- 主表单容器 ----------
  .sheet {
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    background: var(--surface);
    box-shadow: var(--shadow-sm);
  }

  .block {
    padding: 22px 26px;
    border-bottom: 1px solid var(--line);

    &--last {
      border-bottom: none;
    }
  }

  .block__head {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 16px;
  }

  .block__title {
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.1px;
  }

  .block__hint {
    color: var(--text-3);
    font-size: 12px;

    &--accent {
      color: var(--accent-600);
      font-weight: 600;
    }
  }

  .block__foot {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 10px;
    color: var(--text-3);
    font-size: 12px;
  }

  // ---------- 字段 ----------
  .grid {
    display: grid;
    gap: 16px;

    &--2 {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  .field {
    margin-bottom: 16px;

    &:last-child {
      margin-bottom: 0;
    }

    &--row {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
  }

  .field__label {
    display: block;
    margin-bottom: 7px;
    color: var(--text-2);
    font-size: 13px;
    font-weight: 600;

    em {
      color: var(--el-color-danger);
      font-style: normal;
    }

    &--inline {
      margin-bottom: 0;
    }
  }

  .field__optional {
    color: var(--text-3);
    font-size: 11px;
    font-weight: 400;
  }

  .field__hint {
    color: var(--text-3);
    font-size: 12px;
  }

  .field__full {
    width: 100%;
  }

  // el-input-number 关掉控制按钮后文字默认居中，价格字段左对齐更像正常输入框
  .field__full :deep(.el-input__inner) {
    text-align: left;
  }

  // 标题输入给更大的字号，像在写内容而不是填表
  .field__input--lg :deep(.el-input__inner) {
    font-size: 15px;
    font-weight: 600;
  }

  // ---------- 已选面交点 ----------
  .picked {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 14px;
    margin-top: 14px;
    border: 1px solid var(--brand-100);
    border-radius: var(--r-md);
    background: var(--brand-50);
    flex-wrap: wrap;
  }

  .picked__icon {
    color: var(--brand-600);
    font-size: 15px;
  }

  .picked__body {
    flex: 1;
    min-width: 0;
  }

  .picked__name {
    color: var(--brand-700);
    font-size: 13px;
    font-weight: 600;
  }

  .picked__guide {
    color: var(--brand-600);
    font-size: 12px;
  }

  // ---------- 底部操作条 ----------
  .bar {
    position: sticky;
    z-index: 5;
    bottom: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 12px 18px;
    margin-top: 16px;
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    background: rgb(255 255 255 / 92%);
    box-shadow: var(--shadow-md);
    backdrop-filter: blur(8px);
    flex-wrap: wrap;
  }

  .bar__tip {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--text-3);
    font-size: 12.5px;
  }

  .bar__actions {
    display: flex;
    gap: 10px;
    margin-left: auto;
  }

  .bar__icon {
    margin-right: 4px;
  }

  .legal {
    margin-top: 12px;
    color: var(--text-3);
    font-size: 12px;
    line-height: 1.7;
    text-align: center;
  }

  // ---------- 面交点下拉里的细节 ----------
  &__point-select {
    width: 380px;
    max-width: 100%;
  }

  &__point-option {
    margin-right: 6px;
  }

  &__point-option-type {
    color: var(--text-3);
    font-size: 12px;
  }

  &__point-empty {
    margin: 0;
    padding: 10px 0;
    color: var(--text-3);
    font-size: 13px;
    text-align: center;
  }

  &__point-more {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 8px 12px;
    border-top: 1px solid var(--line);
    color: var(--brand-600);
    font-size: 12.5px;
    cursor: pointer;

    &:hover {
      background: var(--brand-50);
    }
  }
}

@media (max-width: 720px) {
  .publish {
    .grid--2 {
      grid-template-columns: 1fr;
    }

    .bar {
      justify-content: stretch;

      .bar__actions {
        margin-left: 0;
      }
    }
  }
}
</style>
