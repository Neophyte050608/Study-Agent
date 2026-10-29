import { ref, watch } from 'vue'
import { applyTheme } from '../theme.js'

export function createThemeState(environment) {
  let saved
  try { saved = environment?.localStorage?.getItem('theme') } catch { /* Storage can be blocked. */ }
  const preferredDark = saved === 'dark' || (saved !== 'light' && Boolean(environment?.matchMedia?.('(prefers-color-scheme: dark)').matches))
  const isDark = ref(preferredDark)
  const update = dark => {
    applyTheme(environment?.document, dark)
    try { environment?.localStorage?.setItem('theme', dark ? 'dark' : 'light') } catch { /* In-memory theme still works. */ }
  }
  update(isDark.value)
  watch(isDark, update, { flush: 'sync' })
  return { isDark, toggleTheme: () => { isDark.value = !isDark.value } }
}

let sharedTheme
export function useTheme() {
  return sharedTheme ||= createThemeState(typeof window === 'undefined' ? undefined : window)
}
