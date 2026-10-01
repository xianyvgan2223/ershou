import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchCart } from '@/api/cart'
import { useUserStore } from '@/stores/user'

/**
 * 购物车件数（顶栏「购物车」红点）。
 *
 * <p>以前这个数是 DefaultLayout 自己在 onMounted 里拉的，**只在布局挂载时算一次**：
 * 用户在商品详情页点「加入购物车」后，红点纹丝不动，看着像没加进去（得刷新页面才出现）。
 * 现在收敛到 store：加入购物车 / 结算 / 移除之后都调一次 refresh()。
 *
 * <p>计数口径是**件数之和**（不是条目数）：购物车里 1 条 × 3 件显示 3，
 * 和「我的面交」那种"单数"红点区分开。
 */
export const useCartStore = defineStore('cart', () => {
  const count = ref(0)

  const label = computed(() => (count.value > 99 ? '99+' : String(count.value)))

  async function refresh(): Promise<void> {
    const userStore = useUserStore()
    if (!userStore.isLogin) {
      count.value = 0
      return
    }
    try {
      const groups = await fetchCart()
      count.value = groups.reduce(
        (sum, group) => sum + group.items.reduce((n, item) => n + item.quantity, 0),
        0,
      )
    } catch {
      // 红点不是关键路径：失败就保持上一次的值，不要因为一次请求失败就闪没
    }
  }

  function reset(): void {
    count.value = 0
  }

  return { count, label, refresh, reset }
})
