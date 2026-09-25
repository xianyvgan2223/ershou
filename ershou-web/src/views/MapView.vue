<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Location, Refresh } from '@element-plus/icons-vue'
import CampusMap from '@/components/CampusMap.vue'
import { fetchMapPoints, fetchTradePoints, pointTypeLabel, suggestTradePoint } from '@/api/map'
import type { MapConfig, MapPoint, TradePoint } from '@/api/map'
import { fetchItems } from '@/api/item'
import type { ItemVO } from '@/api/item'
import type { PickCoord } from '@/components/CampusMap.vue'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { formatPrice } from '@/utils/format'

/** 同一区域判定半径（像素） */
const NEARBY_RADIUS = 220

const router = useRouter()

const points = ref<MapPoint[]>([])
const tradePoints = ref<TradePoint[]>([])
const mapConfig = ref<MapConfig | null>(null)
const items = ref<ItemVO[]>([])
const selectedPoint = ref<MapPoint | null>(null)
const loadingMap = ref(false)
const loadingItems = ref(false)
const pickable = ref(false)
const pickedCoord = ref<PickCoord | null>(null)
const suggestVisible = ref(false)
const suggestName = ref('')
const suggestGuide = ref('')
const suggesting = ref(false)

const sortedPoints = computed(() =>
  [...points.value].sort((a, b) => b.itemCount - a.itemCount || a.id - b.id),
)

const totalOnSale = computed(() => points.value.reduce((sum, point) => sum + (point.itemCount || 0), 0))

/** 当前选中的点位 / 区域能看到的商品 */
const pointMatch = computed<{ list: ItemVO[]; scope: 'point' | 'nearby' | 'none' }>(() => {
  const current = selectedPoint.value
  if (!current) return { list: [], scope: 'none' }
  const exact = items.value.filter((item) => item.preferPointId === current.id)
  if (exact.length) return { list: exact, scope: 'point' }
  const nearIds = new Set(
    points.value
      .filter(
        (point) =>
          point.id !== current.id &&
          Math.hypot(point.coordX - current.coordX, point.coordY - current.coordY) <= NEARBY_RADIUS,
      )
      .map((point) => point.id),
  )
  const nearby = items.value.filter(
    (item) => item.preferPointId !== null && nearIds.has(item.preferPointId),
  )
  return nearby.length ? { list: nearby, scope: 'nearby' } : { list: [], scope: 'none' }
})

async function loadMap(): Promise<void> {
  loadingMap.value = true
  try {
    const result = await fetchMapPoints(DEFAULT_CAMPUS_ID)
    mapConfig.value = result.mapConfig ?? null
    points.value = result.points ?? []
    if (!selectedPoint.value && points.value.length) {
      selectedPoint.value = sortedPoints.value[0] ?? null
    }
  } catch {
    points.value = []
  } finally {
    loadingMap.value = false
  }
}

async function loadTradePoints(): Promise<void> {
  try {
    tradePoints.value = await fetchTradePoints(DEFAULT_CAMPUS_ID)
  } catch {
    tradePoints.value = []
  }
}

async function loadItems(): Promise<void> {
  loadingItems.value = true
  try {
    const result = await fetchItems({ pageNum: 1, pageSize: 100, sort: 'latest' })
    items.value = result.records ?? []
  } catch {
    items.value = []
  } finally {
    loadingItems.value = false
  }
}

function handleSelect(point: MapPoint): void {
  selectedPoint.value = point
}

function openItem(id: number): void {
  router.push(`/item/${id}`)
}

function handlePointSelectFromList(point: MapPoint): void {
  selectedPoint.value = point
}

/** 选点模式下点击地图空白处 */
function handlePick(coord: PickCoord): void {
  pickedCoord.value = coord
  suggestName.value = ''
  suggestGuide.value = ''
  suggestVisible.value = true
  ElMessage.info(`已选中坐标 X=${coord.coordX}，Y=${coord.coordY}`)
}

async function submitSuggest(): Promise<void> {
  if (!pickedCoord.value) return
  if (!suggestName.value.trim()) {
    ElMessage.warning('请填写点位名称')
    return
  }
  suggesting.value = true
  try {
    await suggestTradePoint({
      campusId: DEFAULT_CAMPUS_ID,
      name: suggestName.value.trim(),
      coordX: pickedCoord.value.coordX,
      coordY: pickedCoord.value.coordY,
      guideText: suggestGuide.value.trim(),
    })
    ElMessage.success('推荐成功，感谢你帮同学们补全面交点')
    suggestVisible.value = false
    pickable.value = false
    pickedCoord.value = null
    await loadMap()
    await loadTradePoints()
  } catch {
    // 响应拦截器已提示
  } finally {
    suggesting.value = false
  }
}

