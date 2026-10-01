<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import ImageUploader from '@/components/ImageUploader.vue'
import PointPickerDialog from '@/components/PointPickerDialog.vue'
import { fetchCategories } from '@/api/auth'
import { publishItem } from '@/api/item'
import { fetchMapPoints, pointTypeLabel } from '@/api/map'
import type { MapConfig, TradePoint } from '@/api/map'
import { tradePointLabel, useTradePoints } from '@/composables/useTradePoints'
import type { Category } from '@/api/types'
import { CONDITION_OPTIONS } from '@/utils/constants'

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
        <h2 class="page-title">发布闲置</h2>
        <p class="page-subtitle">描述越真实，成交越快；请勿发布违规物品</p>
      </div>
      <el-button text type="primary" @click="router.push('/publish-list')">我的发布</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="16">
        <el-card class="section-card" shadow="never">
          <el-form :model="form" label-width="96px" label-position="right">
            <el-form-item label="商品标题" required>
              <el-input v-model="form.title" maxlength="40" show-word-limit placeholder="例如：九成新《数据结构》教材（C 语言版）" />
            </el-form-item>

            <el-form-item label="商品描述" required>
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="5"
                maxlength="500"
                show-word-limit
                placeholder="写清楚成色、使用时长、有无笔记/配件等，方便同学判断"
              />
            </el-form-item>

            <el-form-item label="商品分类" required>
              <el-select
                v-model="form.categoryId"
                filterable
                placeholder="输入关键字搜索分类"
                style="width: 220px"
              >
                <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-select
                v-model="form.conditionLevel"
                filterable
                placeholder="成色"
                style="width: 180px; margin-left: 12px"
              >
                <el-option v-for="item in CONDITION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>

            <el-form-item label="价格" required>
              <el-input-number v-model="form.price" :min="0" :max="99999" :precision="2" :step="1" />
              <span class="publish__unit">元</span>
              <span class="publish__inline-label">原价</span>
              <el-input-number v-model="form.originalPrice" :min="0" :max="99999" :precision="2" :step="1" />
              <span class="publish__unit">元（选填）</span>
            </el-form-item>

            <el-form-item label="交易设置">
              <el-switch v-model="form.negotiable" active-text="可议价" inactive-text="一口价" inline-prompt />
              <el-switch
                v-model="form.isUnique"
                class="publish__switch"
                active-text="唯一件"
                inactive-text="多件库存"
                inline-prompt
              />
              <template v-if="!form.isUnique">
                <span class="publish__inline-label">库存</span>
                <el-input-number v-model="form.stock" :min="1" :max="999" />
              </template>
            </el-form-item>

            <el-form-item label="商品图片" required>
              <ImageUploader v-model="form.images" :limit="6" biz="item" />
            </el-form-item>

            <el-form-item label="常用面交点">
              <el-select
                v-model="form.preferPointId"
                filterable
                clearable
                :loading="pointsLoading"
                placeholder="输入地点名搜索，例如「食堂」「快递」"
                style="width: 300px"
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
              <el-button text type="primary" @click="pickerVisible = true">地图上新增地点</el-button>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">
                立即发布
              </el-button>
              <el-button size="large" @click="resetForm">重置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card class="section-card" shadow="never">
          <template #header><span class="publish__card-title">发布小贴士</span></template>
          <ul class="publish__tips">
            <li>图片尽量拍实物，建议 3 张以上：整体、细节、瑕疵处。</li>
            <li>选择跟你宿舍/教学楼近的面交点，成交速度更快。</li>
            <li>贵重物品建议在图书馆、食堂门口等有监控的区域面交。</li>
            <li>教材、考研资料、宿舍小电器是站内最热销的品类。</li>
          </ul>
          <el-divider />
          <div v-if="selectedPoint" class="publish__point">
            <p class="publish__point-name">已选面交点：{{ selectedPoint.name }}</p>
            <p v-if="selectedPoint.guideText" class="text-muted">{{ selectedPoint.guideText }}</p>
            <div class="publish__point-tags">
              <el-tag v-for="tag in selectedPoint.safeTags" :key="tag" size="small" type="success" effect="plain">
                {{ tag }}
              </el-tag>
            </div>
          </div>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="禁止发布"
            description="违规电器、代写代考、烟酒、药品、账号类虚拟物品等，一经发现将下架并扣减信用分。"
          />
        </el-card>
      </el-col>
    </el-row>

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
  &__unit {
    margin-left: 8px;
    color: var(--ershou-text-light);
  }

  &__inline-label {
    margin: 0 8px 0 20px;
    color: var(--ershou-text-light);
  }

  &__switch {
    margin-left: 20px;
  }

  &__card-title {
    font-weight: 600;
  }

  &__tips {
    margin: 0;
    padding-left: 18px;
    color: #606266;
    font-size: 13px;
    line-height: 26px;
  }

  &__point {
    margin-bottom: 14px;
  }

  &__point-name {
    margin: 0 0 4px;
    font-weight: 600;
  }

  &__point-tags {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
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
</style>
