<template>
  <div class="bg-surface text-on-surface min-h-screen relative">
    <header class="fixed top-0 right-0 h-16 bg-white/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
    <div class="flex items-center gap-4">
      <h1 class="text-xl font-bold tracking-tight text-indigo-700">意图树管理 <span class="text-slate-500 font-medium text-sm ml-2">/ 树结构浏览、节点定位与全局路由参数配置</span></h1>
    </div>
    <div class="flex items-center gap-2">
      <RouterLink to="/intent-list" class="px-3 py-1.5 text-xs rounded border border-slate-200 text-slate-600 hover:bg-slate-50">
        切到列表页
      </RouterLink>
      <button @click="reload" :disabled="loading || saving" class="px-3 py-1.5 text-xs rounded border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-50">
        刷新
      </button>
      <button @click="saveAll" :disabled="loading || saving" class="px-3 py-1.5 text-xs rounded bg-[#0057c2] text-white hover:opacity-90 disabled:opacity-50">
        保存全局配置
      </button>
    </div>
  </header>

  <main class="min-h-screen bg-slate-50 pt-24 pb-10 px-8 space-y-6 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
    <div class="grid grid-cols-4 gap-4">
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#0057c2]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">系统状态</div>
        <div class="text-2xl font-bold text-[#1a1c1c]">{{ stats.enabled ? '已启用' : '已禁用' }}</div>
      </div>
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#425d97]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">叶子意图数</div>
        <div class="text-2xl font-bold text-[#1a1c1c]">{{ stats.leafIntentCount ?? flatLeafs.length }}</div>
      </div>
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#9e3d00]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">槽位精炼样例</div>
        <div class="text-2xl font-bold text-[#1a1c1c]">{{ stats.slotRefineCaseCount ?? (config.slotRefineCases || []).length }}</div>
      </div>
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#ba1a1a]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">置信度阈值</div>
        <div class="text-2xl font-bold text-[#1a1c1c]">{{ Number(config.confidenceThreshold ?? 0.65).toFixed(2) }}</div>
      </div>
    </div>

    <div class="grid gap-6 lg:grid-cols-[1.3fr_1fr]">
      <section class="bg-white rounded shadow-sm p-5">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-sm font-semibold text-slate-900">意图路径树</h2>
          <span class="text-xs text-slate-500">{{ hint }}</span>
        </div>
        <div class="space-y-1 max-h-[560px] overflow-auto">
          <template v-if="treeNodes.length === 0">
            <div class="text-center text-sm text-slate-500 py-10">暂无意图节点，请先新增叶子意图</div>
          </template>
          <template v-else>
            <TreeNode
              v-for="node in treeNodes"
              :key="node.key"
              :node="node"
              :depth="0"
              :expanded-map="expandedMap"
              :selected-key="selectedKey"
              @toggle="toggleExpand"
              @select="selectNode"
            />
          </template>
        </div>
      </section>

      <section class="bg-white rounded shadow-sm p-5">
        <h2 class="text-sm font-semibold text-slate-900 mb-4">节点详情</h2>
        <template v-if="!selectedLeaf">
          <div class="text-center text-sm text-slate-500 py-10">请选择左侧节点</div>
        </template>
        <template v-else>
          <div class="space-y-3 text-sm">
            <div>
              <p class="text-xs text-slate-500">Intent ID</p>
              <p class="font-semibold text-slate-900">{{ selectedLeaf.intentId }}</p>
            </div>
            <div>
              <p class="text-xs text-slate-500">名称</p>
              <p class="text-slate-900">{{ selectedLeaf.name || '-' }}</p>
            </div>
            <div>
              <p class="text-xs text-slate-500">任务类型</p>
              <p class="text-slate-900">{{ selectedLeaf.taskType || '-' }}</p>
            </div>
            <div>
              <p class="text-xs text-slate-500">路径</p>
              <p class="text-slate-900">{{ selectedLeaf.pathText }}</p>
            </div>
            <div class="flex flex-wrap gap-2 pt-2">
              <button class="px-3 py-1.5 text-xs rounded border border-slate-200 hover:bg-slate-50" @click="openEditSelected">
                编辑节点
              </button>
              <button class="px-3 py-1.5 text-xs rounded border border-slate-200 hover:bg-slate-50" @click="createChild">
                新增子节点
              </button>
              <button class="px-3 py-1.5 text-xs rounded border border-rose-200 text-rose-700 bg-rose-50 hover:bg-rose-100" @click="offlineSelected">
                下线节点
              </button>
            </div>
            <p class="text-xs text-slate-400">提示：编辑将跳转独立编辑页；下线会立即保存配置。</p>
          </div>
        </template>
      </section>
    </div>

    <section class="bg-white rounded shadow-sm p-5 space-y-4">
      <h2 class="text-sm font-semibold text-slate-900">路由参数</h2>
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="text-xs text-slate-600">意图引擎开关</label>
          <div class="mt-2">
            <input v-model="config.enabled" type="checkbox" class="w-10 h-5 bg-[#c1c6d7] rounded-full appearance-none checked:bg-[#0057c2] relative before:content-[''] before:absolute before:w-4 before:h-4 before:bg-white before:rounded-full before:top-0.5 before:left-0.5 checked:before:translate-x-5 before:transition-all cursor-pointer">
          </div>
        </div>
        <div>
          <label class="text-xs text-slate-600">置信度阈值</label>
          <input v-model.number="config.confidenceThreshold" type="range" min="0" max="1" step="0.05" class="w-full accent-[#0057c2]">
        </div>
        <div>
          <label class="text-xs text-slate-600">最小置信差</label>
          <input v-model.number="config.minGap" type="number" step="0.01" class="w-full mt-1 bg-slate-100 rounded px-3 py-2 text-xs">
        </div>
        <div>
          <label class="text-xs text-slate-600">歧义比例</label>
          <input v-model.number="config.ambiguityRatio" type="number" step="0.01" class="w-full mt-1 bg-slate-100 rounded px-3 py-2 text-xs">
        </div>
      </div>
    </section>
  </main>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useIntentTreeAdmin } from '../composables/useIntentTreeAdmin'
