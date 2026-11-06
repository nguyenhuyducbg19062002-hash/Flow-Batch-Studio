<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-[2px]">
    <form @submit.prevent="submit" class="card w-full max-w-2xl max-h-[92vh] overflow-y-auto shadow-2xl">
      <div class="card-head">
        <Film class="w-5 h-5 text-indigo-500 mt-0.5" />
        <div class="flex-1">
          <div class="card-title">{{ project ? 'Sửa phim' : 'Thêm phim mới' }}</div>
          <div class="card-sub">Nạp prompt từ thư mục kết quả của Omni-Flash-Pipeline</div>
        </div>
        <button type="button" @click="$emit('close')" class="text-slate-400 hover:text-slate-700"><X class="w-5 h-5" /></button>
      </div>

      <div class="p-5 space-y-4">
        <div>
          <label class="label">Tên phim <span class="text-rose-500">*</span></label>
          <input v-model="form.name" required placeholder="Ví dụ: Tập 5. Hà - Cupid" class="input" />
        </div>

        <div class="space-y-2">
          <FolderInput v-model="form.sourceDir" label="Thư mục kết quả của Omni-Flash-Pipeline (khuyên dùng)"
            placeholder="Chọn thư mục của tập: chứa Excel, ảnh tham chiếu, phim…"
            hint="Chọn thư mục này là đủ: app tự nạp các file Excel quen thuộc, tự tìm Folder Ảnh và Folder Video trong đó (không phân biệt hoa thường, đủ dấu hay thiếu dấu), chưa có thì tự tạo mới khi bấm Tạo phim." />
          <template v-if="!project">
            <div v-if="scanning" class="text-xs text-slate-500">Đang quét thư mục…</div>
            <div v-else-if="scanError" class="text-xs text-rose-600">{{ scanError }}</div>
            <ul v-else-if="scan" class="text-xs space-y-1 px-1">
              <li :class="scan.promptFiles.length ? 'text-emerald-700' : 'text-slate-500'">
                {{ scan.promptFiles.length ? `✓ Tìm thấy ${scan.promptFiles.length} file prompt (${scan.promptFiles.join(', ')})` : 'Không thấy file prompt trong thư mục này: thêm Excel sau ở trang Phim.' }}
              </li>
              <li :class="scan.imageExists ? 'text-emerald-700' : 'text-amber-700'">
                {{ scan.imageExists ? `✓ Dùng thư mục ảnh có sẵn "${base(scan.imageDir)}" (${scan.imageCount} ảnh)` : `+ Chưa có thư mục ảnh: sẽ tạo mới "${base(scan.imageDir)}"` }}
              </li>
              <li :class="scan.videoExists ? 'text-emerald-700' : 'text-amber-700'">
                {{ scan.videoExists ? `✓ Dùng thư mục video có sẵn "${base(scan.videoDir)}" (${scan.videoCount} video)` : `+ Chưa có thư mục video: sẽ tạo mới "${base(scan.videoDir)}"` }}
              </li>
            </ul>
          </template>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FolderInput v-model="form.imageDir" label="Folder Ảnh (sau khi đổi tên)" placeholder="Để trống: tự tìm / tạo"
            hint="Bấm &quot;Đổi tên&quot; thì ảnh được chuyển vào đây, tên = Reference ID (CHAR-CLARA-1.png). Video lấy ảnh tham chiếu từ đây." />
          <FolderInput v-model="form.videoDir" label="Folder Video (sau khi đổi tên)" placeholder="Để trống: tự tìm / tạo"
            hint="Bấm &quot;Đổi tên&quot; thì video được chuyển vào đây, tên = tên cảnh + số (EP06-S1-01.mp4)." />
          <FolderInput v-model="form.imageReviewDir" label="Thư mục ảnh chờ duyệt" placeholder="Để trống: &quot;Ảnh chờ duyệt&quot; cạnh Folder Ảnh"
            hint="Ảnh tạo xong tải về đây, tên theo số thứ tự (001.png, 002.png…) để nhân viên duyệt." />
          <FolderInput v-model="form.videoReviewDir" label="Thư mục video chờ duyệt" placeholder="Để trống: &quot;Video chờ duyệt&quot; cạnh Folder Video"
            hint="Video tạo xong tải về đây, tên theo số thứ tự (001.mp4, 002.mp4…)." />
        </div>

        <div>
          <label class="label">Chế độ chạy</label>
          <div class="grid grid-cols-2 gap-2 p-1 rounded-xl bg-slate-100">
            <button v-for="m in modes" :key="m.value" type="button" @click="form.runMode = m.value"
              :class="form.runMode === m.value ? 'bg-white shadow-sm text-indigo-700' : 'text-slate-600 hover:text-slate-800'"
              class="px-3 py-2 rounded-lg text-left text-sm transition">
              <div class="font-medium flex items-center gap-1.5"><component :is="m.icon" class="w-4 h-4" /> {{ m.label }}</div>
              <div class="text-[11px] opacity-80 mt-0.5">{{ m.desc }}</div>
            </button>
          </div>
        </div>

        <p class="text-[11px] text-slate-500 px-1">
          Quy tắc bắt buộc: video chỉ được tạo khi ĐỦ ảnh tham chiếu (có sẵn hoặc app đã tạo). Thiếu ảnh nào thì app tạo ảnh đó trước.
        </p>

        <label class="flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition"
          :class="form.waitAllImages ? 'border-indigo-200 bg-indigo-50/50' : 'border-slate-200'">
          <input type="checkbox" v-model="form.waitAllImages" class="mt-0.5 w-4 h-4 accent-indigo-600" />
          <span>
            <span class="block text-sm font-medium text-slate-700">Xong hết ảnh rồi mới tạo video</span>
            <span class="block text-[11px] text-slate-500 mt-0.5">
              Bật: tạo toàn bộ ảnh reference trước, sau đó mới tự upload ảnh lên Flow để tạo video.
              Tắt (chạy theo cảnh): ảnh có sẵn thì bỏ qua; cảnh nào đủ ảnh thì tạo video ngay; cảnh nào thiếu ảnh thì app
              tạo đúng ảnh đó trước rồi mới tạo video. Ảnh không cảnh nào cần thì không tạo.
            </span>
          </span>
        </label>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label class="label">Nền tảng</label>
            <select v-model="form.provider" class="input">
              <option value="FLOW_EXTENSION">Google Flow qua Extension (Chrome của bạn)</option>
              <option value="FLOW_BROWSER">Google Flow (trình duyệt Chrome)</option>
              <option value="GOOGLE_AI_STUDIO">Google AI Studio (API key)</option>
              <option value="VERTEX_API">Vertex AI API (Imagen / Veo)</option>
              <option value="SEEDANCE_API">Seedance qua ModelArk (API key)</option>
              <option value="FAKE">Chạy thử (không gọi nền tảng nào)</option>
            </select>
            <p v-if="form.provider === 'FLOW_EXTENSION'" class="hint">Không cần thêm tài khoản: chỉ cần cài extension Flow Batch Studio vào Chrome và mở một tab flow.google.com đã đăng nhập (dùng Google đang đăng nhập ở đó). Extension tự upload ảnh, gõ prompt có @tag ngay trên tab Flow. Chạy ngầm: tự bấm Tạo và tải về. Mở Flow: chỉ điền sẵn, bạn tự bấm Tạo; file tải về được app tự nhận. Mỗi lúc chạy một cảnh.</p>
            <p v-else-if="form.provider === 'GOOGLE_AI_STUDIO'" class="hint">Google AI Studio: tạo ảnh (Imagen 3) và video (Veo 2) qua API chính thức từ aistudio.google.com bằng API key. Chế độ chạy: chạy nền.</p>
            <p v-else-if="form.provider === 'SEEDANCE_API'" class="hint">Seedance tự gửi prompt kèm tới 9 ảnh tham chiếu và tự tải video về. Cần tài khoản Seedance (API key) ở màn Tài khoản; chế độ chạy phải là chạy nền.</p>
            <p v-else class="hint">Prompt Omni dùng tới 7 ảnh reference: nên dùng Google Flow.</p>
          </div>
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div>
              <label class="label">Song song</label>
              <input v-model.number="form.maxParallel" type="number" min="1" max="20" class="input" title="Số cảnh chạy cùng lúc; tối đa bằng số tài khoản sẵn sàng (mỗi tài khoản một cửa sổ)" />
            </div>
            <div>
              <label class="label">Số lần thử</label>
              <input v-model.number="form.maxAttempts" type="number" min="1" max="10" class="input" />
            </div>
            <div>
              <label class="label">Tỉ lệ</label>
              <select v-model="form.aspectRatio" class="input">
                <option>16:9</option><option>9:16</option><option>1:1</option>
                <template v-if="isSeedance"><option>4:3</option><option>3:4</option><option>21:9</option>
                  <option value="adaptive">Smart (tự theo ảnh)</option></template>
              </select>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label class="label">Độ dài video</label>
            <select v-model="form.videoDuration" class="input">
              <option :value="null">Mặc định (theo prompt / nền tảng)</option>
              <option v-for="s in durations" :key="s" :value="s">{{ s }} giây</option>
            </select>
          </div>
          <div>
            <label class="label">Chất lượng tải video về</label>
            <select v-model="form.videoQuality" class="input">
              <option value="480p">480p</option>
              <option value="720p">720p</option>
              <option value="1080p">1080p</option>
            </select>
          </div>
          <label v-if="isSeedance" class="sm:col-span-2 flex items-center gap-2 text-sm text-slate-700">
            <input v-model="form.generateAudio" type="checkbox" class="rounded border-slate-300 text-indigo-600" />
            Tạo kèm âm thanh (sound): thoại, tiếng động theo prompt
          </label>
          <p class="hint col-span-2 -mt-2">Prompt Omni đã ghi sẵn thời lượng mỗi cảnh ("Requested duration: 10 seconds"); chỉ đổi độ dài khi nền tảng cần chọn riêng.</p>
        </div>
      </div>

      <div class="flex justify-end gap-2 px-5 py-4 border-t border-slate-100 bg-slate-50/60 rounded-b-2xl">
        <button type="button" @click="$emit('close')" class="btn">Hủy</button>
        <button type="submit" :disabled="saving" class="btn-primary">{{ project ? 'Lưu thay đổi' : 'Tạo phim' }}</button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Film, MonitorPlay, Sparkles, X } from 'lucide-vue-next'
