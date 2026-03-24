import { httpGet } from './http'

export async function loadSidebarMenus() {
  const menus = await httpGet('/api/settings/menu')
  return menus
    .filter((item) => item.position === 'SIDEBAR')
    .sort((a, b) => a.orderIndex - b.orderIndex)
}
