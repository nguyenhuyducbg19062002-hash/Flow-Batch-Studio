import { reactive } from 'vue'
import { projectsApi, systemApi, accountsApi } from './api'

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key)
    return v === null ? fallback : JSON.parse(v)
  } catch {
    return fallback
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* trình duyệt chặn localStorage: bỏ qua, chỉ là tiện ích */
  }
}

/**
 * Trạng thái dùng chung giữa các màn: phim đang chọn, danh sách phim, trạng thái hệ thống, thông báo nổi.
 * App.vue gọi refresh() định kỳ; các màn gọi refresh() ngay sau khi thao tác để cập nhật tức thì.
 */
export const store = reactive({
  projectId: load('afm.projectId', null),
  view: ((v) => (['review', 'scenes'].includes(v) ? 'images' : v))(load('afm.view', 'projects')),
  projects: [],
  accounts: [],
  info: null,
  online: true,
  toasts: [],

  get current() {
    return this.projects.find((p) => p.project.id === this.projectId) || null
  },
  get readyAccounts() {
    return this.accounts.filter((a) => a.status === 'READY' || a.status === 'LEASED').length
  },

  async refresh() {
    try {
      const [info, projects, accounts] = await Promise.all([systemApi.info(), projectsApi.list(), accountsApi.list()])
      this.info = info
      this.projects = projects
      this.accounts = accounts
      this.online = true
      if (!this.current && projects.length) this.selectProject(projects[0].project.id)
    } catch {
      this.online = false
    }
  },
  selectProject(id) {
    this.projectId = id
    save('afm.projectId', id)
  },
  go(view) {
    this.view = view
    save('afm.view', view)
  },
  toast(message, kind = 'info') {
    const id = Date.now() + Math.random()
    this.toasts.push({ id, message, kind })
    setTimeout(() => {
      this.toasts = this.toasts.filter((t) => t.id !== id)
    }, kind === 'error' ? 8000 : 3500)
  },
})
