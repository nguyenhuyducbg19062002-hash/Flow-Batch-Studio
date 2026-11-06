<template>
  <div class="space-y-4">
    <!-- Cột danh sách phim hẹp (một phim thường chỉ vài dòng), phần chi tiết chiếm phần còn lại -->
    <div class="grid grid-cols-1 xl:grid-cols-[340px_minmax(0,1fr)] gap-4 items-start">
      <!-- Danh sách phim -->
      <section class="card flex flex-col">
        <div class="card-head items-center">
          <span class="icon-tile icon-tile-indigo"><Film class="w-[18px] h-[18px]" /></span>
          <div class="flex-1 min-w-0">
            <div class="card-title">Danh sách phim <span class="count-pill ml-1">{{ store.projects.length }}</span></div>
            <div class="card-sub">Mỗi phim là một thư mục output của Omni</div>
          </div>
        </div>

        <div class="p-3 flex flex-col gap-2">
          <div v-if="!store.projects.length" class="flex flex-col items-center justify-center text-center py-10 px-4">
            <span class="icon-tile icon-tile-slate !w-12 !h-12"><Clapperboard class="w-6 h-6" /></span>
            <div class="text-sm font-medium text-slate-800 mt-3">Bắt đầu với phim đầu tiên</div>
            <div class="text-xs text-slate-500 mt-1">Tạo phim, chọn Folder Ảnh và Folder Video, rồi nạp file prompt của Omni.</div>
          </div>

          <button v-for="pv in store.projects" :key="pv.project.id" @click="store.selectProject(pv.project.id)"
            :class="pv.project.id === store.projectId ? 'border-indigo-300 bg-indigo-50/50 ring-1 ring-indigo-200' : 'border-slate-200 hover:border-slate-300 hover:bg-slate-50/60 bg-white'"
            class="w-full text-left p-3 rounded-xl border transition">
            <div class="flex items-center gap-3">
              <span class="icon-tile bg-gradient-to-br from-indigo-500 to-violet-500 text-white"><Clapperboard class="w-[18px] h-[18px]" /></span>
              <div class="flex-1 min-w-0">
                <div class="flex items-center justify-between gap-2">
                  <span class="font-semibold text-[13.5px] text-slate-900 truncate">{{ pv.project.name }}</span>
                  <StatusBadge :status="pv.project.status" />
                </div>
                <div class="flex items-center gap-3 text-[11.5px] text-slate-500 mt-1">
                  <span class="inline-flex items-center gap-1" title="Ảnh"><ImageIcon class="w-3.5 h-3.5" />{{ pv.imagesTotal }}</span>
                  <span class="inline-flex items-center gap-1" title="Video"><Video class="w-3.5 h-3.5" />{{ pv.total - pv.imagesTotal }}</span>
                  <span class="inline-flex items-center gap-1 text-emerald-700" title="Đã xong"><CheckCircle2 class="w-3.5 h-3.5" />{{ pv.counts.SUCCESS }}</span>
                  <span v-if="pv.counts.FAILED" class="inline-flex items-center gap-1 text-rose-600" title="Lỗi"><AlertTriangle class="w-3.5 h-3.5" />{{ pv.counts.FAILED }}</span>
                </div>
              </div>
            </div>
            <div class="flex items-center gap-2 mt-3">
              <div class="progress flex-1 !h-1.5"><span :style="{ width: pct(pv) + '%' }"></span></div>
              <span class="text-[11px] font-semibold text-slate-600 tabular-nums w-9 text-right">{{ pct(pv) }}%</span>
            </div>
          </button>
        </div>

        <div class="px-3 pb-3">
          <button @click="openModal(null)" class="btn btn-block"><Plus class="w-4 h-4" /> Thêm phim mới</button>
        </div>
      </section>

      <!-- Chi tiết phim -->
      <section class="card flex flex-col">
        <div v-if="!current" class="flex flex-col items-center justify-center text-center p-12">
          <span class="icon-tile icon-tile-slate !w-12 !h-12"><Images class="w-6 h-6" /></span>
          <div class="text-sm font-medium text-slate-800 mt-3">Chưa chọn phim</div>
          <div class="text-xs text-slate-500 mt-1">Thông tin, thư mục và tiến độ của phim sẽ hiện ở đây.</div>
        </div>

        <template v-else>
        <!-- Đầu phim: icon, tên, nhãn, tiến độ -->
        <div class="p-5 border-b border-slate-100">
          <div class="flex flex-wrap items-start gap-4">
            <span class="icon-tile !w-12 !h-12 !rounded-xl bg-gradient-to-br from-indigo-500 to-violet-500 text-white shadow-[0_4px_12px_rgba(99,102,241,0.3)]">
              <Clapperboard class="w-6 h-6" />
            </span>
            <div class="flex-1 min-w-[200px]">
              <div class="flex items-center gap-2 flex-wrap">
                <h2 class="text-[18px] font-semibold text-slate-900 leading-snug">{{ current.project.name }}</h2>
                <StatusBadge :status="current.project.status" />
              </div>
              <div class="flex flex-wrap gap-1.5 mt-2">
                <span class="chip"><component :is="current.project.runMode === 'BACKGROUND' ? Sparkles : MousePointerClick" class="w-3.5 h-3.5 text-slate-400" />
                  {{ current.project.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow (bạn tự bấm tạo)' }}</span>
                <span class="chip"><Globe class="w-3.5 h-3.5 text-slate-400" />{{ providerLabel(current.project.provider) }}</span>
                <span class="chip font-mono !text-[11px]"><Hash class="w-3.5 h-3.5 text-slate-400" />{{ current.project.code }}</span>
              </div>
            </div>
            <button @click="openModal(current.project)" class="btn btn-sm"><Pencil class="w-3.5 h-3.5" /> Sửa phim</button>
          </div>
          <div class="grid sm:grid-cols-2 gap-x-6 gap-y-3 mt-5">
            <div>
              <div class="flex items-center justify-between text-[12px] mb-1.5">
                <span class="inline-flex items-center gap-1.5 font-medium text-slate-600"><ImageIcon class="w-3.5 h-3.5 text-violet-500" /> Ảnh đã tạo</span>
                <span class="tabular-nums text-slate-500"><b class="text-slate-900">{{ made('IMAGE') }}</b>/{{ kc('IMAGE').TOTAL || 0 }}</span>
              </div>
              <div class="progress"><span :style="{ width: ratio(made('IMAGE'), kc('IMAGE').TOTAL) + '%' }"></span></div>
            </div>
            <div>
              <div class="flex items-center justify-between text-[12px] mb-1.5">
                <span class="inline-flex items-center gap-1.5 font-medium text-slate-600"><Video class="w-3.5 h-3.5 text-indigo-500" /> Video đã tạo</span>
                <span class="tabular-nums text-slate-500"><b class="text-slate-900">{{ made('VIDEO') }}</b>/{{ kc('VIDEO').TOTAL || 0 }}</span>
              </div>
              <div class="progress"><span :style="{ width: ratio(made('VIDEO'), kc('VIDEO').TOTAL) + '%' }"></span></div>
            </div>
          </div>
        </div>

        <div class="p-5 flex flex-col gap-4">
          <div v-if="current.project.status === 'PAUSED' && current.project.pausedReason" class="note-warn whitespace-pre-wrap flex gap-2">
            <PauseCircle class="w-4 h-4 shrink-0 mt-0.5" />
            <span><b>Tạm dừng:</b> {{ current.project.pausedReason }}</span>
          </div>
          <div v-if="current.project.status === 'RUNNING' && current.readyAccounts === 0" class="note-danger flex gap-2">
            <UserX class="w-4 h-4 shrink-0 mt-0.5" />
            <span v-if="current.project.provider === 'FLOW_EXTENSION'">
              Flow đang tạm nghỉ (Flow báo nghi ngờ tự động hoá hoặc hết lượt). App tự chạy tiếp khi hết thời gian nghỉ.
              <button @click="store.go('accounts')" class="underline font-medium ml-1">Xem ở Tài khoản</button>
            </span>
            <span v-else>
              Không còn tài khoản {{ providerLabel(current.project.provider) }} nào sẵn sàng (hết quota, cần đăng nhập hoặc chưa có).
              Job sẽ tự chạy tiếp khi có tài khoản.
              <button @click="store.go('accounts')" class="underline font-medium ml-1">Mở Tài khoản</button>
            </span>
          </div>
          <div v-if="current.project.runMode === 'FOREGROUND' && current.counts.GENERATING > 0" class="note-warn flex gap-2">
            <MousePointerClick class="w-4 h-4 shrink-0 mt-0.5" />
            <span>
              <b>Đang chờ bạn thao tác trên cửa sổ Google Flow.</b> Làm theo hộp hướng dẫn ở góc phải dưới của cửa sổ Flow:
              mở/tạo dự án → gắn ảnh theo thứ tự (thư mục ảnh đánh số đã được mở sẵn) → Copy prompt, dán, bấm Tạo →
              bấm <b>Tải xuống</b>: app tự lưu vào Folder Video. Đóng cửa sổ Flow = bỏ qua cảnh đó.
            </span>
          </div>

          <!-- Số liệu: mỗi trạng thái một icon + màu mang nghĩa -->
          <div class="grid grid-cols-2 sm:grid-cols-3 2xl:grid-cols-6 gap-2">
            <div v-for="s in stats" :key="s.key" class="rounded-xl border border-slate-200 p-3 flex items-center gap-3" :title="s.title || s.label">
              <span class="icon-tile" :class="s.tile"><component :is="s.icon" class="w-[18px] h-[18px]" /></span>
              <div class="min-w-0">
                <div class="text-[11.5px] font-medium text-slate-500 truncate">{{ s.label }}</div>
                <div class="text-[19px] font-semibold text-slate-900 tabular-nums leading-tight">{{ s.value }}</div>
              </div>
            </div>
          </div>

          <!-- Thư mục -->
          <div>
            <div class="section-label mb-2">Thư mục</div>
            <div class="grid grid-cols-1 gap-2">
              <button v-for="d in folders" :key="d.label" @click="openFolder(d.path)" :disabled="!d.path" :title="'Mở ' + d.path"
                class="group w-full flex items-center gap-3 text-left p-2 pr-3 rounded-xl border border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/40 transition disabled:opacity-50">
                <span class="icon-tile !w-8 !h-8 !rounded-lg" :class="d.tile"><component :is="d.icon" class="w-4 h-4" /></span>
                <span class="min-w-0 flex-1">
                  <span class="block text-[11.5px] font-medium text-slate-500">{{ d.label }}</span>
                  <span class="block font-mono text-[12px] text-slate-800 truncate">{{ d.path || '—' }}</span>
                </span>
                <span class="inline-flex items-center gap-1 text-[12px] font-medium text-slate-400 group-hover:text-indigo-600 shrink-0">
                  <FolderOpen class="w-4 h-4" /> Mở
                </span>
              </button>
            </div>
          </div>

          <div v-if="current.missingRefs.length" class="note-danger flex gap-2">
            <AlertTriangle class="w-4 h-4 shrink-0 mt-0.5" />
            <span>
              <b>Thiếu {{ current.missingRefs.length }} ảnh gốc</b> mà các cảnh cần nhưng chưa có prompt tạo ảnh:
              <span class="font-mono">{{ current.missingRefs.slice(0, 8).join(', ') }}{{ current.missingRefs.length > 8 ? '…' : '' }}</span>.
              Bấm <b>"Thêm ảnh có sẵn"</b> và chọn thư mục chứa các ảnh đặt tên theo ID.
            </span>
          </div>

          <!-- Nạp dữ liệu -->
          <div>
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1 mb-2">
              <span class="section-label">Nạp dữ liệu</span>
              <span class="inline-flex items-center gap-1 text-[11.5px] text-slate-500"><Images class="w-3.5 h-3.5" /> Kho ảnh: <b class="text-slate-800">{{ current.assetsCount }}</b></span>
              <button v-if="current.project.sourceDir" @click="doImport" :disabled="busy" class="inline-flex items-center gap-1 text-[11.5px] text-indigo-600 hover:underline">
                <RefreshCw class="w-3.5 h-3.5" /> Nạp lại tất cả từ thư mục Omni
              </button>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 2xl:grid-cols-3 gap-2" :class="{ 'opacity-60 pointer-events-none': busy }">
              <button @click="importMovieFolder" class="action-tile"
                title="Chọn thư mục phim (ví dụ D:\Dev\Tập 5. Hà - Cupid): app tự tìm file prompt ảnh/video (.xlsx, .txt) và ảnh có sẵn bên trong">
                <span class="icon-tile icon-tile-indigo"><FolderOpen class="w-[18px] h-[18px]" /></span>
                <span class="min-w-0"><span class="action-tile-title">Nạp cả thư mục phim</span><span class="action-tile-hint">Excel / .txt + ảnh có sẵn bên trong</span></span>
              </button>
              <label class="action-tile" title="reference-prompts.xlsx / prompt-anh-panel.xlsx / .txt mỗi dòng 1 prompt">
                <span class="icon-tile icon-tile-violet"><ImageIcon class="w-[18px] h-[18px]" /></span>
                <span class="min-w-0"><span class="action-tile-title">Thêm file ảnh</span><span class="action-tile-hint">reference-prompts, prompt-anh-panel</span></span>
                <input type="file" accept=".xlsx,.txt" class="hidden" @change="importExcel($event, 'IMAGE')" />
              </label>
              <label class="action-tile" title="all-video-prompts.xlsx / FINAL_VIDEO_PROMPTS.txt">
                <span class="icon-tile icon-tile-sky"><Video class="w-[18px] h-[18px]" /></span>
                <span class="min-w-0"><span class="action-tile-title">Thêm file video</span><span class="action-tile-hint">all-video-prompts.xlsx / .txt</span></span>
                <input type="file" accept=".xlsx,.txt" class="hidden" @change="importExcel($event, 'VIDEO')" />
              </label>
              <button @click="importAssets" class="action-tile" title="Thư mục ảnh gốc đặt tên theo ID: CHAR-CALEB-1.png, LOC-1.jpg…">
                <span class="icon-tile icon-tile-emerald"><FolderInputIcon class="w-[18px] h-[18px]" /></span>
                <span class="min-w-0"><span class="action-tile-title">Thêm ảnh có sẵn</span><span class="action-tile-hint">Ảnh đặt tên theo ID, khỏi phải tạo</span></span>
              </button>
              <button @click="tools = 'text'" class="action-tile">
                <span class="icon-tile icon-tile-amber"><PencilLine class="w-[18px] h-[18px]" /></span>
                <span class="min-w-0"><span class="action-tile-title">Nhập thủ công</span><span class="action-tile-hint">Dán prompt, mỗi dòng một cảnh</span></span>
              </button>
              <button @click="tools = 'groups'" class="action-tile">
                <span class="icon-tile icon-tile-rose"><FolderTree class="w-[18px] h-[18px]" /></span>
                <span class="min-w-0"><span class="action-tile-title">Ảnh → Video theo thư mục con</span><span class="action-tile-hint">Mỗi thư mục con thành một cảnh</span></span>
              </button>
            </div>
          </div>

          <!-- Thanh hành động: một nút chính (Chạy / Tạm dừng), các nút phụ, Xóa phim tách sang phải -->
          <div class="flex flex-wrap items-center gap-2 pt-4 border-t border-slate-100">
            <button @click="store.go('images')" class="btn-primary !px-5"><ImageIcon class="w-4 h-4" /> Tạo ảnh</button>
            <button @click="store.go('videos')" class="btn !px-5"><Video class="w-4 h-4" /> Tạo video</button>
            <button v-if="current.project.status === 'RUNNING'" @click="pause" :disabled="busy" class="btn-warn"><Pause class="w-4 h-4" /> Dừng cả hai</button>
            <button v-if="current.counts.FAILED + current.counts.CANCELLED > 0" @click="retryFailed" :disabled="busy" class="btn">
              <RotateCcw class="w-4 h-4" /> Thử lại mục lỗi
            </button>
            <button @click="verifyFiles" :disabled="busy" class="btn" title="Tìm file đã lưu bị mất/hỏng trong Folder Ảnh/Video và nhận video đã có sẵn">
              <ShieldCheck class="w-4 h-4" /> Kiểm tra file thiếu/hỏng
            </button>
            <button @click="remove" class="btn-ghost !text-rose-600 hover:!bg-rose-50 ml-auto"><Trash2 class="w-4 h-4" /> Xóa phim</button>
          </div>
        </div>
        </template>
      </section>
    </div>

    <!-- Cài đặt của phim (giống thanh "Cài đặt chung") -->
    <section v-if="current" class="card px-5 py-4">
      <div class="flex flex-wrap items-center gap-x-4 gap-y-3">
        <div class="flex items-center gap-2.5 mr-2">
          <span class="icon-tile icon-tile-slate !w-8 !h-8 !rounded-lg"><SlidersHorizontal class="w-4 h-4" /></span>
          <div>
            <div class="card-title">Cài đặt phim</div>
            <div class="text-[11px] text-slate-500">Áp dụng cho cả ảnh và video</div>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-1.5">
          <span class="chip" title="Tỉ lệ khung hình"><RectangleHorizontal class="w-3.5 h-3.5 text-slate-400" /><b>{{ current.project.aspectRatio }}</b></span>
          <span class="chip" title="Độ dài video"><Timer class="w-3.5 h-3.5 text-slate-400" /><b>{{ current.project.videoDuration ? current.project.videoDuration + 's' : 'theo prompt' }}</b></span>
          <span class="chip" title="Chất lượng tải video"><Gauge class="w-3.5 h-3.5 text-slate-400" /><b>{{ current.project.videoQuality || '720p' }}</b></span>
          <span class="chip" title="Chế độ chạy"><component :is="current.project.runMode === 'BACKGROUND' ? Sparkles : MousePointerClick" class="w-3.5 h-3.5 text-slate-400" /><b>{{ current.project.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow' }}</b></span>
          <span class="chip" title="Nền tảng"><Globe class="w-3.5 h-3.5 text-slate-400" /><b>{{ providerLabel(current.project.provider) }}</b></span>
          <span class="chip" title="Số cảnh chạy song song"><Layers class="w-3.5 h-3.5 text-slate-400" />Song song <b>{{ current.project.maxParallel }}</b></span>
          <span class="chip" title="Số lần thử lại khi lỗi"><RotateCcw class="w-3.5 h-3.5 text-slate-400" />Thử <b>{{ current.project.maxAttempts }}</b> lần</span>
        </div>
        <button @click="openModal(current.project)" class="btn btn-sm ml-auto"><Pencil class="w-3.5 h-3.5" /> Sửa</button>
      </div>
    </section>


    <ProjectModal :show="showModal" :project="editing" @close="showModal = false" @saved="onSaved" />
    <ImportToolsModal :show="!!tools" :mode="tools || 'text'" :project-id="current?.project.id" @close="tools = null" @done="onToolsDone" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  AlertTriangle, Archive, CheckCircle2, Clapperboard, Clock, FileText, Film, FolderInput as FolderInputIcon, FolderOpen, FolderTree,
  Gauge, Globe, Hash, Image as ImageIcon, Images, Info, Layers, LayoutList, Loader2, MousePointerClick, Pause, PauseCircle, Pencil,
  PencilLine, Play, Plus, RectangleHorizontal, RefreshCw, RotateCcw, ShieldCheck, SlidersHorizontal, Sparkles, Timer, Trash2, UserX, Video,
} from 'lucide-vue-next'
import ImportToolsModal from '@/components/ImportToolsModal.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ProjectModal from '@/components/ProjectModal.vue'
import { batchApi, projectsApi, jobsApi, systemApi, errMsg } from '@/api'
import { store } from '@/store'

const jobs = ref([])
const busy = ref(false)
const showModal = ref(false)
const editing = ref(null)

const current = computed(() => store.current)

const stats = computed(() => {
  const c = current.value?.counts || {}
  return [
    { key: 'pending', label: 'Chờ chạy', value: c.PENDING ?? 0, icon: Clock, tile: 'icon-tile-slate' },
    { key: 'gen', label: 'Đang tạo', value: c.GENERATING ?? 0, icon: Loader2, tile: 'icon-tile-sky' },
    { key: 'review', label: 'Chờ duyệt', value: c.AWAITING_REVIEW ?? 0, icon: FolderOpen, tile: 'icon-tile-amber' },
    { key: 'done', label: 'Đã đổi tên', value: (c.SUCCESS ?? 0) + (c.APPROVED ?? 0) + (c.DOWNLOADING ?? 0), icon: CheckCircle2, tile: 'icon-tile-emerald' },
    { key: 'failed', label: 'Lỗi', value: c.FAILED ?? 0, icon: AlertTriangle, tile: 'icon-tile-rose' },
    { key: 'cancelled', label: 'Có sẵn/hủy', title: 'Ảnh có sẵn (khỏi phải tạo) hoặc cảnh đã hủy', value: c.CANCELLED ?? 0, icon: Archive, tile: 'icon-tile-slate' },
  ]
})

const folders = computed(() => current.value ? [
  { label: 'Nguồn prompt', path: current.value.project.sourceDir, icon: FileText, tile: 'icon-tile-amber' },
  { label: 'Ảnh chờ duyệt (1.png, 2.png…)', path: current.value.reviewDirs?.IMAGE, icon: FolderOpen, tile: 'icon-tile-amber' },
  { label: 'Folder Ảnh (sau khi đổi tên)', path: current.value.project.imageDir, icon: ImageIcon, tile: 'icon-tile-violet' },
  { label: 'Video chờ duyệt (1.mp4, 2.mp4…)', path: current.value.reviewDirs?.VIDEO, icon: FolderOpen, tile: 'icon-tile-amber' },
  { label: 'Folder Video (sau khi đổi tên)', path: current.value.project.videoDir, icon: Video, tile: 'icon-tile-sky' },
] : [])

const kc = (k) => current.value?.kindCounts?.[k] || {}
const made = (k) => ['AWAITING_REVIEW', 'SUCCESS', 'APPROVED', 'DOWNLOADING'].reduce((n, st) => n + (kc(k)[st] || 0), 0)
const ratio = (a, b) => (b ? Math.min(100, Math.round((a / b) * 100)) : 0)
const pct = (pv) => ratio(pv.counts.SUCCESS, pv.total)
const providerLabel = (p) => ({ FLOW_BROWSER: 'Google Flow', FLOW_EXTENSION: 'Flow (Extension)', GOOGLE_AI_STUDIO: 'Google AI Studio', VERTEX_API: 'Vertex AI', SEEDANCE_API: 'Seedance', FAKE: 'Chạy thử' }[p] || p)
const modeText = (p) => `${p.runMode === 'BACKGROUND' ? 'Chạy ngầm' : 'Mở Flow (bạn tự bấm tạo)'} · ${providerLabel(p.provider)}`

async function loadJobs() {
  /* danh sách từng mục nằm ở màn Tạo ảnh / Tạo video */
}

async function reload() {
  await store.refresh()
  await loadJobs()
}

async function run(fn, okMsg) {
  busy.value = true
  try {
    const r = await fn()
    if (okMsg) store.toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 'success')
    await reload()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    busy.value = false
  }
}

const id = () => current.value.project.id
const describeImport = (r) => `${r.file}: ${r.rows} ${r.kind === 'IMAGE' ? 'ảnh' : 'video'} (mới ${r.added}, đổi ${r.updated}, giữ nguyên ${r.unchanged})`
  + (r.refsFromTags ? `; ${r.refsFromTags} cảnh lấy ảnh từ tag @… trong prompt` : '')
  + (r.tagMismatch ? `\n⚠ ${r.tagMismatch} cảnh có cột Reference IDs khác tag trong prompt, ví dụ: ${r.mismatchExamples[0]}` : '')

const tools = ref(null)

async function importMovieFolder() {
  let path
  try {
    path = (await systemApi.pickFolder(current.value.project.sourceDir || '')).path
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return
  }
  if (!path) return
  await run(() => projectsApi.importMovieFolder(id(), path), (r) => 'Đã nạp thư mục phim:\n' + r.files.map(describeImport).join('\n')
    + `\nẢnh có sẵn: thêm ${r.assets.added}` + (r.assets.skippedJobs ? `, bỏ qua tạo ${r.assets.skippedJobs} ảnh đã có` : '')
    + (r.skipped.length ? `\nBỏ qua: ${r.skipped.join(', ')}` : ''))
}

async function onToolsDone(r) {
  tools.value = null
  store.toast('Đã thêm ' + describeImport(r), 'success')
  await reload()
}
const doImport = () => run(() => projectsApi.importPrompts(id()), (list) => 'Đã nạp:\n' + list.map(describeImport).join('\n'))

async function importExcel(e, kind) {
  const file = e.target.files[0]
  e.target.value = ''
  if (!file) return
  await run(() => projectsApi.importFile(id(), kind, file), (r) => 'Đã nạp ' + describeImport(r)
    + (r.missingRefs.length ? `\nCòn thiếu ${r.missingRefs.length} ảnh gốc: bấm "Thêm ảnh có sẵn".` : ''))
}

async function importAssets() {
  let path
  try {
    path = (await systemApi.pickFolder(current.value.project.imageDir)).path
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return
  }
  if (!path) return
  await run(() => projectsApi.importAssetFolder(id(), path), (r) => `Đã thêm ${r.added} ảnh có sẵn`
    + (r.replaced ? `, thay ${r.replaced} ảnh` : '')
    + (r.skippedJobs ? `; bỏ qua ${r.skippedJobs} ảnh không cần tạo nữa` : '')
    + (r.notReferenced ? `\n${r.notReferenced} file không thuộc phim (tên không trùng ID nào): ${r.notReferencedNames.slice(0, 5).join(', ')}` : ''))
}
const start = () => run(() => projectsApi.start(id()), 'Đã cho phim chạy')

function formOf(p, changes) {
  return {
    name: p.name, code: p.code, sourceDir: p.sourceDir, imageDir: p.imageDir, videoDir: p.videoDir, imageReviewDir: p.imageReviewDir, videoReviewDir: p.videoReviewDir,
    runMode: p.runMode, provider: p.provider, aspectRatio: p.aspectRatio,
    maxParallel: p.maxParallel, maxAttempts: p.maxAttempts, waitAllImages: p.waitAllImages,
    videoDuration: p.videoDuration, videoQuality: p.videoQuality, autoApproveImages: !!p.autoApproveImages, ...changes,
  }
}

/** Công tắc "Tự duyệt ảnh tham chiếu": ảnh tạo xong vào kho ngay, video cần ảnh đó chạy liền */

/** Công tắc "Tạo video ngay khi đủ ảnh của cảnh" (= tắt "duyệt xong hết ảnh rồi mới tạo video") */
function toggleVideoEarly() {
  const p = current.value.project
  const early = p.waitAllImages === false
  const form = formOf(p, { waitAllImages: early })
  return run(() => projectsApi.update(id(), form), early
    ? 'Video sẽ đợi xong hết ảnh của phim rồi mới chạy'
    : 'Đủ ảnh của cảnh nào thì video cảnh đó chạy luôn')
}
const pause = () => run(() => projectsApi.pause(id()), 'Đã tạm dừng: job đang tạo sẽ chạy nốt, không giao job mới')
const retryFailed = () => run(() => projectsApi.retryFailed(id()), (r) => `Đã đưa ${r.requeued} mục về hàng đợi`)
const verifyFiles = () => run(() => projectsApi.verifyFiles(id()), (r) => (r.requeued
  ? `Đã kiểm tra ${r.checked} file: ${r.missing} mất, ${r.broken} hỏng → đưa ${r.requeued} mục về hàng đợi tạo lại.`
  : `Đã kiểm tra ${r.checked} file đã lưu: tất cả đều ổn.`)
  + (r.details.length ? '\n' + r.details.join('\n') : ''))   // kể cả "N video đã có sẵn trong Folder Video: đánh dấu xong"

async function remove() {
  if (!confirm(`Xóa phim "${current.value.project.name}" khỏi ứng dụng?\nFile ảnh/video đã lưu trong 2 folder KHÔNG bị xóa.`)) return
  await run(async () => {
    await projectsApi.remove(id())
    store.selectProject(null)
  }, 'Đã xóa phim')
}

async function openFolder(path) {
  try {
    const rd = current.value?.reviewDirs || {}
    if (path && path === rd.IMAGE) return await batchApi.openReview(id(), 'image')
    if (path && path === rd.VIDEO) return await batchApi.openReview(id(), 'video')
    await systemApi.openFolder(path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

function openModal(p) {
  editing.value = p
  showModal.value = true
}

async function onSaved(pv) {
  showModal.value = false
  store.selectProject(pv.project.id)
  await reload()
}

watch(() => store.projectId, loadJobs)

let timer
onMounted(() => {
  loadJobs()
  timer = setInterval(loadJobs, 4000)
})
onUnmounted(() => clearInterval(timer))
</script>
