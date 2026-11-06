<template>
  <div class="space-y-4">
    <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
      <div v-for="s in summary" :key="s.status" class="card px-4 py-3">
        <StatusBadge :status="s.status" />
        <div class="stat-value">{{ s.count }}</div>
      </div>
    </div>

    <section class="card">
      <!-- Cửa sổ hẹp: nút xuống dòng dưới tiêu đề thay vì bóp tiêu đề -->
      <div class="card-head flex-wrap items-center gap-y-3">
        <Users class="w-5 h-5 text-slate-400 shrink-0" />
        <div class="flex-1 min-w-[220px]">
          <div class="card-title whitespace-nowrap">Danh sách tài khoản <span class="count-pill ml-1">{{ store.accounts.length }}</span></div>
          <div class="card-sub">Ưu tiên nhỏ được dùng trước. Hết quota thì job tự chuyển sang tài khoản kế tiếp.</div>
        </div>
        <div class="flex gap-2 shrink-0">
          <button @click="checkAll" :disabled="checkingAll" class="btn"
            title="Tự mở Chrome ẩn kiểm tra từng tài khoản Flow đã đăng nhập chưa rồi cập nhật trạng thái">
            <RotateCcw class="w-4 h-4" :class="checkingAll ? 'animate-spin' : ''" /> Kiểm tra đăng nhập
          </button>
          <button @click="openModal(null)" class="btn-primary"><Plus class="w-4 h-4" /> Thêm tài khoản</button>
        </div>
      </div>

      <div class="overflow-x-auto">
        <table class="table">
          <thead>
            <tr>
              <th>Ưu tiên</th>
              <th>Tài khoản</th>
              <th>Trạng thái</th>
              <th>Dùng gần nhất</th>
              <th class="text-right">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!store.accounts.length">
              <td colspan="5" class="!py-12 text-center">
                <User class="w-10 h-10 mx-auto text-slate-300" />
                <div class="text-sm font-medium text-slate-700 mt-2">Chưa có tài khoản nào</div>
                <div class="text-xs text-slate-500 mt-0.5">Thêm ít nhất một tài khoản Google Flow để bắt đầu.</div>
              </td>
            </tr>
            <tr v-for="a in store.accounts" :key="a.id">
              <td class="font-mono text-xs text-slate-500">{{ a.priority }}</td>
              <td class="min-w-[200px]">
                <div class="font-medium text-slate-900 [overflow-wrap:anywhere]">{{ a.email }}</div>
                <div class="text-[11px] text-slate-500 mt-0.5">{{ providerLabel(a.provider) }}</div>
                <div v-if="a.proxy" class="text-[11px] font-mono text-indigo-600 mt-0.5">proxy {{ a.proxy }}</div>
                <div v-if="a.maxConcurrent > 1" class="text-[11px] text-emerald-700 mt-0.5">chạy {{ a.maxConcurrent }} lệnh cùng lúc</div>
                <div v-if="a.notes" class="text-[11px] text-slate-500 mt-0.5">{{ a.notes }}</div>
              </td>
              <td>
                <StatusBadge :status="a.status" />
                <div v-if="checking[a.id]" class="text-[11px] text-indigo-600 mt-1">đang kiểm tra đăng nhập…</div>
                <div v-else-if="lastCheck[a.id]" class="text-[11px] text-slate-500 mt-1 max-w-xs">{{ lastCheck[a.id] }}</div>
                <div v-if="a.status === 'COOLDOWN' && a.cooldownUntil" class="text-[11px] text-amber-600 mt-1">tới {{ fmt(a.cooldownUntil) }}</div>
                <div v-if="a.lastErrorMessage" class="text-[11px] text-rose-600 mt-1 max-w-xs truncate" :title="a.lastErrorMessage">{{ a.lastErrorMessage }}</div>
              </td>
              <td class="text-xs text-slate-500 whitespace-nowrap tabular-nums">{{ a.lastUsedAt ? fmt(a.lastUsedAt) : 'Chưa dùng' }}</td>
              <td class="w-[1%]">
                <div class="flex flex-wrap justify-end gap-1 min-w-[200px]">
                <template v-if="a.provider === 'FLOW_BROWSER'">
                  <button @click="act(a, 'openLogin')" :disabled="a.status === 'LEASED'" class="btn btn-sm"><KeyRound class="w-3 h-3" /> Đăng nhập</button>
                  <button @click="check(a)" :disabled="checking[a.id] || a.status === 'LEASED'" class="btn btn-sm"
                    title="Tự mở Chrome ẩn kiểm tra đã đăng nhập chưa rồi cập nhật trạng thái">
                    <RotateCcw class="w-3 h-3" :class="checking[a.id] ? 'animate-spin' : ''" /> Kiểm tra lại
                  </button>
                  <button v-if="a.status === 'NEED_LOGIN'" @click="act(a, 'confirmLogin')" class="btn-success btn-sm">Đã đăng nhập xong</button>
                </template>
                <button v-if="['COOLDOWN', 'DISABLED'].includes(a.status)" @click="act(a, 'enable')" class="btn btn-sm">Bật lại</button>
                <button v-if="['READY', 'COOLDOWN', 'NEED_LOGIN'].includes(a.status)" @click="act(a, 'disable')" class="btn btn-sm">Tắt</button>
                <button @click="openModal(a)" class="btn btn-sm" title="Sửa tài khoản"><Pencil class="w-3 h-3" /></button>
                <button @click="remove(a)" :disabled="a.status === 'LEASED'" class="btn-danger btn-sm" title="Xóa tài khoản"><Trash2 class="w-3 h-3" /></button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div class="note-warn space-y-1">
      <p><b>Đăng nhập lần đầu (Google Flow):</b> bấm "Đăng nhập" → Chrome mở với profile riêng của tài khoản → đăng nhập Google, mở thử Flow → <b>đóng cửa sổ Chrome đó</b>. App tự kiểm tra và đổi sang "Sẵn sàng" (không cần bấm gì thêm). Nếu trạng thái chưa đổi, bấm <b>"Kiểm tra lại"</b>; "Đã đăng nhập xong" chỉ để ép sang "Sẵn sàng" khi bạn chắc chắn.</p>
      <p>Tool không lưu mật khẩu. Phiên hết hạn thì tài khoản tự chuyển sang "Cần đăng nhập" và job chạy tiếp bằng tài khoản khác.</p>
    </div>

    <!-- Thêm / sửa -->
    <div v-if="showModal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-[2px]">
      <form @submit.prevent="save" class="card w-full max-w-lg shadow-2xl" autocomplete="off">
        <div class="card-head items-center">
          <User class="w-5 h-5 text-indigo-500" />
          <div class="card-title flex-1">{{ editing ? 'Sửa tài khoản' : 'Thêm tài khoản' }}</div>
          <button type="button" @click="showModal = false" class="text-slate-400 hover:text-slate-700"><X class="w-5 h-5" /></button>
        </div>
        <div class="p-5 space-y-4">
          <div>
            <label class="label">{{ isApi ? 'Tên tài khoản' : 'Email' }} <span class="text-rose-500">*</span></label>
            <input v-model="form.email" :disabled="!!editing" required class="input" :placeholder="isApi ? 'seedance-1' : 'ten@gmail.com'"
              name="afm-account-email" autocomplete="off" />
            <p v-if="isApi" class="hint">Đặt tên gì cũng được, không trùng tên tài khoản khác. Không cần email.</p>
            <p v-else class="hint">App không cần và không lưu mật khẩu Google: bạn tự đăng nhập một lần bằng nút "Đăng nhập".</p>
          </div>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label class="label">Nền tảng</label>
              <select v-model="form.provider" :disabled="!!editing" class="input">
                <option value="FLOW_BROWSER">Google Flow</option>
                <option value="GOOGLE_AI_STUDIO">Google AI Studio (API key)</option>
                <option value="VERTEX_API">Vertex AI</option>
                <option value="SEEDANCE_API">Seedance (ModelArk)</option>
                <option value="FAKE">Chạy thử</option>
              </select>
            </div>
            <div>
              <label class="label">Ưu tiên (nhỏ = dùng trước)</label>
              <input v-model.number="form.priority" type="number" class="input" />
            </div>
          </div>
          <div v-if="form.provider === 'FLOW_BROWSER' || form.provider === 'FLOW_EXTENSION' || form.provider === 'FAKE'">
            <label class="label">Số lệnh chạy cùng lúc (1–5)</label>
            <input v-model.number="form.maxConcurrent" type="number" min="1" max="5" class="input !w-28" />
            <p class="hint">
              <b>1</b> = chạy lần lượt như cũ. Từ <b>2</b> trở lên: trong lúc Flow đang tạo lệnh này, app gửi tiếp các lệnh sau trên cùng tài khoản;
              lệnh nào xong trước thì tải về và lưu vào folder trước. Đã thử ổn với gói ULTRA (3 lệnh); gói khác chưa thử, nên tăng từ từ.
              Gửi dồn nhiều lệnh một tài khoản làm hết lượt nhanh hơn và dễ bị Google để ý hơn.
              Nhớ nâng ô "Song song" của phim (tổng số lệnh chạy cùng lúc của cả phim) cho đủ.
            </p>
          </div>
          <div v-if="form.provider === 'FLOW_BROWSER'">
            <label class="label">Proxy (tùy chọn, không dùng thì để trống)</label>
            <!-- name/autocomplete lạ để trình duyệt không tự điền mật khẩu đã lưu vào ô này -->
            <input v-model="form.proxy" class="input font-mono" placeholder="Để trống nếu không dùng proxy"
              name="afm-proxy-server" autocomplete="off" autocorrect="off" spellcheck="false" data-lpignore="true" data-form-type="other" />
            <p class="hint">Dạng: <span class="font-mono">ip:port</span>, <span class="font-mono">ip:port:user:pass</span> hoặc <span class="font-mono">http://user:pass@ip:port</span>. Đây <b>không phải</b> chỗ nhập mật khẩu Google.</p>
          </div>
          <template v-if="form.provider === 'VERTEX_API'">
            <div>
              <label class="label">File service-account JSON</label>
              <input v-model="form.credentialFile" class="input font-mono" placeholder="D:\keys\project-sa.json" />
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div><label class="label">GCP project</label><input v-model="form.gcpProject" class="input font-mono" /></div>
              <div><label class="label">Vùng (location)</label><input v-model="form.gcpLocation" class="input font-mono" placeholder="us-central1" /></div>
            </div>
          </template>
                    <template v-if="form.provider === 'GOOGLE_AI_STUDIO'">
            <div>
              <label class="label">API key Google AI Studio <span v-if="!editing?.hasApiKey" class="text-rose-500">*</span></label>
              <input v-model="form.apiKey" type="password" class="input font-mono" autocomplete="off" name="afm-studio-key"
                :required="!editing?.hasApiKey"
                :placeholder="editing?.hasApiKey ? 'Đã lưu key: để trống nếu không đổi' : 'Dán API key từ aistudio.google.com'" />
              <p class="hint">Lấy key tại <a href="https://aistudio.google.com/app/apikey" target="_blank" class="text-indigo-600 hover:underline">aistudio.google.com/app/apikey</a>. Key chỉ lưu trong máy này và không hiện lại.</p>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label class="label">Model video</label>
                <input v-model="form.videoModel" list="afm-studio-video-models" class="input font-mono" placeholder="veo-2.0-generate-001" />
                <datalist id="afm-studio-video-models">
                  <option value="veo-2.0-generate-001">Veo 2 (mặc định)</option>
                  <option value="veo-3.1-generate-preview">Veo 3.1 preview</option>
                </datalist>
              </div>
              <div>
                <label class="label">Model ảnh</label>
                <input v-model="form.imageModel" list="afm-studio-image-models" class="input font-mono" placeholder="imagen-3.0-generate-002" />
                <datalist id="afm-studio-image-models">
                  <option value="imagen-3.0-generate-002">Imagen 3 (mặc định, chất lượng cao)</option>
                  <option value="imagen-3.0-fast-generate-001">Imagen 3 Fast (tốc độ nhanh)</option>
                </datalist>
              </div>
            </div>
            <p class="hint -mt-2">Để trống = dùng mặc định Imagen 3 (ảnh) và Veo 2 (video). Mỗi tài khoản chạy độc lập qua API; muốn tăng lượt chạy có thể thêm nhiều key.</p>
          </template>
          <template v-if="form.provider === 'SEEDANCE_API'">
            <div>
              <label class="label">API key ModelArk <span v-if="!editing?.hasApiKey" class="text-rose-500">*</span></label>
              <input v-model="form.apiKey" type="password" class="input font-mono" autocomplete="off" name="afm-ark-key"
                :required="!editing?.hasApiKey"
                :placeholder="editing?.hasApiKey ? 'Đã lưu key: để trống nếu không đổi' : 'Dán API key từ ModelArk console'" />
              <p class="hint">Key chỉ lưu trong máy này và không hiện lại trên giao diện.</p>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label class="label">Model video</label>
                <input v-model="form.videoModel" list="afm-seedance-models" class="input font-mono" placeholder="dreamina-seedance-2-0-mini-260615" />
                <datalist id="afm-seedance-models">
                  <option value="dreamina-seedance-2-0-260128">Seedance 2.0</option>
                  <option value="dreamina-seedance-2-0-mini-260615">Seedance 2.0 mini (mặc định, 720p/480p)</option>
                </datalist>
              </div>
              <div>
                <label class="label">Model ảnh</label>
                <input v-model="form.imageModel" class="input font-mono" placeholder="seedream-5-0-lite-260128" />
              </div>
            </div>
            <p class="hint -mt-2">Bấm vào ô để chọn model có sẵn, hoặc dán mã khác: trên Playground chọn model rồi xem mã ở thanh địa chỉ
              (<span class="font-mono">...gen_video?model=<b>mã-model</b></span>). Để trống = Seedance 2.0 mini. Model phải được bật (Activate) trong ModelArk console. Mỗi tài khoản chạy một cảnh
              một lúc; muốn chạy song song thì thêm cùng key thêm lần nữa với tên khác.</p>
          </template>
          <div>
            <label class="label">Ghi chú</label>
            <input v-model="form.notes" class="input" />
          </div>
        </div>
        <div class="flex justify-end gap-2 px-5 py-4 border-t border-slate-100 bg-slate-50/60 rounded-b-2xl">
          <button type="button" @click="showModal = false" class="btn">Hủy</button>
          <button type="submit" class="btn-primary">Lưu</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { KeyRound, Pencil, Plus, RotateCcw, Trash2, User, Users, X } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { accountsApi, errMsg } from '@/api'
