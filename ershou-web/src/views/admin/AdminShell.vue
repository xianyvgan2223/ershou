<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Clock, DataLine, Goods, Operation, User } from '@element-plus/icons-vue'
import { fetchAdminDashboard } from '@/api/admin'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const userStore = useUserStore()

const NAV = [
  { path: '/admin', label: '运营概览', desc: '待办与总量', icon: DataLine, todo: false },
  { path: '/admin/audit', label: '审核工作台', desc: '商品 / 点位', icon: Operation, todo: true },
  { path: '/admin/items', label: '商品管理', desc: '全状态检索与处置', icon: Goods, todo: false },
  { path: '/admin/users', label: '用户管理', desc: '封禁 / 信用分', icon: User, todo: false },
  { path: '/admin/logs', label: '操作日志', desc: '审计留痕', icon: Clock, todo: false },
]

const todoCount = ref(0)

/** 子路由高亮：/admin 精确匹配，其余前缀匹配 */
const activeMenu = computed(() => {
  const path = route.path
  if (path === '/admin' || path === '/admin/') return '/admin'
  const hit = NAV.filter((n) => n.path !== '/admin').find((n) => path.startsWith(n.path))
  return hit ? hit.path : '/admin'
})

/** 侧栏角标 = 待审商品 + 待审点位（点位审核在同一个工作台里） */
async function loadTodo(): Promise<void> {
  try {
    const data = await fetchAdminDashboard()
    todoCount.value = (data.pendingItems ?? 0) + (data.pendingPoints ?? 0)
  } catch {
    todoCount.value = 0
  }
}

onMounted(loadTodo)
</script>

<template>
  <div class="page admin">
    <div class="page-head">
      <div>
        <h2 class="page-title">运营后台</h2>
        <p class="page-subtitle">
          当前账号 {{ userStore.nickname }} · 所有写操作都会写入审计日志（谁在何时改了什么）
        </p>
      </div>
      <div class="admin__head-right">
        <el-tag v-if="todoCount > 0" type="warning" effect="dark" round>{{ todoCount }} 项待办</el-tag>
        <el-tag v-else type="success" effect="plain" round>暂无待办</el-tag>
        <el-tag type="success" effect="plain">运营模式</el-tag>
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
            <span v-if="item.todo && todoCount > 0" class="admin__badge">{{ todoCount }}</span>
          </el-menu-item>
        </el-menu>

        <div class="admin__side-foot" :class="{ 'is-clear': todoCount === 0 }">
          <p class="admin__side-foot-num">{{ todoCount }}</p>
          <p class="admin__side-foot-label">
            {{ todoCount > 0 ? '项待办等你处理' : '暂无待办，辛苦了' }}
          </p>
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
    padding: 12px 16px;
    border-top: 1px solid var(--line);
    background: #fff8f4;
    text-align: center;

    &.is-clear {
      background: var(--brand-50);
    }
  }

  &__side-foot-num {
    color: var(--accent-600);
    font-size: 22px;
    font-weight: 800;
    line-height: 1.1;
  }

  &__side-foot-label {
    color: var(--text-3);
    font-size: 11px;
  }

  &__main {
    min-width: 0;
  }
}

:deep(.el-menu-item) {
  height: auto;
  padding: 10px 14px;
  line-height: 1.35;
}

:deep(.el-menu-item.is-active) {
  background: var(--brand-50);

  .admin__nav-icon {
    color: var(--brand-600);
  }
}

:deep(.el-menu-item:hover) {
  background: var(--surface-2);
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
