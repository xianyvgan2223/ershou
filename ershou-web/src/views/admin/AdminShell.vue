<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Bell, Clock, DataLine, Goods, List, Operation, User } from '@element-plus/icons-vue'
import { fetchReviewStats } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { useUserStore } from '@/stores/user'
import { closeAllTips, showTip } from '@/utils/feedback'

const route = useRoute()
const userStore = useUserStore()
const adminStore = useAdminStore()

/**
 * 上架审核的待办数单独一份：它不在概览接口里（口径是"还挂在平台上的、还没被人工看过的"），
 * 而且更新频率高得多 —— 每有一条新商品上架就会变，混进概览的 30 秒缓存里反而看不到实时变化。
 */
const reviewPending = ref(0)

const NAV = [
  { path: '/admin', label: '运营概览', desc: '待办与总量', icon: DataLine, todo: false },
  { path: '/admin/review', label: '上架审核', desc: '实时确认每条上架', icon: Bell, badge: 'review' },
  { path: '/admin/audit', label: '审核工作台', desc: '商品 / 点位', icon: Operation, todo: true },
  { path: '/admin/items', label: '商品管理', desc: '全状态检索与处置', icon: Goods, todo: false },
  { path: '/admin/orders', label: '面交单管理', desc: '成交与异常排查', icon: List, todo: false },
  { path: '/admin/users', label: '用户管理', desc: '封禁 / 信用分', icon: User, todo: false },
  { path: '/admin/logs', label: '操作日志', desc: '审计留痕', icon: Clock, todo: false },
]

/** 子路由高亮：/admin 精确匹配，其余前缀匹配 */
const activeMenu = computed(() => {
  const path = route.path
  if (path === '/admin' || path === '/admin/') return '/admin'
  // 长的路径优先，避免 /admin/items 被更短的前缀抢走
  const hit = NAV.filter((n) => n.path !== '/admin')
    .sort((a, b) => b.path.length - a.path.length)
    .find((n) => path.startsWith(n.path))
  return hit ? hit.path : '/admin'
})

const todoCount = computed(() => adminStore.todoCount)

/** 菜单角标：上架审核用自己的待审数，其余用概览的待办数 */
function badgeOf(item: (typeof NAV)[number]): number {
  if (item.badge === 'review') return reviewPending.value
  return item.todo ? todoCount.value : 0
}

async function loadReviewPending(): Promise<void> {
  try {
    const stats = await fetchReviewStats()
    reviewPending.value = stats?.pending ?? 0
  } catch {
    // 拿不到就不显示角标，不影响后台其它功能
  }
}

onMounted(() => {
  // 概览页也会调 load()，store 内部有复用窗口，同时挂载只会打一次接口
  adminStore.load()
  loadReviewPending()
})

// 离开管理台时把还在的气泡收掉：否则用户点完"已审核"马上跳到别的页面，
// 提示会跟着飘过去（它挂在 body 上，不随路由销毁）
onUnmounted(() => {
  closeAllTips()
})

/** 手动刷新待办：运营处置完顺手点一下，不用等缓存 */
async function reloadTodo(): Promise<void> {
  await Promise.all([adminStore.refresh(), loadReviewPending()])
  showTip('待办已刷新', 'success')
}
</script>

<template>
  <div class="page admin-page admin">
    <div class="page-head">
      <div>
        <h2 class="page-title">运营后台</h2>
        <p class="page-subtitle">
          当前账号 {{ userStore.nickname }} · 所有写操作都会写入审计日志（谁在何时改了什么）
        </p>
      </div>
      <div class="admin__head-right">
        <!-- 待办徽标：只在真有待办时出现，不再常挂一个"暂无待办"的绿标签占位置 -->
        <el-tag v-if="todoCount > 0" type="warning" effect="dark" round>{{ todoCount }} 项待办</el-tag>
      </div>
    </div>

    <div class="admin__body">
      <aside class="admin__side">
        <el-menu :default-active="activeMenu" router class="admin__menu">
          <el-menu-item v-for="item in NAV" :key="item.path" :index="item.path">
            <el-icon class="admin__nav-icon"><component :is="item.icon" /></el-icon>
            <div class="admin__nav">
              <span class="admin__nav-label">{{ item.label }}</span>
              <span class="admin__nav-desc">{{ item.desc }}</span>
            </div>
            <span v-if="badgeOf(item) > 0" class="admin__badge">{{ badgeOf(item) }}</span>
          </el-menu-item>
        </el-menu>

        <!-- 待办数字已经在菜单项的角标上（如"上架审核 4"），这里再做一块大数字
             属于重复信息，而且那块底色是侧栏里最重的色块 —— 收敛成一个小刷新入口 -->
        <div class="admin__side-foot">
          <el-button
            text
            size="small"
            :loading="adminStore.loading"
            class="admin__side-foot-refresh"
            @click="reloadTodo"
          >
            刷新待办
          </el-button>
        </div>
      </aside>

      <section class="admin__main">
        <router-view />
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.admin {
  // 管理端列多，比学生端页面宽一些（.page 默认 1240px）
  max-width: 1440px;

  &__head-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__body {
    display: grid;
    grid-template-columns: 200px minmax(0, 1fr);
    gap: 16px;
    align-items: start;
  }

  &__side {
    position: sticky;
    top: 84px;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    background: var(--surface);
    box-shadow: var(--shadow-sm);
  }

  &__menu {
    border-right: none;
  }

  &__nav-icon {
    margin-right: 10px;
    color: var(--text-3);
    font-size: 16px;
  }

  &__nav {
    display: flex;
    flex: 1;
    flex-direction: column;
    line-height: 1.35;
  }

  &__nav-label {
    font-size: 14px;
    font-weight: 600;
  }

  &__nav-desc {
    color: var(--text-3);
    font-size: 11px;
  }

  &__badge {
    min-width: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: var(--accent-500);
    color: #fff;
    font-size: 11px;
    font-weight: 600;
    line-height: 18px;
    text-align: center;
  }

  &__side-foot {
    padding: 8px 16px;
    border-top: 1px solid var(--line);
    text-align: center;
  }

  &__side-foot-refresh {
    color: var(--text-3);
    font-size: 12px;

    // 中性 hover：侧栏里不再出现绿色
    &:hover {
      color: var(--text-1);
    }
  }

  &__main {
    min-width: 0;
  }
}

:deep(.el-menu-item) {
  height: auto;
  padding: 10px 14px;
  line-height: 1.35;
  // 侧栏是"工具导航"，不是内容区：默认中性，当前项只用**灰底 + 深色文字 + 左侧竖条**
  // 表示。原来用品牌绿底 + 绿图标 + 绿字，一列下来绿得很吵。
  border-left: 3px solid transparent;
  border-radius: 0;
  color: var(--text-2);
}

:deep(.el-menu-item.is-active) {
  // 用极浅的灰 + 一根深色竖条区分当前项；不再上绿色
  background: var(--surface-2);
  border-left-color: var(--text-1);
  color: var(--text-1);

  .admin__nav-label {
    font-weight: 700;
  }

  .admin__nav-icon {
    color: var(--text-1);
  }
}

:deep(.el-menu-item:hover) {
  background: var(--surface-2);
  color: var(--text-1);
}

@media (max-width: 900px) {
  .admin__body {
    grid-template-columns: 1fr;
  }

  .admin__side {
    position: static;
  }
}
</style>
