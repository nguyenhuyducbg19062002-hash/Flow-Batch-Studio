<template>
  <div class="space-y-4">
    <div v-if="!store.current" class="card py-20 text-center text-slate-500">
      Chưa có phim. Vào trang <button class="underline text-indigo-600" @click="store.go('projects')">Phim</button> để tạo phim trước.
    </div>

    <template v-else>
      <!-- Đầu màn: trạng thái phần này + nút chính -->
      <section class="card p-5">
        <div class="flex flex-wrap items-center gap-4">
          <span class="icon-tile !w-11 !h-11 !rounded-xl" :class="isImage ? 'icon-tile-violet' : 'icon-tile-sky'">
            <component :is="isImage ? ImageIcon : Video" class="w-5 h-5" />
          </span>
          <div class="flex-1 min-w-[220px]">
            <div class="flex items-center gap-2 flex-wrap">
              <h2 class="text-[18px] font-semibold text-slate-900 leading-snug">{{ label.title }} · {{ store.current.project.name }}</h2>
              <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[11px] font-semibold border"
                :class="running ? 'bg-sky-50 text-sky-700 border-sky-200' : 'bg-slate-50 text-slate-600 border-slate-200'">
                <span class="w-1.5 h-1.5 rounded-full" :class="running ? 'bg-sky-500 animate-pulse' : 'bg-slate-400'"></span>
                {{ running ? 'Đang chạy' : 'Đang dừng' }}
              </span>
            </div>
            <div class="text-[12.5px] text-slate-500 mt-1">
              Tạo xong tải về thư mục chờ duyệt, đặt tên theo số (1{{ ext }}, 2{{ ext }}, 3{{ ext }}…) đúng thứ tự file prompt.
            </div>
          </div>
          <button v-if="!running" @click="start" :disabled="busy || !counts.TOTAL" class="btn-primary !px-5">
            <Play class="w-4 h-4" /> {{ label.run }}
          </button>
          <button v-else @click="pause" :disabled="busy" class="btn-warn !px-5"><Pause class="w-4 h-4" /> Tạm dừng</button>
        </div>

        <div v-if="store.current.project.pausedReason && !running" class="note-warn mt-4 flex gap-2 !text-sm">
          <Info class="w-4 h-4 shrink-0 mt-0.5" /><span>{{ store.current.project.pausedReason }}</span>
        </div>
        <div v-if="running && store.current.readyAccounts === 0" class="note-danger mt-4 flex gap-2 !text-sm">
          <UserX class="w-4 h-4 shrink-0 mt-0.5" />
          <span>Không còn tài khoản nào sẵn sàng (hết lượt, cần đăng nhập hoặc chưa có). App tự chạy tiếp khi có tài khoản.
            <button @click="store.go('accounts')" class="underline font-medium ml-1">Mở Tài khoản</button></span>
        </div>

        <!-- Số liệu -->
        <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2 mt-4">
          <button v-for="s in stats" :key="s.key" @click="filter = filter === s.filter ? '' : s.filter" :title="s.title"
            class="rounded-xl border p-3 flex items-center gap-3 text-left transition"
            :class="filter && filter === s.filter ? 'border-indigo-300 ring-1 ring-indigo-200 bg-indigo-50/40' : 'border-slate-200 hover:border-slate-300'">
            <span class="icon-tile" :class="s.tile"><component :is="s.icon" class="w-[18px] h-[18px]" /></span>
            <div class="min-w-0">
              <div class="text-[11.5px] font-medium text-slate-500 truncate">{{ s.label }}</div>
              <div class="text-[19px] font-semibold text-slate-900 tabular-nums leading-tight">{{ s.value }}</div>
            </div>
          </button>
        </div>
        <div class="flex items-center gap-2 mt-3">
          <div class="progress flex-1"><span :style="{ width: donePct + '%' }"></span></div>
          <span class="text-[12px] text-slate-500 tabular-nums w-40 text-right">
            đã tạo <b class="text-slate-800">{{ produced }}</b>/{{ counts.TOTAL || 0 }} · {{ donePct }}%
          </span>
        </div>
      </section>

      <!-- 3 bước: nạp → duyệt trong thư mục → đổi tên -->
      <div class="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <section class="card p-4 flex flex-col gap-3">
          <div class="step-head"><span class="step-no">1</span> Nạp prompt {{ label.noun }}</div>
          <div class="grid grid-cols-2 gap-2" :class="{ 'opacity-60 pointer-events-none': busy }">
            <label class="btn cursor-pointer"><FileSpreadsheet class="w-4 h-4" /> Thêm Excel
              <input type="file" accept=".xlsx,.txt" class="hidden" @change="importExcel" />
            </label>
            <button class="btn" @click="showPaste = true"><ClipboardPaste class="w-4 h-4" /> Dán prompt</button>
          </div>
          <p class="text-[12px] text-slate-500 leading-relaxed">
            <template v-if="isImage">
              Excel dạng <span class="font-mono">reference-prompts.xlsx</span>: cột <b>Reference ID</b> + <b>Prompt tạo reference</b>.
              Ảnh đổi đồ (OUTFIT VARIANT) gắn @ảnh gốc: ảnh gốc phải được duyệt và <b>Đổi tên</b> sang Folder Ảnh trước,
              app tự chạy tiếp ảnh đổi đồ sau khi đổi tên. Thêm ảnh tay bằng nút "+" ở từng dòng.
            </template>
            <template v-else>
              Excel dạng <span class="font-mono">all-video-prompts.xlsx</span>. Mỗi <span class="font-mono">@ID</span> trong prompt
              (ví dụ <span class="font-mono">@CHAR-CLARA-1</span>) được upload từ Folder Ảnh và gắn thành chip ảnh trên Flow.
            </template>
          </p>
          <div v-if="!isImage" class="mt-auto pt-2 border-t border-slate-100 flex flex-wrap items-center gap-2 text-[12px]">
            <span :class="missingRefCount ? 'text-rose-600' : 'text-emerald-700'">
              {{ missingRefCount ? missingRefCount + ' video còn thiếu ảnh tham chiếu' : 'Đủ ảnh tham chiếu cho mọi video' }}
            </span>
            <button class="btn btn-sm ml-auto" @click="importAssets" :disabled="busy"><FolderInput class="w-3.5 h-3.5" /> Thêm ảnh có sẵn</button>
          </div>
        </section>

        <section class="card p-4 flex flex-col gap-3">
          <div class="step-head"><span class="step-no">2</span> Duyệt trong thư mục</div>
          <button @click="openReview" class="group w-full flex items-center gap-3 text-left p-2 pr-3 rounded-xl border border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/40 transition">
            <span class="icon-tile !w-8 !h-8 !rounded-lg icon-tile-amber"><FolderOpen class="w-4 h-4" /></span>
            <span class="min-w-0 flex-1">
              <span class="block text-[11.5px] font-medium text-slate-500">Thư mục chờ duyệt · {{ counts.AWAITING_REVIEW || 0 }} file</span>
              <span class="block font-mono text-[12px] text-slate-800 truncate">{{ reviewDir }}</span>
            </span>
            <span class="text-[12px] font-medium text-slate-400 group-hover:text-indigo-600 shrink-0">Mở</span>
          </button>
          <button @click="openErrors" class="group w-full flex items-center gap-3 text-left p-2 pr-3 rounded-xl border border-rose-200 bg-rose-50/40 hover:border-rose-300 hover:bg-rose-50 transition">
            <span class="icon-tile !w-8 !h-8 !rounded-lg icon-tile-rose"><FolderX class="w-4 h-4" /></span>
            <span class="min-w-0 flex-1">
              <span class="block text-[12.5px] font-medium text-slate-800">{{ isImage ? 'Ảnh' : 'Video' }} sai: kéo file vào thư mục <span class="font-mono">lỗi</span></span>
              <span class="block text-[11.5px] text-slate-500">App tự tạo lại đúng số đó (kiểm tra mỗi 20 giây)</span>
            </span>
            <span class="text-[12px] font-medium text-slate-400 group-hover:text-rose-600 shrink-0">Mở</span>
          </button>
          <div>
            <label class="block text-[12px] font-medium text-slate-600 mb-1">Hoặc nhập số bị sai</label>
            <input v-model="redoNumbers" class="input font-mono" placeholder="vd: 5, 12, 30-33" @keydown.enter="redo" />
          </div>
          <input v-model="redoNote" class="input !text-[13px]" placeholder="Ghi chú cần sửa (không bắt buộc), vd: áo màu đỏ, không có chữ" />
          <button @click="redo" :disabled="busy || !redoNumbers.trim()" class="btn"><RotateCcw class="w-4 h-4" /> Làm lại các số này</button>
          <p class="text-[11.5px] text-slate-400">File cũ được cất vào <span class="font-mono">lỗi\đã xử lý</span>; bản mới về đúng tên số cũ.</p>
        </section>

        <section class="card p-4 flex flex-col gap-3">
          <div class="step-head"><span class="step-no">3</span> Đổi tên &amp; chuyển</div>
          <button @click="openFolder(targetDir)" class="group w-full flex items-center gap-3 text-left p-2 pr-3 rounded-xl border border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/40 transition">
            <span class="icon-tile !w-8 !h-8 !rounded-lg icon-tile-emerald"><FolderCheck class="w-4 h-4" /></span>
            <span class="min-w-0 flex-1">
              <span class="block text-[11.5px] font-medium text-slate-500">{{ isImage ? 'Folder Ảnh' : 'Folder Video' }} · {{ doneCount }} file đã đổi tên</span>
              <span class="block font-mono text-[12px] text-slate-800 truncate">{{ targetDir }}</span>
            </span>
            <span class="text-[12px] font-medium text-slate-400 group-hover:text-indigo-600 shrink-0">Mở</span>
          </button>
          <p class="text-[12px] text-slate-500 leading-relaxed">
            Đổi <span class="font-mono">5{{ ext }}</span> thành
            <span class="font-mono">{{ isImage ? 'CHAR-CLARA-1.png' : 'EP06-S1-01.mp4' }}</span>
            ({{ isImage ? 'đúng Reference ID' : 'tên cảnh + số thứ tự trong cảnh' }}) và chuyển sang thư mục trên.
          </p>
          <div v-if="notReady" class="note-warn !text-[12px]">Còn {{ notReady }} {{ label.noun }} chưa tạo xong / lỗi: sẽ giữ lại, đổi tên sau.</div>
          <button @click="finalizeAll" :disabled="busy || !counts.AWAITING_REVIEW" class="btn-success mt-auto">
            <CheckCheck class="w-4 h-4" /> Đổi tên {{ counts.AWAITING_REVIEW || 0 }} file
          </button>
        </section>
      </div>

      <!-- Duyệt nhanh: bấm chọn mục lỗi, rồi làm lại / duyệt hết phần còn lại -->
      <section v-if="reviewItems.length" class="card">
        <div class="p-3 flex flex-wrap items-center gap-3 border-b border-slate-100">
          <div class="min-w-0">
            <div class="card-title">Duyệt nhanh · {{ reviewItems.length }} {{ label.noun }} chờ duyệt</div>
            <div class="text-[11.5px] text-slate-500">Bấm vào {{ label.noun }} sai để đánh dấu lỗi (bấm lại để bỏ). Kính lúp để xem to.</div>
          </div>
          <div class="ml-auto flex flex-wrap items-center gap-2">
            <template v-if="errSel.size">
              <span class="text-[12.5px] font-semibold text-rose-700">Đã chọn {{ errSel.size }} lỗi</span>
              <button class="btn-ghost btn-sm" @click="errSel = new Set()">Bỏ chọn</button>
              <input v-model="errNote" class="input !py-1.5 !text-[12.5px] !w-64" placeholder="Ghi chú cần sửa (không bắt buộc)" />
              <button class="btn btn-sm !border-rose-200 !text-rose-700 hover:!bg-rose-50" :disabled="busy" @click="redoSelected">
                <RotateCcw class="w-3.5 h-3.5" /> Làm lại {{ errSel.size }} {{ label.noun }} lỗi
              </button>
            </template>
            <button class="btn-success btn-sm" :disabled="busy" @click="approveRest">
              <CheckCheck class="w-3.5 h-3.5" />
              {{ errSel.size ? `Duyệt ${reviewItems.length - errSel.size} ${label.noun} còn lại` : `Duyệt hết ${reviewItems.length} ${label.noun}` }}
            </button>
          </div>
        </div>
        <div class="p-3 grid gap-2.5" style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))">
          <div v-for="j in reviewPage" :key="j.id" @click="toggleErr(j)"
            class="group relative rounded-xl overflow-hidden border-2 cursor-pointer bg-slate-900 transition"
            :class="errSel.has(j.id) ? 'border-rose-500 ring-2 ring-rose-200' : 'border-transparent hover:border-indigo-300'">
            <img v-if="j.previewType === 'image'" :src="jobsApi.previewUrl(j)" loading="lazy" class="w-full aspect-video object-cover"
              :class="errSel.has(j.id) ? 'opacity-60' : ''" />
            <video v-else :src="jobsApi.previewUrl(j)" muted preload="metadata" class="w-full aspect-video object-cover"
              :class="errSel.has(j.id) ? 'opacity-60' : ''"></video>
            <span class="absolute top-1.5 left-1.5 px-1.5 py-0.5 rounded-md bg-slate-900/75 text-white text-[12px] font-semibold tabular-nums">{{ j.reviewNo }}</span>
            <span v-if="errSel.has(j.id)" class="absolute top-1.5 right-1.5 px-1.5 py-0.5 rounded-md bg-rose-600 text-white text-[11px] font-semibold">Lỗi</span>
            <button v-else @click.stop="preview = j" title="Xem to"
              class="absolute top-1.5 right-1.5 w-7 h-7 rounded-md bg-white/90 text-slate-700 hidden group-hover:flex items-center justify-center">
              <ZoomIn class="w-4 h-4" />
            </button>
            <div class="px-2 py-1 bg-white text-[11px] font-mono text-slate-600 truncate" :title="j.finalName">{{ j.finalName }}</div>
          </div>
        </div>
        <div v-if="reviewPages > 1" class="flex items-center justify-center gap-2 pb-3">
          <button @click="reviewPg = Math.max(1, reviewPg - 1)" :disabled="reviewPg === 1" class="btn btn-sm">‹ Trước</button>
          <span class="text-sm text-slate-600">Trang {{ reviewPg }} / {{ reviewPages }}</span>
          <button @click="reviewPg = Math.min(reviewPages, reviewPg + 1)" :disabled="reviewPg === reviewPages" class="btn btn-sm">Sau ›</button>
        </div>
      </section>

      <!-- Danh sách -->
      <section class="card">
        <div class="p-3 flex flex-wrap items-center gap-3 border-b border-slate-100">
          <div class="card-title mr-1">Danh sách {{ label.noun }}</div>
          <select v-model="filter" class="input !w-auto !py-1.5">
            <option value="">Mọi trạng thái</option>
            <option v-for="o in filterOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
          <div class="relative flex-1 min-w-[200px]">
            <Search class="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
            <input v-model="q" :placeholder="isImage ? 'Tìm: số (5, 10-20), CHAR-CLARA-1…' : 'Tìm: số (5, 10-20), EP06-S1, chữ trong ghi chú…'" class="input !pl-9 !py-1.5" />
          </div>
          <button @click="load" class="btn btn-sm" title="Tải lại"><RefreshCw class="w-3.5 h-3.5" /></button>
        </div>

        <div v-if="!items.length" class="py-16 text-center">
          <FileSpreadsheet class="w-9 h-9 mx-auto text-indigo-300" />
          <div class="text-slate-700 mt-3">Chưa có prompt {{ label.noun }} nào</div>
          <div class="text-sm text-slate-500 mt-1">Bấm "Thêm Excel" hoặc "Dán prompt" ở bước 1.</div>
        </div>
        <div v-else-if="!filtered.length" class="py-12 text-center text-slate-500 text-sm">Không có mục nào khớp bộ lọc.</div>

        <div v-else class="overflow-x-auto">
          <table class="w-full text-[13px]">
            <thead>
              <tr class="text-left text-[11.5px] text-slate-500 border-b border-slate-100">
                <th class="px-3 py-2 w-16">Số</th>
                <th class="px-3 py-2 w-16"></th>
                <th class="px-3 py-2">Tên sau khi đổi</th>
                <th class="px-3 py-2">Trạng thái</th>
                <th class="px-3 py-2">{{ isImage ? 'Ảnh gốc' : 'Ảnh tham chiếu' }}</th>
                <th class="px-3 py-2 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="j in pageItems" :key="j.id">
                <tr class="border-b border-slate-100 hover:bg-slate-50/60 align-middle" :class="open === j.id ? 'bg-indigo-50/30' : ''">
                  <td class="px-3 py-2 font-mono font-semibold text-slate-800 tabular-nums">{{ pad(j.reviewNo) }}</td>
                  <td class="px-3 py-1.5">
                    <button v-if="j.hasPreview" @click="preview = j" class="block w-14 h-10 rounded-md overflow-hidden bg-slate-900 border border-slate-200 hover:ring-2 hover:ring-indigo-300">
                      <img v-if="j.previewType === 'image'" :src="jobsApi.previewUrl(j)" loading="lazy" class="w-full h-full object-cover" />
                      <PlayCircle v-else class="w-5 h-5 m-auto text-slate-300" />
                    </button>
                  </td>
                  <td class="px-3 py-2">
                    <div class="font-mono text-slate-800">{{ j.finalName }}</div>
                    <div v-if="isImage && j.references.some((r) => !r.available) && j.status === 'PENDING'" class="text-[11px] text-amber-700">
                      chờ ảnh gốc {{ j.references.filter((r) => !r.available).map((r) => r.id).join(', ') }} được đổi tên
                    </div>
                    <div v-if="!isImage && j.summaryVi" class="text-[11.5px] text-slate-500 truncate max-w-[420px]" :title="j.summaryVi">{{ firstLine(j.summaryVi) }}</div>
                  </td>
                  <td class="px-3 py-2">
                    <StatusBadge :status="j.status" />
                    <div v-if="j.reviewFile" class="text-[11px] text-slate-400 font-mono mt-0.5">{{ j.reviewFile }}</div>
                    <div v-if="j.errorMessage && ['FAILED', 'PENDING'].includes(j.status)" class="text-[11px] text-rose-600 truncate max-w-[260px]" :title="j.errorMessage">{{ j.errorMessage }}</div>
                  </td>
                  <td class="px-3 py-2">
                    <span v-if="!j.references.length" class="text-[12px]" :class="isImage ? 'text-slate-400' : 'text-rose-600'">{{ isImage ? '—' : 'chưa có @ID' }}</span>
                    <span v-else class="text-[12px]">
                      <span class="tabular-nums">{{ j.references.filter((r) => r.available).length }}/{{ j.references.length }}</span>
                      <span v-if="j.references.some((r) => !r.available)" class="text-rose-600 ml-1">
                        thiếu {{ j.references.filter((r) => !r.available).map((r) => r.id).slice(0, 3).join(', ') }}
                      </span>
                    </span>
                  </td>
                  <td class="px-3 py-2 text-right whitespace-nowrap">
                    <button @click="toggleOpen(j)" class="btn btn-sm"><component :is="open === j.id ? ChevronUp : ChevronDown" class="w-3.5 h-3.5" /> Chi tiết</button>
                    <button v-if="j.status !== 'GENERATING'" @click="redoOne(j)" :disabled="busy" class="btn btn-sm ml-1" title="Tạo lại mục này"><RotateCcw class="w-3.5 h-3.5" /></button>
                    <button v-else @click="cancel(j)" class="btn btn-sm ml-1" title="Hủy lượt đang tạo"><X class="w-3.5 h-3.5" /></button>
                  </td>
                </tr>
                <tr v-if="open === j.id" class="border-b border-slate-100 bg-slate-50/50">
                  <td colspan="6" class="px-4 py-4">
                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
                      <div>
                        <div class="section-label mb-1.5">Prompt (sửa rồi bấm "Lưu &amp; tạo lại")</div>
                        <textarea v-model="editPrompt" rows="9" class="input font-mono !text-[11.5px] leading-relaxed"></textarea>
                        <div class="flex gap-2 mt-2">
                          <button @click="savePrompt(j)" :disabled="busy || editPrompt === originalPrompt || j.status === 'GENERATING'" class="btn btn-sm">
                            <Save class="w-3.5 h-3.5" /> Lưu &amp; tạo lại
                          </button>
                          <span class="text-[11px] text-slate-400 self-center">{{ j.sourceName ? j.sourceName + ' · dòng ' + j.seqNo : '' }}</span>
                        </div>
                      </div>
                      <div>
                        <div class="section-label mb-1.5">{{ isImage ? 'Ảnh gốc / ảnh tham chiếu (đổi đồ nhân vật)' : 'Ảnh tham chiếu, upload lên Flow theo thứ tự' }}</div>
                        <RefStrip :job="j" :project-id="store.current.project.id" @changed="load" @zoom="(id) => (zoom = id)" @goto="(id) => (q = id)" />
                        <p v-if="!isImage" class="text-[11px] text-slate-400 mt-2">Video chỉ dùng ảnh đã "Đổi tên" sang Folder Ảnh (hoặc ảnh có sẵn), không dùng ảnh còn chờ duyệt.</p>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div v-if="pages > 1" class="flex items-center justify-center gap-2 py-3">
          <button @click="page = Math.max(1, page - 1)" :disabled="page === 1" class="btn btn-sm">‹ Trước</button>
          <span class="text-sm text-slate-600">Trang {{ page }} / {{ pages }} · {{ filtered.length }} mục</span>
          <button @click="page = Math.min(pages, page + 1)" :disabled="page === pages" class="btn btn-sm">Sau ›</button>
        </div>
      </section>
    </template>

    <!-- Xem kết quả -->
    <div v-if="preview" class="fixed inset-0 z-50 bg-slate-900/80 flex flex-col items-center justify-center p-6" @click.self="preview = null">
      <video v-if="preview.previewType === 'video'" :src="jobsApi.previewUrl(preview)" controls autoplay loop class="max-w-full max-h-[82vh] rounded-xl"></video>
      <img v-else :src="jobsApi.previewUrl(preview)" class="max-w-full max-h-[82vh] object-contain rounded-xl shadow-2xl" />
      <div class="mt-3 font-mono text-white text-sm">{{ pad(preview.reviewNo) }} · {{ preview.finalName }}</div>
    </div>
    <div v-if="zoom" class="fixed inset-0 z-[55] bg-slate-900/80 flex flex-col items-center justify-center p-6" @click="zoom = null">
      <img :src="projectsApi.assetUrl(store.current.project.id, zoom)" class="max-w-full max-h-[85vh] object-contain rounded-xl shadow-2xl" />
      <div class="mt-3 font-mono text-white text-sm">{{ zoom }}</div>
    </div>

    <ImportToolsModal :show="showPaste" mode="text" :fixed-kind="kind" :project-id="store.current?.project.id"
      @close="showPaste = false" @done="onPasted" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  AlertTriangle, CheckCheck, CheckCircle2, ChevronDown, ChevronUp, ClipboardPaste, Clock, FileSpreadsheet, FolderCheck, FolderInput,
  FolderOpen, FolderX, Image as ImageIcon, Info, Layers, Loader2, Pause, Play, PlayCircle, RefreshCw, RotateCcw, Save, Search, UserX, Video, X, ZoomIn,
} from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import RefStrip from '@/components/RefStrip.vue'
import ImportToolsModal from '@/components/ImportToolsModal.vue'
import { batchApi, jobsApi, projectsApi, systemApi, errMsg } from '@/api'
import { store } from '@/store'

