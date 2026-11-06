<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-[2px]">
    <div class="card w-full max-w-3xl max-h-[92vh] flex flex-col shadow-2xl">
      <div class="card-head items-center">
        <component :is="mode === 'text' ? PencilLine : FolderTree" class="w-5 h-5 text-indigo-500" />
        <div class="flex-1">
          <div class="card-title">{{ mode === 'text' ? 'Nhập prompt thủ công' : 'Ảnh → Video theo thư mục con' }}</div>
          <div class="card-sub">{{ mode === 'text' ? 'Dán prompt, mỗi dòng một cảnh' : 'Mỗi thư mục con = một nhóm ảnh, kèm file [tên-thư-mục].txt làm prompt' }}</div>
        </div>
        <button @click="$emit('close')" class="text-slate-400 hover:text-slate-700"><X class="w-5 h-5" /></button>
      </div>

      <!-- Nhập thủ công -->
      <div v-if="mode === 'text'" class="p-5 space-y-3 overflow-y-auto">
        <div v-if="!fixedKind" class="grid grid-cols-2 gap-2 p-1 rounded-xl bg-slate-100">
          <button v-for="k in kinds" :key="k.value" type="button" @click="kind = k.value"
            :class="kind === k.value ? 'bg-white shadow-sm text-indigo-700' : 'text-slate-600'"
            class="px-3 py-2 rounded-lg text-sm font-medium flex items-center justify-center gap-1.5">
            <component :is="k.icon" class="w-4 h-4" /> {{ k.label }}
          </button>
        </div>
        <textarea v-model="text" rows="12" class="input font-mono !text-xs leading-relaxed"
          :placeholder="kind === 'VIDEO'
            ? 'STT - 1. IMAGE KEY (...): @PANEL-1 = THE STILLS ... @CHAR-CALEB-1 = CALEB ...\nSTT - 2. IMAGE KEY ...'
            : 'PANEL-1: prompt tạo ảnh ...\nCHAR-CALEB-1 | prompt tạo ảnh nhân vật ...'"></textarea>
        <div class="note-info space-y-1">
          <template v-if="kind === 'VIDEO'">
            <p>Mỗi dòng = 1 video; prompt dài nhiều dòng thì cách các prompt bằng <b>một dòng trống</b>. STT lấy ở đầu prompt ("STT - 12. …"); không có thì nối tiếp sau video cuối.</p>
          </template>
          <template v-else>
            <p>Mỗi dòng = 1 ảnh (hoặc cách nhau bằng một dòng trống), dạng <span class="font-mono">ID: prompt</span> hoặc <span class="font-mono">ID | prompt</span>. ID là tên file sau khi đổi tên; không ghi thì app tự đặt ANH-1, ANH-2…</p>
          </template>
          <p>Ảnh cần gắn được đọc từ khai báo <span class="font-mono">@ID =</span> trong prompt (ví dụ <span class="font-mono">@PANEL-1 = …</span>, <span class="font-mono">@CHAR-CALEB-1 = …</span>), đúng thứ tự khai báo.</p>
        </div>
      </div>

      <!-- Ảnh → Video theo thư mục con -->
      <div v-else class="p-5 space-y-4 overflow-y-auto">
        <FolderInput v-model="folder" label="Thư mục gốc (quét các thư mục con)" placeholder="Chọn thư mục chứa các thư mục con…" />
        <label class="flex items-start gap-3 p-3 rounded-xl border cursor-pointer"
          :class="grouped ? 'border-orange-200 bg-orange-50/70' : 'border-slate-200'">
          <input type="checkbox" v-model="grouped" class="mt-0.5 w-4 h-4 accent-orange-500" />
          <span class="text-sm">
            <span class="font-medium text-slate-700">Chế độ tham chiếu: {{ grouped ? 'BẬT' : 'TẮT' }}</span>
            <span class="block text-[11px] text-slate-500 mt-0.5">
              Bật: cả nhóm ảnh trong một thư mục con thành <b>1 video</b> (ảnh upload theo thứ tự tên file).
              Tắt: <b>mỗi ảnh thành 1 video</b> riêng.
            </span>
          </span>
        </label>
        <div class="note-warn space-y-1">
          <p>Mỗi thư mục con = 1 nhóm ảnh. File <span class="font-mono">[tên-thư-mục].txt</span> (trong thư mục con hoặc cùng cấp) là prompt:
            1 dòng = dùng chung cho cả nhóm, nhiều dòng = theo thứ tự ảnh.</p>
          <p>Ảnh được chép vào Folder Ảnh với ID <span class="font-mono">&lt;tên-thư-mục&gt;-&lt;tên-ảnh&gt;</span>. Video mới nối tiếp sau cảnh cuối của phim.</p>
        </div>
        <pre class="text-[11px] font-mono text-slate-500 bg-slate-50 border border-slate-200 rounded-xl p-3">Thư mục gốc\
  canh-01\  anh1.png  anh2.png  canh-01.txt
  canh-02\  anh1.png             canh-02.txt</pre>
      </div>

      <div class="flex justify-end gap-2 px-5 py-4 border-t border-slate-100 bg-slate-50/60 rounded-b-2xl">
        <button @click="$emit('close')" class="btn">Hủy</button>
        <button @click="submit" :disabled="busy || (mode === 'text' ? !text.trim() : !folder)" class="btn-primary">
          {{ busy ? 'Đang nạp…' : 'Thêm vào phim' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { FolderTree, Image as ImageIcon, PencilLine, Video, X } from 'lucide-vue-next'
import FolderInput from './FolderInput.vue'
import { projectsApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({
  show: Boolean,
  mode: { type: String, default: 'text' }, // 'text' | 'groups'
  projectId: { type: Number, default: null },
  /** 'IMAGE' | 'VIDEO': màn Tạo ảnh / Tạo video chỉ nhập đúng loại của nó */
  fixedKind: { type: String, default: null },
})
const emit = defineEmits(['close', 'done'])

const kinds = [
  { value: 'VIDEO', label: 'Prompt video', icon: Video },
  { value: 'IMAGE', label: 'Prompt ảnh', icon: ImageIcon },
]
const kind = ref('VIDEO')
const text = ref('')
const folder = ref('')
const grouped = ref(false)
const busy = ref(false)
const count = computed(() => text.value.split('\n').filter((l) => l.trim()).length)

watch(() => props.show, (v) => {
  if (v) {
    text.value = ''
    busy.value = false
    if (props.fixedKind) kind.value = props.fixedKind
  }
})

async function submit() {
  busy.value = true
  try {
    const r = props.mode === 'text'
      ? await projectsApi.importText(props.projectId, kind.value, text.value)
      : await projectsApi.importImageGroups(props.projectId, folder.value, grouped.value)
    emit('done', r)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    busy.value = false
  }
}
</script>
