import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '../shell/AppShell.vue'
import MonitoringView from '../views/MonitoringView.vue'
import NotesView from '../views/NotesView.vue'
import ProfileView from '../views/ProfileView.vue'
import OpsView from '../views/OpsView.vue'
import SettingsView from '../views/SettingsView.vue'
import PromptsView from '../views/PromptsView.vue'
import WorkspaceView from '../views/WorkspaceView.vue'
import McpView from '../views/McpView.vue'
import IntentTreeView from '../views/IntentTreeView.vue'
import ChatView from '../views/ChatView.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
import IntentListView from '../views/IntentListView.vue'
import IntentEditView from '../views/IntentEditView.vue'
import RagTraceDetailView from '../views/RagTraceDetailView.vue'
import ModelProvidersView from '../views/ModelProvidersView.vue'

const routes = [
  {
    path: '/',
    component: AppShell,
    children: [
      {
        path: '',
        redirect: '/chat'
      },
      {
        path: 'interview',
        redirect: '/chat'
      },
      {
        path: 'interview.html',
        redirect: '/chat'
      },
      {
        path: 'monitoring',
        name: 'monitoring',
        component: MonitoringView,
        alias: ['monitoring.html']
      },
      {
        path: 'notes',
        name: 'notes',
        component: NotesView,
        alias: ['knowledge', 'knowledge.html', 'notes.html']
      },
      {
        path: 'coding',
        redirect: '/chat'
      },
      {
        path: 'practice',
        redirect: '/chat'
      },
      {
        path: 'practice.html',
        redirect: '/chat'
      },
      {
        path: 'coding.html',
        redirect: '/chat'
      },
      {
        path: 'profile',
        name: 'profile',
        component: ProfileView,
        alias: ['profile.html']
      },
      {
        path: 'ops',
        name: 'ops',
        component: OpsView,
        alias: ['ops.html']
      },
      {
        path: 'ops/:traceId',
        name: 'rag-trace-detail',
        component: RagTraceDetailView
      },
      {
        path: 'model-providers',
        name: 'model-providers',
        component: ModelProvidersView,
        alias: ['model-providers.html']
      },
      {
        path: 'settings',
        name: 'settings',
        component: SettingsView,
        alias: ['settings.html']
      },
      {
        path: 'prompts',
        name: 'prompts',
        component: PromptsView,
        alias: ['prompts.html']
      },
      {
        path: 'workspace',
        name: 'workspace',
        component: WorkspaceView,
        alias: ['workspace.html']
      },
      {
        path: 'mcp',
        name: 'mcp',
        component: McpView,
        alias: ['mcp.html']
      },
      {
        path: 'intent-tree',
        name: 'intent-tree',
        component: IntentTreeView,
        alias: ['intent-tree.html']
      },
      {
        path: 'intent-list',
        name: 'intent-list',
        component: IntentListView
      },
      {
        path: 'intent-list/:index/edit',
        name: 'intent-edit',
        component: IntentEditView
      },
      {
        path: 'chat',
        name: 'chat',
        component: ChatView,
        alias: ['chat.html']
      },
      {
        path: ':pathMatch(.*)*',
        name: 'placeholder',
        component: PlaceholderView
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory('/'),
  routes
})

export default router
