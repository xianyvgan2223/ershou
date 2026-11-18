<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Location, Refresh } from '@element-plus/icons-vue'
import CampusMap from '@/components/CampusMap.vue'
import AmapCampusMap from '@/components/AmapCampusMap.vue'
import { fetchMapPoints, fetchTradePoints, pointTypeLabel } from '@/api/map'
import type { MapConfig, MapPoint, TradePoint } from '@/api/map'
import { fetchItemDetail, fetchItems } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { useTradePoints } from '@/composables/useTradePoints'
import { amapWalkingUri, hasAmapKey } from '@/utils/amap'
import {
  MERGE_RADIUS_METERS,
  groupItemCount,
  groupMinPrice,
  groupPointsByDistance,
  mergedPointOf,
} from '@/utils/pointMerge'
import type { PointGroup } from '@/utils/pointMerge'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'
import { formatPrice } from '@/utils/format'

/** 校区没配尺度时的兜底判定半径（米） */
const DEFAULT_NEARBY_RADIUS = 300
/** 侧栏一次拉多少件商品：原来写死 100，点位一多就有商品永远显示不出来 */
const ITEM_PAGE_SIZE = 60

const router = useRouter()
const route = useRoute()
/** 点位库版本号：发布页/私聊页新增点位后 +1，地图页据此自动刷新，不用手动刷新浏览器 */
const { pointsVersion } = useTradePoints()

const points = ref<MapPoint[]>([])
const tradePoints = ref<TradePoint[]>([])
const mapConfig = ref<MapConfig | null>(null)
const items = ref<ItemVO[]>([])
const selectedPoint = ref<MapPoint | null>(null)
const loadingMap = ref(false)
const loadingItems = ref(false)
/** 高德地图组件对外暴露的能力（避免依赖具体组件类型） */
interface MapComponentApi {
  locate(): Promise<[number, number] | null>
  setCenter(lngLat: [number, number], targetZoom?: number): void
}
const mapRef = ref<MapComponentApi | null>(null)
/** 我的位置（GCJ-02），仅在浏览器内存中使用，不上传后端 */
const myLocation = ref<[number, number] | null>(null)
/** 后端 campus.map_type：1=自绘平面图 2=第三方地图 */
const campusMapType = ref(1)
/* ---------- 搜索 / 筛选 ---------- */
const keyword = ref('')
const onlyWithItems = ref(false)
/* ---------- 从商品详情跳来：只看这件商品的位置 ---------- */
const focusItem = ref<ItemVO | null>(null)
/** true = 只看这件商品所在的面交点（默认进入；用户可手动切回全部） */
const focusOnly = ref(false)

/** 判定半径跟着校区尺度走：花江 1500m 的校区用 300m 几乎等于"全校区" */
const nearbyRadius = computed(() => {
  const size = mapConfig.value?.campusSizeMeters
  if (!size) return DEFAULT_NEARBY_RADIUS
  return Math.min(Math.max(Math.round(size / 4), 120), 500)
})

/** 搜索 + "只看有货"筛选后的点位（列表与地图共用，保证两边一致） */
const filteredPoints = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return points.value.filter((point) => {
    if (onlyWithItems.value && !point.itemCount) return false
    if (!kw) return true
    return (
      point.name.toLowerCase().includes(kw) ||
      pointTypeLabel(point.type).toLowerCase().includes(kw) ||
      (point.guideText ?? '').toLowerCase().includes(kw)
    )
  })
})

/** 这件商品所在的面交点 id（没有就为 null） */
const focusPointId = computed(() => focusItem.value?.preferPointId ?? null)

/**
 * 地图与列表实际显示的点位。
 * "只看这件商品的位置"时**只留这一个点位**——需求很明确：从商品详情跳过来不要被别的标记淹没。
 */
const visiblePoints = computed(() => {
  if (focusOnly.value && focusItem.value) {
    const id = focusPointId.value
    return id ? points.value.filter((point) => point.id === id) : []
  }
  return filteredPoints.value
})

/** 焦点模式下的地图高亮 id */
const highlightPointId = computed(() => (focusOnly.value ? focusPointId.value : null))