import { saveIntentTreeConfig } from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const router = useRouter()
const {
  loading,
  saving,
  hint,
  config,
  stats,
  flatLeafs,
  reload,
  toggleBatchEnabled
} = useIntentTreeAdmin()

const expandedMap = ref({})
const selectedKey = ref('')

const buildTree = (leafs) => {
  const roots = []
  const nodeMap = new Map()
  leafs.forEach((leaf) => {
    const segments = leaf.pathSegments.length > 0 ? leaf.pathSegments : [leaf.intentId || leaf.name || `leaf-${leaf.index}`]
    let parentKey = ''
    segments.forEach((segment, depth) => {
      const key = `${parentKey}/${segment}`
      if (!nodeMap.has(key)) {
        const node = {
          key,
          label: segment,
          depth,
          leaf: null,
          children: []
        }
        nodeMap.set(key, node)
        if (!parentKey) {
          roots.push(node)
        } else {
          const parent = nodeMap.get(parentKey)
          parent.children.push(node)
        }
      }
      parentKey = key
    })
    const leafNode = nodeMap.get(parentKey)
    leafNode.leaf = leaf
  })
  return roots
}

const treeNodes = computed(() => buildTree(flatLeafs.value))

const selectedLeaf = computed(() => {
  const findNode = (nodes) => {
    for (const node of nodes) {
      if (node.key === selectedKey.value && node.leaf) return node.leaf
      if (node.children.length > 0) {
        const found = findNode(node.children)
        if (found) return found
      }
    }
    return null
  }
  return findNode(treeNodes.value)
})

