import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  agentAdminNavigation,
  isAgentSectionActive
} from '../src/views/admin/agents/agentAdminNavigation.js'

test('agent administration exposes exactly three expert sections', () => {
  assert.deepEqual(agentAdminNavigation.map(item => item.path), [
    '/admin/agents/prompts',
    '/admin/agents/intents',
    '/admin/agents/mcp'
  ])
})

test('intent edit deep links remain owned by the intent section', () => {
  assert.equal(
    isAgentSectionActive('/admin/agents/intents/3/edit', '/admin/agents/intents'),
    true
  )
  assert.equal(
    isAgentSectionActive('/admin/agents/mcp', '/admin/agents/intents'),
    false
  )
})

test('agent administration APIs have one domain owner without an aggregate facade', async () => {
  const { readFile, access } = await import('node:fs/promises')
  const { fileURLToPath } = await import('node:url')
  const root = new URL('../src/api/', import.meta.url)
  const promptApi = await readFile(new URL('promptAdmin.js', root), 'utf8')
  const intentApi = await readFile(new URL('intentAdmin.js', root), 'utf8')
  const mcpApi = await readFile(new URL('mcpAdmin.js', root), 'utf8')

  assert.match(promptApi, /export function loadPromptTemplates/)
  assert.match(intentApi, /export function loadIntentTreeConfig/)
  assert.match(mcpApi, /export function loadMcpCapabilities/)
  await assert.rejects(access(fileURLToPath(new URL('admin.js', root))))
})

test('intent tree and list share one page-owned configuration state', async () => {
  const { readFile } = await import('node:fs/promises')
  const base = new URL('../src/views/admin/agents/intents/', import.meta.url)
  const owner = await readFile(new URL('IntentsAdminView.vue', base), 'utf8')
  const tree = await readFile(new URL('IntentTreePanel.vue', base), 'utf8')
  const list = await readFile(new URL('IntentListPanel.vue', base), 'utf8')

  assert.match(owner, /useIntentTreeAdmin\(/)
  assert.doesNotMatch(tree, /useIntentTreeAdmin\(/)
  assert.doesNotMatch(list, /useIntentTreeAdmin\(/)
})

test('prompt, intent, and MCP leaf pages expose visible failure recovery', async () => {
  const { readFile } = await import('node:fs/promises')
  const base = new URL('../src/views/admin/agents/', import.meta.url)
  const files = [
    'prompts/PromptsAdminView.vue',
    'intents/IntentsAdminView.vue',
    'mcp/McpAdminView.vue'
  ]
  for (const file of files) {
    const source = await readFile(new URL(file, base), 'utf8')
    assert.match(source, /v-if="error"/)
    assert.match(source, />重试</)
  }
})

test('intent editor has one return owner and recovers from load failure', async () => {
  const { readFile } = await import('node:fs/promises')
  const editor = await readFile(
    new URL('../src/views/admin/agents/intents/IntentEditAdminView.vue', import.meta.url),
    'utf8'
  )

  assert.match(editor, /const returnTo = '\/admin\/agents\/intents'/)
  assert.doesNotMatch(editor, /route\.query\.from/)
  assert.match(editor, /v-if="error"/)
  assert.match(editor, />重试</)
})
