<script setup lang="ts">
/**
 * 地图选点对话框：在高德地图上点一下，让高德说出"这里可能叫什么"，
 * 列出候选地名给用户挑，选中的地点直接作为面交点使用。
 *
 * 这是"上架商品能选真实地址"的入口。选中的点会通过 POST /trade-points 立即创建
 * （免审核、不做同名合并），提交者本人立刻可以选中使用，不会打断发布流程。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AmapCampusMap from '@/components/AmapCampusMap.vue'
import type { PickCoord } from '@/components/AmapCampusMap.vue'
import { createMyTradePoint, fetchMapPoints, fetchNearbyPoints, pointTypeLabel, saveSearchHitPoint } from '@/api/map'
import type { MapConfig, MapPoint, NearbyPoint, TradePoint } from '@/api/map'
import { useTradePoints } from '@/composables/useTradePoints'
import {
  browserRawGeolocation,
  gpsToGcj02,
  hasAmapKey,
  reverseGeocode,
  reverseGeocodeCandidates,
  searchPlaces,
} from '@/utils/amap'
import type { GeoCandidate, PlaceHit } from '@/utils/amap'
import { readPlaceCache, writePlaceCache } from '@/utils/placeCache'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'

/** 地点名称长度上限：与输入框 maxlength、后端校验保持一致 */
const NAME_MAX_LENGTH = 30

const visible = defineModel<boolean>({ default: false })

const props = withDefaults(
  defineProps<{
    /** 不传则按当前登录用户的校区自动解析（聊天页改地址就没有会话校区可用） */
    campusId?: number
    mapConfig?: MapConfig | null
  }>(),
  { campusId: undefined, mapConfig: null },
)

const emit = defineEmits<{
  (e: 'created', point: TradePoint): void
}>()

const { campusId: userCampusId, points, add } = useTradePoints()
/** 实际使用的校区：优先外部传入，否则取用户资料里的校区 */
const effectiveCampusId = computed(() => props.campusId ?? userCampusId.value ?? DEFAULT_CAMPUS_ID)
/** 兜底底图配置：外部没传就自己拉一次，保证任何页面挂上就能用 */
const loadedMapConfig = ref<MapConfig | null>(null)
const effectiveMapConfig = computed(() => props.mapConfig ?? loadedMapConfig.value)

/** 点位类型选项：与后端 trade_point.point_type 字典一致 */
const TYPE_OPTIONS = [1, 2, 3, 4, 5, 6, 7, 9].map((value) => ({
  value,
  label: pointTypeLabel(value),
}))

const pickable = ref(true)
const picked = ref<PickCoord | null>(null)
const pickedName = ref('')
const guideText = ref('')
const pointType = ref(9)
const submitting = ref(false)

const amapReady = hasAmapKey()

/** 兼容自绘底图校区的像素坐标：只有经纬度才谈得上"真实地址" */
const isGeo = (coord: PickCoord | null): boolean =>
  Boolean(coord && Math.abs(coord.coordX) > 1 && Math.abs(coord.coordY) > 1)

/** 高德给出的候选地名（按距离从近到远） */
const candidates = ref<GeoCandidate[]>([])
const candidatesLoading = ref(false)
/** 用户是否手动改过名称：改过之后就不再自动覆盖 */
const nameTouched = ref(false)
/** 按名字在高德上搜到的地点（可点一下把坐标挪过去） */
const placeHits = ref<PlaceHit[]>([])
const searching = ref(false)
/** 这批结果是不是从本地缓存/点位库直接拿到的（提示用户"没花配额"） */
const hitsFromCache = ref(false)
/** 高德附近没有这个名字时的提示（自由填写也完全允许） */
const customNameHint = ref('')
/** 地图组件引用：换坐标/换候选时要把地图视野跟过去 */
const mapRef = ref<{ setCenter(lngLat: [number, number], zoom?: number): void } | null>(null)