const props = defineProps({ kind: { type: String, required: true } }) // 'IMAGE' | 'VIDEO'

const PAGE_SIZE = 50
const isImage = computed(() => props.kind === 'IMAGE')
const label = computed(() => isImage.value
  ? { title: 'Tạo ảnh', noun: 'ảnh', run: 'Chạy tạo ảnh' }
  : { title: 'Tạo video', noun: 'video', run: 'Chạy tạo video' })
const ext = computed(() => (isImage.value ? '.png' : '.mp4'))
const part = computed(() => (isImage.value ? 'image' : 'video'))

const items = ref([])
const busy = ref(false)
const filter = ref('')
const q = ref('')
const page = ref(1)
const open = ref(null)
const editPrompt = ref('')
const originalPrompt = ref('')
const preview = ref(null)
const zoom = ref(null)
const showPaste = ref(false)
const redoNumbers = ref('')
const redoNote = ref('')

const project = computed(() => store.current?.project)
const running = computed(() => !!(isImage.value ? project.value?.runImages : project.value?.runVideos))
const counts = computed(() => store.current?.kindCounts?.[props.kind] || {})
const reviewDir = computed(() => store.current?.reviewDirs?.[props.kind] || '')
const targetDir = computed(() => (isImage.value ? project.value?.imageDir : project.value?.videoDir) || '')
const doneCount = computed(() => (counts.value.SUCCESS || 0) + (counts.value.APPROVED || 0) + (counts.value.DOWNLOADING || 0))
const produced = computed(() => doneCount.value + (counts.value.AWAITING_REVIEW || 0))
const notReady = computed(() => (counts.value.PENDING || 0) + (counts.value.GENERATING || 0) + (counts.value.FAILED || 0))
const donePct = computed(() => (counts.value.TOTAL ? Math.round((produced.value / counts.value.TOTAL) * 100) : 0))
const missingRefCount = computed(() => items.value.filter((j) => !['SUCCESS', 'CANCELLED'].includes(j.status)
  && (!j.references.length || j.references.some((r) => !r.available))).length)

