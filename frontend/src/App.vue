<template>
  <div class="h-full flex overflow-hidden bg-[var(--bg)]">
    <!-- Màn hẹp (< 768px): nền mờ phía sau ngăn kéo điều hướng; bấm vào để đóng -->
    <div class="sidebar-backdrop" :class="{ 'is-open': navOpen }" @click="navOpen = false"></div>

    <!-- Thanh bên: điều hướng, tách hẳn khỏi khu làm việc bằng nền tối. Cỡ màn hình nào ra sao: xem main.css "Khung app responsive" -->
    <aside class="app-sidebar shrink-0 flex flex-col bg-[var(--sidebar)] text-slate-300" :class="{ 'is-open': navOpen }">
      <div class="brand flex items-center gap-3 px-4 h-16 shrink-0">
        <div class="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-500 flex items-center justify-center shrink-0">
          <Clapperboard class="w-[18px] h-[18px] text-white" />
        </div>
        <div class="brand-text min-w-0 leading-tight">
          <div class="text-[15px] font-semibold text-white tracking-tight">Flow Batch Studio</div>
          <div class="text-[11px] text-slate-400 truncate">Tạo ảnh &amp; video hàng loạt</div>
        </div>
      </div>

      <div class="sidebar-project px-3 pb-3">
        <label class="block text-[11px] font-medium text-slate-400 px-1 mb-1.5">Phim đang chọn</label>
        <select :value="store.projectId ?? ''" @change="store.selectProject(Number($event.target.value))"
          class="sidebar-select" :disabled="!store.projects.length">
          <option v-if="!store.projects.length" value="">Chưa có phim</option>
          <option v-for="pv in store.projects" :key="pv.project.id" :value="pv.project.id">{{ pv.project.name }}</option>
        </select>
      </div>

      <nav class="px-3 space-y-0.5">
        <button v-for="t in tabs" :key="t.key" @click="go(t.key)" :title="t.label"
          :class="store.view === t.key ? 'bg-white/10 text-white before:bg-indigo-400' : 'text-slate-300 hover:bg-white/5 hover:text-white before:bg-transparent'"
          class="nav-item relative w-full flex items-center gap-3 px-3 py-2 rounded-lg text-[13.5px] font-medium transition
            before:absolute before:left-0 before:top-2 before:bottom-2 before:w-[3px] before:rounded-full
            focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400">
          <component :is="t.icon" class="w-[18px] h-[18px] shrink-0" :class="store.view === t.key ? 'text-indigo-300' : 'text-slate-400'" />
          <span class="nav-label flex-1 text-left">{{ t.label }}</span>
        </button>
      </nav>

      <!-- Hàng đợi của phim đang chọn -->
      <div v-if="store.current" class="sidebar-queue mx-3 mt-5 rounded-xl bg-white/[0.04] ring-1 ring-white/5 p-3">
        <div class="text-[11px] font-medium text-slate-400 mb-2">Phim này · ảnh / video</div>
        <div class="space-y-1.5 text-[12.5px]">
          <div v-for="q in queue" :key="q.label" class="flex items-center gap-2">
            <span class="w-1.5 h-1.5 rounded-full" :class="q.dot"></span>
            <span class="flex-1 text-slate-300">{{ q.label }}</span>
            <span class="font-semibold tabular-nums text-white">{{ q.image }} <span class="text-slate-500">/</span> {{ q.video }}</span>
          </div>
        </div>
      </div>

      <div class="mt-auto p-3 space-y-1">
        <div class="foot-item flex items-center gap-2.5 px-3 py-2 text-[12.5px] text-slate-400"
          :title="`Worker Python: ${workers}` + (workers ? '' : ' (chỉ cần khi chạy tự động; Tự làm trên Flow không cần)')">
          <Cpu class="w-4 h-4 shrink-0" />
          <span class="nav-label flex-1">Worker Python</span>
          <span class="nav-label tabular-nums font-semibold" :class="workers ? 'text-emerald-400' : 'text-slate-500'">{{ workers }}</span>
        </div>
        <button @click="quit" title="Thoát ứng dụng"
          class="foot-item w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-[13px] text-slate-400 hover:bg-rose-500/10 hover:text-rose-300 transition">
          <Power class="w-4 h-4 shrink-0" />
          <span class="nav-label">Thoát ứng dụng</span>
        </button>
      </div>
    </aside>

    <div v-if="stopped" class="flex-1 flex items-center justify-center text-slate-500">
      Ứng dụng đã tắt. Bạn có thể đóng cửa sổ này.
    </div>

    <!-- Khu làm việc -->
    <div v-else class="flex-1 min-w-0 flex flex-col">
      <!-- Thanh trên: tiêu đề màn + mô tả + quy trình + trạng thái (thay cho banner to) -->
      <header class="app-topbar h-16 shrink-0 bg-white border-b border-slate-200 px-6 flex items-center gap-6">
        <button class="nav-toggle btn-ghost !h-9 !w-9 !px-0 shrink-0" @click="navOpen = true" title="Mở menu" aria-label="Mở menu">
          <Menu class="w-5 h-5" />
        </button>
        <div class="min-w-0 flex-1">
          <h1 class="page-title truncate">{{ banner.title }}</h1>
          <p class="topbar-desc text-[12.5px] text-slate-500 truncate">{{ banner.desc }}</p>
        </div>
        <!-- Thanh bên thu gọn / ẩn: chọn phim ở đây -->
        <select class="topbar-project input !w-48 shrink-0" :value="store.projectId ?? ''" :disabled="!store.projects.length"
          @change="store.selectProject(Number($event.target.value))" title="Phim đang chọn">
          <option v-if="!store.projects.length" value="">Chưa có phim</option>
          <option v-for="pv in store.projects" :key="pv.project.id" :value="pv.project.id">{{ pv.project.name }}</option>
        </select>
        <ol v-if="banner.step >= 0" class="topbar-steps flex items-center gap-1.5 text-[12px] font-medium shrink-0">
          <template v-for="(s, i) in steps" :key="s">
            <ChevronRight v-if="i" class="w-3.5 h-3.5 text-slate-300" />
            <li :class="i === banner.step ? 'bg-indigo-50 text-indigo-700' : 'text-slate-400'" class="px-2 py-1 rounded-md">
              {{ i + 1 }}. {{ s }}
            </li>
          </template>
        </ol>
        <div class="topbar-status flex items-center gap-3 shrink-0 pl-6 border-l border-slate-200">
          <span class="inline-flex items-center gap-1.5 text-[12.5px] font-medium" :class="statusCls" :title="statusText">
            <span class="w-2 h-2 rounded-full shrink-0" :class="statusDot"></span><span class="topbar-label">{{ statusText }}</span>
          </span>
          <button @click="go('accounts')" class="inline-flex items-center gap-1.5 text-[12.5px] text-slate-600 hover:text-indigo-700"
            title="Tài khoản sẵn sàng / tổng số tài khoản">
            <Users class="w-4 h-4 text-slate-400" />
            <span class="tabular-nums"><b class="text-slate-900">{{ store.readyAccounts }}</b>/{{ store.accounts.length }}<span class="topbar-label"> tài khoản</span></span>
          </button>
        </div>
      </header>

      <main class="flex-1 min-h-0 overflow-y-auto">
        <div class="app-content mx-auto">
          <ProjectsView v-if="store.view === 'projects'" />
          <BatchView v-else-if="store.view === 'images'" kind="IMAGE" />
          <BatchView v-else-if="store.view === 'videos'" kind="VIDEO" />
          <ManualView v-else-if="store.view === 'manual'" />
          <AccountsView v-else-if="store.view === 'accounts'" />
          <SettingsView v-else-if="store.view === 'settings'" />
        </div>
      </main>
    </div>

    <!-- Thông báo nổi -->
    <div class="fixed bottom-5 right-5 z-[60] space-y-2 max-w-md">
      <div v-for="t in store.toasts" :key="t.id"
        :class="{
          'border-rose-200 bg-rose-50 text-rose-700': t.kind === 'error',
          'border-emerald-200 bg-emerald-50 text-emerald-700': t.kind === 'success',
          'border-slate-200 bg-white text-slate-700': t.kind === 'info',
        }"
        class="px-4 py-3 rounded-xl border text-sm shadow-lg whitespace-pre-wrap">
        {{ t.message }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ChevronRight, Clapperboard, Cpu, Film, Hand, Image as ImageIcon, Menu, Power, Settings, User, Users, Video } from 'lucide-vue-next'
