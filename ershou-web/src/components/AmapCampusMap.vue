<script setup lang="ts">
/**
 * 高德真地图版校园地图（mapType=2）
 *
 * 与 CampusMap.vue（Leaflet 自绘平面图）保持完全相同的 props / events，
 * 因此 MapView 只需按 campus.mapType 切换组件，业务代码不用改。
 *
 * 三个关键点：
 * 1. 坐标：优先用点位自带的 lng/lat（GCJ-02）；没有时按 mapConfig 的换算系数
 *    把自绘底图的像素坐标线性映射到经纬度，保证老数据也能立刻显示在高德地图上。
 * 2. 定位：浏览器 Geolocation + 高德定位插件双通道，蓝点显示"我在哪"。
 * 3. 选点：点击地图空白处 emit('pick', { coordX, coordY }) 给出真实经纬度，
 *    coordX/coordY 沿用旧字段名传经纬度，后端加列后即可直接入库。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { gpsToGcj02, hasAmapKey, loadAmap } from '@/utils/amap'
import type { AMapCircle, AMapGeolocationResult, AMapMap, AMapMarker, AMapNamespace } from '@/utils/amap'
import { pointTypeLabel } from '@/api/map'
import type { MapConfig, MapPoint } from '@/api/map'

/** 选点模式返回的坐标 */
export interface PickCoord {
  /** 高德地图下为经度 lng（自绘底图下为像素 X） */
  coordX: number
  /** 高德地图下为纬度 lat（自绘底图下为像素 Y） */
  coordY: number
}

const props = withDefaults(
  defineProps<{
    points?: MapPoint[]
    mapConfig?: MapConfig | null
    height?: string
    selectedId?: number | null
    /** 需要高亮标记的商品 id（从商品详情跳过来时标出"这件东西在哪个点位"） */
    highlightPointId?: number | null
    /** 是否显示"看全部点位"浮动按钮（地图页用；发布/私聊的选点弹窗里不需要） */
    showFitButton?: boolean
    /**
     * 合并组信息：点位 id → 组内点位个数（>1 表示这个气泡是合并出来的）。
     * 只为展示角标，组件本身不做合并计算（合并逻辑在 utils/pointMerge.ts）。
     */
    groupCounts?: Record<number, number>
    showLegend?: boolean
    showLabel?: boolean
  }>(),
  {
    points: () => [],
    mapConfig: null,
    height: '600px',
    selectedId: null,
    highlightPointId: null,
    showFitButton: false,
    groupCounts: () => ({}),
    showLegend: true,
    showLabel: true,
  },
)

const emit = defineEmits<{
  (e: 'select', point: MapPoint): void
  (e: 'pick', coord: PickCoord): void
}>()

/** 选点模式：支持 v-model:pickable 或 :pickable 两种写法 */
const pickable = defineModel<boolean>('pickable', { default: false })

/** 未配置校区中心点时的兜底视野 */
const FALLBACK_CENTER: [number, number] = [116.397428, 39.90923]
const FALLBACK_ZOOM = 15
/** 换算经纬度时假设的校区边长（米） */
const DEFAULT_CAMPUS_SIZE = 800
/** 自绘底图缺省像素范围，与 CampusMap 的 GRID_BOUNDS 保持一致 */
const DEFAULT_PIXEL_BOUNDS = { width: 800, height: 600 }

const containerRef = ref<HTMLDivElement | null>(null)
const errorMsg = ref('')
const mapReady = ref(false)
const loading = ref(false)
const locationBusy = ref(false)
const myLngLat = ref<[number, number] | null>(null)

let AMapRef: AMapNamespace | null = null
let map: AMapMap | null = null
/** 按点位 id 索引：更新选中态只改标记内容，不再"清空 + 全量重建"（点位多时那一下会明显卡顿） */
const markerById = new Map<number, AMapMarker>()
let markers: AMapMarker[] = []
let locationDot: AMapMarker | null = null
let accuracyCircle: AMapCircle | null = null
let resizeObserver: ResizeObserver | null = null

const hasCenter = computed(() => Boolean(props.mapConfig?.center?.lng && props.mapConfig?.center?.lat))
const center = computed<[number, number]>(() =>
  hasCenter.value
    ? [props.mapConfig!.center!.lng, props.mapConfig!.center!.lat]
    : FALLBACK_CENTER,
)
const zoom = computed(() => props.mapConfig?.zoom ?? FALLBACK_ZOOM)

