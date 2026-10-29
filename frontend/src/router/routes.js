export const routes = [{
  path: '/',
  component: () => import('../shell/AppShell.vue'),
  children: [
    { path: '', redirect: '/chat' },
    { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue') },
    { path: 'notes', name: 'notes', component: () => import('../views/NotesView.vue') },
    { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue') },
    { path: 'learning/plan-preview', name: 'learning-plan-preview', component: () => import('../views/LearningPlanPreviewView.vue') },
    {
      path: 'admin',
      component: () => import('../views/admin/AdminLayout.vue'),
      redirect: '/admin/models',
      children: [
        { path: 'models', name: 'admin-models', component: () => import('../views/admin/models/ModelsAdminView.vue') },
        { path: 'rag', name: 'admin-rag', component: () => import('../views/admin/rag/RagAdminView.vue') },
        { path: 'rag/traces/:traceId', name: 'admin-rag-trace', component: () => import('../views/admin/rag/RagTraceDetailView.vue') },
        {
          path: 'agents',
          component: () => import('../views/admin/agents/AgentAdminLayout.vue'),
          redirect: '/admin/agents/prompts',
          children: [
            { path: 'prompts', name: 'admin-agent-prompts', component: () => import('../views/admin/agents/prompts/PromptsAdminView.vue') },
            { path: 'intents', name: 'admin-agent-intents', component: () => import('../views/admin/agents/intents/IntentsAdminView.vue') },
            { path: 'intents/:index/edit', name: 'admin-agent-intent-edit', component: () => import('../views/admin/agents/intents/IntentEditAdminView.vue') },
            { path: 'mcp', name: 'admin-agent-mcp', component: () => import('../views/admin/agents/mcp/McpAdminView.vue') }
          ]
        }
      ]
    },
    { path: ':pathMatch(.*)*', name: 'placeholder', component: () => import('../views/PlaceholderView.vue') }
  ]
}]
