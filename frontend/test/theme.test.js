import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createThemeOverrides, applyTheme, palettes } from '../src/theme.js'
import { createThemeState, useTheme } from '../src/composables/useTheme.js'

function environment(saved, systemDark = false) {
  const values = new Map(saved ? [['theme', saved]] : [])
  const variables = new Map()
  const classes = new Set()
  return {
    document: { documentElement: {
      style: { setProperty: (key, value) => variables.set(key, value) },
      classList: { toggle: (name, enabled) => enabled ? classes.add(name) : classes.delete(name) }
    } },
    localStorage: { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) },
    matchMedia: () => ({ matches: systemDark }), values, variables, classes
  }
}

test('original semantic colors and Naive controls share one palette', () => {
  assert.equal(createThemeOverrides(false).common.primaryColor, 'rgb(79, 70, 229)')
  assert.equal(createThemeOverrides(true).common.primaryColor, 'rgb(99, 102, 241)')
  assert.equal(createThemeOverrides(true).common.cardColor, 'rgb(15, 23, 42)')
  assert.equal(createThemeOverrides(false).common.borderRadius, '8px')
  assert.equal(createThemeOverrides(true).Card.borderRadius, '12px')
  const env = environment()
  applyTheme(env.document, true)
  for (const [key, value] of Object.entries(palettes.dark)) assert.equal(env.variables.get(`--color-${key}`), value)
  assert.equal(env.classes.has('dark'), true)
})

test('saved preference wins, toggle updates DOM and persists synchronously', () => {
  const env = environment('light', true)
  const state = createThemeState(env)
  assert.equal(state.isDark.value, false)
  state.toggleTheme()
  assert.equal(state.isDark.value, true)
  assert.equal(env.values.get('theme'), 'dark')
  assert.equal(env.classes.has('dark'), true)
  state.isDark.value = false
  assert.equal(env.classes.has('dark'), false)
  assert.equal(env.values.get('theme'), 'light')
})

test('unset or invalid preference uses system color; storage may be blocked', () => {
  for (const saved of [undefined, 'invalid']) assert.equal(createThemeState(environment(saved, true)).isDark.value, true)
  const env = environment(undefined, true)
  Object.defineProperty(env, 'localStorage', { get() { throw new Error('blocked') } })
  const state = createThemeState(env)
  assert.equal(state.isDark.value, true)
  assert.doesNotThrow(() => state.toggleTheme())
  assert.equal(env.classes.has('dark'), false)
})

test('useTheme consumers share the same state and server import is safe', () => {
  assert.equal(useTheme(), useTheme())
  assert.equal(useTheme().isDark, useTheme().isDark)
})