/* ------------------------------------------------------------------ *
 * 坐标系换算
 * ------------------------------------------------------------------ */

/** 每个像素对应的经度增量：优先取配置，否则按"校区边长"均分像素宽度推算 */
function lngPerPixel(): number {
  const config = props.mapConfig
  if (config?.lngPerPixel) return config.lngPerPixel
  const width = config?.pixelBounds?.width || config?.width || DEFAULT_PIXEL_BOUNDS.width
  const meters = config?.campusSizeMeters || DEFAULT_CAMPUS_SIZE
  return meters / width / metersPerLngDegree(center.value[1])
}

function latPerPixel(): number {
  const config = props.mapConfig
  if (config?.latPerPixel) return config.latPerPixel
  const height = config?.pixelBounds?.height || config?.height || DEFAULT_PIXEL_BOUNDS.height
  const meters = config?.campusSizeMeters || DEFAULT_CAMPUS_SIZE
  return meters / height / 111320
}

/** 1 经度 ≈ 111320 * cos(lat) 米 */
function metersPerLngDegree(lat: number): number {
  return 111320 * Math.max(Math.cos((lat * Math.PI) / 180), 0.1)
}

/** 像素坐标 → GCJ-02 经纬度：以校区中心点为准做等比映射 */
function pixelToLngLat(coordX: number, coordY: number): [number, number] {
  const config = props.mapConfig
  const [originLng, originLat] = center.value
  const width = config?.pixelBounds?.width || config?.width || DEFAULT_PIXEL_BOUNDS.width
  const height = config?.pixelBounds?.height || config?.height || DEFAULT_PIXEL_BOUNDS.height
  const lng = originLng + (coordX - width / 2) * lngPerPixel()
  const lat = originLat - (coordY - height / 2) * latPerPixel()
  return [lng, lat]
}

/** 经纬度 → 像素坐标（用户在高德地图上选点后回填到自绘底图用） */
function lngLatToPixel(lng: number, lat: number): PickCoord {
  const config = props.mapConfig
  const [originLng, originLat] = center.value
  const width = config?.pixelBounds?.width || config?.width || DEFAULT_PIXEL_BOUNDS.width
  const height = config?.pixelBounds?.height || config?.height || DEFAULT_PIXEL_BOUNDS.height
  return {
    coordX: Math.round(width / 2 + (lng - originLng) / lngPerPixel()),
    coordY: Math.round(height / 2 - (lat - originLat) / latPerPixel()),
  }
}

/** 点位的最终展示坐标：真实经纬度优先，否则由像素坐标换算 */
function pointLngLat(point: MapPoint): [number, number] | null {
  if (point.lng && point.lat) return [point.lng, point.lat]
  if (point.coordX === null || point.coordX === undefined) return null
  if (point.coordY === null || point.coordY === undefined) return null
  if (!hasCenter.value) return null
  return pixelToLngLat(point.coordX, point.coordY)
}

/** 两点间球面距离（米），用于"离我最近"排序与米制筛选；已通过 defineExpose 对外提供 */
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

defineExpose({ invalidateSize, refresh, locate, setCenter, fitAll, distanceMeters, lngLatToPixel, center, zoom })

/* ------------------------------------------------------------------ *
 * 标记渲染
 * ------------------------------------------------------------------ */

function bubbleHtml(point: MapPoint): string {
  const classNames = ['amap-campus__bubble']
  if (!point.itemCount) classNames.push('is-empty')
  if (props.selectedId === point.id) classNames.push('is-active')
  if (props.highlightPointId === point.id) classNames.push('is-highlight')
  // 合并组：气泡右上角标个"N"，点开详情能看到组内每个精确点位
  const memberCount = props.groupCounts[point.id] ?? 1
  if (memberCount > 1) classNames.push('is-merged')
  const label = props.showLabel
    ? `<span class="amap-campus__label">${escapeHtml(point.name)}</span>`
    : ''
  const badge = memberCount > 1 ? `<i class="amap-campus__merged">${memberCount}</i>` : ''
  // ⚠️ 角标必须锚在**气泡**上，所以要给气泡单独套一层 relative 的 .amap-campus__pin。
  // 直接挂在 .amap-campus__marker 上会飘走：那个容器是 flex-column 且含名称标签，
  // 宽度被标签撑大、top 又对着标签上方 —— 角标会跑到标签右上角、离气泡很远（踩过）。
  return `<div class="amap-campus__marker" data-point-id="${point.id}">
      <span class="amap-campus__pin">
        <span class="${classNames.join(' ')}">${point.itemCount ?? 0}</span>
        ${badge}
      </span>
      ${label}
    </div>`
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => {
    switch (ch) {
      case '&':
        return '&amp;'
      case '<':
        return '&lt;'
      case '>':
        return '&gt;'
      case '"':
        return '&quot;'
      default:
        return '&#39;'
    }
  })
}

