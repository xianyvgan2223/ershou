<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Lock, Location, Moon, ShoppingBag, Star, Sunny } from '@element-plus/icons-vue'
import { sendSmsCode } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { useTheme } from '@/composables/useTheme'
import { DEFAULT_CAMPUS_ID, DEFAULT_SCHOOL_ID, DEV_SMS_CODE } from '@/utils/constants'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { isDark, toggleTheme } = useTheme()

const phone = ref('')
const code = ref('')
const countdown = ref(0)
const sending = ref(false)
const submitting = ref(false)
let timer: number | null = null

const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(phone.value))
const codeButtonText = computed(() => (countdown.value > 0 ? `${countdown.value}s 后重发` : '获取验证码'))

const FEATURES = [
  { icon: Location, title: '校内面交点', desc: '地图上选个地方，下课顺路就见' },
  { icon: ShoppingBag, title: '免手续费', desc: '平台不经手资金，当面验货当面付' },
  { icon: Star, title: '同校信用', desc: '信用分 + 评价，靠谱的人一眼可见' },
]

function startCountdown(): void {
  countdown.value = 60
  if (timer !== null) window.clearInterval(timer)
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      window.clearInterval(timer as number)
      timer = null
    }
  }, 1000)
}

async function handleSendCode(): Promise<void> {
  if (!phoneValid.value) {
    ElMessage.warning('请输入正确的 11 位手机号')
    return
  }
  sending.value = true
  try {
    await sendSmsCode(phone.value)
    ElMessage.success(`验证码已发送，开发环境固定为 ${DEV_SMS_CODE}`)
    startCountdown()
  } catch {
    // 响应拦截器已提示
  } finally {
    sending.value = false
  }
}

