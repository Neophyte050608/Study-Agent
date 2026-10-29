import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './styles.css'
import { useTheme } from './composables/useTheme.js'

useTheme()
createApp(App).use(router).mount('#app')