function exitFocus(): void {
  focusOnly.value = false
}

/**
 * 展示用的点位：把 **100 米内** 的点位合成一个（地图气泡与列表都只出现一条）。
 *
 * 合并只影响"看和选"：`selectedPoint` 存的仍是真实点位，商品详情 / 面交单里的地址
 * 永远精确到具体点位，不会被合并。
 */
const pointGroups = computed<PointGroup<MapPoint>[]>(() =>
  groupPointsByDistance(visiblePoints.value, MERGE_RADIUS_METERS),
)

/** 全部组（含被搜索/筛选掉的），用于选中合并点位时反查组内成员 */
const allPointGroups = computed<PointGroup<MapPoint>[]>(() =>
  groupPointsByDistance(points.value, MERGE_RADIUS_METERS),
)

/** 送到地图上的点位：每个合并组一个代表点位 */
const mapPoints = computed<MapPoint[]>(() => pointGroups.value.map((group) => mergedPointOf(group)))

/** 气泡角标：代表点位 id → 组内点位个数 */
const groupCounts = computed<Record<number, number>>(() => {
  const counts: Record<number, number> = {}
  pointGroups.value.forEach((group) => {
    counts[group.rep.id] = group.members.length
  })
  return counts
})

/** 有几个气泡是合并出来的（副标题上说明一句，避免"点位怎么变少了"的疑问） */
const mergedCount = computed(
  () => pointGroups.value.filter((group) => group.members.length > 1).length,
)

/** 选中点位（可能是合并组的代表）所属的组 */
const selectedGroup = computed<PointGroup<MapPoint> | null>(() => {
  const current = selectedPoint.value
  if (!current) return null
  return allPointGroups.value.find((group) => group.rep.id === current.id) ?? null
})

/** 选中点位所在组的全部点位 id（用于把组内商品一起列出来） */
const selectedGroupIds = computed<number[]>(
  () => selectedGroup.value?.members.map((point) => point.id) ?? [],
)

/** 合并了多个点位时才显示"组内明细" */
const showGroupDetail = computed(() => (selectedGroup.value?.members.length ?? 0) > 1)

/** 选中点位/组的汇总数字 */
const selectedSummary = computed(() => {
  const group = selectedGroup.value
  if (!group) return { itemCount: 0, minPrice: null as number | null }
  if (group.members.length <= 1) {
    return { itemCount: group.rep.itemCount ?? 0, minPrice: group.rep.minPrice ?? null }
  }
  return { itemCount: groupItemCount(group), minPrice: groupMinPrice(group) }
})

/** 侧栏列表用：按在售数倒序 */
const sortedVisiblePoints = computed(() => {
  const merged = pointGroups.value.map((group) => mergedPointOf(group))
  return merged.sort((a, b) => (b.itemCount ?? 0) - (a.itemCount ?? 0) || a.id - b.id)
})

/** 被筛选掉时给用户一句解释，避免"点了搜索地图就空了"的困惑 */
const filterHint = computed(() => {
  if (focusOnly.value) return ''
  if (!points.value.length) return ''
  if (visiblePoints.value.length === points.value.length) return ''
  return `已筛掉 ${points.value.length - visiblePoints.value.length} 个点位`
})

/** 只有"校区配了第三方地图 + 本地配了高德 Key"才启用真地图，否则保持自绘平面图 */
const useAmap = computed(() => campusMapType.value === 2 && hasAmapKey())

const totalOnSale = computed(() => points.value.reduce((sum, point) => sum + (point.itemCount || 0), 0))

/** 像素坐标 → GCJ-02 经纬度：与 AmapCampusMap 内部换算保持一致 */
function pointLngLat(point: MapPoint): [number, number] | null {
  if (point.lng && point.lat) return [point.lng, point.lat]
  if (point.coordX === null || point.coordX === undefined) return null
  if (point.coordY === null || point.coordY === undefined) return null
  const config = mapConfig.value
  if (!config?.center?.lng || !config.center.lat) return null
  const width = config.pixelBounds?.width || config.width || 800
  const height = config.pixelBounds?.height || config.height || 600
  const campusSize = config.campusSizeMeters || 800
  const lngPerPixel = config.lngPerPixel || campusSize / width / (111320 * Math.cos((config.center.lat * Math.PI) / 180))
  const latPerPixel = config.latPerPixel || campusSize / height / 111320
  return [
    config.center.lng + (point.coordX - width / 2) * lngPerPixel,
    config.center.lat - (point.coordY - height / 2) * latPerPixel,
  ]
}

