# Hệ thống thiết kế Flow Batch Studio

Nguồn sự thật: `frontend/src/assets/main.css` (biến ở `:root` + lớp thành phần trong `@layer components`). Tài liệu này giải thích từng giá trị để giữ nhất quán khi thêm màn mới.

## Mục lục
1. Màu
2. Chữ
3. Khoảng cách, bo góc, đổ bóng
4. Khung app
5. Thành phần dùng chung
6. Mẫu bố cục hay dùng

## 1. Màu

| Vai trò | Giá trị | Dùng cho |
|---|---|---|
| Nền app `--bg` | `#f3f4f7` | nền phía sau các card |
| Bề mặt | `#ffffff` (`bg-white`) | card, modal, ô nhập |
| Bề mặt phụ | `slate-50` | dòng bảng khi hover, khối phụ trong card |
| Viền | `slate-200` (`--line` `#e5e7eb`) | viền card, phân cách |
| Chữ chính | `slate-900` | tiêu đề, số liệu |
| Chữ thường | `slate-700` | nội dung |
| Chữ phụ | `slate-500` | mô tả, nhãn (đạt tương phản 4.5:1 trên trắng) |
| Thương hiệu | `indigo-600` / hover `indigo-700` / nền nhạt `indigo-50` | nút chính, mục đang chọn, liên kết |
| Thanh bên `--sidebar` | `#0f1729` | nền điều hướng; chữ `slate-300`, mục đang chọn chữ trắng trên `white/10` + vạch `indigo-400` |

Màu trạng thái (khớp `StatusBadge.vue`):
- Chờ chạy / đã hủy: `slate` · Đang tạo: `sky` · Đang lưu: `amber` · Xong: `emerald` · Lỗi: `rose`.
- Badge = nền `-50`, chữ `-700`, viền `-200`, chấm `-500`.

Không dùng gradient cho nền khối nội dung; gradient chỉ được dùng cho logo.

## 2. Chữ

Font `Inter` (có dấu tiếng Việt), dự phòng `Segoe UI`. Bật `font-feature-settings: "cv11", "ss01"` để số và chữ dễ đọc hơn.

| Bậc | Cỡ / độ đậm | Dùng cho |
|---|---|---|
| Tiêu đề trang `.page-title` | 20px / 600 | một lần mỗi màn |
| Tiêu đề card `.card-title` | 14px / 600, `slate-900` | đầu mỗi card |
| Nội dung | 14px / 400 | đoạn chữ, ô nhập |
| Phụ `.card-sub`, `.hint` | 12px / 400, `slate-500` | mô tả dưới tiêu đề |
| Nhãn nhóm `.section-label` | 11px / 600, uppercase, giãn chữ | "SẢN XUẤT", "KẾT QUẢ" (chỉ chữ rất ngắn) |
| Số liệu `.stat-value` | 22px / 600, `tabular-nums` | ô thống kê |

Dòng có dấu tiếng Việt cần `leading-snug` trở lên; đừng dùng `leading-none` cho chữ.

## 3. Khoảng cách, bo góc, đổ bóng

- Bậc 4px. Trong card: `p-4` (card nhỏ) / `p-5` (card chính). Giữa các khối: `gap-4`. Giữa nhãn và ô nhập: `mb-1.5`.
- Bo góc: card `rounded-xl` (12px), nút & ô nhập `rounded-[10px]`, badge `rounded-md`.
- Đổ bóng: card `shadow-[0_1px_2px_rgba(16,24,40,0.04)]`; modal / menu nổi `shadow-xl`. Không đổ bóng nhiều lớp cho card thường.

## 4. Khung app (`App.vue`)

```
┌──────────────┬──────────────────────────────────────────────┐
│ Thanh bên    │ Thanh trên: tiêu đề trang · mô tả · trạng thái│
│ (tối, 232px) ├──────────────────────────────────────────────┤
│ logo         │                                              │
│ phim đang    │   nội dung màn (cuộn riêng)                  │
│ chọn         │                                              │
│ điều hướng   │                                              │
│ hàng đợi     │                                              │
│ worker/thoát │                                              │
└──────────────┴──────────────────────────────────────────────┘
```
- Thanh bên cố định, không cuộn theo nội dung. Thanh trên cao ~64px, nền trắng, viền dưới.
- Không có banner to: tiêu đề + mô tả + bước (Tài khoản → Phim → Lưu) nằm gọn trong thanh trên.