onMounted(() => {
  loadMap()
  loadTradePoints()
  loadItems()
})
</script>

<template>
  <div class="page map-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">校内地图</h2>
        <p class="page-subtitle">
          点气泡看该点位在售的闲置 · 共 {{ points.length }} 个面交点、{{ totalOnSale }} 件在售
        </p>
      </div>
      <div class="map-page__tools">
        <label class="switch">
          <el-switch v-model="pickable" />
          <span>选点模式</span>
        </label>
        <el-button :icon="Refresh" :loading="loadingMap" round @click="loadMap">刷新</el-button>
      </div>
    </div>

    <div class="layout">
      <!-- 地图 -->
      <section class="map-box">
        <CampusMap
          v-model:pickable="pickable"
          :points="points"
          :map-config="mapConfig"
          :selected-id="selectedPoint?.id ?? null"
          height="640px"
          @select="handleSelect"
          @pick="handlePick"
        />
      </section>

      <!-- 侧栏 -->
      <aside class="side">
        <section class="panel side__point">
          <template v-if="selectedPoint">
            <div class="point-head">
              <div>
                <h3 class="point-head__name">{{ selectedPoint.name }}</h3>
                <p class="point-head__type">{{ pointTypeLabel(selectedPoint.type) }}</p>
              </div>
              <div class="safety">
                <b>{{ selectedPoint.safetyScore }}</b>
                <span>安全分</span>
              </div>
            </div>

            <div class="chips">
              <span v-for="tag in selectedPoint.safeTags" :key="tag" class="chip chip--brand">{{ tag }}</span>
              <span class="chip">在售 {{ selectedPoint.itemCount }} 件</span>
              <span v-if="selectedPoint.minPrice" class="chip chip--accent">最低 ¥{{ formatPrice(selectedPoint.minPrice) }}</span>
            </div>

            <div v-if="selectedPoint.guideText" class="guide">
              <span class="guide__icon"><el-icon><Location /></el-icon></span>
              <p>{{ selectedPoint.guideText }}</p>
            </div>
          </template>
          <p v-else class="page-subtitle">点击地图上的气泡查看该面交点</p>
        </section>

        <section class="panel side__items">
          <div class="panel-head">
            <h3 class="panel-title">
              {{ pointMatch.scope === 'nearby' ? '附近点位的闲置' : '该点位的闲置' }}
            </h3>
            <span v-if="pointMatch.list.length" class="text-muted">{{ pointMatch.list.length }} 件</span>
          </div>

          <p v-if="pointMatch.scope === 'nearby'" class="nearby-tip">
            这个点位暂时没有在售商品，请看附近的
          </p>

          <div v-loading="loadingItems" class="rows">
            <button
              v-for="item in pointMatch.list"
              :key="item.id"
              class="row"
              type="button"
              @click="openItem(item.id)"
            >
              <img class="row__cover" :src="item.coverUrl" :alt="item.title" />
              <span class="row__body">
                <span class="row__title">{{ item.title }}</span>
                <span class="row__meta">
                  <b class="row__price">¥{{ formatPrice(item.price) }}</b>
                  <em>{{ item.preferPointName || '面交点待定' }}</em>
                </span>
              </span>
            </button>

            <div v-if="!pointMatch.list.length && !loadingItems" class="rows__empty">
              <p>这个点位暂时没有在售商品</p>
              <el-button text type="primary" @click="router.push('/')">去首页逛逛 →</el-button>
            </div>
          </div>
        </section>

        <section v-if="tradePoints.length" class="panel side__list">
          <h3 class="panel-title">全部面交点（{{ tradePoints.length }}）</h3>
          <div class="points">
            <button
              v-for="point in sortedPoints"
              :key="point.id"
              class="point-row"
              :class="{ 'is-active': selectedPoint?.id === point.id }"
              type="button"
              @click="handlePointSelectFromList(point)"
            >
              <span class="point-row__name">{{ point.name }}</span>
              <span class="point-row__type">{{ pointTypeLabel(point.type) }}</span>
              <span class="point-row__count" :class="{ 'is-zero': !point.itemCount }">{{ point.itemCount }}</span>
            </button>
          </div>
        </section>
      </aside>
    </div>

    <el-dialog v-model="suggestVisible" title="推荐一个新的面交点" width="480px">
      <p class="dialog-tip">
        你选中了坐标 X={{ pickedCoord?.coordX }}、Y={{ pickedCoord?.coordY }}，给它起个同学们看得懂的名字吧。
      </p>
      <el-form label-position="top">
        <el-form-item label="点位名称">
          <el-input v-model="suggestName" placeholder="例如：3 号宿舍楼下快递柜" maxlength="20" show-word-limit />
        </el-form-item>
        <el-form-item label="指引说明（选填）">
          <el-input
            v-model="suggestGuide"
            type="textarea"
            :rows="2"
            placeholder="例如：从东门进直走 100 米，蓝色快递柜旁"
            maxlength="60"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="suggestVisible = false">取消</el-button>
        <el-button type="primary" :loading="suggesting" @click="submitSuggest">提交推荐</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.map-page {
  &__tools {
    display: flex;
    align-items: center;
    gap: 14px;
  }
}

