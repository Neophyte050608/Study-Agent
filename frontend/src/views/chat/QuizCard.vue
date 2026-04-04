<template>
  <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm overflow-hidden my-4">
    <!-- Header -->
    <div class="px-5 py-3 border-b border-slate-100 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="material-symbols-outlined text-indigo-500">quiz</span>
        <span class="font-bold text-slate-800 dark:text-slate-200">{{ payload.topic }} 答题卡</span>
        <span class="text-xs text-slate-400 font-normal px-2 py-0.5 bg-slate-200 dark:bg-slate-700 rounded-full">{{ payload.difficulty }}</span>
      </div>
      <div v-if="!submitted" class="text-sm font-medium text-slate-500">
        进度: {{ currentIndex + 1 }} / {{ payload.totalQuestions }}
      </div>
    </div>

    <!-- Quiz Content -->
    <div class="p-6">
      <template v-if="!submitted">
        <!-- Question Stem -->
        <div class="mb-6">
          <div class="text-[16px] font-bold text-slate-800 dark:text-slate-200 leading-relaxed">
            {{ currentQuestion.index }}. {{ currentQuestion.stem }}
          </div>
        </div>

        <!-- Options -->
        <div class="space-y-3">
          <button
            v-for="(option, idx) in currentQuestion.options"
            :key="idx"
            @click="selectOption(option)"
            :disabled="showResult"
            class="w-full text-left px-4 py-3.5 rounded-xl border-2 transition-all flex items-center gap-3 group"
            :class="getOptionClass(option)"
          >
            <span class="w-7 h-7 flex items-center justify-center rounded-full border border-slate-300 group-hover:border-indigo-400 text-sm font-bold shrink-0 transition-colors"
                  :class="getSelectedOptionCircleClass(option)">
              {{ getOptionPrefix(option) }}
            </span>
            <span class="flex-1 text-[15px] font-medium">{{ option }}</span>
            
            <span v-if="showResult && isCorrectOption(option)" class="material-symbols-outlined text-green-500">check_circle</span>
            <span v-else-if="showResult && isSelected(option) && !isCorrectOption(option)" class="material-symbols-outlined text-red-500">cancel</span>
          </button>
        </div>

        <!-- Feedback & Explanation -->
        <div v-if="showResult" class="mt-8 p-4 rounded-xl bg-slate-50 dark:bg-slate-900/50 border border-slate-200 dark:border-slate-700 animate-in fade-in slide-in-from-top-2 duration-300">
          <div class="flex items-center gap-2 mb-2">
             <span :class="isCurrentCorrect ? 'text-green-600' : 'text-red-600'" class="font-bold flex items-center gap-1">
               <span class="material-symbols-outlined text-sm">{{ isCurrentCorrect ? 'check_circle' : 'cancel' }}</span>
               {{ isCurrentCorrect ? '回答正确' : '回答错误' }}
             </span>
             <span v-if="!isCurrentCorrect" class="text-slate-500 text-sm">正确答案: {{ currentQuestion.correctAnswer }}</span>
          </div>
          <div class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed italic">
            <strong>解析:</strong> {{ currentQuestion.explanation }}
          </div>
        </div>

        <!-- Next Button -->
        <div v-if="showResult" class="mt-6 flex justify-end">
          <button 
            @click="nextQuestion"
            class="px-6 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl font-medium transition-colors flex items-center gap-2"
          >
            {{ isLast ? '完成并提交' : '下一题' }}
            <span class="material-symbols-outlined text-sm">arrow_forward</span>
          </button>
        </div>
      </template>

      <!-- Results View -->
      <div v-else class="flex flex-col items-center py-6 text-center animate-in zoom-in duration-500">
        <div class="w-24 h-24 rounded-full bg-indigo-50 dark:bg-indigo-900/30 flex items-center justify-center mb-4 border-4 border-indigo-100 dark:border-indigo-800">
          <span class="text-3xl font-black text-indigo-600 dark:text-indigo-400">{{ score }}</span>
        </div>
        <h3 class="text-xl font-bold text-slate-800 dark:text-slate-200">练习已完成!</h3>
        <p class="text-slate-500 mt-2 mb-6">
          您答对了 {{ correctCount }} / {{ payload.totalQuestions }} 道题
        </p>
        
        <div class="grid grid-cols-2 gap-4 w-full max-w-sm">
          <div class="p-3 bg-slate-50 dark:bg-slate-900/30 rounded-xl border border-slate-100 dark:border-slate-800">
            <div class="text-xs text-slate-400 mb-1">正确率</div>
            <div class="font-bold text-slate-700 dark:text-slate-300">{{ score }}%</div>
          </div>
          <div class="p-3 bg-slate-50 dark:bg-slate-900/30 rounded-xl border border-slate-100 dark:border-slate-800">
            <div class="text-xs text-slate-400 mb-1">耗时</div>
            <div class="font-bold text-slate-700 dark:text-slate-300">{{ duration }}秒</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { httpPostJson } from '../../api/http'

