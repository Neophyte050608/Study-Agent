export const primaryNavigation = Object.freeze([
  Object.freeze({ id: 'CHAT', title: 'AI 助手', icon: 'chat_bubble', path: '/chat' }),
  Object.freeze({ id: 'KNOWLEDGE', title: '知识库', icon: 'description', path: '/notes' }),
  Object.freeze({ id: 'PROFILE', title: '能力画像', icon: 'analytics', path: '/profile' }),
  Object.freeze({ id: 'ADMIN', title: '管理中心', icon: 'admin_panel_settings', path: '/admin' })
])

export function isPrimaryNavigationActive(id, currentPath) {
  const path = String(currentPath || '/').replace(/\/$/, '') || '/'
  if (id === 'ADMIN') return path === '/admin' || path.startsWith('/admin/')
  return primaryNavigation.some(item => item.id === id && item.path === path)
}

export function resolveSidebarCollapsed(userCollapsed, narrowViewport) {
  return Boolean(userCollapsed || narrowViewport)
}
