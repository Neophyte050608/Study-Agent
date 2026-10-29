import { test } from 'node:test'
import assert from 'node:assert/strict'
import { routes } from '../src/router/routes.js'

function collectPaths(records, parent = '') {
  return records.flatMap(route => {
    const path = route.path.startsWith('/')
      ? route.path
      : `${parent.replace(/\/$/, '')}/${route.path}`.replace(/\/+/g, '/')
    return [path, ...collectPaths(route.children || [], path)]
  })
}

test('router exposes current product and nested admin paths', () => {
  const paths = collectPaths(routes)
  for (const path of [
    '/chat', '/notes', '/profile', '/admin', '/admin/models', '/admin/rag',
    '/admin/rag/traces/:traceId', '/admin/agents/prompts',
    '/admin/agents/intents', '/admin/agents/intents/:index/edit', '/admin/agents/mcp'
  ]) assert.ok(paths.includes(path), `missing ${path}`)
})

test('obsolete product and management routes are not registered', () => {
  const paths = collectPaths(routes)
  for (const path of [
    '/interview', '/coding', '/practice', '/monitoring', '/model-providers', '/ops',
    '/rag-dashboard', '/settings', '/workspace', '/mcp', '/intent-tree',
    '/intent-list', '/prompts'
  ]) assert.equal(paths.includes(path), false, `obsolete route ${path}`)
})

test('unknown paths do not advertise retired compatibility entries', async () => {
  const { readFile } = await import('node:fs/promises')
  const source = await readFile(new URL('../src/views/PlaceholderView.vue', import.meta.url), 'utf8')

  assert.match(source, /页面不存在/)
  assert.doesNotMatch(source, /迁移|原页面入口|兼容/)
})