/** 球面距离（米） */
function distanceMeters(a: [number, number], b: [number, number]): number {
  const R = 6371008.8
  const rad = (d: number) => (d * Math.PI) / 180
  const dLat = rad(b[1] - a[1])
  const dLng = rad(b[0] - a[0])
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** 我 → 某点位的距离文本，未定位时返回空 */
function distanceText(point: MapPoint): string {
  const me = myLocation.value
  if (!me) return ''
  const target = pointLngLat(point)
  if (!target) return ''
  const meters = distanceMeters(me, target)
  return meters < 1000 ? `${Math.round(meters)} m` : `${(meters / 1000).toFixed(1)} km`
}

/** 当前选中的点位 / 区域能看到的商品 */
const pointMatch = computed<{ list: ItemVO[]; scope: 'point' | 'nearby' | 'none' }>(() => {
  const current = selectedPoint.value
  if (!current) return { list: [], scope: 'none' }
  // 合并组：把组内所有点位的商品一起列出来（用户点的是一个"地方"，不是一条记录）
  const groupIds = selectedGroupIds.value.length ? selectedGroupIds.value : [current.id]
  const exact = items.value.filter(
    (item) => item.preferPointId !== null && groupIds.includes(item.preferPointId),
  )
  if (exact.length) return { list: exact, scope: 'point' }
  const origin = pointLngLat(current)
  const nearIds = new Set(
    points.value
      .filter((point) => {
        if (groupIds.includes(point.id) || !origin) return false
        const target = pointLngLat(point)
        return Boolean(target && distanceMeters(origin, target) <= nearbyRadius.value)
      })
      .map((point) => point.id),
  )
  const nearby = items.value.filter(
    (item) => item.preferPointId !== null && nearIds.has(item.preferPointId),
  )
  return nearby.length ? { list: nearby, scope: 'nearby' } : { list: [], scope: 'none' }
})

/** 点位详情里的导航链接（只有拿到 GCJ-02 经纬度才给，像素坐标没法导航） */
const selectedPointGeo = computed<[number, number] | null>(() => {
  const point = selectedPoint.value
  if (!point?.lng || !point?.lat) return null
  return [point.lng, point.lat]
})

/** 商品所在点位的经纬度（高亮那件商品的位置） */
const focusItemGeo = computed<[number, number] | null>(() => {
  const pointId = focusItem.value?.preferPointId
  if (!pointId) return null
  const point = points.value.find((item) => item.id === pointId)
  if (!point?.lng || !point?.lat) return null
  return [point.lng, point.lat]
})

function openNavigation(): void {
  const point = selectedPoint.value
  const geo = selectedPointGeo.value
  if (!point || !geo) {
    ElMessage.warning('这个点位没有真实经纬度，暂时没法导航')
    return
  }
  window.open(amapWalkingUri(geo[0], geo[1], { name: point.name }), '_blank')
}

function openFocusNavigation(): void {
  const geo = focusItemGeo.value
  if (!geo) {
    ElMessage.warning('这件商品的面交点没有经纬度，暂时没法导航')
    return
  }
  const name = focusItem.value?.preferPointName || '面交点'
  window.open(amapWalkingUri(geo[0], geo[1], { name }), '_blank')
}

/** 从商品详情跳过来时：只显示这件商品所在的面交点，并把地图移过去 */
async function focusItemById(itemId: number): Promise<void> {
  try {
    focusItem.value = await fetchItemDetail(itemId)
  } catch {
    focusItem.value = null
    return
  }
  const pointId = focusItem.value?.preferPointId
  if (!pointId) {
    // 这件商品没设面交点：不进入焦点模式，正常显示全部点位
    return
  }
  const target = points.value.find((point) => point.id === pointId)
  if (!target) return
  focusOnly.value = true
  selectedPoint.value = target
  const geo = pointLngLat(target)
  if (geo && useAmap.value) mapRef.value?.setCenter(geo, 17)
}

async function loadMap(): Promise<void> {
  loadingMap.value = true
  try {
    const result = await fetchMapPoints(DEFAULT_CAMPUS_ID)
    mapConfig.value = result.mapConfig ?? null
    campusMapType.value = result.mapType ?? 1
    points.value = result.points ?? []
    // 默认选中商品最多的点位（而不是数组里的第一个）
    if (!selectedPoint.value && points.value.length) {
      selectedPoint.value = [...points.value].sort((a, b) => b.itemCount - a.itemCount)[0] ?? null
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
    const result = await fetchItems({ pageNum: 1, pageSize: ITEM_PAGE_SIZE, sort: 'latest' })
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

/** 把地图移到我的位置（高德模式下同时画蓝点） */
async function goMyLocation(): Promise<void> {
  if (!useAmap.value) {
    ElMessage.info('当前校区使用自绘平面图，暂不支持定位')
    return
  }
  const result = await mapRef.value?.locate()
  if (result) {
    myLocation.value = result
    ElMessage.success('已定位到你的位置，距离按实际米数显示')
  } else {
    ElMessage.warning('定位失败：请允许浏览器获取位置（需 HTTPS 或 localhost）')
  }
}

onMounted(async () => {
  await loadMap()
  void loadTradePoints()
  void loadItems()
  // 从商品详情点"在地图上看"会带 ?itemId=：只显示这件商品所在的那个点位
  const itemId = Number(route.query.itemId)
  if (Number.isFinite(itemId) && itemId > 0) await focusItemById(itemId)
})

// 别的页面（发布页选址、私聊页改地址）新增了点位 → 地图页自动刷新，不必手按刷新
watch(pointsVersion, () => {
  void loadMap()
  void loadTradePoints()
})

// 退出焦点模式时清掉 URL 上的 itemId，避免刷新后又只剩一个点位
watch(focusOnly, (value) => {
  if (!value && route.query.itemId) void router.replace({ path: '/map' })
})
</script>

<template>
  <div class="page map-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">校内地图</h2>
        <p class="page-subtitle">
          点气泡看该点位在售的闲置 · 共 {{ mapPoints.length }} 个面交点、{{ totalOnSale }} 件在售
          <template v-if="mergedCount > 0">
            （其中 {{ mergedCount }} 个是相近点位合并显示的）
          </template>
        </p>
      </div>
      <div class="map-page__tools">
        <el-input
          v-model="keyword"
          class="map-page__search"
          placeholder="搜点位：图书馆 / 食堂 / 快递"
          clearable
        />
        <el-checkbox v-model="onlyWithItems">只看有在售的</el-checkbox>
        <el-button v-if="useAmap" round @click="goMyLocation">我的位置</el-button>
        <el-button :icon="Refresh" :loading="loadingMap" round @click="loadMap">刷新</el-button>
      </div>
    </div>

    <!-- 从商品详情跳过来：只显示这一件商品的位置 -->
    <div v-if="focusItem" class="map-page__focus">
      <span class="map-page__focus-text">
        正在看《{{ focusItem.title }}》的位置
        <template v-if="focusOnly">· 地图上只显示这一个面交点</template>
      </span>
      <el-button v-if="focusOnly" size="small" round @click="exitFocus">显示全部点位</el-button>
    </div>

    <div class="layout">
      <!-- 地图：校区配了第三方地图就走高德，否则保持自绘平面图 -->
      <section class="map-box">
        <AmapCampusMap
          v-if="useAmap"
          ref="mapRef"
          :points="mapPoints"
          :map-config="mapConfig"
          :selected-id="selectedPoint?.id ?? null"
          :highlight-point-id="highlightPointId"
          :group-counts="groupCounts"
          :show-fit-button="!focusOnly"
          height="100%"
          @select="handleSelect"
        />
        <CampusMap
          v-else
          :points="mapPoints"
          :map-config="mapConfig"
          :selected-id="selectedPoint?.id ?? null"
          :highlight-point-id="highlightPointId"
          :group-counts="groupCounts"
          height="100%"
          @select="handleSelect"
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
              <span class="chip">在售 {{ selectedSummary.itemCount }} 件</span>
              <span v-if="selectedSummary.minPrice" class="chip chip--accent">
                最低 ¥{{ formatPrice(selectedSummary.minPrice) }}
              </span>
              <span v-if="distanceText(selectedPoint)" class="chip">{{ distanceText(selectedPoint) }}</span>
            </div>

            <!-- 合并组：把组内每个精确点位列出来，地址一目了然 -->
            <div v-if="showGroupDetail" class="subpoints">
              <p class="subpoints__title">
                这里合并了 {{ selectedGroup?.members.length }} 个相近点位（不足 {{ MERGE_RADIUS_METERS }} 米）
              </p>
              <ul class="subpoints__list">
                <li v-for="member in selectedGroup?.members" :key="member.id" class="subpoints__item">
                  <span class="subpoints__name">{{ member.name }}</span>
                  <span class="subpoints__meta">
                    {{ pointTypeLabel(member.type) }} · 在售 {{ member.itemCount }}
                    <template v-if="member.guideText"> · {{ member.guideText }}</template>
                  </span>
                </li>
              </ul>
            </div>

            <div v-if="selectedPoint.guideText" class="guide">
              <span class="guide__icon"><el-icon><Location /></el-icon></span>
              <p>{{ selectedPoint.guideText }}</p>
            </div>

            <!-- 高德 URI 唤起导航：纯链接跳转，不消耗接口配额 -->
            <div class="point-actions">
              <el-button
                v-if="selectedPointGeo"
                type="primary"
                size="small"
                round
                @click="openNavigation"
              >
                怎么去（高德导航）
              </el-button>
              <span v-else class="point-actions__hint">该点位没有经纬度，无法导航</span>
            </div>
          </template>
          <p v-else class="page-subtitle">点击地图上的气泡查看该面交点</p>
        </section>

        <!-- 从商品详情跳过来：把"这件东西在哪"直接摆在最上面 -->
        <section v-if="focusItem" class="panel side__focus">
          <div class="focus">
            <img class="focus__cover" :src="focusItem.coverUrl" :alt="focusItem.title" />
            <div class="focus__body">
              <p class="focus__title">{{ focusItem.title }}</p>
              <p class="focus__meta">
                <b>¥{{ formatPrice(focusItem.price) }}</b>
                <span>面交点：{{ focusItem.preferPointName || '待协商' }}</span>
              </p>
            </div>
          </div>
          <div class="point-actions">
            <el-button size="small" round @click="openItem(focusItem.id)">看商品详情</el-button>
            <el-button
              v-if="focusItemGeo"
              size="small"
              type="primary"
              round
              @click="openFocusNavigation"
            >
              怎么去
            </el-button>
          </div>
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
              :class="{ 'is-focus': focusItem?.id === item.id }"
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
          <div class="panel-head">
            <h3 class="panel-title">面交点（{{ sortedVisiblePoints.length }}）</h3>
            <span v-if="filterHint" class="text-muted">{{ filterHint }}</span>
            <span v-else-if="sortedVisiblePoints.length < visiblePoints.length" class="text-muted">
              已合并 {{ visiblePoints.length - sortedVisiblePoints.length }} 个相近点位
            </span>
          </div>
          <div class="points">
            <button
              v-for="point in sortedVisiblePoints"
              :key="point.id"
              class="point-row"
              :class="{
                'is-active': selectedPoint?.id === point.id,
                'is-focus': focusItem?.preferPointId === point.id,
              }"
              type="button"
              @click="handlePointSelectFromList(point)"
            >
              <span class="point-row__name">{{ point.name }}</span>
              <span v-if="distanceText(point)" class="point-row__distance">{{ distanceText(point) }}</span>
              <span class="point-row__type">{{ pointTypeLabel(point.type) }}</span>
              <span class="point-row__count" :class="{ 'is-zero': !point.itemCount }">{{ point.itemCount }}</span>
            </button>
            <p v-if="!sortedVisiblePoints.length" class="points__empty">
              没有匹配「{{ keyword }}」的点位
              <el-button text type="primary" size="small" @click="keyword = ''; onlyWithItems = false">
                清空筛选
              </el-button>
            </p>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.map-page {
  display: flex;
  flex-direction: column;
  min-height: 0;

  &__tools {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__search {
    width: 220px;
  }

  &__picking {
    margin-bottom: 12px;
  }

  /* 从商品详情跳来的提示条：说明"为什么地图上只有一个点" */
  &__focus {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
    padding: 8px 14px;
    border: 1px solid #fecaca;
    border-radius: var(--r-md);
    background: #fef2f2;
    font-size: 13px;
  }

  &__focus-text {
    flex: 1;
    overflow: hidden;
    color: #b91c1c;
    text-overflow: ellipsis;
    white-space: nowrap;
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

/* 地图与侧栏同高、随窗口伸缩：以前地图写死 640px，小屏侧栏被压 */
.layout {
  display: grid;
  flex: 1;
  min-height: 480px;
  gap: 18px;
  grid-template-columns: minmax(0, 1fr) 360px;
  align-items: stretch;
  height: calc(100vh - 232px);
}

.map-box {
  overflow: hidden;
  height: 100%;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.side {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  gap: 14px;

  // 商品列表吃掉剩余高度（原来固定 420px，窗口一大就浪费）
  &__items {
    display: flex;
    overflow: hidden;
    flex: 1;
    flex-direction: column;
    min-height: 200px;
  }

  // 点位列表给固定但更合理的高度：它是最长的列表
  &__list {
    display: flex;
    flex: 0 0 auto;
    flex-direction: column;
    max-height: 34%;
    min-height: 150px;
  }
}

/* 从商品详情跳过来时的定位卡片 */
.focus {
  display: flex;
  align-items: center;
  gap: 10px;

  &__cover {
    width: 52px;
    height: 52px;
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
    gap: 2px;
  }

  &__title {
    display: -webkit-box;
    overflow: hidden;
    margin: 0;
    color: var(--text-1);
    font-size: 13.5px;
    font-weight: 600;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__meta {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin: 0;
    color: var(--text-3);
    font-size: 12px;

    b {
      color: var(--accent-500);
      font-size: 14px;
    }
  }
}

.point-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;

  &__hint {
    color: var(--text-3);
    font-size: 12px;
  }
}

/* 合并组内的精确点位明细 */
.subpoints {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #ddd6fe;
  border-radius: var(--r-md);
  background: #f5f3ff;

  &__title {
    margin: 0 0 6px;
    color: #6d28d9;
    font-size: 12.5px;
    font-weight: 600;
  }

  &__list {
    margin: 0;
    padding-left: 16px;
    color: var(--text-2);
  }

  &__item {
    font-size: 12.5px;
    line-height: 1.9;
  }

  &__name {
    color: var(--text-1);
    font-weight: 600;
    margin-right: 6px;
  }

  &__meta {
    color: var(--text-3);
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

  // 从商品详情跳过来时高亮那一件
  &.is-focus {
    border-color: #ef4444;
    background: #fff5f5;
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
  overflow-y: auto;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-height: 0;

  &__empty {
    margin: 0;
    padding: 18px 0;
    color: var(--text-3);
    font-size: 13px;
    text-align: center;
  }
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

  // 商品所在的点位：左边一条红杠，和地图上的红气泡对应
  &.is-focus {
    box-shadow: inset 3px 0 0 #ef4444;
    background: #fef2f2;

    .point-row__name {
      color: #b91c1c;
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

  &__distance {
    flex-shrink: 0;
    color: var(--brand-600);
    font-size: 11.5px;
    font-weight: 600;
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
  line-height: 1.8;
}

.dialog-tag {
  margin: 0 4px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--brand-100);
  color: var(--brand-700);
  font-size: 11px;
}

@media (max-width: 1080px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
