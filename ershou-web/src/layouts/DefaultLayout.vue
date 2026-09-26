<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ChatDotRound,
  House,
  MapLocation,
  Plus,
  Search,
  Setting,
  ShoppingCart,
  Star,
  Tickets,
  User,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useMeetupStore } from '@/stores/meetup'
import { fetchCart } from '@/api/cart'
import { nameInitial } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()
const meetupStore = useMeetupStore()
const cartCount = ref(0)
const searchKeyword = ref('')

const NAV_LINKS = [
  { path: '/', label: '首页', icon: House },
  { path: '/map', label: '校内地图', icon: MapLocation },
  { path: '/chat', label: '消息', icon: ChatDotRound },
  { path: '/cart', label: '购物车', icon: ShoppingCart },
  { path: '/orders', label: '我的面交', icon: Tickets },
  { path: '/profile', label: '我的', icon: User },
]

const activeMenu = computed(() => {
  const path = route.path
  if (path === '/') return '/'
  if (path.startsWith('/map')) return '/map'
  if (path.startsWith('/chat')) return '/chat'
  if (path.startsWith('/cart')) return '/cart'
  if (path.startsWith('/orders')) return '/orders'
  if (path.startsWith('/profile') || path.startsWith('/favorites') || path.startsWith('/publish-list')) {
    return '/profile'
  }
  return ''
})

const creditText = computed(() => (userStore.isLogin ? `信用分 ${userStore.creditScore}` : ''))

async function loadCartCount(): Promise<void> {
  if (!userStore.isLogin) return
  try {
    const groups = await fetchCart()
    cartCount.value = groups.reduce(
      (sum, group) => sum + group.items.reduce((count, item) => count + item.quantity, 0),
      0,
    )
  } catch {
    cartCount.value = 0
  }
}

/** 交易类动作（接受/拒绝/成交）之后局部刷新红点，不用等下一轮轮询 */
function refreshMeetupTodo(): void {
  if (userStore.isLogin) void meetupStore.loadTodo()
}

function handleHeaderSearch(): void {
  const keyword = searchKeyword.value.trim()
  router.push({ path: '/', query: keyword ? { keyword } : {} })
}

async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '再想想',
        type: 'warning',
      })
    } catch {
      return
    }
    chatStore.disconnect()
    meetupStore.reset()
    userStore.logout()
    cartCount.value = 0
    ElMessage.success('已退出登录')
    router.push('/')
    return
  }
  router.push(command)
}

onMounted(async () => {
  if (userStore.isLogin && !userStore.profile) {
    try {
      await userStore.fetchProfile()
    } catch {
      // 拦截器已提示
    }
  }
  await loadCartCount()
  // 私聊连接与未读红点挂在布局上：切页面不断线，红点全局可见
  if (userStore.isLogin) {
    chatStore.connect()
    await chatStore.loadUnread()
    // 面交待办红点：对方随时可能接受/拒绝，靠轮询兜底（30s 一次，接口只做两个 count）
    meetupStore.startPolling()
  }
})

watch(
  () => route.path,
  (path) => {
    // 离开/回到相关页面时对齐一次，避免"刚处理完还挂着红点"
    if (path.startsWith('/orders') || path.startsWith('/chat')) refreshMeetupTodo()
  },
)
</script>

