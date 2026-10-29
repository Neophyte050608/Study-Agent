export const agentAdminNavigation = Object.freeze([
  { title: '提示词', path: '/admin/agents/prompts' },
  { title: '意图路由', path: '/admin/agents/intents' },
  { title: 'MCP 能力', path: '/admin/agents/mcp' }
])

export function isAgentSectionActive(currentPath, sectionPath) {
  const current = String(currentPath || '')
  const section = String(sectionPath || '')
  return Boolean(section) && (current === section || current.startsWith(`${section}/`))
}
