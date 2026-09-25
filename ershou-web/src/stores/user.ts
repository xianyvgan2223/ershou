import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchCurrentUser, login as loginApi } from '@/api/auth'
import type { LoginParams, LoginResult, LoginUser, UserProfile } from '@/api/auth'
import { clearToken, getToken, setToken } from '@/utils/request'

/**
 * 当前登录用户：token 持久化在 localStorage，
 * profile 为 /users/me 的完整资料，userInfo 为登录接口返回的概要。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken())
  const profile = ref<UserProfile | null>(null)
  const userInfo = ref<LoginUser | null>(null)
  const loading = ref(false)

  const isLogin = computed(() => Boolean(token.value))
  const nickname = computed(() => profile.value?.nickname || userInfo.value?.nickname || '同学')
  const avatar = computed(() => profile.value?.avatar || userInfo.value?.avatar || '')
  const creditScore = computed(() => profile.value?.creditScore ?? userInfo.value?.creditScore ?? 0)
  const creditLevel = computed(() => profile.value?.creditLevel || '')
  const campusName = computed(() => profile.value?.campusName || userInfo.value?.campusName || '')
  const identityFlag = computed(() => profile.value?.identityFlag ?? userInfo.value?.identityFlag ?? 0)

  /** 登录（手机号 + 验证码），成功后写入 token 并拉取资料 */
  async function login(params: LoginParams): Promise<LoginResult> {
    loading.value = true
    try {
      const result = await loginApi(params)
      token.value = result.token
      setToken(result.token)
      userInfo.value = result.user
      await fetchProfile()
      return result
    } finally {
      loading.value = false
    }
  }

  /** 拉取当前用户资料 */
  async function fetchProfile(): Promise<UserProfile | null> {
    if (!token.value) return null
    const data = await fetchCurrentUser()
    profile.value = data
    return data
  }

  /** 退出登录 */
  function logout(): void {
    token.value = ''
    profile.value = null
    userInfo.value = null
    clearToken()
  }

  return {
    token,
    profile,
    userInfo,
    loading,
    isLogin,
    nickname,
    avatar,
    creditScore,
    creditLevel,
    campusName,
    identityFlag,
    login,
    fetchProfile,
    logout,
  }
})