const expandPath = (path) => {
  if (!path) return
  const segments = String(path).replace(/>/g, '/').split('/').map((item) => item.trim()).filter(Boolean)
  let key = ''
  segments.forEach((segment) => {
    key = `${key}/${segment}`
    expandedMap.value[key] = true
  })
}

const selectNode = (node) => {
  selectedKey.value = node.key
}

const toggleExpand = (key) => {
  expandedMap.value[key] = !expandedMap.value[key]
}

const saveAll = async () => {
  saving.value = true
  hint.value = '正在保存全局配置...'
  try {
    await saveIntentTreeConfig(config.value)
    await reload()
  } finally {
    saving.value = false
  }
}

const openEditSelected = () => {
  if (!selectedLeaf.value) return
  router.push(`/intent-list/${selectedLeaf.value.index}/edit?from=${encodeURIComponent(route.fullPath)}`)
}

const createChild = () => {
  if (!selectedLeaf.value) return
  router.push(`/intent-list/new/edit?parentIndex=${selectedLeaf.value.index}&from=${encodeURIComponent(route.fullPath)}`)
}

const offlineSelected = async () => {
  if (!selectedLeaf.value) return
  const ok = window.confirm(`确认下线节点 [${selectedLeaf.value.intentId || selectedLeaf.value.name}] 吗？`)
  if (!ok) return
  await toggleBatchEnabled(false, [selectedLeaf.value.index])
  await reload()
}

const TreeNode = defineComponent({
  props: {
    node: { type: Object, required: true },
    depth: { type: Number, required: true },
    expandedMap: { type: Object, required: true },
    selectedKey: { type: String, required: true }
  },
  emits: ['toggle', 'select'],
  setup(props, { emit }) {
    return () => {
      const node = props.node
      const hasChildren = node.children.length > 0
      const expanded = props.expandedMap[node.key] !== false
      const selected = props.selectedKey === node.key
      const baseClass = selected ? 'bg-slate-100 text-slate-900' : 'hover:bg-slate-50 text-slate-700'
      return h('div', {}, [
        h('div', {
          class: `flex items-center justify-between rounded px-2 py-1 cursor-pointer ${baseClass}`,
          style: { paddingLeft: `${props.depth * 14 + 8}px` },
          onClick: () => emit('select', node)
        }, [
          h('div', { class: 'flex items-center gap-2' }, [
            hasChildren
              ? h('button', {
                  class: 'text-xs text-slate-500',
                  onClick: (event) => {
                    event.stopPropagation()
                    emit('toggle', node.key)
                  }
                }, expanded ? '▾' : '▸')
              : h('span', { class: 'w-3' }, ''),
            h('span', { class: 'text-sm' }, node.label)
          ]),
          node.leaf
            ? h('span', { class: `text-[10px] px-2 py-0.5 rounded ${node.leaf.enabled ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600'}` }, node.leaf.enabled ? '启用' : '下线')
            : null
        ]),
        hasChildren && expanded
          ? h('div', {}, node.children.map((child) =>
              h(TreeNode, {
                key: child.key,
                node: child,
                depth: props.depth + 1,
                expandedMap: props.expandedMap,
                selectedKey: props.selectedKey,
                onToggle: (key) => emit('toggle', key),
                onSelect: (selectedNode) => emit('select', selectedNode)
              })
            ))
          : null
      ])
    }
  }
})

onMounted(async () => {
  await reload()
  const focusIntentCode = route.query.intentCode ? String(route.query.intentCode) : ''
  if (focusIntentCode) {
    const matchedLeaf = flatLeafs.value.find((item) => item.intentId === focusIntentCode)
    if (matchedLeaf) {
      expandPath(matchedLeaf.path)
      const focusSegments = matchedLeaf.pathSegments.length > 0 ? matchedLeaf.pathSegments : [matchedLeaf.intentId]
      selectedKey.value = `/${focusSegments.join('/')}`
    }
  }
})
</script>