const stats = computed(() => [
  { key: 'total', label: 'Tổng', value: counts.value.TOTAL || 0, icon: Layers, tile: 'icon-tile-slate', filter: '' },
  { key: 'pending', label: 'Chờ chạy', value: counts.value.PENDING || 0, icon: Clock, tile: 'icon-tile-slate', filter: 'PENDING' },
  { key: 'gen', label: 'Đang tạo', value: counts.value.GENERATING || 0, icon: Loader2, tile: 'icon-tile-sky', filter: 'GENERATING' },
  { key: 'review', label: 'Chờ duyệt', value: counts.value.AWAITING_REVIEW || 0, icon: FolderOpen, tile: 'icon-tile-amber', filter: 'AWAITING_REVIEW', title: 'Đã tải về thư mục chờ duyệt' },
  { key: 'failed', label: 'Lỗi', value: counts.value.FAILED || 0, icon: AlertTriangle, tile: 'icon-tile-rose', filter: 'FAILED' },
  { key: 'done', label: 'Đã đổi tên', value: doneCount.value, icon: CheckCircle2, tile: 'icon-tile-emerald', filter: 'SUCCESS' },
])
const filterOptions = [
  { value: 'PENDING', label: 'Chờ chạy' },
  { value: 'GENERATING', label: 'Đang tạo' },
  { value: 'AWAITING_REVIEW', label: 'Chờ duyệt' },
  { value: 'FAILED', label: 'Lỗi' },
  { value: 'SUCCESS', label: 'Đã đổi tên' },
  { value: 'CANCELLED', label: 'Đã hủy / có sẵn' },
]

