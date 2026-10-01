import { computed, ref } from 'vue'
import { fetchMyTradePoints, fetchTradePoints, pointTypeLabel } from '@/api/map'
import type { TradePoint } from '@/api/map'
import { useUserStore } from '@/stores/user'
import { MERGE_RADIUS_METERS, groupPointsByDistance, mergedPointOf } from '@/utils/pointMerge'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'

/**
 * 面交点选项：发布页 / 购物车结算 / 约面交弹窗共用。
 *
 * 模块级缓存（同一校区只拉一次）：这几个页面来回切换时不必重复请求；
 * 校区取当前登录用户的校区，未登录或资料未加载时回落默认校区。
 *
 * 除了官方点位，还会把「我自己提交过的点位」并进来（含待审），
 * 这样用户新增地点后刷新页面依然能选中，不会出现"刚建的点位不见了"。
 */
const points = ref<TradePoint[]>([])
const loading = ref(false)
/** 点位库版本号：任何地方新增/修改点位后 +1，各页面 watch 它来刷新自己的地图/列表 */
const pointsVersion = ref(0)
let loadedCampusId: number | null = null

/** 通知全局：点位库变了（新增/改了点位），监听方应重新拉数据 */
export function bumpPointsVersion(): void {
  pointsVersion.value += 1
}

export function tradePointLabel(point: TradePoint): string {
  const base = `${point.name}（${pointTypeLabel(point.type)}）`
  return point.auditStatus === 0 ? `${base} · 审核中` : base
}

export function useTradePoints() {
  const userStore = useUserStore()
  const campusId = computed(() => userStore.profile?.campusId ?? DEFAULT_CAMPUS_ID)

  /** 合并自己提交的点位（去重，官方列表优先） */
  function mergeMine(mine: TradePoint[]): void {
    if (!mine.length) return
    const known = new Set(points.value.map((point) => point.id))
    points.value = [...points.value, ...mine.filter((point) => !known.has(point.id))]
  }

  /** force=true 强制回源（例如从地图页推荐完点位回到列表） */
  async function load(force = false): Promise<TradePoint[]> {
    if (!force && loadedCampusId === campusId.value && points.value.length) {
      return points.value
    }
    loading.value = true
    try {
      points.value = await fetchTradePoints(campusId.value)
      loadedCampusId = campusId.value
      try {
        mergeMine(await fetchMyTradePoints(campusId.value))
      } catch {
        // 未登录等情况拿不到自己的点位，不影响官方列表
      }
    } catch {
      // 响应拦截器已提示；这里保持空列表，页面会显示"暂无点位"引导
      points.value = []
    } finally {
      loading.value = false
    }
    return points.value
  }

  /** 新增点位后立即插入选项列表（不必等下次回源）；同时把版本号 +1 通知其它页面 */
  function add(point: TradePoint, silent = false): void {
    if (point && !points.value.some((item) => item.id === point.id)) {
      points.value = [point, ...points.value]
    }
    if (!silent) {
      bumpPointsVersion()
    }
  }

  function find(id?: number | null): TradePoint | null {
    if (id === null || id === undefined) return null
    return points.value.find((point) => point.id === id) ?? null
  }

  /** 已选点位的说明文案（挑选面交点时给用户一点安全感） */
  function guideOf(id?: number | null): string {
    return find(id)?.guideText ?? ''
  }

  /**
   * 下拉里实际展示的点位：**100 米内的合并成一条**（名称带「（N 个点位）」）。
   *
   * 选中后拿到的是代表点位的 id（真实存在的一条），所以商品/面交单的引用仍然精确。
   */
  const options = computed<TradePoint[]>(() =>
    groupPointsByDistance(points.value, MERGE_RADIUS_METERS).map((group) => mergedPointOf(group)),
  )

  return { points, options, loading, campusId, pointsVersion, load, add, find, guideOf }
}