/**
 * 我附近的点位（服务端 **Redis GEO** 检索，0 次高德调用）。
 *
 * 这是"选点"最自然的入口：学生只关心宿舍/教学楼附近。
 * 之所以不用高德的周边搜索（5000 次/月），就是因为这个动作太频繁了。
 */
const nearby = ref<NearbyPoint[]>([])
const nearbyLoading = ref(false)
const nearbyTried = ref(false)

/**
 * 逆地理编码结果的坐标级缓存（只活在本次弹窗打开期间）。
 *
 * 为什么需要：`reverseGeocodeCandidates()` 是**唯一**会因为"点地图"而消耗高德额度的调用。
 * 用户常常在同一个位置反复点（放大看看、缩小看看、来回比较），缓存之后同一个坐标只调一次。
 * key 保留 5 位小数（≈1 米），足够精确又容易命中。
 */
const reverseCache = new Map<string, GeoCandidate[]>()

const pickedPoints = ref<MapPoint[]>([])

/**
 * "正在选取的新点位"用的固定 id。
 *
 * 它是个**尚未保存**的点，所以给个不可能是真实点位（真实 id 都是正数）的负数，
 * 用来告诉地图组件"这个点要画成选点针，不要画成数字气泡"——
 * 新点位的 itemCount 恒为 0，画成气泡就是一个难看的「0」。
 */
const PICKED_POINT_ID = -1

function refreshPickedMarker(): void {
  if (!picked.value) {
    pickedPoints.value = []
    return
  }
  pickedPoints.value = [
    {
      id: PICKED_POINT_ID,
      name: pickedName.value || '新点位',
      type: pointType.value,
      coordX: null,
      coordY: null,
      lng: picked.value.coordX,
      lat: picked.value.coordY,
      safetyScore: 0,
      safeTags: [],
      itemCount: 0,
      minPrice: 0,
      guideText: '',
    },
  ]
}

watch(picked, refreshPickedMarker)
watch(pickedName, refreshPickedMarker)

/** 距点击位置多少米（候选列表上展示，让用户判断哪个才是他站的地方） */
function distanceText(candidate: GeoCandidate): string {
  if (candidate.distance === null) return '门牌地址'
  return candidate.distance < 1000
    ? `${Math.round(candidate.distance)} 米`
    : `${(candidate.distance / 1000).toFixed(1)} 公里`
}

/**
 * 点某个候选：把点位挪到高德认定的那个位置，并让地图跟过去。
 *
 * 之前这里只改名称不改坐标，结果"点了候选但地图上的图标纹丝不动"——
 * 因为坐标仍是用户随手点的地方，看着像没生效。现在优先用 POI 自带坐标。
 */
function chooseCandidate(candidate: GeoCandidate): void {
  pickedName.value = candidate.name
  nameTouched.value = true
  // 只有 POI 才有自己的坐标；兜底的"门牌地址"没有，保持用户点击的位置
  if (candidate.lng !== null && candidate.lat !== null) {
    picked.value = { coordX: candidate.lng, coordY: candidate.lat }
  }
  refreshPickedMarker()
  panToPicked(17)
  if (candidate.lng !== null && candidate.lat !== null) {
    ElMessage.success(`已切到高德上的「${candidate.name}」，坐标也挪过去了`)
  }
}

/** 把地图视野移到坐标上：点候选/搜到地点后要跟过去，否则用户看不到自己选了哪 */
function panToPicked(zoom = 17): void {
  const coord = picked.value
  if (!coord || !isGeo(coord)) return
  mapRef.value?.setCenter([coord.coordX, coord.coordY], zoom)
}

