<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { pointTypeLabel } from '@/api/map'
import type { MapConfig, MapPoint } from '@/api/map'

/** 选点模式返回的像素坐标：coordX 为 Leaflet 的 lng，coordY 为 lat */
export interface PickCoord {
  coordX: number
  coordY: number
}

const props = withDefaults(
  defineProps<{
    points?: MapPoint[]
    mapConfig?: MapConfig | null
    height?: string
    selectedId?: number | null
    showLegend?: boolean
    showLabel?: boolean
  }>(),
  {
    points: () => [],
    mapConfig: null,
    height: '600px',
    selectedId: null,
    showLegend: true,
    showLabel: true,
  },
)

const emit = defineEmits<{
  (e: 'select', point: MapPoint): void
  (e: 'pick', coord: PickCoord): void
}>()

/** 可选点模式：支持 v-model:pickable="flag" 或 :pickable="flag" 两种写法 */
const pickable = defineModel<boolean>('pickable', { default: false })

/** 无底图时的固定像素画布：[[0,0],[800,600]] */
const GRID_BOUNDS: L.LatLngTuple[] = [
  [0, 0],
  [800, 600],
]

const containerRef = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null
let markerLayer: L.LayerGroup | null = null
let baseLayer: L.ImageOverlay | L.Rectangle | null = null
let resizeObserver: ResizeObserver | null = null

function hasImageBase(): boolean {
  const config = props.mapConfig
  return Boolean(config && config.imgUrl && config.width && config.height)
}

/** 有底图：[[0,0],[height,width]]；无底图：固定 [[0,0],[800,600]] */
function currentBounds(): L.LatLngTuple[] {
  const config = props.mapConfig
  if (config && hasImageBase()) {
    return [
      [0, 0],
      [config.height as number, config.width as number],
    ]
  }
  return GRID_BOUNDS
}

function renderBaseLayer(): void {
  if (!map) return
  if (baseLayer) {
    map.removeLayer(baseLayer)
    baseLayer = null
  }
  const container = containerRef.value
  if (hasImageBase() && props.mapConfig?.imgUrl) {
    container?.classList.remove('campus-map__canvas--grid')
    baseLayer = L.imageOverlay(props.mapConfig.imgUrl, currentBounds(), { interactive: false }).addTo(map)
  } else {
    // 无底图：CSS 网格背景（见样式中的 repeating-linear-gradient），这里只描一个边框
    container?.classList.add('campus-map__canvas--grid')
    baseLayer = L.rectangle(currentBounds(), {
      color: '#6ee7b7',
      weight: 2,
      dashArray: '6 6',
      fill: false,
      interactive: false,
    }).addTo(map)
  }
  map.fitBounds(currentBounds(), { padding: [16, 16] })
}

function bubbleHtml(point: MapPoint): string {
  const classNames = ['campus-map__bubble']
  if (!point.itemCount) classNames.push('is-empty')
  if (props.selectedId === point.id) classNames.push('is-active')
  return `<div class="${classNames.join(' ')}">${point.itemCount}</div>`
}

function renderMarkers(): void {
  if (!map) return
  if (!markerLayer) markerLayer = L.layerGroup().addTo(map)
  markerLayer.clearLayers()
  props.points.forEach((point) => {
    const marker = L.marker([point.coordY, point.coordX], {
      icon: L.divIcon({
        className: 'campus-map__marker',
        html: bubbleHtml(point),
        iconSize: [34, 34],
        iconAnchor: [17, 17],
      }),
      title: point.name,
      keyboard: false,
      riseOnHover: true,
    })
    marker.bindTooltip(`${point.name}（${pointTypeLabel(point.type)}）· ${point.itemCount} 件在售`, {
      direction: 'top',
      offset: [0, -30],
      opacity: 0.95,
    })
    if (props.showLabel) {
      marker.bindTooltip(point.name, {
        permanent: true,
        direction: 'bottom',
        className: 'campus-map__label',
        offset: [0, 4],
      })
    }
    marker.on('click', () => emit('select', point))
    marker.addTo(markerLayer as L.LayerGroup)
  })
}

function panToSelected(): void {
  if (!map || props.selectedId === null) return
  const target = props.points.find((point) => point.id === props.selectedId)
  if (target) map.panTo([target.coordY, target.coordX])
}

function invalidateSize(): void {
  map?.invalidateSize()
}

