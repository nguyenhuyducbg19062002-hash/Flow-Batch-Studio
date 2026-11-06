<template>
  <div class="px-4 py-2 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-slate-600">
    <span>
      <b class="text-slate-800">{{ from }}–{{ to }}</b> / {{ total }} mục
    </span>

    <div class="flex items-center gap-1">
      <button class="btn btn-sm" :disabled="page <= 1" @click="go(1)" title="Trang đầu"><ChevronsLeft class="w-3.5 h-3.5" /></button>
      <button class="btn btn-sm" :disabled="page <= 1" @click="go(page - 1)" title="Trang trước"><ChevronLeft class="w-3.5 h-3.5" /></button>
      <template v-for="(p, i) in pages" :key="i">
        <span v-if="p === '…'" class="px-1 text-slate-400">…</span>
        <button v-else class="btn btn-sm min-w-[2rem]" :class="p === page ? '!bg-indigo-600 !text-white !border-indigo-600' : ''"
          @click="go(p)">{{ p }}</button>
      </template>
      <button class="btn btn-sm" :disabled="page >= pageCount" @click="go(page + 1)" title="Trang sau"><ChevronRight class="w-3.5 h-3.5" /></button>
      <button class="btn btn-sm" :disabled="page >= pageCount" @click="go(pageCount)" title="Trang cuối"><ChevronsRight class="w-3.5 h-3.5" /></button>
    </div>

    <label class="flex items-center gap-1.5 whitespace-nowrap">
      Đi tới trang
      <input type="number" min="1" :max="pageCount" :value="page" class="input !w-16 !py-0.5 !text-xs"
        @change="go(Number($event.target.value))" />
      / {{ pageCount }}
    </label>

    <label class="flex items-center gap-1.5 whitespace-nowrap ml-auto">
      Mỗi trang
      <select :value="pageSize" class="input !w-auto !py-0.5 !text-xs" @change="$emit('update:pageSize', Number($event.target.value))">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}</option>
      </select>
    </label>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-vue-next'

const props = defineProps({
  page: { type: Number, required: true },
  pageSize: { type: Number, required: true },
  total: { type: Number, required: true },
  sizes: { type: Array, default: () => [25, 50, 100, 200] },
})
const emit = defineEmits(['update:page', 'update:pageSize'])

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const from = computed(() => (props.total ? (props.page - 1) * props.pageSize + 1 : 0))
const to = computed(() => Math.min(props.total, props.page * props.pageSize))

// 1 … 4 5 [6] 7 8 … 40
const pages = computed(() => {
  const n = pageCount.value
  const cur = props.page
  const keep = new Set([1, n, cur, cur - 1, cur - 2, cur + 1, cur + 2].filter((p) => p >= 1 && p <= n))
  const sorted = [...keep].sort((a, b) => a - b)
  const out = []
  sorted.forEach((p, i) => {
    if (i && p - sorted[i - 1] > 1) out.push('…')
    out.push(p)
  })
  return out
})

function go(p) {
  const target = Math.min(pageCount.value, Math.max(1, Math.floor(p) || 1))
  if (target !== props.page) emit('update:page', target)
}
</script>
