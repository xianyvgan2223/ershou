import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import ElementPlus from 'unplugin-element-plus/vite'

/**
 * 后端地址（Vite 开发服务器 `/api`、`/ws` 的代理目标）。
 *
 * 默认仍是本机旧单体 `8080`，老流程不变；切到 Spring Cloud 网关（8088）做微服务演示时二选一：
 *   1) `npm run dev -- --mode cloud` —— 读取本目录 `.env.cloud` 里的 `VITE_PROXY_TARGET=http://127.0.0.1:8088`
 *   2) `$env:VITE_PROXY_TARGET='http://127.0.0.1:8088'; npm run dev`（真实环境变量优先级最高）
 * 优先级：真实环境变量 > `ershou-web/.env.[mode]` > 默认 8080。
 *
 * 说明：`vite build` 产物本身不经过代理（生产由 Nginx 同源反代 `/api`），
 * 所以构建时这个值不影响产物；它只对 `npm run dev` 本地联调有效。
 */
const DEFAULT_PROXY_TARGET = 'http://127.0.0.1:8080'
/** ershou-web 目录（用于单独加载本目录下的 .env / .env.[mode]，与应用变量无关） */
const WEB_ROOT = fileURLToPath(new URL('.', import.meta.url))

// 校园二手交易平台 · 学生端构建配置
export default defineConfig(({ mode }) => {
  // 只从 ershou-web 目录加载 VITE_ 变量来取代理目标；
  // 应用运行时变量仍由 envDir: '..'（仓库根目录 .env）提供，二者互不影响。
  const localEnv = loadEnv(mode, WEB_ROOT, 'VITE_')
  const PROXY_TARGET =
    process.env.VITE_PROXY_TARGET || localEnv.VITE_PROXY_TARGET || DEFAULT_PROXY_TARGET

  return {
    // 高德等密钥统一放仓库根目录的 .env（已被 .gitignore 忽略），前端只读 VITE_ 前缀的变量。
    // 注意：vite 仍会加载 ershou-web/.env*，两者同名时以 .env.local 优先。
    envDir: '..',
    plugins: [
      vue(),
      // 自动引入 vue / vue-router / pinia 的组合式 API 与 Element Plus 的函数式组件（ElMessage 等）
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver({ importStyle: 'css' })],
        dts: 'src/types/auto-imports.d.ts',
      }),
      // Element Plus 组件按需注册（模板里直接写 <el-button> 即可）
      Components({
        dirs: [],
        resolvers: [ElementPlusResolver({ importStyle: 'css' })],
        dts: 'src/types/components.d.ts',
      }),
      // 处理手写的 import { ElMessage } from 'element-plus'，自动补上样式
      ElementPlus({ useSource: false }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      open: false,
      proxy: {
        '/api': {
          target: PROXY_TARGET,
          changeOrigin: true,
        },
        // 私聊 WebSocket：ws:true 让 Vite 把升级请求透传给后端（生产由 Nginx 同源反代）。
        // ⚠️ Spring Cloud 阶段 `/ws/**` 仍由网关反代到旧单体 8080，所以 ws 目标跟随同一个地址。
        '/ws': {
          target: PROXY_TARGET.replace(/^http/, 'ws'),
          ws: true,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      chunkSizeWarningLimit: 2000,
    },
  }
})
