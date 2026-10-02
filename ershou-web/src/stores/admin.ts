import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchAdminDashboard } from '@/api/admin'
import type { AdminDashboard } from '@/api/admin'

/** 概览接口自带 30 秒读缓存，同一页面里短时间内重复请求没有意义 */
const REUSE_WINDOW_MS = 2000

/**
 * 运营后台的共享状态：概览统计与待办数。
 *
 * 以前 `AdminShell` 自己在 `onMounted` 拉一次概览算角标，概览页再拉一次同样的接口，
 * 而且运营审核完商品角标不会变 —— 要刷新整页才更新（后端 30 秒缓存 + 前端不重拉，双重原因）。
 * 现在两侧读同一份 store，写操作后统一 `refresh()`。
 */
export const useAdminStore = defineStore('admin', () => {
  const dashboard = ref<AdminDashboard | null>(null)
  const loading = ref(false)

  let lastLoadedAt = 0
  let inflight: Promise<void> | null = null

  /** 侧栏角标：待审商品 + 待审点位（点位审核在同一个工作台里） */
  const todoCount = computed(
    () => (dashboard.value?.pendingItems ?? 0) + (dashboard.value?.pendingPoints ?? 0),
  )

  /**
   * 拉取概览。
   * @param force true 时忽略复用窗口，强制真请求（后台写操作之后用）
   */
  async function load(force = false): Promise<void> {
    // 同一时刻只允许一个请求在飞：Shell 与概览页同时挂载时不会打两次
    if (inflight) return inflight
    if (!force && dashboard.value && Date.now() - lastLoadedAt < REUSE_WINDOW_MS) return

    inflight = (async () => {
      loading.value = true
      try {
        dashboard.value = await fetchAdminDashboard()
        lastLoadedAt = Date.now()
      } catch {
        // 响应拦截器已经弹过提示；这里保留上一次的数据，别把角标清成 0
      } finally {
        loading.value = false
        inflight = null
      }
    })()
    return inflight
  }

  /** 后台写操作（审核 / 下架 / 封禁 / 改信用分）之后调用，让角标立刻跟上 */
  function refresh(): Promise<void> {
    return load(true)
  }

  return { dashboard, loading, todoCount, load, refresh }
})