<template>
  <div class="layout">
    <header class="header">
      <div class="header__inner">
        <router-link to="/" class="brand">
          <span class="brand__mark">
            <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 9.5 12 3l9 6.5" />
              <path d="M5 10v10h14V10" />
              <path d="M9.5 20v-5.5h5V20" />
            </svg>
          </span>
          <span class="brand__text">
            <b>校园二手</b>
            <i>同校面交 · 免手续费</i>
          </span>
        </router-link>

        <div class="header__search">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索教材、数码、自行车…"
            :prefix-icon="Search"
            clearable
            @keyup.enter="handleHeaderSearch"
            @clear="handleHeaderSearch"
          />
        </div>

        <nav class="nav">
          <router-link
            v-for="link in NAV_LINKS"
            :key="link.path"
            :to="link.path"
            class="nav__link"
            :class="{ 'is-active': activeMenu === link.path }"
          >
            <el-icon><component :is="link.icon" /></el-icon>
            <span>{{ link.label }}</span>
            <em v-if="link.path === '/cart' && cartCount > 0" class="nav__dot">{{ cartCount }}</em>
            <em v-else-if="link.path === '/chat' && chatStore.unreadTotal > 0" class="nav__dot">
              {{ chatStore.unreadTotal > 99 ? '99+' : chatStore.unreadTotal }}
            </em>
            <em v-else-if="link.path === '/orders' && meetupStore.todoCount > 0" class="nav__dot">
              {{ meetupStore.label }}
            </em>
          </router-link>
        </nav>

        <div class="header__actions">
          <el-button class="btn-publish" :icon="Plus" round @click="router.push('/publish')">
            <span class="btn-publish__text">发布闲置</span>
          </el-button>
          <el-dropdown v-if="userStore.isLogin" trigger="click" @command="handleCommand">
            <span class="account">
              <el-avatar :size="30" :src="userStore.avatar">
                {{ nameInitial(userStore.nickname) }}
              </el-avatar>
              <span class="account__name">{{ userStore.nickname }}</span>
              <el-icon class="account__arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ creditText }}</el-dropdown-item>
                <el-dropdown-item command="/profile" :icon="User">个人中心</el-dropdown-item>
                <el-dropdown-item command="/publish-list">我的发布</el-dropdown-item>
                <el-dropdown-item command="/favorites" :icon="Star">我的收藏</el-dropdown-item>
                <el-dropdown-item command="/orders" :icon="Tickets">
                  我的面交<span v-if="meetupStore.todoCount > 0" class="account__dot">{{ meetupStore.todoCount }} 项待处理</span>
                </el-dropdown-item>
                <el-dropdown-item v-if="userStore.isAdmin" command="/admin" :icon="Setting" divided>
                  运营后台
                </el-dropdown-item>
                <el-dropdown-item command="logout" :divided="!userStore.isAdmin">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-else class="btn-login" text @click="router.push('/login')">
            登录 / 注册
          </el-button>
        </div>
      </div>
    </header>

    <main class="layout__main">
      <router-view />
    </main>

    <footer class="footer">
      <div class="footer__inner">
        <div class="footer__brand">
          <span class="brand__mark brand__mark--sm">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 9.5 12 3l9 6.5" />
              <path d="M5 10v10h14V10" />
              <path d="M9.5 20v-5.5h5V20" />
            </svg>
          </span>
          <div>
            <p class="footer__name">校园二手 · 校内交易平台</p>
            <p class="footer__slogan">平台只提供信息与监管，交易资金不经平台，面交自提更安心</p>
          </div>
        </div>
        <div class="footer__links">
          <router-link to="/map">校内地图</router-link>
          <router-link to="/publish">发布闲置</router-link>
          <router-link to="/orders">我的面交</router-link>
          <span class="footer__copy">© 2026 校园二手</span>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.layout {
  display: flex;
  min-height: 100%;
  flex-direction: column;

  &__main {
    flex: 1;
  }
}