import { store } from '@/store'

const showModal = ref(false)
const editing = ref(null)
const blank = () => ({ email: '', provider: 'FLOW_BROWSER', priority: 100, maxConcurrent: 1, proxy: '', notes: '', credentialFile: '', gcpProject: '', gcpLocation: '', apiKey: '', videoModel: '', imageModel: '' })
const form = ref(blank())
const isApi = computed(() => form.value.provider === 'SEEDANCE_API' || form.value.provider === 'GOOGLE_AI_STUDIO')

const summary = computed(() => ['READY', 'LEASED', 'COOLDOWN', 'NEED_LOGIN', 'DISABLED']
  .map((status) => ({ status, count: store.accounts.filter((a) => a.status === status).length })))

const providerLabel = (p) => ({ FLOW_BROWSER: 'Google Flow', FLOW_EXTENSION: 'Flow (Extension)', GOOGLE_AI_STUDIO: 'Google AI Studio', VERTEX_API: 'Vertex AI', SEEDANCE_API: 'Seedance', FAKE: 'Chạy thử' }[p] || p)
const fmt = (s) => new Date(s).toLocaleString('vi-VN')

function openModal(a) {
  editing.value = a
  form.value = a ? { ...blank(), ...Object.fromEntries(Object.entries(a).map(([k, v]) => [k, v ?? ''])) } : blank()
  if (a) form.value.maxConcurrent = a.maxConcurrent || 1   // tài khoản cũ chưa có giá trị: 1 = chạy lần lượt
  showModal.value = true
}