function refresh(): void {
  renderBaseLayer()
  renderMarkers()
}

onMounted(async () => {
  await nextTick()
  const container = containerRef.value
  if (!container) return
  map = L.map(container, {
    crs: L.CRS.Simple,
    minZoom: -4,
    maxZoom: 2,
    zoomSnap: 0.25,
    zoomDelta: 0.5,
    attributionControl: false,
    zoomControl: true,
    maxBoundsViscosity: 0.6,
  })
  map.on('click', (event: L.LeafletMouseEvent) => {
    if (!pickable.value) return
    // 注意：CRS.Simple 下 latlng.lat 是 Y，latlng.lng 是 X
    emit('pick', { coordX: Math.round(event.latlng.lng), coordY: Math.round(event.latlng.lat) })
  })
  renderBaseLayer()
  renderMarkers()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => invalidateSize())
    resizeObserver.observe(container)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (map) {
    map.off()
    map.remove()
    map = null
  }
  markerLayer = null
  baseLayer = null
})

watch(
  () => props.points,
  () => renderMarkers(),
)
watch(
  () => props.mapConfig,
  () => refresh(),
)
watch(
  () => props.selectedId,
  () => {
    renderMarkers()
    panToSelected()
  },
)

defineExpose({ invalidateSize, refresh })
</script>

<template>
  <div class="campus-map" :style="{ height }">
    <div ref="containerRef" class="campus-map__canvas" :class="{ 'is-picking': pickable }"></div>

    <div v-if="pickable" class="campus-map__hint">选点模式：点击地图空白处即可取得该处坐标</div>

    <div v-if="showLegend" class="campus-map__legend">
      <span class="campus-map__legend-item"><i class="dot dot--has"></i>有在售商品</span>
      <span class="campus-map__legend-item"><i class="dot dot--empty"></i>暂无商品</span>
      <span class="campus-map__legend-item"><i class="dot dot--active"></i>已选中</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.campus-map {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 10px;
  background: #fff;

  &__canvas {
    width: 100%;
    height: 100%;
    background-color: #f7f9fc;

    &.is-picking {
      cursor: crosshair;
    }

    // 无底图时用 CSS 网格充当校园平面图
    &.campus-map__canvas--grid {
      background-color: #f6faf8;
      background-image:
        repeating-linear-gradient(0deg, rgb(18 185 129 / 16%) 0 1px, transparent 1px 40px),
        repeating-linear-gradient(90deg, rgb(18 185 129 / 16%) 0 1px, transparent 1px 40px),
        repeating-linear-gradient(0deg, rgb(18 185 129 / 9%) 0 1px, transparent 1px 200px),
        repeating-linear-gradient(90deg, rgb(18 185 129 / 9%) 0 1px, transparent 1px 200px);
      background-size: 40px 40px, 40px 40px, 200px 200px, 200px 200px;

      &::after {
        content: '示意平面图 · 尚未配置校区底图';
        position: absolute;
        bottom: 12px;
        left: 12px;
        z-index: 400;
        padding: 4px 10px;
        border-radius: 999px;
        background: rgb(255 255 255 / 88%);
        color: var(--text-3);
        font-size: 11.5px;
        pointer-events: none;
      }
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
  }

  &__legend {
    position: absolute;
    right: 12px;
    bottom: 12px;
    z-index: 500;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 9px 12px;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: rgb(255 255 255 / 94%);
    backdrop-filter: blur(6px);
    box-shadow: var(--shadow-sm);
    color: var(--text-3);
    font-size: 12px;
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
  }

  // Leaflet 运行时插入的 DOM 需要用 :deep 才能命中
  :deep(.leaflet-container) {
    // 只能用 background-color；用 background 简写会把网格背景一起重置掉
    background-color: transparent;
    font-family: inherit;
    outline: none;
  }

  :deep(.campus-map__bubble) {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    border: 2px solid #fff;
    border-radius: 50%;
    background: var(--brand-500);
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
      transform: scale(1.1);
    }
  }

  :deep(.campus-map__label) {
    padding: 1px 4px;
    border: none;
    background: rgb(255 255 255 / 85%);
    box-shadow: none;
    color: #606266;
    font-size: 12px;

    &::before {
      display: none;
    }
  }

  :deep(.leaflet-tooltip-top) {
    font-size: 12px;
  }
}
</style>
