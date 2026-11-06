<template>
  <div>
    <label v-if="label" class="label flex items-center gap-1.5">
      <Folder class="w-3.5 h-3.5 text-slate-400" /> {{ label }}
    </label>
    <div class="flex gap-2">
      <input
        :value="modelValue"
        @input="$emit('update:modelValue', $event.target.value)"
        type="text"
        :placeholder="placeholder"
        class="input flex-1 min-w-0 font-mono !text-[13px]" />
      <button type="button" @click="pick" :disabled="picking" class="btn">
        <FolderOpen class="w-4 h-4" />
        {{ picking ? 'Đang mở…' : 'Chọn folder' }}
      </button>
    </div>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Folder, FolderOpen } from 'lucide-vue-next'
import { systemApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: '' },
  hint: { type: String, default: '' },
  placeholder: { type: String, default: 'Chọn thư mục…' },
})
const emit = defineEmits(['update:modelValue'])
const picking = ref(false)

// Hộp thoại chọn thư mục của Windows (do backend mở, vì giao diện không được đọc đường dẫn thật trên máy)
async function pick() {
  picking.value = true
  try {
    const { path } = await systemApi.pickFolder(props.modelValue)
    if (path) emit('update:modelValue', path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    picking.value = false
  }
}
</script>
