# Google Flow qua Extension (FLOW_EXTENSION)

App ra lệnh; extension trong Chrome **thao tác giao diện** tab flow.google.com đã đăng nhập giống người dùng:
upload ảnh tham chiếu, gõ prompt có chip `@tag`, chọn cài đặt (x1, độ dài, độ phân giải), bấm Tạo, tải kết quả về.
Các bước này dùng lại nguyên logic đã chạy thật của "Google Flow (trình duyệt)" (`drivers/flow_browser.py`,
`selectors.json`), chỉ khác là chạy trên tab Chrome của bạn thay vì một cửa sổ riêng.

```
worker Python (drivers/flow_extension_ui.py, Playwright connect_over_cdp)
   -> ws 127.0.0.1:18323 (CDP)  flow_ext/bridge.py  <- ws 127.0.0.1:18321 <- extension Chrome --chrome.debugger--> tab Flow
   -> http 127.0.0.1:18322/downloads  (file Chrome vừa tải xong, extension báo qua chrome.downloads)
```

Bridge giả làm một Chrome cho Playwright: tự trả lời vài lệnh cấp trình duyệt, còn lại chuyển cho extension chạy trên tab
bằng `chrome.debugger` (cách của chế độ extension trong Playwright MCP). Trong lúc app điều khiển, Chrome hiện dải
"… đã bắt đầu gỡ lỗi trình duyệt này": bình thường, dải tự tắt khi cảnh xong. **Mỗi lúc chỉ chạy một cảnh** (chỉ có
một tab Flow); cảnh khác chờ tới lượt (tối đa `AVS_FLOW_TAB_BUSY_WAIT_S`, mặc định 1800 giây).

## Cài đặt (một lần)
1. Chrome → `chrome://extensions` → bật **Developer mode** → **Load unpacked** → chọn thư mục
   `engine\flow_ext\extension` (bản cài: `C:\FlowBatchStudio\engine\flow_ext\extension`).
   Cập nhật extension (bản 0.2.0 thêm quyền `debugger`, `downloads`): bấm nút tải lại ↻ trên thẻ extension.
2. Mở `https://flow.google.com`, đăng nhập Google, nên mở sẵn một dự án.
3. Chrome → Cài đặt → Tải xuống: **tắt** "Hỏi vị trí lưu từng tệp trước khi tải xuống" (bật thì app không tự nhận được file).
4. Không cần thêm tài khoản: app tự tạo một tài khoản nội bộ "Chrome (Flow Extension)" và dùng Google đang đăng nhập.
5. Khi tạo phim chọn nền tảng **Google Flow qua Extension** và chế độ chạy:
   - **Chạy ngầm**: app tự làm hết, kể cả bấm Tạo và Tải xuống.
   - **Mở Flow**: app chỉ điền sẵn ảnh + prompt, bạn kiểm tra rồi bấm Tạo. Có kết quả thì app tự bấm Tải xuống (bạn tự
     tải cũng được); file tải về được nhận vào phim.
   - Ô "Link dự án Flow" có link `…/project/<id>` thì dùng dự án đó; để trống thì app tự tạo một dự án và nhớ lại.

Đừng thao tác trên tab Flow khi app đang điền (chuột / phím của bạn chen vào làm lệch prompt).

Biểu tượng extension hiện **ON** khi đã nối được với app. Extension chưa kết nối thì cảnh chờ tối đa
`AVS_FLOW_EXTENSION_WAIT_S` giây (mặc định 180) rồi mới báo lỗi.

## Cách cũ: gọi thẳng API nội bộ (tắt mặc định)
Đặt `AVS_FLOW_EXT_MODE=api` để dùng lại `drivers/flow_extension.py`: gọi API batchexecute của Flow (tham khảo FlowKit,
https://github.com/crisng95/flowkit, MIT), không bấm giao diện. Đã gặp ngày 2026-11-06: Flow nhận lệnh tạo video
(`MZZa6b`) nhưng dự án không có video nào, `as29s` trả `[5]` mãi; driver báo lỗi sau `AVS_FLOW_NOT_FOUND_GRACE_S`
(mặc định 180) giây và không tự gửi lại lệnh tạo.

| Loại cảnh | Lệnh Flow |
|---|---|
| Ảnh tham chiếu | upload `maseQ` một lần, nhớ media id theo file (`%LOCALAPPDATA%\FlowBatchStudio\flow_ext_media.json`) |
| Ảnh | `ogiZ0b`, model Nano Banana Pro (`GEM_PIX_2`, đổi bằng `AVS_FLOW_IMAGE_MODEL`), kèm ảnh tham chiếu |
| Video có ảnh tham chiếu (1–7) | Omni Flash `MZZa6b` (`abra_r2v_<4/6/8/10>s`) → chờ `jwpduf` / `Zzl0ze` → `as29s` |
| Video không ảnh | Omni Flash text-to-video `YhhmEf` (`abra_t2v_<N>s`) → `as29s` |

Bridge cho cả máy gửi lần lượt từng lệnh tạo, cách nhau tối thiểu `AVS_FLOW_MIN_GAP_S` (mặc định 3 giây). Flow báo
`PUBLIC_ERROR_UNUSUAL_ACTIVITY` thì ngừng gửi lệnh tạo `AVS_FLOW_HOLD_S` giây (mặc định 3600).

## Khi Flow thay đổi
Giao diện: sửa selector trong `engine/selectors.json` (dùng chung với "Google Flow (trình duyệt)").
API (cách cũ): tên lệnh và vị trí các trường nằm ở `engine/drivers/flow_batch.py`.
