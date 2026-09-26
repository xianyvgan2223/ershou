import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchCurrentUser, login as loginApi } from '@/api/auth'
import type { LoginParams, LoginResult, LoginUser, UserProfile } from '@/api/auth'
import { updateMyProfile } from '@/api/user'
import type { UpdateProfileParams } from '@/api/user'
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
  /** 是否运营人员（后端 /users/me 下发） */
  const isAdmin = computed(() => profile.value?.isAdmin ?? false)

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

  /**
   * 编辑资料（昵称 / 头像 / 学校校区 / 小铺信息）。
   *
   * 后端回的是更新后的完整资料，直接覆盖 profile；
   * 同时同步登录概要里的昵称 / 头像 / 校区，否则顶栏和头像占位还停留在旧值。
   */
  async function updateProfile(params: UpdateProfileParams): Promise<UserProfile> {
    const data = await updateMyProfile(params)
    profile.value = data
    if (userInfo.value) {
      userInfo.value = {
        ...userInfo.value,
        nickname: data.nickname,
        avatar: data.avatar,
        campusId: data.campusId ?? userInfo.value.campusId,
        campusName: data.campusName || userInfo.value.campusName,
      }
    }
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
    isAdmin,
    login,
    fetchProfile,
    updateProfile,
    logout,
  }
})
