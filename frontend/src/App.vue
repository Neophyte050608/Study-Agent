<script setup>
import { computed } from 'vue'
import { NConfigProvider, NMessageProvider, NDialogProvider, darkTheme, zhCN, dateZhCN } from 'naive-ui'
import { useTheme } from './composables/useTheme.js'
import { createThemeOverrides } from './theme.js'

const { isDark } = useTheme()
const overrides = computed(() => createThemeOverrides(isDark.value))
</script>

<template>
  <NConfigProvider abstract preflight-style-disabled :theme="isDark ? darkTheme : null" :theme-overrides="overrides" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider>
      <NDialogProvider>
        <RouterView />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