function clearMarkers(): void {
  if (map && markers.length) map.remove(markers)
  markers = []
  markerById.clear()
}

function renderMarkers(): void {
  if (!map || !AMapRef) return
  const t0 = performance.now()
  clearMarkers()
  props.points.forEach((point) => {
    const lngLat = pointLngLat(point)
    if (!lngLat) return
    const marker = new AMapRef!.Marker({
      position: lngLat,
      content: bubbleHtml(point),
      anchor: 'center',
      zIndex: props.selectedId === point.id ? 200 : 100,
      title: `${point.name}（${pointTypeLabel(point.type)}）· ${point.itemCount} 件在售`,
      // 标记内部是自定义 DOM，必须让 AMap 把点击事件透进来，否则 marker 收不到 click
      clickable: true,
    })
    marker.setExtData(point)
    // 关键：气泡点击 → emit('select')。这段曾经在重构里被删掉，导致"点气泡没反应"
    marker.on('click', () => {
      emit('select', point)
    })
    marker.setMap(map)
    markers.push(marker)
    markerById.set(point.id, marker)
  })
  const cost = performance.now() - t0
  const container = containerRef.value
  if (container) {
    // 点位规模的可观测指标：点位一多，这段耗时会线性上涨，页面就"卡"在这里
    container.dataset.markerCount = String(markers.length)
    container.dataset.markerMs = cost.toFixed(0)
  }
  // 同时写进标题，方便无头压测脚本从 Chrome 历史记录里读回来
  document.title = `MARKERS_DONE n=${markers.length} ms=${cost.toFixed(0)} total=${markers.length * 4}dom`
}

/**
 * 只刷新气泡内容与层级（选中/高亮变化时用）。
 * 以前这里是按数组下标取标记，点位里只要有坐标缺失就会错位；现在按 id 查。
 */
function refreshMarkerContent(): void {
  props.points.forEach((point) => {
    const marker = markerById.get(point.id)
    if (!marker) return
    marker.setContent(bubbleHtml(point))
  })
}

/* ------------------------------------------------------------------ *
 * 定位
 * ------------------------------------------------------------------ */

function drawMyLocation(lngLat: [number, number], accuracy: number | null): void {
  if (!map || !AMapRef) return
  myLngLat.value = lngLat
  if (!locationDot) {
    locationDot = new AMapRef.Marker({
      position: lngLat,
      anchor: 'center',
      zIndex: 300,
      content: '<div class="amap-campus__me"></div>',
    })
    locationDot.setMap(map)
  } else {
    locationDot.setPosition(lngLat)
  }
  if (accuracy && accuracy > 0) {
    if (!accuracyCircle) {
      accuracyCircle = new AMapRef.Circle({
        center: lngLat,
        radius: accuracy,
        strokeColor: '#409eff',
        strokeOpacity: 0.35,
        strokeWeight: 1,
        fillColor: '#409eff',
        fillOpacity: 0.12,
        zIndex: 90,
      })
      accuracyCircle.setMap(map)
    } else {
      accuracyCircle.setCenter(lngLat)
      accuracyCircle.setRadius(accuracy)
    }
  }
}

