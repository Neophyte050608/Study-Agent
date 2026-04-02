import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '../shell/AppShell.vue'
import InterviewView from '../views/InterviewView.vue'
import MonitoringView from '../views/MonitoringView.vue'
import NotesView from '../views/NotesView.vue'
import CodingView from '../views/CodingView.vue'
import ProfileView from '../views/ProfileView.vue'
import OpsView from '../views/OpsView.vue'
import SettingsView from '../views/SettingsView.vue'
import PromptsView from '../views/PromptsView.vue'
import WorkspaceView from '../views/WorkspaceView.vue'
import McpView from '../views/McpView.vue'
import IntentTreeView from '../views/IntentTreeView.vue'
import ChatView from '../views/ChatView.vue'
import PlaceholderView from '../views/PlaceholderView.vue'

const routes = [
  {
    path: '/',
    component: AppShell,
    children: [
      {
        path: '',
        redirect: '/interview'
      },
      {
        path: 'interview',
        name: 'interview',
        component: InterviewView,
        alias: ['interview.html']
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
        name: 'coding',
        component: CodingView,
        alias: ['practice', 'practice.html', 'coding.html']
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