const pad = (n) => String(n ?? '')
const firstLine = (s) => String(s).split('\n').filter((l) => l.trim()).slice(0, 2).join(' · ')

/** "5, 10-20" → tìm theo số; còn lại tìm theo tên / ghi chú. */
function matches(j, text) {
  const t = text.trim().toLowerCase()
  if (!t) return true
  if (/^[\d\s,;-]+$/.test(t)) {
    return t.split(/[,;\s]+/).filter(Boolean).some((p) => {
      const [a, b] = p.split('-').map((x) => parseInt(x, 10))
      return Number.isNaN(b) || b === undefined ? j.reviewNo === a : j.reviewNo >= Math.min(a, b) && j.reviewNo <= Math.max(a, b)
    })
  }
  return [j.finalName, j.externalId, j.summaryVi].some((v) => v && String(v).toLowerCase().includes(t))
}
const filtered = computed(() => items.value.filter((j) => (!filter.value
  || j.status === filter.value
  || (filter.value === 'SUCCESS' && ['APPROVED', 'DOWNLOADING'].includes(j.status))) && matches(j, q.value)))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))
const pageItems = computed(() => filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))
watch([filter, q], () => { page.value = 1 })

async function load() {
  if (!store.projectId) return
  try {
    const all = await jobsApi.list(store.projectId, null, false)
    items.value = all.filter((j) => j.kind === props.kind).sort((a, b) => a.reviewNo - b.reviewNo)
  } catch (e) {
    console.error(e)
  }
}