import FolderInput from './FolderInput.vue'
import { projectsApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({ show: Boolean, project: { type: Object, default: null } })
const emit = defineEmits(['close', 'saved'])

const modes = [
  { value: 'BACKGROUND', label: 'Chạy ngầm', desc: 'Tool tự tạo và lưu vào folder', icon: Sparkles },
  { value: 'FOREGROUND', label: 'Mở Flow', desc: 'Điền sẵn, bạn tự bấm tạo', icon: MonitorPlay },
]

const blank = () => ({
  name: '', sourceDir: '', imageDir: '', videoDir: '', imageReviewDir: '', videoReviewDir: '', runMode: 'BACKGROUND', provider: 'FLOW_EXTENSION',
  aspectRatio: '16:9', maxParallel: 3, maxAttempts: 3, waitAllImages: false,
  videoDuration: null, videoQuality: '720p', autoApproveImages: false, generateAudio: true,
})
const form = ref(blank())
const isSeedance = computed(() => form.value.provider === 'SEEDANCE_API')
// Seedance 2.0: 4–15 giây; Flow / Veo chỉ các mốc ngắn
const durations = computed(() => isSeedance.value ? [4, 5, 6, 8, 10, 12, 15] : [4, 5, 6, 8, 10])
const saving = ref(false)

// Thư mục kết quả của Omni-Flash-Pipeline (phim mới): quét sau khi dừng gõ / chọn xong, rồi điền sẵn tên phim, Folder Ảnh,
// Folder Video. Chỉ ghi đè những ô còn trống hoặc do lần quét trước điền; ô người dùng tự gõ thì giữ nguyên.
// Backend cũng tự tìm / tạo khi hai ô này trống, nên bấm Tạo phim ngay (trước khi quét xong) vẫn đúng.
const scan = ref(null)
const scanning = ref(false)
const scanError = ref('')
let autoName = ''
let autoImage = ''
let autoVideo = ''
let scanTimer = null
let scanSeq = 0
const base = (p) => String(p || '').split(/[\\/]/).filter(Boolean).pop() || p

watch(() => form.value.sourceDir, (path) => {
  clearTimeout(scanTimer)
  scan.value = null
  scanError.value = ''
  const seq = ++scanSeq
  if (props.project || !path || !path.trim()) { scanning.value = false; return }
  scanning.value = true
  scanTimer = setTimeout(async () => {
    try {
      const s = await projectsApi.scanFolder(path.trim())
      if (seq !== scanSeq) return   // đã đổi thư mục trong lúc quét: bỏ kết quả cũ
      scan.value = s
      if (!form.value.name || form.value.name === autoName) {
        form.value.name = s.name
        autoName = s.name
      }
      if (!form.value.imageDir || form.value.imageDir === autoImage) {
        form.value.imageDir = s.imageDir
        autoImage = s.imageDir
      }
      if (!form.value.videoDir || form.value.videoDir === autoVideo) {
        form.value.videoDir = s.videoDir
        autoVideo = s.videoDir
      }
    } catch (e) {
      if (seq === scanSeq) scanError.value = errMsg(e)
    } finally {
      if (seq === scanSeq) scanning.value = false
    }
  }, 600)
})

watch(() => [props.show, props.project], () => {
  if (!props.show) return
  scan.value = null
  scanError.value = ''
  autoName = autoImage = autoVideo = ''
  form.value = props.project
    ? { ...blank(), ...props.project, waitAllImages: props.project.waitAllImages !== false,
        videoDuration: props.project.videoDuration ?? null, videoQuality: props.project.videoQuality || '720p',
        generateAudio: props.project.generateAudio ?? true,
        autoApproveImages: !!props.project.autoApproveImages }
    : blank()
}, { immediate: true })

async function submit() {
  saving.value = true
  try {
    const saved = props.project
      ? await projectsApi.update(props.project.id, form.value)
      : await projectsApi.create(form.value)
    // phim mới: backend đã tự nạp prompt + ảnh có sẵn; hiện kết quả (không có gì để nạp thì chỉ nhắc nhẹ)
    const notes = Array.isArray(saved?.autoImport) ? saved.autoImport : []
    store.toast(props.project ? 'Đã lưu phim'
      : 'Đã tạo phim.' + (notes.length ? '\n' + notes.join('\n') : ' Chưa có file prompt để nạp: thêm Excel ảnh / video ở trang Phim.'), 'success')
    emit('saved', saved)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    saving.value = false
  }
}
</script>
