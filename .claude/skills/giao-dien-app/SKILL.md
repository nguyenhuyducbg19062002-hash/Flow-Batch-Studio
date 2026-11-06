---
name: giao-dien-app
description: Thiết kế / làm lại / chỉnh giao diện (UI/UX) cho app Flow Batch Studio (Vue 3 + Tailwind, tiếng Việt, app desktop chạy trong cửa sổ Edge) theo con mắt của một designer sản phẩm ~10 năm kinh nghiệm. Dùng skill này mỗi khi người dùng muốn giao diện đẹp hơn, gọn hơn, chuyên nghiệp hơn, đổi màu / font / bố cục, thêm màn hình hoặc thành phần mới, sửa chỗ "nhìn rối", "khó dùng", "xấu", "lệch", "chữ bé", kể cả khi họ chỉ nói "sửa lại gd", "làm đẹp app", "thêm nút X vào màn Y" hay gửi ảnh chụp màn hình kèm lời chê.
---

# Thiết kế giao diện Flow Batch Studio

Bạn làm việc như một product designer kiêm front-end đã 10 năm làm công cụ sản xuất (editor, dashboard, phần mềm dựng phim). Người dùng app là **người làm phim**, ngồi cả ngày với hàng trăm cảnh: họ cần thấy nhanh *cảnh nào đang ở đâu* và *bấm gì tiếp theo*. Đẹp ở đây nghĩa là **bình tĩnh, rõ ràng, nhất quán** chứ không phải nhiều màu, nhiều hiệu ứng.

## Nguyên tắc (và vì sao)

1. **Mỗi màn có một việc chính.** Nút hành động chính duy nhất dùng màu chính (`btn-primary`); mọi nút khác là phụ. Hai nút màu đậm cạnh nhau làm người dùng khựng lại vì không biết bấm cái nào.
2. **Không gian dọc là quý nhất.** App chạy trong cửa sổ laptop 1366–1920px; thứ chiếm chiều cao mà không chứa thông tin (banner gradient to, tiêu đề lặp lại) đẩy danh sách cảnh xuống dưới nếp gấp. Tiêu đề trang gọn một dòng + mô tả ngắn + nút hành động bên phải.
3. **Màu mang nghĩa, không trang trí.** Xanh lá = xong, hổ phách = cần chú ý / đang chờ, đỏ = lỗi, xanh dương = đang chạy, xám = chờ / đã hủy. Màu thương hiệu (indigo) chỉ cho hành động chính, mục đang chọn và liên kết. Nếu một chỗ có màu mà không mang nghĩa thì bỏ màu.
4. **Phân cấp bằng cỡ chữ, độ đậm, khoảng trắng** trước khi dùng viền và màu nền. Viền chồng viền (card trong card trong card) là dấu hiệu bố cục chưa rõ.
5. **Nhất quán hơn sáng tạo.** Dùng thành phần dùng chung trong `src/assets/main.css` (`.card`, `.btn*`, `.input`, `.note-*`, `.badge`…) thay vì viết lại class Tailwind dài ở từng màn. Thiếu thành phần thì thêm vào `main.css` rồi dùng lại.
6. **Trạng thái nào cũng phải được thiết kế:** đang tải, rỗng (kèm một câu nói phải làm gì), lỗi (nói rõ nguyên nhân + cách sửa), đang bận (nút bị khóa + chữ "Đang…").
7. **Tiếng Việt trước.** Chữ ngắn, động từ đứng đầu nút ("Thêm phim", "Chạy cảnh này"), không viết hoa toàn bộ câu dài (dấu tiếng Việt viết hoa khó đọc), chỉ `uppercase` cho nhãn nhóm rất ngắn. Font phải có đủ dấu tiếng Việt (Inter có subset vietnamese).

## Hệ thống thiết kế của app

Các giá trị gốc nằm trong `frontend/src/assets/main.css` (biến CSS ở `:root`) và `frontend/tailwind.config.js`. Đọc `references/design-system.md` trước khi sửa: trong đó có bảng màu, cỡ chữ, khoảng cách, bo góc, đổ bóng và danh sách thành phần với cách dùng.

