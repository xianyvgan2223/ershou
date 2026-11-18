import { computed, ref } from 'vue'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'ershou:theme'
const theme = ref<Theme>('light')

/** 把主题落到 <html>：element-plus 的暗色变量与项目自定义变量都依赖 .dark */
function apply(value: Theme): void {
  theme.value = value
  const root = document.documentElement
  root.classList.toggle('dark', value === 'dark')
  root.dataset.theme = value
  root.style.colorScheme = value
}

/** 在 mount 之前调用，避免首屏先闪一下白色再切暗色 */
export function initTheme(): void {
  let saved: string | null = null
  try {
    saved = localStorage.getItem(STORAGE_KEY)
  } catch {
    // 隐私模式等场景读不到 localStorage 时按默认白底处理
  }
  apply(saved === 'dark' ? 'dark' : 'light')
}

export function useTheme() {
  const isDark = computed(() => theme.value === 'dark')

  function setTheme(value: Theme): void {
    apply(value)
    try {
      localStorage.setItem(STORAGE_KEY, value)
    } catch {
      // 写不进去不影响本次主题切换
    }
  }

  function toggleTheme(): void {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, isDark, setTheme, toggleTheme }
}
