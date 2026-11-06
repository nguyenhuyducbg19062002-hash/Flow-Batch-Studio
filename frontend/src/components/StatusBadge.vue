<template>
  <span :class="cls" class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-semibold whitespace-nowrap border">
    <span class="w-1.5 h-1.5 rounded-full" :class="dot"></span>
    {{ label }}
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, required: true } })

// [nhãn, màu nền/chữ/viền, màu chấm]
const MAP = {
  // job
  PENDING: ['Chờ chạy', 'bg-slate-50 text-slate-600 border-slate-200', 'bg-slate-400'],
  GENERATING: ['Đang tạo', 'bg-sky-50 text-sky-700 border-sky-200', 'bg-sky-500 animate-pulse'],
  AWAITING_REVIEW: ['Chờ duyệt', 'bg-amber-50 text-amber-700 border-amber-200', 'bg-amber-500'],
  APPROVED: ['Đang lưu', 'bg-emerald-50 text-emerald-700 border-emerald-200', 'bg-emerald-500'],
  DOWNLOADING: ['Đang lưu', 'bg-emerald-50 text-emerald-700 border-emerald-200', 'bg-emerald-500 animate-pulse'],
  SUCCESS: ['Đã đổi tên', 'bg-emerald-50 text-emerald-700 border-emerald-200', 'bg-emerald-500'],
  FAILED: ['Lỗi', 'bg-rose-50 text-rose-700 border-rose-200', 'bg-rose-500'],
  CANCELLED: ['Đã hủy', 'bg-slate-50 text-slate-500 border-slate-200', 'bg-slate-400'],
  // tài khoản
  READY: ['Sẵn sàng', 'bg-emerald-50 text-emerald-700 border-emerald-200', 'bg-emerald-500'],
  LEASED: ['Đang chạy', 'bg-sky-50 text-sky-700 border-sky-200', 'bg-sky-500 animate-pulse'],
  COOLDOWN: ['Tạm nghỉ', 'bg-amber-50 text-amber-700 border-amber-200', 'bg-amber-500'],
  NEED_LOGIN: ['Cần đăng nhập', 'bg-orange-50 text-orange-700 border-orange-200', 'bg-orange-500'],
  DISABLED: ['Đã tắt', 'bg-slate-50 text-slate-500 border-slate-200', 'bg-slate-400'],
  // phim
  DRAFT: ['Nháp', 'bg-slate-50 text-slate-600 border-slate-200', 'bg-slate-400'],
  RUNNING: ['Đang chạy', 'bg-sky-50 text-sky-700 border-sky-200', 'bg-sky-500 animate-pulse'],
  PAUSED: ['Tạm dừng', 'bg-amber-50 text-amber-700 border-amber-200', 'bg-amber-500'],
}

const entry = computed(() => MAP[props.status] || [props.status, 'bg-slate-50 text-slate-600 border-slate-200', 'bg-slate-400'])
const label = computed(() => entry.value[0])
const cls = computed(() => entry.value[1])
const dot = computed(() => entry.value[2])
</script>
