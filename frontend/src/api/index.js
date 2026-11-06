import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 120000 })

/** Thông báo lỗi tiếng Việt từ backend ({"error": "..."}), hoặc lỗi mạng. */
export function errMsg(e) {
  return e?.response?.data?.error || e?.message || 'Lỗi không xác định'
}

const data = (p) => p.then((r) => r.data)

export const projectsApi = {
  list: () => data(http.get('/projects')),
  get: (id) => data(http.get(`/projects/${id}`)),
  create: (form) => data(http.post('/projects', form)),
  update: (id, form) => data(http.put(`/projects/${id}`, form)),
  remove: (id) => data(http.delete(`/projects/${id}`)),
  /** Quét thư mục tổng của một tập: trả về thư mục Excel / ảnh / video để điền sẵn form (chưa tạo gì trên đĩa) */
  scanFolder: (path) => data(http.post('/projects/scan-folder', { path })),
  importPrompts: (id) => data(http.post(`/projects/${id}/import`)),
  start: (id) => data(http.post(`/projects/${id}/start`)),
  pause: (id) => data(http.post(`/projects/${id}/pause`)),
  retryFailed: (id) => data(http.post(`/projects/${id}/retry-failed`)),
  verifyFiles: (id) => data(http.post(`/projects/${id}/verify-files`)),
  /** kind = 'IMAGE' ("Thêm Excel ảnh") hoặc 'VIDEO' ("Thêm Excel video") */
  importFile: (id, kind, file) => {
    const fd = new FormData()
    fd.append('file', file)
    return data(http.post(`/projects/${id}/import-file`, fd, { params: { kind } }))
  },
  importAssetFolder: (id, path) => data(http.post(`/projects/${id}/assets/import-folder`, { path })),
  /** "Nạp cả thư mục phim": mọi file prompt (.xlsx/.txt) + ảnh có sẵn bên trong */
  importMovieFolder: (id, path) => data(http.post(`/projects/${id}/import-folder`, { path })),
  /** "Nhập thủ công": mỗi dòng một prompt */
  importText: (id, kind, text) => data(http.post(`/projects/${id}/import-text`, { kind, text })),
  /** "Ảnh → Video theo thư mục con" */
  importImageGroups: (id, path, grouped) => data(http.post(`/projects/${id}/import-image-groups`, { path, grouped })),
  assets: (id) => data(http.get(`/projects/${id}/assets`)),
  /** Chạy các cảnh đã chọn (theo thứ tự). only = chỉ chạy các cảnh này rồi phim tự tạm dừng. */
  runSelected: (id, jobIds, only, append = false) => data(http.post(`/projects/${id}/run-selected`, { jobIds, only, append })),
  assetUrl: (projectId, refId) => `/api/projects/${projectId}/assets/${encodeURIComponent(refId)}/file`,
}

/** Hai màn Tạo ảnh / Tạo video: part = 'image' | 'video' */
export const batchApi = {
  start: (id, part) => data(http.post(`/projects/${id}/batch/${part}/start`)),
  pause: (id, part) => data(http.post(`/projects/${id}/batch/${part}/pause`)),
  /** numbers: "5, 12, 30-33"; note: ghi chú cần sửa (thêm vào cuối prompt) */
  redo: (id, part, numbers, note) => data(http.post(`/projects/${id}/batch/${part}/redo`, { numbers, note })),
  /** Đổi tên mọi file trong thư mục chờ duyệt sang tên đúng, chuyển vào Folder Ảnh / Folder Video */
  finalize: (id, part) => data(http.post(`/projects/${id}/batch/${part}/finalize`, null, { timeout: 600000 })),
  openReview: (id, part) => data(http.post(`/projects/${id}/batch/${part}/open-review`)),
  /** Thư mục "lỗi": kéo file sai vào đây, app tự tạo lại số đó */
  openErrors: (id, part) => data(http.post(`/projects/${id}/batch/${part}/open-errors`)),
  scanErrors: (id, part) => data(http.post(`/projects/${id}/batch/${part}/scan-errors`)),
}

export const promptsApi = {
  /** Nút "+": thêm một hoặc nhiều ảnh vào cuối danh sách ảnh reference của một cảnh */
  addReferences: (promptId, files) => {
    const fd = new FormData()
    for (const f of files) fd.append('files', f)
    return data(http.post(`/prompts/${promptId}/references`, fd))
  },
  removeReference: (promptId, refId) => data(http.delete(`/prompts/${promptId}/references/${encodeURIComponent(refId)}`)),
}