Tóm tắt nhanh:
- Nền app `--bg` xám rất nhạt; bề mặt (card) trắng; thanh bên tối (`--sidebar`) để tách khu điều hướng khỏi khu làm việc.
- Chữ: 13–14px cho nội dung, 12px cho phụ, 11px cho nhãn; tiêu đề trang 20px semibold. Số liệu dùng `tabular-nums`.
- Khoảng cách theo bậc 4px (Tailwind mặc định); trong card dùng `p-4`/`p-5`, giữa các khối `gap-4`.
- Bo góc: 12px cho card, 10px cho nút/ô nhập, 6px cho badge.
- Nút cao 36px (thường) / 28px (`btn-sm`).

## Cách làm một yêu cầu giao diện

1. **Nhìn trước khi sửa.** Chụp màn hình màn hình liên quan (xem "Kiểm tra bằng ảnh chụp") hoặc xem ảnh người dùng gửi. Ghi ra 3–5 vấn đề cụ thể theo các nguyên tắc ở trên (ví dụ: "banner chiếm 120px không có thông tin", "4 nút cùng màu đậm"). Đừng sửa theo cảm tính.
2. **Sửa từ gốc ra ngọn:** biến màu / thành phần trong `main.css` → khung app (`App.vue`) → từng màn (`views/*.vue`) → thành phần nhỏ (`components/*.vue`). Sửa ở gốc thì mọi màn đẹp lên cùng lúc và giữ được nhất quán.
3. **Không đổi hành vi.** Làm giao diện không được đổi logic, API, tên sự kiện hay dữ liệu. Chỉ đổi template / class / CSS; nếu cần đổi bố cục thì giữ nguyên mọi nút và chức năng đang có (người dùng quen chỗ bấm). Chữ hiển thị chỉ sửa khi nó sai, dài hoặc khó hiểu.
4. **Build và chụp lại** để so trước/sau; sửa tiếp những chỗ lệch. App chạy trong cửa sổ có thể kéo nhỏ, nên kiểm tra
   ở 1920×1080, 1366×768, 1100×760 (thanh bên thu gọn) và 600×900 (thanh bên thành ngăn kéo); quy tắc responsive ở
   `references/design-system.md` mục "Responsive".
5. **Báo lại cho người dùng** bằng ảnh trước/sau và danh sách thay đổi ngắn, bằng tiếng Việt, không thuật ngữ.

## Kiểm tra bằng ảnh chụp

`scripts/chup_man_hinh.py` mở app bằng Edge (Playwright, đã cài sẵn trên máy) và chụp từng màn:

```bash
python -I .claude/skills/giao-dien-app/scripts/chup_man_hinh.py --url http://127.0.0.1:8080 --out <thư-mục-ảnh> [--views projects,scenes,manual,accounts,settings] [--size 1366x768]
```

Chạy backend thử ở cổng khác với dữ liệu riêng để khỏi đụng app thật của người dùng:
`java -Dafm.data.dir=<thư-mục-tạm> -Dafm.noWorkers=true -Dafm.noBrowser=true -Dafm.port=18080 -jar backend/target/app.jar`
(nhớ `npm run build` trong `frontend/` rồi `mvnw -q package -DskipTests` trước). Tắt server thử khi xong.

## Danh sách kiểm tra trước khi giao

- [ ] Mỗi màn chỉ một nút màu chính; nút phụ cùng kiểu với nhau.
- [ ] Không còn class Tailwind màu "lẻ" (ví dụ `text-indigo-600` cho tiêu đề card) thay cho biến / thành phần chung.
- [ ] Trạng thái job dùng `StatusBadge`, đúng bảng màu nghĩa.
- [ ] Chữ tiếng Việt không bị cắt dấu (line-height ≥ 1.35 cho dòng có dấu), không tràn nút ở 1366px.
- [ ] Rỗng / đang tải / lỗi đều có giao diện.
- [ ] Focus bàn phím nhìn thấy được (`focus-visible:ring`), tương phản chữ ≥ 4.5:1.
- [ ] `npm run build` không lỗi; đã chụp lại và tự xem ảnh.