/** 优先用浏览器定位（不需要高德配额），失败再退到高德定位插件 */
function locate(): Promise<[number, number] | null> {
  if (locationBusy.value) return Promise.resolve(myLngLat.value)
  locationBusy.value = true
  const finish = (result: [number, number] | null) => {
    locationBusy.value = false
    return result
  }

  return new Promise<[number, number] | null>((resolve) => {
    const useAmapFallback = () => {
      if (!AMapRef) return resolve(finish(null))
      const geolocation = new AMapRef.Geolocation({ timeout: 8000, enableHighAccuracy: true })
      geolocation.getCurrentPosition((status: string, result: AMapGeolocationResult) => {
        if (status === 'complete' && result.position) {
          // 高德定位插件返回的已经是 GCJ-02，直接用
          const lngLat: [number, number] = [result.position.getLng(), result.position.getLat()]
          drawMyLocation(lngLat, result.accuracy ?? null)
          map?.setZoomAndCenter(Math.max(zoom.value, 16), lngLat)
          resolve(finish(lngLat))
        } else {
          errorMsg.value = result?.message || '定位失败，请检查浏览器定位权限'
          resolve(finish(null))
        }
      })
    }

    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      useAmapFallback()
      return
    }
    navigator.geolocation.getCurrentPosition(
      (position) => {
        /**
         * ⚠️ **必须转坐标系**：浏览器给的是 WGS-84，高德底图是 GCJ-02。
         * 桂林这一带两者相差约 **490 米**，不转的话蓝点会画到半公里外，
         * 地图上按它算的"距离"也全错（这是实测出来的，不是理论值）。
         * 转换不消耗配额（JS API 本地能力），失败退回本地公式。
         */
        const wgs: [number, number] = [position.coords.longitude, position.coords.latitude]
        gpsToGcj02(wgs[0], wgs[1])
          .then((gcj) => {
            drawMyLocation(gcj, position.coords.accuracy ?? null)
            map?.setZoomAndCenter(Math.max(zoom.value, 16), gcj)
            resolve(finish(gcj))
          })
          .catch(() => {
            drawMyLocation(wgs, position.coords.accuracy ?? null)
            map?.setZoomAndCenter(Math.max(zoom.value, 16), wgs)
            resolve(finish(wgs))
          })
      },
      () => useAmapFallback(),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 30000 },
    )
  })
}

/** 供外部（点位列表 / 发布页）把地图移到指定坐标 */
function setCenter(lngLat: [number, number], targetZoom?: number): void {
  if (!map) return
  map.setZoomAndCenter(targetZoom ?? Math.max(zoom.value, 16), lngLat)
}

/** 视野拉回全部点位：拖远了点一下就能回来（比页面级的"刷新"更符合直觉） */
function fitAll(): void {
  if (!map || !markers.length) return
  if (markers.length === 1) {
    map.setZoomAndCenter(zoom.value, center.value)
    return
  }
  map.setFitView(markers, false, [70, 70, 70, 70])
}

/**
 * 点位集合变化后重新框视野。
 * 单点时 setFitView 会顶到最大级别（周围一圈全糊），所以单点用配置的缩放置中。
 */
function refitForPoints(): void {
  if (!map || !markers.length) return
  fitAll()
}

/* ------------------------------------------------------------------ *
 * 地图初始化
 * ------------------------------------------------------------------ */

function renderBase(): void {
  if (!map) return
  renderMarkers()
}

function invalidateSize(): void {
  map?.resize()
}

function refresh(): void {
  if (!map) return
  map.setZoomAndCenter(zoom.value, center.value)
  renderBase()
}

function onMapClick(event: { lnglat: { getLng(): number; getLat(): number } }): void {
  if (!pickable.value) return
  emit('pick', {
    coordX: Number(event.lnglat.getLng().toFixed(6)),
    coordY: Number(event.lnglat.getLat().toFixed(6)),
  })
}

onMounted(async () => {
  await nextTick()
  const container = containerRef.value
  if (!container) return

  if (!hasAmapKey()) {
    errorMsg.value = '未配置高德 Key，已自动使用自绘平面图'
    return
  }

  loading.value = true
  try {
    const AMap = await loadAmap()
    AMapRef = AMap
    map = new AMap.Map(container, {
      zoom: zoom.value,
      center: center.value,
      resizeEnable: true,
      rotateEnable: false,
      pitchEnable: false,
      viewMode: '2D',
      mapStyle: props.mapConfig?.mapStyle || 'amap://styles/normal',
    })
    map.on('click', onMapClick)

    AMap.plugin('AMap.Scale', () => {
      const ScaleCtor = (AMap as unknown as Record<string, new (options?: unknown) => unknown>)['Scale']
      if (ScaleCtor && map) map.add(new ScaleCtor({ position: 'lb' }))
    })
    AMap.plugin('AMap.ToolBar', () => {
      const ToolBarCtor = (AMap as unknown as Record<string, new (options?: unknown) => unknown>)['ToolBar']
      if (ToolBarCtor && map) map.add(new ToolBarCtor({ position: { top: '96px', right: '14px' } }))
    })

    renderBase()
    // 视野：多个点位就自适应框住全部；只有一个点位（例如只看某件商品的位置）就用配置的
    // 缩放级别居中，否则 setFitView 会把单点放到最大级别，周围一圈全糊。
    if (markers.length > 1) {
      map.setFitView(markers, false, [70, 70, 70, 70])
    } else {
      map.setZoomAndCenter(zoom.value, center.value)
    }
    // 把当前视野中心写到 DOM 上，便于排查"地图到底定位到哪了"（无头截图/自动化测试也能读）
    const center0 = map.getCenter()
    container.dataset.mapCenter = `${center0.getLng().toFixed(6)},${center0.getLat().toFixed(6)}`
    container.dataset.mapZoom = String(map.getZoom())
    mapReady.value = true

    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => invalidateSize())
      resizeObserver.observe(container)
    }
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : '高德地图加载失败'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (map) {
    map.clearMap()
    map.destroy()
    map = null
  }
  markers = []
  locationDot = null
  accuracyCircle = null
  AMapRef = null
})

