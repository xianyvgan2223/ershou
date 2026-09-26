import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchMeetupTodoCount } from '@/api/order'
import { useUserStore } from '@/stores/user'

/**
 * 面交待办：顶栏「我的面交」红点。
 *
 * <p>和私聊未读一样，红点要全局可见 —— 卖家收到约面交申请、买家被接受后还没点
 * "我收到了"，都不该等到自己点开「我的面交」才发现。
 */
export const useMeetupStore = defineStore('meetup', () => {
  const todoCount = ref(0)
  const asSeller = ref(0)
  const asBuyer = ref(0)
  let timer: number | null = null

  const label = computed(() => (todoCount.value > 99 ? '99+' : String(todoCount.value)))

  async function loadTodo(): Promise<void> {
    const userStore = useUserStore()
    if (!userStore.isLogin) {
      todoCount.value = 0
      return
    }
    try {
      const data = await fetchMeetupTodoCount()
      todoCount.value = data.total ?? 0
      asSeller.value = data.asSeller ?? 0
      asBuyer.value = data.asBuyer ?? 0
    } catch {
      // 拦截器已提示；红点保持上一次的值，不因为一次失败就闪没
    }
  }

  /** 布局里启动轮询：面交单的变化多来自对方（卖家接受/拒绝），不轮询红点不会自己亮 */
  function startPolling(intervalMs = 30_000): void {
    stopPolling()
    void loadTodo()
    timer = window.setInterval(() => void loadTodo(), intervalMs)
  }

  function stopPolling(): void {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  function reset(): void {
    stopPolling()
    todoCount.value = 0
    asSeller.value = 0
    asBuyer.value = 0
  }

  return { todoCount, asSeller, asBuyer, label, loadTodo, startPolling, stopPolling, reset }
})