async function run(fn, okMsg) {
  busy.value = true
  try {
    const r = await fn()
    if (okMsg) store.toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 'success')
    await Promise.all([store.refresh(), load()])
    return r
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return null
  } finally {
    busy.value = false
  }
}

const pid = () => store.current.project.id
const start = () => run(() => batchApi.start(pid(), part.value), `Đang ${label.value.title.toLowerCase()}: kết quả về thư mục chờ duyệt`)
const pause = () => run(() => batchApi.pause(pid(), part.value), 'Đã tạm dừng: mục đang tạo chạy nốt, không mở mục mới')

async function redo() {
  const r = await run(() => batchApi.redo(pid(), part.value, redoNumbers.value, redoNote.value), (x) =>
    `Đang làm lại ${x.queued} ${label.value.noun}`
    + (x.notFound.length ? `\nKhông có số: ${x.notFound.join(', ')}` : '')
    + (x.skipped.length ? `\nBỏ qua: ${x.skipped.join(', ')}` : ''))
  if (r) {
    redoNumbers.value = ''
    redoNote.value = ''
  }
}

// ----- Duyệt nhanh -----
const REVIEW_PAGE = 60
const errSel = ref(new Set())
const errNote = ref('')
const reviewPg = ref(1)
const reviewItems = computed(() => items.value.filter((j) => j.status === 'AWAITING_REVIEW' && j.hasPreview))
const reviewPages = computed(() => Math.max(1, Math.ceil(reviewItems.value.length / REVIEW_PAGE)))
const reviewPage = computed(() => reviewItems.value.slice((reviewPg.value - 1) * REVIEW_PAGE, reviewPg.value * REVIEW_PAGE))
watch(reviewPages, (n) => { if (reviewPg.value > n) reviewPg.value = n })
// mục đã rời trạng thái chờ duyệt (đang làm lại / đã đổi tên) thì bỏ khỏi lựa chọn
watch(reviewItems, (list) => {
  const ids = new Set(list.map((j) => j.id))
  if ([...errSel.value].some((id) => !ids.has(id))) errSel.value = new Set([...errSel.value].filter((id) => ids.has(id)))
})
function toggleErr(j) {
  const next = new Set(errSel.value)
  if (next.has(j.id)) next.delete(j.id)
  else next.add(j.id)
  errSel.value = next
}
const selectedNumbers = () => reviewItems.value.filter((j) => errSel.value.has(j.id)).map((j) => j.reviewNo).join(',')