import ManualView from './views/ManualView.vue'
import ProjectsView from './views/ProjectsView.vue'
import BatchView from './views/BatchView.vue'
import AccountsView from './views/AccountsView.vue'
import SettingsView from './views/SettingsView.vue'
import { systemApi } from './api'
import { store } from './store'

const tabs = [
  { key: 'accounts', label: 'Tài khoản', icon: User },
  { key: 'projects', label: 'Phim', icon: Film },
  { key: 'images', label: 'Tạo ảnh', icon: ImageIcon },
  { key: 'videos', label: 'Tạo video', icon: Video },
  { key: 'manual', label: 'Tự làm trên Flow', icon: Hand },
  { key: 'settings', label: 'Cài đặt', icon: Settings },
]

const steps = ['Chạy', 'Duyệt thư mục', 'Đổi tên']
const BANNERS = {
  accounts: { title: 'Tài khoản', step: -1, desc: 'Đăng nhập Google Flow một lần cho mỗi tài khoản; hết quota sẽ tự chuyển tài khoản.' },
  projects: { title: 'Phim', step: -1, desc: 'Tạo phim, chọn thư mục; nạp cả thư mục Omni một lần cho cả ảnh lẫn video.' },
  images: { title: 'Tạo ảnh hàng loạt', step: 0, desc: 'Tạo ảnh theo prompt → thư mục chờ duyệt (001.png…) → báo số sai làm lại → đổi tên đúng ID.' },
  videos: { title: 'Tạo video hàng loạt', step: 0, desc: 'Tự upload ảnh @ID, gắn chip vào prompt, tạo video → thư mục chờ duyệt (001.mp4…) → đổi tên.' },
  manual: { title: 'Tự làm trên Flow', step: 1, desc: 'Bạn dán prompt, gắn ảnh, bấm Tạo và Tải xuống trên Flow; app tự nhận file và đặt đúng tên.' },
  settings: { title: 'Cài đặt', step: -1, desc: 'Số worker, thời gian chờ render, thời gian nghỉ khi hết quota.' },
}
const banner = computed(() => BANNERS[store.view] || BANNERS.projects)