watch(
  () => [props.points, props.groupCounts],
  () => {
    renderBase()
    // 点位集合变了（例如从"只看这件商品"切回全部）要把视野跟着调整
    refitForPoints()
  },
  { deep: true },
)
watch(
  () => props.mapConfig,
  () => {
    if (!map) return
    map.setCenter(center.value)
    map.setZoom(zoom.value)
    renderBase()
  },
)
watch(
  () => props.selectedId,
  () => {
    refreshMarkerContent()
    const target = props.points.find((point) => point.id === props.selectedId)
    if (!target) return
    const lngLat = pointLngLat(target)
    if (lngLat) map?.setCenter(lngLat)
  },
)
watch(pickable, (value) => {
  containerRef.value?.classList.toggle('is-picking', value)
})
</script>

<template>
  <div class="amap-campus" :style="{ height }">
    <div ref="containerRef" class="amap-campus__canvas" :class="{ 'is-picking': pickable }"></div>

    <div v-if="loading" class="amap-campus__hint">正在加载高德地图…</div>
    <div v-else-if="errorMsg" class="amap-campus__hint amap-campus__hint--error">{{ errorMsg }}</div>
    <div v-else-if="pickable" class="amap-campus__hint">
      选点模式：点击地图上任意位置，即可取得该处经纬度
    </div>

    <button
      v-if="!errorMsg"
      class="amap-campus__locate"
      type="button"
      :disabled="locationBusy"
      @click="locate"
    >
      {{ locationBusy ? '定位中…' : '定位我的位置' }}
    </button>

    <button
      v-if="!errorMsg && showFitButton && points.length > 1"
      class="amap-campus__action"
      type="button"
      @click="fitAll"
    >
      看全部点位
    </button>

    <div v-if="showLegend" class="amap-campus__legend">
      <span class="amap-campus__legend-item"><i class="dot dot--has"></i>有在售商品</span>
      <span class="amap-campus__legend-item"><i class="dot dot--empty"></i>暂无商品</span>
      <span class="amap-campus__legend-item"><i class="dot dot--active"></i>已选中</span>
      <span class="amap-campus__legend-item"><i class="dot dot--me"></i>我的位置</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.amap-campus {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 10px;
  background: #fff;

  &__canvas {
    width: 100%;
    height: 100%;

    &.is-picking {
      cursor: crosshair;
    }
  }

  &__hint {
    position: absolute;
    top: 12px;
    left: 50%;
    z-index: 500;
    padding: 6px 14px;
    border-radius: 16px;
    background: rgb(64 158 255 / 92%);
    color: #fff;
    font-size: 12px;
    transform: translateX(-50%);
    pointer-events: none;

    &--error {
      background: rgb(245 108 108 / 94%);
    }
  }

  &__locate {
    position: absolute;
    top: 12px;
    right: 12px;
    z-index: 550;
    padding: 7px 14px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: rgb(255 255 255 / 96%);
    box-shadow: var(--shadow-sm);
    color: var(--text-1);
    cursor: pointer;
    font-family: inherit;
    font-size: 12.5px;
    transition: all 0.16s ease;

    &:hover:not(:disabled) {
      border-color: var(--brand-300);
      color: var(--brand-700);
    }

    &:disabled {
      cursor: default;
      opacity: 0.7;
    }
  }

  // 地图上的浮动操作：选点 / 看全部点位
  &__action {
    position: absolute;
    top: 52px;
    right: 12px;
    z-index: 550;
    padding: 7px 14px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: rgb(255 255 255 / 96%);
    box-shadow: var(--shadow-sm);
    color: var(--text-1);
    cursor: pointer;
    font-family: inherit;
    font-size: 12.5px;
    transition: all 0.16s ease;

    &:hover {
      border-color: var(--brand-300);
      color: var(--brand-700);
    }

    &.is-on {
      border-color: var(--brand-500);
      background: var(--brand-500);
      color: #fff;
    }
  }

  &__fit {
    top: 92px;
  }

  &__legend {
    position: absolute;
    right: 12px;
    bottom: 12px;
    z-index: 500;
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 7px 10px;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: rgb(255 255 255 / 92%);
    backdrop-filter: blur(6px);
    box-shadow: var(--shadow-sm);
    color: var(--text-3);
    font-size: 11.5px;
  }

  &__legend-item {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .dot {
    display: inline-block;
    width: 10px;
    height: 10px;
    border-radius: 50%;

    &--has {
      background: var(--brand-500);
    }

    &--empty {
      background: #cbd5e1;
    }

    &--active {
      background: var(--accent-500);
    }

    &--me {
      background: #409eff;
      box-shadow: 0 0 0 3px rgb(64 158 255 / 22%);
    }
  }

  // 高德把 content 插进自己的容器里，这里用 :deep 命中
  :deep(.amap-campus__marker) {
    position: relative;
    display: flex;
    align-items: center;
    flex-direction: column;
    cursor: pointer;
  }

  // 气泡的定位锚：角标（合并个数）相对它定位，这样才紧贴气泡右上角。
  // 不能用外面的 .amap-campus__marker 当锚 —— 它是 flex-column 且含名称标签，
  // 宽度会被标签撑大（角标于是飘到标签右上角）。
  :deep(.amap-campus__pin) {
    position: relative;
    display: inline-flex;
  }

  // 合并出来的气泡：右上角一个"N"角标，提示"这里其实有 N 个点位"
  :deep(.amap-campus__merged) {
    position: absolute;
    top: -5px;
    right: -5px;
    z-index: 2;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    box-sizing: border-box;
    border: 1.5px solid #fff;
    border-radius: 999px;
    background: #7c3aed;
    color: #fff;
    font-size: 10.5px;
    font-style: normal;
    font-weight: 700;
    line-height: 13px;
    text-align: center;
  }

  :deep(.amap-campus__bubble) {
    display: flex;
    align-items: center;
    justify-content: center;
    box-sizing: border-box;
    width: 34px;
    height: 34px;
    border: 2px solid #fff;
    border-radius: 50%;
    background: var(--brand-500);
    box-shadow: 0 2px 6px rgb(15 23 42 / 24%);
    color: #fff;
    font-size: 13px;
    font-weight: 600;
    line-height: 1;
    transition: transform 0.15s ease;

    &.is-empty {
      background: #94a3b8;
    }

    &.is-active {
      background: var(--accent-500);
      transform: scale(1.12);
    }

    // 商品定位：这件东西就在这个点位，用红色 + 呼吸圈抓注意力
    &.is-highlight {
      background: #ef4444;
      transform: scale(1.18);
      box-shadow: 0 0 0 6px rgb(239 68 68 / 22%);
      animation: amap-highlight-pulse 1.6s ease-in-out infinite;
    }
  }

  @keyframes amap-highlight-pulse {
    0%,
    100% {
      box-shadow: 0 0 0 5px rgb(239 68 68 / 22%);
    }

    50% {
      box-shadow: 0 0 0 10px rgb(239 68 68 / 10%);
    }
  }

  :deep(.amap-campus__label) {
    max-width: 108px;
    margin-top: 2px;
    padding: 1px 6px;
    border-radius: 999px;
    background: rgb(255 255 255 / 88%);
    color: #4b5563;
    font-size: 11.5px;
    line-height: 16px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  // 合并气泡用紫色描边区分单个点位
  :deep(.amap-campus__bubble.is-merged) {
    border-color: #ddd6fe;
  }

  :deep(.amap-campus__me) {
    box-sizing: border-box;
    width: 16px;
    height: 16px;
    border: 3px solid #fff;
    border-radius: 50%;
    background: #409eff;
    box-shadow: 0 0 0 3px rgb(64 158 255 / 30%);
  }
}
</style>