async function handleLogin(): Promise<void> {
  if (!phoneValid.value) {
    ElMessage.warning('请输入正确的 11 位手机号')
    return
  }
  if (!code.value) {
    ElMessage.warning('请输入短信验证码')
    return
  }
  submitting.value = true
  try {
    const result = await userStore.login({
      phone: phone.value,
      code: code.value,
      schoolId: DEFAULT_SCHOOL_ID,
      campusId: DEFAULT_CAMPUS_ID,
    })
    ElMessage.success(result.user?.newUser ? '欢迎加入校园二手！' : '登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.replace(redirect)
  } catch {
    // 响应拦截器已提示
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <div class="login">
    <el-tooltip :content="isDark ? '切换为日间模式' : '切换为夜间模式'" placement="bottom">
      <el-button
        class="login__theme"
        circle
        :icon="isDark ? Sunny : Moon"
        :aria-label="isDark ? '切换为日间模式' : '切换为夜间模式'"
        @click="toggleTheme"
      />
    </el-tooltip>
    <div class="login__panel">
      <!-- 左侧品牌区 -->
      <aside class="brand-panel">
        <div class="brand-panel__logo">
          <span class="brand-panel__mark">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 9.5 12 3l9 6.5" />
              <path d="M5 10v10h14V10" />
              <path d="M9.5 20v-5.5h5V20" />
            </svg>
          </span>
          <b>校园二手</b>
        </div>

        <h1 class="brand-panel__title">把闲置<br />交给同校的同学</h1>
        <p class="brand-panel__desc">0 手续费 · 校内面交 · 信用保障</p>

        <ul class="feature-list">
          <li v-for="feature in FEATURES" :key="feature.title">
            <span class="feature-list__icon"><el-icon><component :is="feature.icon" /></el-icon></span>
            <div>
              <p class="feature-list__title">{{ feature.title }}</p>
              <p class="feature-list__desc">{{ feature.desc }}</p>
            </div>
          </li>
        </ul>

        <div class="brand-panel__glow" aria-hidden="true" />
      </aside>

      <!-- 右侧表单 -->
      <section class="form-panel">
        <div class="form-card">
          <h2 class="form-card__title">登录 / 注册</h2>
          <p class="form-card__sub">手机号 + 验证码，不用记密码</p>

          <el-form label-position="top" size="large" @submit.prevent>
            <el-form-item label="手机号">
              <el-input v-model="phone" :prefix-icon="Iphone" placeholder="请输入 11 位手机号" maxlength="11" clearable />
            </el-form-item>
            <el-form-item label="短信验证码">
              <div class="code-row">
                <el-input v-model="code" :prefix-icon="Lock" placeholder="请输入验证码" maxlength="6" @keyup.enter="handleLogin" />
                <el-button :disabled="countdown > 0" :loading="sending" @click="handleSendCode">
                  {{ codeButtonText }}
                </el-button>
              </div>
            </el-form-item>
          </el-form>

          <el-button class="form-card__submit" size="large" :loading="submitting" @click="handleLogin">
            登录 / 注册
          </el-button>

          <p class="form-card__dev">
            开发环境验证码固定为 <b>{{ DEV_SMS_CODE }}</b>，直接输入即可
          </p>
          <p class="form-card__note">登录即代表同意《校园二手交易公约》，请勿交易违规物品</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background: var(--bg);

  &__theme {
    position: fixed;
    top: 18px;
    right: 18px;
    z-index: 20;
    border-color: var(--line-strong);
    background: var(--surface);
    color: var(--text-2);
  }

  &__panel {
    display: grid;
    overflow: hidden;
    width: 100%;
    max-width: 960px;
    border: 1px solid var(--line);
    border-radius: 22px;
    background: var(--surface);
    box-shadow: var(--shadow-lg);
    grid-template-columns: 1.05fr 1fr;
  }
}

.brand-panel {
  position: relative;
  overflow: hidden;
  padding: 44px 40px;
  background: var(--brand-600);
  color: #fff;

  &__logo {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 17px;
    font-weight: 700;
  }

  &__mark {
    display: grid;
    width: 40px;
    height: 40px;
    place-items: center;
    border-radius: 13px;
    background: rgb(255 255 255 / 18%);
    backdrop-filter: blur(6px);
  }

  &__title {
    margin: 30px 0 10px;
    font-size: 30px;
    font-weight: 800;
    letter-spacing: -0.6px;
    line-height: 1.3;
  }

  &__desc {
    color: rgb(255 255 255 / 80%);
    font-size: 14px;
  }

  &__glow {
    position: absolute;
    right: -70px;
    bottom: -90px;
    width: 260px;
    height: 260px;
    border-radius: 50%;
    background: radial-gradient(circle, rgb(255 255 255 / 26%), transparent 68%);
  }
}

.feature-list {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 34px 0 0;
  padding: 0;
  list-style: none;

  li {
    display: flex;
    align-items: flex-start;
    gap: 12px;
  }

  &__icon {
    display: grid;
    width: 34px;
    height: 34px;
    flex-shrink: 0;
    place-items: center;
    border-radius: 11px;
    background: rgb(255 255 255 / 16%);
  }

  &__title {
    font-size: 14px;
    font-weight: 600;
  }

  &__desc {
    color: rgb(255 255 255 / 74%);
    font-size: 12.5px;
  }
}

.form-panel {
  display: grid;
  place-items: center;
  padding: 44px 40px;
}

.form-card {
  width: 100%;
  max-width: 340px;

  &__title {
    font-size: 22px;
    font-weight: 700;
    letter-spacing: -0.3px;
  }

  &__sub {
    margin: 6px 0 22px;
    color: var(--text-3);
    font-size: 13px;
  }

  &__submit {
    width: 100%;
    margin-top: 6px;
    border: none;
    border-radius: var(--r-pill);
    background: var(--brand-500);
    color: #fff;
    font-weight: 600;

    &:hover,
    &:focus {
      background: var(--brand-600);
      color: #fff;
    }
  }

  &__dev {
    margin-top: 16px;
    padding: 9px 12px;
    border-radius: var(--r-md);
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 12.5px;
    text-align: center;

    b {
      letter-spacing: 1px;
    }
  }

  &__note {
    margin-top: 12px;
    color: var(--text-3);
    font-size: 11.5px;
    text-align: center;
  }
}

.code-row {
  display: flex;
  gap: 10px;
  width: 100%;

  .el-button {
    flex-shrink: 0;
    width: 116px;
  }
}

@media (max-width: 820px) {
  .login__panel {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    padding: 30px 26px;

    &__title {
      margin-top: 18px;
      font-size: 24px;
    }
  }

  .feature-list {
    display: none;
  }

  .form-panel {
    padding: 28px 22px 34px;
  }
}
</style>