export const jobsApi = {
  /** full=false: không kèm nội dung prompt (danh sách dài); lấy prompt của một cảnh bằng get(id) */
  list: (projectId, status, full = true) => data(http.get(`/projects/${projectId}/jobs`, { params: { ...(status ? { status } : {}), full } })),
  get: (id) => data(http.get(`/jobs/${id}`)),
  history: (id) => data(http.get(`/jobs/${id}/history`)),
  approve: (id) => data(http.post(`/jobs/${id}/approve`)),
  approveBatch: (ids) => data(http.post('/jobs/approve-batch', ids)),
  regenerate: (id, promptText, note) => data(http.post(`/jobs/${id}/regenerate`, { promptText, note })),
  cancel: (id) => data(http.post(`/jobs/${id}/cancel`)),
  /** "Chạy cảnh này": đưa cảnh lên đầu hàng đợi và cho phim chạy */
  runNow: (id) => data(http.post(`/jobs/${id}/run-now`)),
  /** "Sửa trên Flow": gửi yêu cầu sửa cho chính video cũ trên Flow (không tạo lại từ đầu) */
  flowEdit: (id, instruction) => data(http.post(`/jobs/${id}/flow-edit`, { instruction })),
  /** "Đưa file đã tải về vào app": file ảnh / video người dùng tự tải từ Flow → lưu vào folder, đánh dấu xong */
  manualResult: (id, file) => {
    const fd = new FormData()
    fd.append('file', file)
    return data(http.post(`/jobs/${id}/manual-result`, fd, { timeout: 600000 }))
  },
  previewUrl: (job) => `/api/jobs/${job.id}/preview?v=${encodeURIComponent(job.generatedAt || '')}`,
}

/** Chế độ "Tự làm trên Flow": app bày cảnh, người dùng tự tạo trên Flow, app nhận file tải về */
export const manualApi = {
  /** Cảnh kế tiếp cần làm; skip = các cảnh đã bấm "Để sau"; imagesOnly = "Chỉ làm ảnh" (làm hết ảnh trước video) */
  next: (projectId, skip = [], imagesOnly = false) =>
    data(http.get(`/projects/${projectId}/manual/next`, {
      params: { ...(skip.length ? { skip: skip.join(',') } : {}), imagesOnly },
    })),
  /** Chép ảnh reference của cảnh ra một thư mục riêng và mở Explorer để kéo thả vào Flow */
  openRefs: (jobId, only) =>
    data(http.post(`/jobs/${jobId}/manual/open-refs`, null, { params: only ? { only: only.join(',') } : {} })),
  /** File ảnh/video đã tải xong trong thư mục Downloads, mới hơn after (epoch ms) */
  downloads: (after, kind) => data(http.get('/manual/downloads', { params: { after, kind } })),
  importDownload: (jobId, path) => data(http.post(`/jobs/${jobId}/manual/import`, { path }, { timeout: 600000 })),
}

export const accountsApi = {
  list: () => data(http.get('/accounts')),
  create: (form) => data(http.post('/accounts', form)),
  update: (id, form) => data(http.put(`/accounts/${id}`, form)),
  remove: (id) => data(http.delete(`/accounts/${id}`)),
  openLogin: (id) => data(http.post(`/accounts/${id}/open-login`)),
  confirmLogin: (id) => data(http.post(`/accounts/${id}/confirm-login`)),
  /** Tự mở Chrome ẩn với profile của tài khoản, xem đã đăng nhập chưa rồi cập nhật trạng thái: { account, state, email, message } */
  checkLogin: (id) => data(http.post(`/accounts/${id}/check-login`, null, { timeout: 100000 })),
  enable: (id) => data(http.post(`/accounts/${id}/enable`)),
  disable: (id) => data(http.post(`/accounts/${id}/disable`)),
}

export const settingsApi = {
  get: () => data(http.get('/settings')),
  save: (s) => data(http.post('/settings', s)),
}

export const systemApi = {
  info: () => data(http.get('/system/info')),
  shutdown: () => data(http.post('/system/shutdown')),
  pickFolder: (initial) => data(http.post('/system/pick-folder', { initial: initial || '' })),
  openFolder: (path) => data(http.post('/system/open-folder', { path })),
}
