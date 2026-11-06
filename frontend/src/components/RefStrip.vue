<template>
  <div>
    <div class="flex flex-wrap items-start gap-2">
      <div v-for="(r, i) in job.references" :key="r.id" class="group relative" :style="{ width: size + 'px' }">
        <div class="relative rounded-lg overflow-hidden border bg-slate-100"
          :class="r.available ? 'border-slate-200' : r.producerStatus ? 'border-indigo-200 bg-indigo-50' : 'border-rose-300 bg-rose-50'"
          :style="{ width: size + 'px', height: size * 0.75 + 'px' }">
          <img v-if="r.available" :src="projectsApi.assetUrl(projectId, r.id)" loading="lazy"
            class="w-full h-full object-cover cursor-zoom-in" @click="$emit('zoom', r.id)" :alt="r.id" />
          <!-- Ảnh app sẽ tạo (ví dụ PANEL-1 từ prompt-anh-panel.xlsx): không phải lỗi, chỉ là chưa tới lượt -->
          <button v-else-if="r.producerStatus" type="button" @click="$emit('goto', r.id)"
            :title="isScene(r.id)
              ? `${r.id} là KHUNG CẢNH (tờ 4 khung hình của cảnh này) app sẽ tạo ở tab Tạo ảnh (${producerLabel(r.producerStatus)}). Bấm để xem.`
              : `${r.id} là ảnh app sẽ tạo ở tab Tạo ảnh (${producerLabel(r.producerStatus)}). Bấm để xem.`"
            class="w-full h-full flex flex-col items-center justify-center text-indigo-600 text-[10px] leading-tight text-center px-1 hover:bg-indigo-100">
            <Film v-if="isScene(r.id)" class="w-4 h-4 mb-0.5" />
            <ImagePlus v-else class="w-4 h-4 mb-0.5" />
            <span class="font-semibold">{{ isScene(r.id) ? 'Khung cảnh' : 'Ảnh sẽ tạo' }}</span>
            <span class="opacity-80">{{ producerLabel(r.producerStatus) }}</span>
          </button>
          <div v-else class="w-full h-full flex flex-col items-center justify-center text-rose-500 text-[10px] leading-tight text-center px-1">
            <ImageOff class="w-4 h-4 mb-0.5" /> thiếu ảnh
          </div>
          <span class="absolute top-1 left-1 px-1 rounded bg-slate-900/70 text-white text-[10px] font-semibold">{{ i + 1 }}</span>
          <button v-if="editable && r.userAdded" @click="remove(r.id)" title="Bỏ ảnh này khỏi cảnh"
            class="absolute top-1 right-1 w-5 h-5 rounded-full bg-white/90 text-rose-600 shadow hidden group-hover:flex items-center justify-center">
            <X class="w-3 h-3" />
          </button>
        </div>
        <div class="mt-0.5 text-[10px] font-mono truncate" :class="r.userAdded ? 'text-indigo-600' : 'text-slate-500'" :title="r.id">
          {{ r.userAdded ? '+ ' : '' }}{{ r.id }}
        </div>
      </div>

      <label v-if="editable" :style="{ width: size + 'px', height: size * 0.75 + 'px' }" title="Thêm ảnh cho cảnh này (chọn được nhiều ảnh)"
        class="rounded-lg border-2 border-dashed border-slate-300 hover:border-indigo-400 hover:bg-indigo-50/50 text-slate-400 hover:text-indigo-600 flex flex-col items-center justify-center cursor-pointer transition">
        <Plus class="w-5 h-5" />
        <span class="text-[10px] mt-0.5">{{ uploading ? 'Đang thêm…' : 'Thêm ảnh' }}</span>
        <input type="file" accept=".png,.jpg,.jpeg,.webp" multiple class="hidden" :disabled="uploading" @change="add" />
      </label>
    </div>
    <p v-if="job.references.length > 7" class="text-[11px] text-amber-600 mt-1.5">
      Cảnh này có {{ job.references.length }} ảnh; Google Flow nhận tối đa 7 ảnh cho một lần tạo.
    </p>
    <p v-if="toCreate.length" class="text-[11px] text-indigo-600 mt-1">
      Chờ {{ toCreate.join(', ') }}:
      {{ toCreate.every(isScene) ? 'khung cảnh này (tờ 4 khung hình)' : toCreate.some(isScene) ? 'khung cảnh và ảnh này' : 'ảnh này' }}
      được tạo ở tab <b>Tạo ảnh</b> (từ file prompt ảnh). Tạo xong thì cảnh này tự chạy.
    </p>
    <p v-if="missing.length" class="text-[11px] text-rose-600 mt-1">
      Thiếu {{ missing.join(', ') }}: không có prompt tạo ảnh này và chưa có file. Bấm "Thêm ảnh có sẵn" hoặc nút "+".
    </p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Film, ImageOff, ImagePlus, Plus, X } from 'lucide-vue-next'
import { projectsApi, promptsApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({
  job: { type: Object, required: true },
  projectId: { type: Number, required: true },
  editable: { type: Boolean, default: true },
  size: { type: Number, default: 84 },
})
const emit = defineEmits(['changed', 'zoom', 'goto'])
const uploading = ref(false)

// chưa có ảnh nhưng app sẽ tạo (có prompt ở tab Tạo ảnh) ≠ thiếu thật (không prompt, không file)
const toCreate = computed(() => props.job.references.filter((r) => !r.available && r.producerStatus).map((r) => r.id))
const missing = computed(() => props.job.references.filter((r) => !r.available && !r.producerStatus).map((r) => r.id))

/** PANEL-n là KHUNG CẢNH (một tờ 4 khung hình của cảnh n), không phải ảnh nhân vật / bối cảnh / đồ vật */
const isScene = (id) => String(id).startsWith('PANEL-')

const PRODUCER = {
  PENDING: 'chờ tạo', GENERATING: 'đang tạo', AWAITING_REVIEW: 'đang lưu', APPROVED: 'đang lưu',
  DOWNLOADING: 'đang lưu', SUCCESS: 'xong', FAILED: 'tạo lỗi', CANCELLED: 'đã hủy',
}
const producerLabel = (s) => PRODUCER[s] || s

async function add(e) {
  const files = [...e.target.files]
  e.target.value = ''
  if (!files.length) return
  uploading.value = true
  try {
    const r = await promptsApi.addReferences(props.job.promptId, files)
    store.toast(`Đã thêm ${r.added.length} ảnh vào ${props.job.kind === 'IMAGE' ? 'ảnh' : 'cảnh'} ${props.job.externalId}: ${r.added.join(', ')}`, 'success')
    emit('changed')
  } catch (err) {
    store.toast(errMsg(err), 'error')
  } finally {
    uploading.value = false
  }
}

async function remove(refId) {
  try {
    await promptsApi.removeReference(props.job.promptId, refId)
    emit('changed')
  } catch (err) {
    store.toast(errMsg(err), 'error')
  }
}
</script>
