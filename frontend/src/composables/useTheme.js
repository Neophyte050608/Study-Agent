import { ref, onMounted, watch } from 'vue'

export function useTheme() {
  const isDark = ref(false)

  const updateTheme = (dark) => {
    if (dark) {
      document.documentElement.classList.add('dark')
      localStorage.setItem('theme', 'dark')
    } else {
      document.documentElement.classList.remove('dark')
      localStorage.setItem('theme', 'light')
    }
  }

  const toggleTheme = () => {
    isDark.value = !isDark.value
  }

  onMounted(() => {
    const savedTheme = localStorage.getItem('theme')
    if (savedTheme) {
      isDark.value = savedTheme === 'dark'
    } else {
      isDark.value = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    }
    updateTheme(isDark.value)
  })

  watch(isDark, (newVal) => {
    updateTheme(newVal)
  })

  return {
    isDark,
    toggleTheme
  }
}
