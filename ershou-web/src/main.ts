import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/main.scss'
import '@/styles/admin.scss'
import '@/styles/theme.scss'
import { initTheme } from '@/composables/useTheme'

// 必须在 mount 之前恢复主题，否则夜间模式会先闪一下白底
initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