const props = defineProps({
  payload: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['completed'])

const currentIndex = ref(0)
const selectedOption = ref(null)
const showResult = ref(false)
const results = ref([]) // { index: number, isCorrect: boolean, selected: string }
const submitted = ref(false)
const startTime = ref(Date.now())
const endTime = ref(null)

const currentQuestion = computed(() => props.payload.questions[currentIndex.value])
const isLast = computed(() => currentIndex.value === props.payload.totalQuestions - 1)
const isCurrentCorrect = computed(() => {
  if (!selectedOption.value) return false
  const prefix = getOptionPrefix(selectedOption.value)
  return prefix.toUpperCase() === currentQuestion.value.correctAnswer.toUpperCase()
})

const correctCount = computed(() => results.value.filter(r => r.isCorrect).length)
const score = computed(() => Math.round((correctCount.value / props.payload.totalQuestions) * 100))
const duration = computed(() => {
  if (!endTime.value) return 0
  return Math.round((endTime.value - startTime.value) / 1000)
})

const getOptionPrefix = (option) => {
  const match = option.match(/^([A-Da-d])[\.\s]/)
  return match ? match[1] : option.charAt(0)
}

const selectOption = (option) => {
  if (showResult.value) return
  selectedOption.value = option
  showResult.value = true
  
  results.value.push({
    index: currentQuestion.value.index,
    isCorrect: isCurrentCorrect.value,
    selected: getOptionPrefix(option)
  })
}

const nextQuestion = async () => {
  if (isLast.value) {
    await submitResults()
    return
  }
  
  currentIndex.value++
  selectedOption.value = null
  showResult.value = false
}

const submitResults = async () => {
  endTime.value = Date.now()
  submitted.value = true
  
  try {
    // 调用 Phase 1.6 中暴露的接口
    await httpPostJson('/api/coding/batch-quiz/submit', {
      sessionId: props.payload.sessionId,
      results: results.value
    })
    emit('completed', { score: score.value, results: results.value })
  } catch (e) {
    console.error('Failed to submit quiz results', e)
  }
}

const isSelected = (option) => selectedOption.value === option
const isCorrectOption = (option) => {
  const prefix = getOptionPrefix(option)
  return prefix.toUpperCase() === currentQuestion.value.correctAnswer.toUpperCase()
}

const getOptionClass = (option) => {
  if (!showResult.value) {
    return isSelected(option) 
      ? 'border-indigo-600 bg-indigo-50/50 dark:bg-indigo-900/20 text-indigo-700 dark:text-indigo-400' 
      : 'border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600 text-slate-700 dark:text-slate-300 bg-white dark:bg-slate-800/40'
  }
  
  if (isCorrectOption(option)) {
    return 'border-green-500 bg-green-50/50 dark:bg-green-900/20 text-green-700 dark:text-green-400'
  }
  
  if (isSelected(option) && !isCorrectOption(option)) {
    return 'border-red-500 bg-red-50/50 dark:bg-red-900/20 text-red-700 dark:text-red-400'
  }
  
  return 'border-slate-200 dark:border-slate-700 text-slate-400 dark:text-slate-500 opacity-60'
}

const getSelectedOptionCircleClass = (option) => {
  if (!showResult.value) {
    return isSelected(option) ? 'bg-indigo-600 border-indigo-600 text-white' : ''
  }
  if (isCorrectOption(option)) {
    return 'bg-green-600 border-green-600 text-white'
  }
  if (isSelected(option) && !isCorrectOption(option)) {
    return 'bg-red-600 border-red-600 text-white'
  }
  return ''
}
</script>

<style scoped>
.animate-in {
  animation: animate-in 0.3s ease-out;
}
@keyframes animate-in {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