async function save() {
  try {
    if (editing.value) await accountsApi.update(editing.value.id, form.value)
    else await accountsApi.create(form.value)
    showModal.value = false
    store.toast(editing.value ? 'Đã lưu tài khoản' : 'Đã thêm tài khoản' + (form.value.provider === 'FLOW_BROWSER' ? '. Bấm "Đăng nhập" để đăng nhập lần đầu.' : ''), 'success')
    await store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

const MSG = {
  openLogin: 'Đã mở Chrome. Đăng nhập Google rồi ĐÓNG cửa sổ Chrome đó: app tự nhận ra và chuyển sang "Sẵn sàng".',
  confirmLogin: 'Tài khoản đã sẵn sàng',
  enable: 'Đã bật lại tài khoản',
  disable: 'Đã tắt tài khoản',
}

async function act(a, action) {
  try {
    await accountsApi[action](a.id)
    store.toast(MSG[action], 'success')
    await store.refresh()
    if (action === 'openLogin') watchLogin(a)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

// ---- Kiểm tra đăng nhập: app tự mở Chrome ẩn với profile của tài khoản, xem đã đăng nhập chưa rồi cập nhật trạng thái ----
const checking = ref({})        // id tài khoản → đang kiểm tra
const lastCheck = ref({})       // id tài khoản → thông báo của lần kiểm tra gần nhất
const checkingAll = ref(false)
let alive = true
onBeforeUnmount(() => { alive = false })

const isFlow = (a) => a.provider === 'FLOW_BROWSER'

/** silent: không hiện thông báo (kiểm tra nền); chỉ báo khi tài khoản vừa chuyển sang Sẵn sàng. */
async function check(a, silent = false) {
  if (checking.value[a.id]) return null
  checking.value = { ...checking.value, [a.id]: true }
  try {
    const r = await accountsApi.checkLogin(a.id)
    lastCheck.value = { ...lastCheck.value, [a.id]: r.message }
    if (!silent || (a.status !== 'READY' && r.account?.status === 'READY')) {
      store.toast(`${a.email}: ${r.message}`, r.account?.status === 'READY' ? 'success' : 'error')
    }
    await store.refresh()
    return r
  } catch (e) {
    if (!silent) store.toast(errMsg(e), 'error')
    return null
  } finally {
    checking.value = { ...checking.value, [a.id]: false }
  }
}

async function checkAll() {
  checkingAll.value = true
  try {
    for (const a of [...store.accounts].filter((x) => isFlow(x) && x.status !== 'LEASED')) {
      if (!alive) break
      await check(a, false)
    }
  } finally {
    checkingAll.value = false
  }
}

// Sau khi bấm "Đăng nhập": cứ 6 giây kiểm tra một lần (tối đa ~6 phút). Cửa sổ Chrome đăng nhập còn mở thì kết quả là "đang mở";
// đăng nhập xong và đóng cửa sổ thì lần kiểm tra kế tiếp thấy đã đăng nhập → tài khoản tự chuyển sang Sẵn sàng.
const watching = new Set()
async function watchLogin(a) {
  if (watching.has(a.id)) return
  watching.add(a.id)
  try {
    for (let i = 0; i < 60 && alive; i++) {
      await new Promise((resolve) => setTimeout(resolve, 6000))
      if (!alive) break
      const r = await check(a, true)
      if (r?.account?.status === 'READY') break
    }
  } finally {
    watching.delete(a.id)
  }
}

// Mở trang: tài khoản đang "Cần đăng nhập" được kiểm tra một lần (có thể đã đăng nhập từ trước mà trạng thái bị kẹt)
onMounted(async () => {
  for (const a of store.accounts.filter((x) => isFlow(x) && x.status === 'NEED_LOGIN')) {
    if (!alive) break
    await check(a, true)
  }
})

async function remove(a) {
  if (!confirm(`Xóa tài khoản ${a.email}? Thư mục profile (cookie) vẫn giữ trên đĩa.`)) return
  try {
    await accountsApi.remove(a.id)
    await store.refresh()
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
</script>