async function redoSelected() {
  if (!errSel.value.size) return
  const r = await run(() => batchApi.redo(pid(), part.value, selectedNumbers(), errNote.value),
    (x) => `Đang làm lại ${x.queued} ${label.value.noun} lỗi`)
  if (r) {
    errSel.value = new Set()
    errNote.value = ''
  }
}

/** Duyệt hết: mục đánh dấu lỗi đi làm lại, mọi mục còn lại đổi tên sang thư mục đích. */
async function approveRest() {
  const nErr = errSel.value.size
  const nOk = reviewItems.value.length - nErr
  let msg = `Duyệt ${nOk} ${label.value.noun}: đổi tên và chuyển sang\n${targetDir.value}`
  if (nErr) msg += `\n\n${nErr} ${label.value.noun} đánh dấu lỗi sẽ được làm lại.`
  if (!confirm(msg)) return
  busy.value = true
  try {
    let redone = 0
    if (nErr) {
      redone = (await batchApi.redo(pid(), part.value, selectedNumbers(), errNote.value)).queued
      errSel.value = new Set()
      errNote.value = ''
    }
    const r = await batchApi.finalize(pid(), part.value)
    store.toast(`Đã duyệt và đổi tên ${r.renamed} ${label.value.noun} → ${r.targetDir}`
      + (redone ? `\nĐang làm lại ${redone} ${label.value.noun} lỗi` : '')
      + (r.resumed ? `\nChạy tiếp ${r.resumed} ${label.value.noun} đang chờ ảnh gốc` : '')
      + (r.errors.length ? `\nLỗi ${r.errors.length}: ${r.errors.slice(0, 3).join('; ')}` : ''), r.errors.length ? 'error' : 'success')
    await Promise.all([store.refresh(), load()])
  } catch (e) {
    store.toast(errMsg(e), 'error')
  } finally {
    busy.value = false
  }
}

