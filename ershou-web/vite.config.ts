import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import ElementPlus from 'unplugin-element-plus/vite'

// 后端地址：默认本机 8080；需要同时跑第二个实例（例如 8081）时用环境变量覆盖，
// 例如： $env:VITE_PROXY_TARGET='http://127.0.0.1:8081'; npm run dev
const PROXY_TARGET = process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080'

// 校园二手交易平台 · 学生端构建配置
export default defineConfig({
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
      // 私聊 WebSocket：ws:true 让 Vite 把升级请求透传给后端（生产由 Nginx 同源反代）
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
})