.switch {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
}

.layout {
  display: grid;
  gap: 18px;
  grid-template-columns: minmax(0, 1fr) 360px;
  align-items: start;
}

.map-box {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
  box-shadow: var(--shadow-sm);
}

.side {
  display: flex;
  flex-direction: column;
  gap: 18px;

  &__items {
    display: flex;
    flex-direction: column;
    max-height: 420px;
  }

  &__list {
    max-height: 280px;
    overflow-y: auto;
  }
}

// ---------- 点位信息 ----------
.point-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  &__name {
    font-size: 16px;
    font-weight: 700;
  }

  &__type {
    color: var(--text-3);
    font-size: 12.5px;
  }
}

.safety {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 12px;
  border-radius: var(--r-md);
  background: var(--brand-50);

  b {
    color: var(--brand-700);
    font-size: 19px;
    font-weight: 800;
    line-height: 1.2;
  }

  span {
    color: var(--brand-600);
    font-size: 11px;
  }
}

.chips {
  display: flex;
  gap: 6px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.guide {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-700);
  font-size: 12.5px;
  line-height: 1.7;

  &__icon {
    margin-top: 2px;
  }
}

// ---------- 商品行 ----------
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  .panel-title {
    margin-bottom: 0;
  }
}

.nearby-tip {
  margin-top: 10px;
  padding: 7px 10px;
  border-radius: var(--r-sm);
  background: #fff8f4;
  color: var(--accent-600);
  font-size: 12px;
}

.rows {
  display: flex;
  overflow-y: auto;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;

  &__empty {
    padding: 26px 0;
    color: var(--text-3);
    font-size: 13px;
    text-align: center;
  }
}

.row {
  display: flex;
  gap: 10px;
  width: 100%;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: var(--r-md);
  background: var(--surface-2);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: all 0.18s ease;

  &:hover {
    border-color: var(--brand-200);
    background: #fff;
    transform: translateX(2px);
  }

  &__cover {
    width: 56px;
    height: 56px;
    flex-shrink: 0;
    border-radius: var(--r-sm);
    background: #eef2f6;
    object-fit: cover;
  }

  &__body {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    justify-content: space-between;
  }

  &__title {
    display: -webkit-box;
    overflow: hidden;
    color: var(--text-1);
    font-size: 13px;
    font-weight: 500;
    line-height: 1.5;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__meta {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
  }

  &__price {
    color: var(--accent-500);
    font-size: 14px;
    font-weight: 700;
  }

  &__meta em {
    overflow: hidden;
    color: var(--text-3);
    font-size: 11.5px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

// ---------- 点位列表 ----------
.points {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.point-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: background 0.16s ease;

  &:hover {
    background: var(--surface-2);
  }

  &.is-active {
    background: var(--brand-50);

    .point-row__name {
      color: var(--brand-700);
      font-weight: 600;
    }
  }

  &__name {
    flex: 1;
    overflow: hidden;
    color: var(--text-2);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__type {
    color: var(--text-3);
    font-size: 11.5px;
  }

  &__count {
    min-width: 22px;
    padding: 0 6px;
    border-radius: 999px;
    background: var(--brand-100);
    color: var(--brand-700);
    font-size: 11.5px;
    font-weight: 600;
    line-height: 18px;
    text-align: center;

    &.is-zero {
      background: var(--line);
      color: var(--text-3);
    }
  }
}

.dialog-tip {
  margin-bottom: 14px;
  color: var(--text-2);
  font-size: 13px;
}

@media (max-width: 1080px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
