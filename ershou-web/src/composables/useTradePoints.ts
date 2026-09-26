import { computed, ref } from 'vue'
import { fetchTradePoints, pointTypeLabel } from '@/api/map'
import type { TradePoint } from '@/api/map'
import { useUserStore } from '@/stores/user'
import { DEFAULT_CAMPUS_ID } from '@/utils/constants'

/**
 * 面交点选项：发布页 / 购物车结算 / 约面交弹窗共用。
 *
 * 模块级缓存（同一校区只拉一次）：这几个页面来回切换时不必重复请求；
 * 校区取当前登录用户的校区，未登录或资料未加载时回落默认校区。
 */
const points = ref<TradePoint[]>([])
const loading = ref(false)
let loadedCampusId: number | null = null

export function tradePointLabel(point: TradePoint): string {
  return `${point.name}（${pointTypeLabel(point.type)}）`
}

export function useTradePoints() {
  const userStore = useUserStore()
  const campusId = computed(() => userStore.profile?.campusId ?? DEFAULT_CAMPUS_ID)

  /** force=true 强制回源（例如从地图页推荐完点位回到列表） */
  async function load(force = false): Promise<TradePoint[]> {
    if (!force && loadedCampusId === campusId.value && points.value.length) {
      return points.value
    }
    loading.value = true
    try {
      points.value = await fetchTradePoints(campusId.value)
      loadedCampusId = campusId.value
    } catch {
      // 响应拦截器已提示；这里保持空列表，页面会显示"暂无点位"引导
      points.value = []
    } finally {
      loading.value = false
    }
    return points.value
  }

  function find(id?: number | null): TradePoint | null {
    if (id === null || id === undefined) return null
    return points.value.find((point) => point.id === id) ?? null
  }

  /** 已选点位的说明文案（挑选面交点时给用户一点安全感） */
  function guideOf(id?: number | null): string {
    return find(id)?.guideText ?? ''
  }

  return { points, loading, campusId, load, find, guideOf }
}
