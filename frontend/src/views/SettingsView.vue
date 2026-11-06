<template>
  <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
    <form @submit.prevent="save" class="card flex flex-col">
      <div class="card-head">
        <SlidersHorizontal class="w-5 h-5 text-indigo-500 mt-0.5" />
        <div>
          <div class="card-title">Cài đặt chung</div>
          <div class="card-sub">Áp dụng cho mọi phim. Thư mục ảnh/video đặt riêng trong từng phim.</div>
        </div>
      </div>
      <div class="p-5 grid grid-cols-1 md:grid-cols-2 gap-5">
        <div v-for="f in fields" :key="f.key">
          <label class="label">{{ f.label }}</label>
          <select v-if="f.type === 'bool'" v-model="settings[f.key]" class="input">
            <option value="false">Không</option>
            <option value="true">Có</option>
          </select>
          <input v-else v-model="settings[f.key]" type="number" :min="f.min" :max="f.max" class="input" />
          <p class="hint">{{ f.hint }}</p>
        </div>
      </div>
      <div class="mt-auto flex justify-end px-5 py-4 border-t border-slate-100">
        <button type="submit" class="btn-primary">Lưu cài đặt</button>
      </div>
    </form>

    <section class="card">
      <div class="card-head">
        <Cpu class="w-5 h-5 text-indigo-500 mt-0.5" />
        <div>
          <div class="card-title">Hệ thống</div>
          <div class="card-sub">Đường dẫn dữ liệu và trạng thái worker</div>
        </div>
      </div>
      <div v-if="store.info" class="p-5 space-y-2 text-sm">
        <div class="flex items-center gap-2">
          <span class="w-2 h-2 rounded-full" :class="store.info.workersAlive ? 'bg-emerald-500' : 'bg-rose-500'"></span>
          Worker Python đang chạy: <b>{{ store.info.workersAlive }}</b>
        </div>
        <button v-for="d in dirs" :key="d.label" @click="open(d.path)"
          class="w-full flex items-center gap-2 text-left px-3 py-2 rounded-xl border border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/40 text-xs transition">
          <Folder class="w-4 h-4 text-slate-400 shrink-0" />
          <span class="text-slate-500 shrink-0">{{ d.label }}:</span>
          <span class="font-mono text-slate-700 truncate">{{ d.path }}</span>
        </button>
        <div class="text-xs text-slate-500 pt-1">Thư mục cài đặt: <span class="font-mono">{{ store.info.homeDir }}</span> · Java {{ store.info.javaVersion }}</div>
        <div class="note-info mt-3">
          Selector Google Flow nằm ở <span class="font-mono">engine\selectors.json</span> trong thư mục cài đặt.
          Flow đổi giao diện thì sửa file này, không cần cài lại.
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Cpu, Folder, SlidersHorizontal } from 'lucide-vue-next'
import { settingsApi, systemApi, errMsg } from '@/api'
import { store } from '@/store'

const fields = [
  { key: 'worker_processes', label: 'Số worker chạy song song', min: 1, max: 20, hint: 'Mỗi worker mở một trình duyệt Chrome. Khuyến nghị 2–5 tùy RAM. Áp dụng ngay khi lưu.' },
  { key: 'job_timeout_minutes', label: 'Thời gian chờ render tối đa (phút)', min: 1, max: 120, hint: 'Quá thời gian này coi là lỗi tạm thời và thử lại.' },
  { key: 'quota_cooldown_minutes', label: 'Tài khoản hết quota nghỉ (phút)', min: 1, max: 10080, hint: 'Sau thời gian này tài khoản được dùng lại tự động.' },
  { key: 'headless', label: 'Ẩn trình duyệt khi chạy ngầm', type: 'bool', hint: 'Google Flow có thể chặn chế độ ẩn; hãy thử trước với vài job.' },
  { key: 'staging_retention_days', label: 'Giữ file xem trước cũ (ngày)', min: 1, max: 365, hint: 'File kết quả bị bỏ khi tạo lại.' },
]

const settings = ref({})
const dirs = computed(() => store.info ? [
  { label: 'Dữ liệu', path: store.info.dataDir },
  { label: 'Log', path: store.info.logsDir },
] : [])

onMounted(async () => {
  try {
    settings.value = await settingsApi.get()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
})

async function save() {
  try {
    await settingsApi.save(settings.value)
    store.toast('Đã lưu cài đặt', 'success')
    store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function open(path) {
  try {
    await systemApi.openFolder(path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
</script>
