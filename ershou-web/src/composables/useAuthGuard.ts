import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken } from '@/utils/request'

/** 需要登录的操作统一走这里：未登录时提示并跳到登录页（带 redirect） */
export function useAuthGuard() {
  const route = useRoute()
  const router = useRouter()

  function ensureLogin(tip = '请先登录后再操作'): boolean {
    if (getToken()) return true
    ElMessage.warning(tip)
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return false
  }

  return { ensureLogin }
}