/** Màn hẹp: ngăn kéo điều hướng đang mở. Chọn một màn thì đóng lại. */
const navOpen = ref(false)
function go(view) {
  store.go(view)
  navOpen.value = false
}
function onKey(e) {
  if (e.key === 'Escape') navOpen.value = false
}

const stopped = ref(false)
const workers = computed(() => store.info?.workersAlive ?? 0)
// 0 worker không phải lỗi: Tự làm trên Flow không cần worker
const statusText = computed(() => (!store.online ? 'Mất kết nối' : workers.value ? 'Sẵn sàng' : 'Chế độ tay'))
const statusCls = computed(() => (!store.online ? 'text-rose-600' : workers.value ? 'text-emerald-700' : 'text-slate-600'))
const statusDot = computed(() => (!store.online ? 'bg-rose-500' : workers.value ? 'bg-emerald-500' : 'bg-slate-400'))

const queue = computed(() => {
  const k = store.current?.kindCounts || {}
  const v = (kind, st) => k[kind]?.[st] ?? 0
  const row = (label, st, dot) => ({ label, image: v('IMAGE', st), video: v('VIDEO', st), dot })
  return [
    row('Chờ chạy', 'PENDING', 'bg-slate-400'),
    row('Đang tạo', 'GENERATING', 'bg-sky-400'),
    row('Chờ duyệt', 'AWAITING_REVIEW', 'bg-amber-400'),
    row('Đã đổi tên', 'SUCCESS', 'bg-emerald-400'),
    row('Lỗi', 'FAILED', 'bg-rose-400'),
  ]
})

async function quit() {
  if (!confirm('Tắt ứng dụng? Các job đang tạo sẽ dừng và chạy lại từ đầu ở lần mở sau.')) return
  try {
    await systemApi.shutdown()
  } finally {
    stopped.value = true
    // Đóng cửa sổ ngay cùng lúc với app tắt. Chạy được trong cửa sổ app (Edge --app); mở bằng tab trình duyệt thường
    // thì trình duyệt chặn, khi đó dòng "Ứng dụng đã tắt" ở trên vẫn hiện và backend cũng tự đóng cửa sổ của nó.
    try { window.close() } catch { /* bị chặn thì thôi */ }
  }
}

// Báo cho backend biết cửa sổ còn mở; đóng cửa sổ (chế độ app desktop) thì backend tự tắt.
function heartbeat() {
  fetch('/api/system/ui-heartbeat', { method: 'POST' }).catch(() => {})
}
function onPageHide() {
  navigator.sendBeacon?.('/api/system/ui-closed')
}

let pollTimer
let beatTimer
onMounted(() => {
  store.refresh()
  heartbeat()
  pollTimer = setInterval(() => !stopped.value && store.refresh(), 4000)
  beatTimer = setInterval(() => !stopped.value && heartbeat(), 3000)
  window.addEventListener('pagehide', onPageHide)
  window.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  clearInterval(pollTimer)
  clearInterval(beatTimer)
  window.removeEventListener('pagehide', onPageHide)
  window.removeEventListener('keydown', onKey)
})
</script>