### Responsive (cửa sổ Edge hay bị thu nhỏ / chia đôi màn hình)

Khung app đổi theo media query trong `main.css` (khối "Khung app responsive"):

| Bề rộng cửa sổ | Thanh bên | Thanh trên | Lề nội dung |
|---|---|---|---|
| ≥ 1920px | đầy đủ 232px | mô tả + 3 bước + trạng thái | 32px, rộng tối đa 1760px |
| 1280 – 1919px | đầy đủ 232px | mô tả + 3 bước + trạng thái | 24px, rộng tối đa 1600px |
| 768 – 1279px | chỉ icon 72px (`title` hiện tên khi rê chuột) | ô chọn phim chuyển lên đây, ẩn 3 bước | 20px |
| < 768px | ngăn kéo, mở bằng nút ☰, đóng khi chọn màn / bấm nền mờ / Esc | chỉ tiêu đề + chấm trạng thái + số tài khoản | 12px |

Trong từng màn dùng tiền tố Tailwind (cũng là media query `min-width`): viết cho màn hẹp trước rồi mở rộng, ví dụ
`grid-cols-1 sm:grid-cols-2 lg:grid-cols-5`, `flex-col lg:flex-row`. Quy tắc:
- Không lưới nào cố định ≥ 3 cột mà không có bậc nhỏ hơn.
- Phần đầu card có nút: `flex-wrap` + khối chữ `min-w-[220px]` để nút xuống dòng thay vì bóp tiêu đề.
- Bảng rộng nằm trong `overflow-x-auto`; cột chữ dài đặt `min-w-[…]` + `[overflow-wrap:anywhere]` (không dùng `break-all`: chữ bị ngắt vụn).
- Ẩn bớt bằng class mang nghĩa (`.topbar-desc`, `.nav-label`) trong media query, không xóa chức năng: mọi nút vẫn bấm được ở mọi cỡ.
- Kiểm tra bằng `chup_man_hinh.py --size` ở 1920x1080, 1366x768, 1100x760 và 600x900.

## 5. Thành phần dùng chung (`main.css`)

| Lớp | Khi nào dùng |
|---|---|
| `.card` / `.card-head` / `.card-title` / `.card-sub` | mọi khối nội dung |
| `.btn-primary` | hành động chính duy nhất của màn / card |
| `.btn` | hành động phụ (viền, nền trắng) |
| `.btn-ghost` | hành động nhẹ trong danh sách, thanh công cụ |
| `.btn-success` / `.btn-warn` / `.btn-danger` | chỉ khi màu mang nghĩa (lưu xong, cảnh báo, xóa) |
| `.btn-sm` | nút trong dòng bảng / thẻ cảnh |
| `.input`, `.label`, `.hint` | form |
| `.note-info` / `.note-warn` / `.note-danger` | thông báo trong trang |
| `.count-pill` | số đếm cạnh tiêu đề / tab |
| `.seg` + `.seg-item` (+ `.is-active`) | nút chọn kiểu tab nhỏ (Theo cảnh / Chỉ làm ảnh, Ảnh / Video) |
| `.stat` + `.stat-label` + `.stat-value` | ô thống kê |
| `.table` | bảng |
| `kbd` | phím tắt |

## 6. Mẫu bố cục hay dùng

- **Danh sách + chi tiết** (Phim & hàng đợi): cột trái danh sách `w-[380px]`, phải chi tiết `flex-1`.
- **Thẻ cảnh** (Danh sách cảnh): trái nội dung (mô tả, prompt thu gọn, ảnh reference), phải cột kết quả `w-72` cố định.
- **Thanh công cụ lọc**: `.seg` cho loại → select trạng thái → ô tìm kiếm co giãn → nút làm mới (`.btn-ghost`).
- **Trạng thái rỗng**: icon `w-10 h-10 text-slate-300`, một câu chính `text-slate-700 font-medium`, một câu hướng dẫn `text-slate-500`, tối đa một nút.
