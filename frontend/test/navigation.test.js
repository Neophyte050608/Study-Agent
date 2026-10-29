import { test } from 'node:test'
import assert from 'node:assert/strict'
import * as navigation from '../src/shell/navigation.js'

const { isPrimaryNavigationActive, primaryNavigation } = navigation

test('primary navigation contains exactly the four stable product entries', () => {
  assert.deepEqual(primaryNavigation.map(({ id, title, path }) => ({ id, title, path })), [
    { id: 'CHAT', title: 'AI 助手', path: '/chat' },
    { id: 'KNOWLEDGE', title: '知识库', path: '/notes' },
    { id: 'PROFILE', title: '能力画像', path: '/profile' },
    { id: 'ADMIN', title: '管理中心', path: '/admin' }
  ])
})

test('all admin descendants activate only the administration entry', () => {
  assert.equal(isPrimaryNavigationActive('ADMIN', '/admin/models'), true)
  assert.equal(isPrimaryNavigationActive('ADMIN', '/admin/agents/intents/2/edit'), true)
  assert.equal(isPrimaryNavigationActive('CHAT', '/admin/rag'), false)
})

test('narrow viewports keep the application shell collapsed', () => {
  assert.equal(typeof navigation.resolveSidebarCollapsed, 'function')
  assert.equal(navigation.resolveSidebarCollapsed(false, true), true)
  assert.equal(navigation.resolveSidebarCollapsed(true, true), true)
  assert.equal(navigation.resolveSidebarCollapsed(false, false), false)
  assert.equal(navigation.resolveSidebarCollapsed(true, false), true)
})