// ---------- 顶栏 ----------
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 1px solid var(--line);
  background: rgb(255 255 255 / 88%);
  backdrop-filter: saturate(180%) blur(12px);

  &__inner {
    display: flex;
    align-items: center;
    gap: 14px;
    max-width: 1240px;
    height: 64px;
    margin: 0 auto;
    padding: 0 20px;
  }

  &__search {
    flex: 1 1 200px;
    min-width: 150px;
    max-width: 300px;

    :deep(.el-input__wrapper) {
      border-radius: var(--r-pill);
      background: var(--surface-2);
      box-shadow: none;

      &:hover,
      &.is-focus {
        background: #fff;
        box-shadow: 0 0 0 1px var(--brand-300) inset;
      }
    }
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-left: auto;
    white-space: nowrap;
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;

  &__mark {
    display: grid;
    width: 38px;
    height: 38px;
    place-items: center;
    border-radius: 12px;
    background: var(--brand-500);
    color: #fff;

    &--sm {
      width: 30px;
      height: 30px;
      border-radius: 9px;
    }
  }

  &__text {
    display: flex;
    flex-direction: column;
    line-height: 1.2;

    b {
      font-size: 16px;
      font-weight: 700;
      letter-spacing: -0.2px;
    }

    i {
      color: var(--text-3);
      font-size: 11px;
      font-style: normal;
    }
  }
}

.nav {
  display: flex;
  flex-shrink: 0; // 导航是"内容优先"的：宁可压缩搜索框，也不能把中文标签挤成竖排
  align-items: center;
  gap: 2px;

  &__link {
    position: relative;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    white-space: nowrap;
    border-radius: var(--r-pill);
    color: var(--text-2);
    font-size: 13.5px;
    transition: all 0.18s ease;

    &:hover {
      background: var(--surface-2);
      color: var(--text-1);
    }

    &.is-active {
      background: var(--brand-50);
      color: var(--brand-700);
      font-weight: 600;
    }
  }

  &__dot {
    min-width: 17px;
    padding: 0 4px;
    border-radius: 999px;
    background: var(--accent-500);
    color: #fff;
    font-size: 11px;
    font-style: normal;
    font-weight: 600;
    line-height: 17px;
    text-align: center;
  }
}

.btn-publish {
  border: none;
  background: var(--brand-500);
  color: #fff;
  font-weight: 600;

  &:hover,
  &:focus {
    background: var(--brand-600);
    color: #fff;
  }
}

.btn-login {
  color: var(--brand-600);
  font-weight: 600;
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border-radius: var(--r-pill);
  cursor: pointer;
  outline: none;
  transition: background 0.18s ease;

  &:hover {
    background: var(--surface-2);
  }

  &__name {
    max-width: 96px;
    overflow: hidden;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__arrow {
    color: var(--text-3);
    font-size: 12px;
  }
}

// 下拉菜单里的"待处理"角标（我的面交）
.account__dot {
  margin-left: 8px;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: var(--accent-500);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
}

// ---------- 页脚 ----------
.footer {
  margin-top: auto;
  border-top: 1px solid var(--line);
  background: #fff;

  &__inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    max-width: 1240px;
    margin: 0 auto;
    padding: 22px 20px;
    flex-wrap: wrap;
  }

  &__brand {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
  }

  &__slogan {
    color: var(--text-3);
    font-size: 12px;
  }

  &__links {
    display: flex;
    align-items: center;
    gap: 18px;
    color: var(--text-2);
    font-size: 13px;

    a:hover {
      color: var(--brand-600);
    }
  }

  &__copy {
    color: var(--text-3);
  }
}

@media (max-width: 1080px) {
  .header__search {
    display: none;
  }
}

@media (max-width: 860px) {
  .nav__link span {
    display: none;
  }

  .brand__text i {
    display: none;
  }

  // 窄屏：导航占据中间剩余空间并支持横滑。
  // 注意要 flex: 1 1 auto——只写 flex-shrink: 1 会被右侧按钮挤成 0 宽（导航直接消失）
  .nav {
    overflow-x: auto;
    flex: 1 1 auto;
    min-width: 0;
    justify-content: flex-start;
    scrollbar-width: none;

    &::-webkit-scrollbar {
      display: none;
    }
  }
}

@media (max-width: 560px) {
  .header__inner {
    gap: 10px;
    padding: 0 12px;
  }

  // 超窄屏：发布按钮只留 "+" 图标，把横向空间让给导航
  .btn-publish__text {
    display: none;
  }

  .btn-publish {
    padding: 8px 11px;
  }
}
</style>