/** 距离文案 */
function nearDistance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters)} 米` : `${(meters / 1000).toFixed(1)} 公里`
}

/**
 * 拉"我附近的点位"（服务端 **Redis GEO** 检索，0 次高德调用）。
 *
 * 坐标用浏览器原生定位（也是 0 配额），并转成 GCJ-02——
 * 浏览器给的是 WGS-84，不转换在国内会偏几百米，附近的点位就选错楼了。
 * 转换优先走高德的 `AMap.convertFrom`（权威），失败退回本地公式。
 */
async function loadNearby(silent = false): Promise<void> {
  if (nearbyLoading.value) return
  nearbyLoading.value = true
  if (!silent) nearbyTried.value = true
  try {
    const wgs = await browserRawGeolocation()
    if (!wgs) {
      nearbyTried.value = true
      if (!silent) ElMessage.info('浏览器没给定位权限，可以在地图上点一下位置，或直接填写名称')
      return
    }
    const coord = await gpsToGcj02(wgs[0], wgs[1])
    const list = await fetchNearbyPoints({
      campusId: effectiveCampusId.value,
      lng: coord[0],
      lat: coord[1],
      radius: 800,
      limit: 12,
    })
    nearby.value = list
    nearbyTried.value = true
    if (!list.length && !silent) {
      ElMessage.info('你附近 800 米内还没有面交点，可以在地图上点一个位置新建')
    }
  } catch {
    nearbyTried.value = true
  } finally {
    nearbyLoading.value = false
  }
}

/** 选中"附近点位"里的一个：坐标、名称、类型全部就位，不用再搜 */
function chooseNearby(point: NearbyPoint): void {
  if (point.lng === null || point.lat === null) {
    ElMessage.warning('这个点位没有坐标，请在地图上点选位置')
    return
  }
  picked.value = { coordX: point.lng, coordY: point.lat }
  pickedName.value = point.name
  pointType.value = point.pointType ?? 9
  if (point.guideText) guideText.value = point.guideText
  nameTouched.value = true
  placeHits.value = []
  candidates.value = []
  customNameHint.value = ''
  refreshPickedMarker()
  panToPicked(18)
  ElMessage.success(`已选「${point.name}」（距你 ${nearDistance(point.distanceMeters)}，来自点位库，没花高德配额）`)
}

async function onPick(coord: PickCoord): Promise<void> {
  picked.value = coord
  candidates.value = []
  placeHits.value = []
  customNameHint.value = ''
  if (!isGeo(coord)) {
    ElMessage.info('当前校区用的是自绘平面图，建议切到高德地图校区后再选真实地址')
    return
  }
  candidatesLoading.value = true
  try {
    // 让高德列出这一圈里所有地名，而不是只取最近的一个（最近的可能是一辆共享单车）
    //
    // 注意：这一步是**唯一**会因为"点地图"而消耗高德额度的调用（逆地理编码，基础 LBS）。
    // 所以做了坐标级缓存：同一个位置反复点（放大缩小、来回比较）只算一次。
    const cacheKey = `${coord.coordX.toFixed(5)},${coord.coordY.toFixed(5)}`
    const cached = reverseCache.get(cacheKey)
    const list = cached ?? (await reverseGeocodeCandidates(coord.coordX, coord.coordY))
    if (!cached) {
      reverseCache.set(cacheKey, list)
    }
    candidates.value = list
    if (!list.length) {
      ElMessage.info('高德没识别出附近地点，直接自己填一个名字也行')
      return
    }
    // 自动选第一个（最近的）填进名称，但用户可随时改选其它候选；手填过就不再覆盖
    if (!nameTouched.value || !pickedName.value.trim()) {
      pickedName.value = list[0].name
      nameTouched.value = false
    }
    ElMessage.success(`高德识别到 ${list.length} 个候选地点，可点选切换；也可以直接自己填名字`)
  } finally {
    candidatesLoading.value = false
  }
}

/**
 * 点"跳到这里"：把坐标换成高德搜到的那个位置（比随手一点更准），**并让地图跟过去**。
 *
 * 同时**把它落库**（source=3）：下次再搜同一个名字就直接命中数据库，不再消耗搜索配额。
 * 已有同名点位时后端会复用那条，不会重复插入。
 */
async function useHitLocation(hit: PlaceHit): Promise<void> {
  picked.value = { coordX: hit.lng, coordY: hit.lat }
  pickedName.value = hit.name
  nameTouched.value = true
  placeHits.value = []
  customNameHint.value = ''
  refreshPickedMarker()
  panToPicked()
  // 已经来自库里（hitsFromCache 且带 id）就不用再存一遍
  if (!hitsFromCache.value) {
    await cacheHitToDb(hit)
  }
  ElMessage.success(`坐标已挪到「${hit.name}」`)
}

/**
 * 把搜索结果落库，让"先数据库、后高德"这条链真的走通。
 *
 * 失败不影响用户操作（点位库是优化手段，不是必需品），所以只提示不抛错。
 */
async function cacheHitToDb(hit: PlaceHit): Promise<void> {
  try {
    const point = await saveSearchHitPoint({
      campusId: effectiveCampusId.value,
      name: hit.name,
      lng: hit.lng,
      lat: hit.lat,
      guideText: hit.address || undefined,
      pointType: pointType.value,
    })
    // 落库后同步进共享列表，其它页面（地图/发布/私聊）立刻能选到
    add(point)
    hitsFromCache.value = true
  } catch {
    // 响应拦截器已提示；落库失败只是下次还得搜一遍
  }
}

/**
 * 按名字核对/搜索。**三级优先，只有最后一级才消耗高德配额**：
 *
 *  ① 点位库里已经有的（别的同学建过、或自己建过）—— 0 次调用；
 *  ② 本地搜索缓存（上次搜过的名字，存 localStorage，30 天有效）—— 0 次调用；
 *  ③ 才轮到高德「地点搜索」（个人认证只有 5000 次/月，所以只在用户主动点击时执行）。
 *
 * 第 ③ 步的结果**必须写回缓存**：同一个名字下次就是本地命中，不会再花配额。
 * 三级都找不到也不拦着——名字是自由文本，用户想叫"相思湖""清苑11栋"完全可以。
 */
async function lookupByName(): Promise<void> {
  const keyword = pickedName.value.trim()
  if (!picked.value || !isGeo(picked.value)) {
    ElMessage.warning('先在地图上点一个位置')
    return
  }
  if (!keyword) {
    ElMessage.warning('先填一个名字，或直接在地图上点选位置')
    return
  }
  customNameHint.value = ''
  placeHits.value = []
  hitsFromCache.value = false
  const campusId = effectiveCampusId.value

  const toHit = (point: TradePoint): PlaceHit => ({
    name: point.name,
    address: point.guideText || '平台里已有的面交点',
    lng: point.lng as number,
    lat: point.lat as number,
    distance: null,
  })

  // ① 数据库（点位库）：最高优先。别的同学建过、自己建过、或之前搜过落库的都在这里，
  //    命中就 0 次高德调用——这就是"先数据库、再高德"的落点。
  const inDb = points.value.filter(
    (point) => point.name.includes(keyword) && point.lng && point.lat,
  )
  if (inDb.length) {
    placeHits.value = inDb.map(toHit)
    hitsFromCache.value = true
    ElMessage.success(`点位库里已经有「${keyword}」，直接用就好（没花高德配额）`)
    return
  }

  // ② 本地缓存（localStorage）：点过"跳到这里"就会写一份，跨会话有效
  const cached = readPlaceCache(campusId, keyword)
  if (cached?.length) {
    // 本地缓存里有的、库里却没有（例如换了浏览器、或上次落库失败）：这次补写进数据库，
    // 让下一级判断重新回到"数据库优先"
    for (const hit of cached) {
      await cacheHitToDb(hit)
    }
    placeHits.value = cached
    hitsFromCache.value = true
    ElMessage.success(`「${keyword}」是搜过的，已从本地记录取出（没花高德配额）`)
    return
  }

  // ③ 本次选点已识别的候选里对上（0 配额）
  const local = candidates.value.filter((item) => item.name.includes(keyword))
  if (local.length) {
    candidates.value = local
    ElMessage.success(`高德附近匹配到 ${local.length} 个叫「${keyword}」的地点`)
    return
  }

  // ④ 才轮到高德搜索（花配额）：结果写本地缓存 + **点选后落库**
  searching.value = true
  try {
    const hits = await searchPlaces(keyword, {
      near: [picked.value.coordX, picked.value.coordY],
      radius: 3000,
    })
    placeHits.value = hits
    if (hits.length) {
      writePlaceCache(campusId, keyword, hits)
      ElMessage.success(
        `高德搜到 ${hits.length} 个「${keyword}」；选中后会把坐标存进点位库，以后不再消耗配额`,
      )
      return
    }
    // 搜不到：尊重自由填写，只提示一句，不拦提交
    const nearest = await reverseGeocode(picked.value.coordX, picked.value.coordY)
    customNameHint.value = nearest?.name
      ? `高德上没有叫「${keyword}」的地点，这里最近的是「${nearest.name}」。名字仍按你填的保存。`
      : `高德上没有叫「${keyword}」的地点。名字仍按你填的保存。`
    ElMessage.info('高德没搜到这个名称，直接用它也可以')
  } finally {
    searching.value = false
  }
}

async function submit(): Promise<void> {
  const name = pickedName.value.trim()
  if (!picked.value) {
    ElMessage.warning('请先在地图上点击选择位置')
    return
  }
  if (name.length < 2) {
    ElMessage.warning('地点名称至少 2 个字')
    return
  }
  if (name.length > NAME_MAX_LENGTH) {
    ElMessage.warning(`地点名称最多 ${NAME_MAX_LENGTH} 个字，当前 ${name.length} 个`)
    return
  }
  submitting.value = true
  try {
    const point = await createMyTradePoint({
      campusId: effectiveCampusId.value,
      name,
      lng: picked.value.coordX,
      lat: picked.value.coordY,
      guideText: guideText.value.trim(),
      pointType: pointType.value,
    })
    // 同名点位后端**不会合并**，会各自独立存在。地图上出现两个同名标记会让人困惑，
    // 所以这里明确说一句，避免用户以为"被合并了"。
    const duplicated = points.value.some((item) => item.id !== point.id && item.name === point.name)
    if (duplicated) {
      ElMessage.info(`已新增「${point.name}」（与其他点位同名，但两条独立存在）`)
    } else {
      ElMessage.success('地点已添加，可直接使用')
    }
    // add() 会把点位插进共享列表并把版本号 +1，其它页面（地图、发布、私聊）据此自动刷新
    add(point)
    emit('created', point)
    visible.value = false
  } catch {
    // 响应拦截器已提示
  } finally {
    submitting.value = false
  }
}

function reset(): void {
  picked.value = null
  pickedName.value = ''
  guideText.value = ''
  pointType.value = 9
  pickedPoints.value = []
  candidates.value = []
  placeHits.value = []
  customNameHint.value = ''
  nameTouched.value = false
  nearby.value = []
  nearbyTried.value = false
}

function onClosed(): void {
  reset()
}

// 打开时若外部没给底图配置，自己拉一次（按解析出来的校区）
watch(visible, async (open) => {
  if (!open) return
  // 顺带静默拉一次"附近点位"（Redis GEO，0 配额）：有结果用户可以直接点
  void loadNearby(true)
  if (props.mapConfig) return
  try {
    const data = await fetchMapPoints(effectiveCampusId.value)
    loadedMapConfig.value = data.mapConfig ?? null
  } catch {
    loadedMapConfig.value = null
  }
})

/**
 * 自动化测试/截图用的预置选点：URL 上带 `?pick=lng,lat` 时自动走一遍"用户点了地图"。
 * 只做一件事——把坐标交给 onPick，和真实点击完全同一条代码路径。
 * 没有这个参数时行为不变（正常使用完全不受影响）。
 */
async function applyPresetPick(): Promise<void> {
  if (typeof window === 'undefined') return
  const raw = new URLSearchParams(window.location.search).get('pick')
  if (!raw) return
  const [lng, lat] = raw.split(',').map((n) => Number(n.trim()))
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
  await nextTick()
  await onPick({ coordX: lng, coordY: lat })
}

onMounted(applyPresetPick)
</script>

<template>
  <el-dialog
    v-model="visible"
    title="在地图上选一个真实面交点"
    width="1060px"
    top="6vh"
    destroy-on-close
    class="picker-dialog"
    @closed="onClosed"
  >
    <el-alert
      v-if="!amapReady"
      type="warning"
      :closable="false"
      show-icon
      title="未配置高德 Key"
      description="当前环境没有高德地图，无法选真实地址。请在 .env 配置 VITE_AMAP_JS_KEY 后重启前端。"
      style="margin-bottom: 12px"
    />

    <div class="picker">
      <div class="picker__map">
        <AmapCampusMap
          ref="mapRef"
          v-model:pickable="pickable"
          :points="pickedPoints"
          :map-config="effectiveMapConfig"
          :show-legend="false"
          :pick-pin-id="PICKED_POINT_ID"
          height="500px"
          @pick="onPick"
        />
      </div>

      <div class="picker__panel">
        <!-- 我附近的点位：Redis GEO 检索，0 次高德调用。选点最自然的入口 -->
        <div v-if="nearby.length || nearbyLoading || nearbyTried" class="picker__nearby">
          <div class="picker__nearby-head">
            <span class="picker__label">我附近的点位</span>
            <el-button link size="small" :loading="nearbyLoading" @click="loadNearby(false)">
              {{ nearby.length ? '重新定位' : '定位一下' }}
            </el-button>
          </div>
          <p class="picker__hint">
            按你的位置在<strong>点位库</strong>里找 800 米内的（走 Redis，不消耗高德配额）
          </p>
          <div v-if="nearby.length" class="cand cand--nearby">
            <button
              v-for="point in nearby"
              :key="point.id"
              class="cand__item"
              :class="{ 'is-on': pickedName.trim() === point.name }"
              type="button"
              @click="chooseNearby(point)"
            >
              <span class="cand__name">
                {{ point.name }}
                <em class="cand__badge">{{ pointTypeLabel(point.pointType) }}</em>
              </span>
              <span class="cand__meta">
                距你 {{ nearDistance(point.distanceMeters) }}
                <template v-if="point.guideText"> · {{ point.guideText }}</template>
              </span>
            </button>
          </div>
          <p v-else-if="nearbyTried && !nearbyLoading" class="picker__hint">
            附近没有已建好的点位，直接在地图上点一个位置新建即可
          </p>
        </div>

        <div class="picker__block">
          <p class="picker__label">已选坐标</p>
          <p class="picker__coord" :class="{ 'is-empty': !picked }">
            <template v-if="picked">
              <template v-if="isGeo(picked)">
                {{ picked.coordX }}, {{ picked.coordY }}<span class="picker__tag">GCJ-02</span>
              </template>
              <template v-else>X={{ picked.coordX }}, Y={{ picked.coordY }}（像素坐标，非真实地址）</template>
            </template>
            <span v-else>① 先在左侧地图上点一下你要面交的位置</span>
          </p>
        </div>

        <el-form label-position="top" class="picker__form">
          <el-form-item label="地点名称" required>
            <!-- 输入框独占一行：以前和"搜一下"挤在一行，200px 宽打字很难受 -->
            <el-input
              v-model="pickedName"
              :placeholder="candidatesLoading ? '高德正在识别地点名…' : '自己填也行，例如：相思湖 / 清苑11栋'"
              :maxlength="NAME_MAX_LENGTH"
              show-word-limit
              @input="nameTouched = true"
            />
            <div class="picker__name-actions">
              <el-button
                size="small"
                :loading="searching"
                :disabled="!picked || !isGeo(picked)"
                title="先在高德附近找同名的，找不到再按名字搜高德（占搜索配额）"
                @click="lookupByName"
              >
                在高德上核对这个名字
              </el-button>
              <span class="picker__hint picker__hint--inline">名称可自由填写</span>
            </div>
          </el-form-item>

          <!-- 候选/搜索结果统一放进固定高度的滚动区，避免候选多时把下面的表单顶下去 -->
          <div v-if="placeHits.length || candidates.length || customNameHint" class="picker__results">
            <!-- 名称在高德上搜到多个：点一个把坐标挪过去（比随手一点更准） -->
            <el-form-item v-if="placeHits.length" label="高德搜到的地点（点一下把坐标挪过去）">
              <span v-if="hitsFromCache" class="cand__cache-tip">
                来自点位库 / 本地记录，没消耗高德配额
              </span>
              <div class="cand">
                <button
                  v-for="hit in placeHits"
                  :key="hit.name + hit.lng"
                  class="cand__item"
                  type="button"
                  @click="useHitLocation(hit)"
                >
                  <span class="cand__name">{{ hit.name }}<em class="cand__badge">跳到这里</em></span>
                  <span class="cand__meta">
                    <template v-if="hit.distance !== null">{{ Math.round(hit.distance) }} 米 · </template>
                    {{ hit.address || '（无地址）' }}
                  </span>
                </button>
              </div>
            </el-form-item>

            <!-- 高德附近没有这个名字：提示一句，但名字照样按用户填的保存 -->
            <el-alert
              v-if="customNameHint"
              class="picker__custom"
              type="info"
              :closable="false"
              show-icon
            title="高德上没搜到这个名称"
            :description="customNameHint"
          />

          <!-- 高德给出的候选地名：点一个就把点位挪到高德认定的位置（只取最近的一个经常不是用户站的地方） -->
          <el-form-item v-if="candidates.length" label="高德识别到的地点（点选切换，坐标会一起挪过去）">
            <div v-loading="candidatesLoading" class="cand">
              <button
                v-for="candidate in candidates"
                :key="candidate.name"
                class="cand__item"
                :class="{ 'is-on': candidate.name === pickedName.trim() }"
                type="button"
                @click="chooseCandidate(candidate)"
              >
                <span class="cand__name">
                  {{ candidate.name }}
                  <em v-if="!candidate.isPoi" class="cand__badge">门牌地址</em>
                </span>
                <span class="cand__meta">
                  {{ distanceText(candidate) }}
                  <template v-if="candidate.address"> · {{ candidate.address }}</template>
                </span>
              </button>
            </div>
            </el-form-item>
          </div>

          <el-form-item label="点位类型">
            <el-select v-model="pointType" style="width: 100%">
              <el-option
                v-for="item in TYPE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="指引说明（选填）">
            <el-input
              v-model="guideText"
              type="textarea"
              :rows="2"
              placeholder="例如：从东门进直走 100 米，蓝色快递柜旁"
              maxlength="60"
              show-word-limit
            />
          </el-form-item>
        </el-form>

        <p class="picker__tip">
          新增的地点<strong>无需审核，买卖双方立刻可见</strong>，可直接用于发布商品或在私聊里改面交地址。
        </p>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!picked" @click="submit">
        添加并使用
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.picker {
  display: grid;
  gap: 18px;
  // 表单列加宽到 340px：300px 时名称下方的说明会被折成三行
  grid-template-columns: minmax(0, 1fr) 340px;

  &__map {
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
  }

  &__panel {
    display: flex;
    overflow: hidden;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  &__block {
    margin-bottom: 10px;
  }

  &__nearby {
    margin-bottom: 12px;
    padding: 9px 10px;
    border: 1px solid var(--brand-100, var(--line));
    border-radius: var(--r-sm);
    background: var(--brand-50);
  }

  &__nearby-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .cand--nearby {
    max-height: 172px;
    margin-top: 6px;

    .cand__item {
      background: #fff;
    }
  }

  &__form {
    display: flex;
    overflow-y: auto;
    flex: 1;
    flex-direction: column;
    min-height: 0;

    // 表单项之间压紧一点，整个弹窗才装得下
    :deep(.el-form-item) {
      margin-bottom: 12px;
    }

    :deep(.el-form-item__label) {
      padding-bottom: 2px;
      font-size: 12.5px;
      line-height: 18px;
    }
  }

  // 候选/搜索结果：固定高度滚动，不用把表单顶下去
  &__results {
    overflow-y: auto;
    max-height: 216px;
    margin-bottom: 4px;
    padding-right: 2px;

    :deep(.el-form-item) {
      margin-bottom: 8px;
    }
  }

  &__label {
    margin: 0 0 4px;
    color: var(--text-2);
    font-size: 13px;
    font-weight: 600;
  }

  &__coord {
    margin: 0;
    padding: 8px 10px;
    border-radius: var(--r-sm);
    background: var(--surface-2);
    color: var(--text-1);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12.5px;
    word-break: break-all;

    // 还没选点：这句是首屏唯一指引，给品牌色而不是灰色小字
    &.is-empty {
      background: var(--brand-50);
      color: var(--brand-700);
      font-family: inherit;
      font-size: 13px;
      font-weight: 600;
    }
  }

  &__tag {
    margin-left: 8px;
    padding: 1px 6px;
    border-radius: 999px;
    background: var(--brand-100);
    color: var(--brand-700);
    font-family: inherit;
    font-size: 11px;
  }

  &__empty {
    color: var(--text-3);
    font-family: inherit;
  }

  &__tip {
    margin: 0 0 2px;
    padding: 8px 10px;
    border-radius: var(--r-sm);
    background: #fff8f4;
    color: var(--accent-600);
    font-size: 12px;
    line-height: 1.7;

    strong {
      color: var(--accent-700, var(--accent-600));
    }
  }

  &__name-row {
    display: flex;
    gap: 8px;
    width: 100%;
  }

  &__name-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    margin-top: 6px;
    flex-wrap: wrap;
  }

  &__hint {
    margin: 4px 0 0;
    color: var(--text-3);
    font-size: 11.5px;
    line-height: 1.6;

    &--inline {
      margin: 0;
      flex: 1;
      min-width: 140px;
    }
  }

  &__custom {
    margin-bottom: 10px;
  }
}

/* 高德候选地点列表 */
.cand {
  display: flex;
  overflow-y: auto;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  max-height: 200px;
  padding: 1px;

  &__item {
    display: flex;
    flex-direction: column;
    gap: 1px;
    padding: 6px 9px;
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    background: #fff;
    cursor: pointer;
    font-family: inherit;
    text-align: left;
    transition: all 0.14s ease;

    &:hover {
      border-color: var(--brand-300);
      background: var(--brand-50);
    }

    &.is-on {
      border-color: var(--brand-500);
      background: var(--brand-50);

      .cand__name {
        color: var(--brand-700);
        font-weight: 600;
      }
    }
  }

  &__name {
    color: var(--text-1);
    font-size: 13px;
  }

  &__badge {
    margin-left: 6px;
    padding: 0 6px;
    border-radius: 999px;
    background: var(--line);
    color: var(--text-3);
    font-size: 10.5px;
    font-style: normal;
  }

  &__cache-tip {
    display: block;
    margin-bottom: 6px;
    color: #16a34a;
    font-size: 11.5px;
  }

  &__meta {
    overflow: hidden;
    color: var(--text-3);
    font-size: 11.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

@media (max-width: 900px) {
  .picker {
    grid-template-columns: 1fr;
  }
}
</style>