const redoOne = (j) => run(() => batchApi.redo(pid(), part.value, String(j.reviewNo), ''), `Đang làm lại số ${pad(j.reviewNo)}`)
const cancel = (j) => run(() => jobsApi.cancel(j.id), `Đã yêu cầu dừng số ${pad(j.reviewNo)}`)

async function finalizeAll() {
  const n = counts.value.AWAITING_REVIEW || 0
  let msg = `Đổi tên ${n} file trong thư mục chờ duyệt và chuyển sang\n${targetDir.value}?`
  if (notReady.value) msg += `\n\n${notReady.value} ${label.value.noun} chưa xong sẽ giữ lại, đổi tên sau.`
  if (!confirm(msg)) return
  await run(() => batchApi.finalize(pid(), part.value), (r) => `Đã đổi tên ${r.renamed} file → ${r.targetDir}`
    + (r.resumed ? `\nChạy tiếp ${r.resumed} ${label.value.noun} đang chờ ảnh gốc` : '')
    + (r.errors.length ? `\nLỗi ${r.errors.length}: ${r.errors.slice(0, 3).join('; ')}` : ''))
}

async function openReview() {
  try {
    await batchApi.openReview(pid(), part.value)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
async function openErrors() {
  try {
    await batchApi.openErrors(pid(), part.value)
    const r = await batchApi.scanErrors(pid(), part.value)
    if (r.queued) {
      store.toast(`Đang làm lại ${r.queued} ${label.value.noun} từ thư mục lỗi: ${r.files.join(', ')}`, 'success')
      await Promise.all([store.refresh(), load()])
    }
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}
async function openFolder(path) {
  try {
    await systemApi.openFolder(path)
  } catch (e) {
    store.toast(errMsg(e), 'error')
  }
}

async function importExcel(e) {
  const file = e.target.files[0]
  e.target.value = ''
  if (!file) return
  await run(() => projectsApi.importFile(pid(), props.kind, file), (r) =>
    `Đã nạp ${r.file}: ${r.rows} ${label.value.noun} (mới ${r.added}, đổi ${r.updated}, giữ nguyên ${r.unchanged})`
    + (r.missingRefs?.length ? `\nCòn thiếu ${r.missingRefs.length} ảnh: ${r.missingRefs.slice(0, 5).join(', ')}` : ''))
}
async function onPasted(r) {
  showPaste.value = false
  store.toast(`Đã thêm ${r.rows} ${label.value.noun} (mới ${r.added})`, 'success')
  await Promise.all([store.refresh(), load()])
}
async function importAssets() {
  let path
  try {
    path = (await systemApi.pickFolder(project.value.imageDir)).path
  } catch (e) {
    store.toast(errMsg(e), 'error')
    return
  }
  if (!path) return
  await run(() => projectsApi.importAssetFolder(pid(), path), (r) => `Đã thêm ${r.added} ảnh có sẵn` + (r.replaced ? `, thay ${r.replaced}` : '')
    + (r.notReferenced ? `\n${r.notReferenced} file không trùng ID nào` : ''))
}

async function toggleOpen(j) {
  if (open.value === j.id) {
    open.value = null
    return
  }
  open.value = j.id
  editPrompt.value = originalPrompt.value = 'Đang tải…'
  try {
    const full = await jobsApi.get(j.id)
    if (open.value === j.id) editPrompt.value = originalPrompt.value = full.prompt || ''
  } catch (e) {
    editPrompt.value = originalPrompt.value = ''
    store.toast(errMsg(e), 'error')
  }
}
async function savePrompt(j) {
  const r = await run(() => jobsApi.regenerate(j.id, editPrompt.value, null), `Đã lưu prompt và tạo lại số ${pad(j.reviewNo)}`)
  if (r) originalPrompt.value = editPrompt.value
}

watch(() => [store.projectId, props.kind], () => {
  items.value = []
  open.value = null
  filter.value = ''
  q.value = ''
  load()
})

let timer
onMounted(() => {
  load()
  timer = setInterval(load, 6000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.step-head {
  @apply flex items-center gap-2 text-[14px] font-semibold text-slate-900;
}
.step-no {
  @apply w-6 h-6 rounded-full bg-indigo-50 text-indigo-700 text-[12px] font-semibold flex items-center justify-center;
}
</style>
