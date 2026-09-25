import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { getToken } from '@/utils/request'
import { useUserStore } from '@/stores/user'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题 */
    title?: string
    /** 是否需要登录 */
    requiresAuth?: boolean
    /** 是否需要运营人员权限 */
    requiresAdmin?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/',
    component: DefaultLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页' },
      },
      {
        path: 'map',
        name: 'campus-map',
        component: () => import('@/views/MapView.vue'),
        meta: { title: '校内地图' },
      },
      {
        path: 'item/:id',
        name: 'item-detail',
        component: () => import('@/views/ItemDetailView.vue'),
        meta: { title: '商品详情' },
      },
      {
        path: 'publish',
        name: 'publish',
        component: () => import('@/views/PublishView.vue'),
        meta: { title: '发布闲置', requiresAuth: true },
      },
      {
        path: 'publish-list',
        name: 'publish-list',
        component: () => import('@/views/PublishListView.vue'),
        meta: { title: '我的发布', requiresAuth: true },
      },
      {
        path: 'cart',
        name: 'cart',
        component: () => import('@/views/CartView.vue'),
        meta: { title: '购物车', requiresAuth: true },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { title: '我的面交', requiresAuth: true },
      },
      {
        path: 'favorites',
        name: 'favorites',
        component: () => import('@/views/FavoritesView.vue'),
        meta: { title: '我的收藏', requiresAuth: true },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '我的', requiresAuth: true },
      },
      {
        path: 'admin',
        component: () => import('@/views/admin/AdminShell.vue'),
        meta: { title: '运营后台', requiresAuth: true, requiresAdmin: true },
        children: [
          {
            path: '',
            name: 'admin-dashboard',
            component: () => import('@/views/admin/AdminDashboardView.vue'),
            meta: { title: '运营概览', requiresAuth: true, requiresAdmin: true },
          },
          {
            path: 'audit',
            name: 'admin-audit',
            component: () => import('@/views/admin/AdminAuditView.vue'),
            meta: { title: '审核工作台', requiresAuth: true, requiresAdmin: true },
          },
          {
            path: 'items',
            name: 'admin-items',
            component: () => import('@/views/admin/AdminItemsView.vue'),
            meta: { title: '商品管理', requiresAuth: true, requiresAdmin: true },
          },
          {
            path: 'users',
            name: 'admin-users',
            component: () => import('@/views/admin/AdminUsersView.vue'),
            meta: { title: '用户管理', requiresAuth: true, requiresAdmin: true },
          },
          {
            path: 'logs',
            name: 'admin-logs',
            component: () => import('@/views/admin/AdminLogsView.vue'),
            meta: { title: '操作日志', requiresAuth: true, requiresAdmin: true },
          },
        ],
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && getToken()) {
    return { path: '/' }
  }
  if (to.meta.requiresAdmin) {
    const userStore = useUserStore()
    try {
      if (!userStore.profile) {
        await userStore.fetchProfile()
      }
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (!userStore.isAdmin) {
      ElMessage.error('该页面仅运营人员可访问')
      return { path: '/' }
    }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 校园二手` : '校园二手 · 校内交易'
})

export default router
