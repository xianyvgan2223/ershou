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
        // 平台严选（官方代卖专区）：商品与代卖点都是公开可读的，**不需要登录**；
        // 领券 / 抢购 / 下单这类动作由页面自己检查登录后跳 `/login?redirect=/platform`。
        path: 'platform',
        name: 'platform',
        component: () => import('@/views/PlatformView.vue'),
        meta: { title: '平台严选' },
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
        // 用户主页：未登录也能看（后端接口公开，不含联系方式）
        path: 'user/:id',
        name: 'user-profile',
        component: () => import('@/views/UserProfileView.vue'),
        meta: { title: '用户主页' },
      },
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '消息', requiresAuth: true },
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
        path: 'blocked',
        name: 'blocked',
        component: () => import('@/views/ProfileBlockView.vue'),
        meta: { title: '我的黑名单', requiresAuth: true },
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
            // 上架审核：实时接收新上架/改名的商品，逐条确认（与审核工作台是两条队列）
            path: 'review',
            name: 'admin-review',
            component: () => import('@/views/admin/AdminReviewView.vue'),
            meta: { title: '上架审核', requiresAuth: true, requiresAdmin: true },
          },
          {
            path: 'items',
            name: 'admin-items',
            component: () => import('@/views/admin/AdminItemsView.vue'),
            meta: { title: '商品管理', requiresAuth: true, requiresAdmin: true },
          },
          {
            path: 'orders',
            name: 'admin-orders',
            component: () => import('@/views/admin/AdminOrdersView.vue'),
            meta: { title: '面交单管理', requiresAuth: true, requiresAdmin: true },
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
          {
            // 平台代卖点管理入口已迁到独立运营台（第 3 轮），旧地址保留跳转兼容
            path: 'platform-points',
            redirect: '/console/platform/points',
          },
        ],
      },
    ],
  },
  {
    // 平台运营台：独立全屏 Shell（不套 DefaultLayout / AdminShell），第 3 轮新增。
    // 父级 meta 会被子路由继承（Vue Router 4 的 to.meta 合并），现有守卫无需改。
    path: '/console/platform',
    component: () => import('@/views/console/PlatformConsoleShell.vue'),
    meta: { title: '平台运营台', requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'console-overview',
        component: () => import('@/views/console/PlatformConsoleOverview.vue'),
        meta: { title: '运营概览' },
      },
      {
        path: 'items',
        name: 'console-items',
        component: () => import('@/views/console/PlatformConsoleItems.vue'),
        meta: { title: '平台商品' },
      },
      {
        path: 'points',
        name: 'console-points',
        component: () => import('@/views/console/PlatformConsolePoints.vue'),
        meta: { title: '代卖点' },
      },
      {
        path: 'coupons',
        name: 'console-coupons',
        component: () => import('@/views/console/PlatformConsoleCoupons.vue'),
        meta: { title: '优惠券' },
      },
      {
        path: 'flash-sales',
        name: 'console-flash',
        component: () => import('@/views/console/PlatformConsoleFlash.vue'),
        meta: { title: '秒杀场次' },
      },
      {
        path: 'orders',
        name: 'console-orders',
        component: () => import('@/views/console/PlatformConsoleOrders.vue'),
        meta: { title: '平台订单' },
      },
      {
        path: 'cache',
        name: 'console-cache',
        component: () => import('@/views/console/PlatformConsoleCache.vue'),
        meta: { title: '缓存运维' },
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
