# Chạy song song nhiều lệnh trên một tài khoản (nhánh `feature/chay-song-song-nhieu-lenh`)

Mục tiêu: trong lúc Flow đang tạo lệnh A, app gửi tiếp lệnh B, C… trên **cùng tài khoản**; lệnh nào xong trước thì tải về và
chuyển sang "Chờ duyệt" trước. Nhiều tài khoản cùng chạy như vậy. Thời gian chờ Google (video 5–7 phút) được chồng lên nhau
thay vì cộng dồn.

## 0. Trạng thái triển khai

| Phần | Trạng thái |
|---|---|
| Backend: `accounts.max_concurrent`, `POST /api/worker/claim-more`, nhả tài khoản theo số job còn chạy | Xong, có test (`JobClaimServiceMoreTest`, 11 ca) |
| Lõi phiên `engine/drivers/flow_session.py` (gắn theo thứ tự, chống vượt số lệnh, lệch pha, hủy, quá hạn, hết lượt) | Xong, có test (`engine/tests/test_flow_session.py`, 14 ca) |
| Driver Flow thật `FlowBrowserDriver.run_session` + `_SessionOps` | Xong, đã chạy thật trên Flow (ảnh + video, xem mục 5) |
| Worker `run_session` (heartbeat riêng từng job, báo ngay khi xong) | Xong |
| Driver giả (FAKE) chạy theo phiên, kiểm thử cả vòng backend + worker không cần Google | Xong (mục 5) |
| Giao diện: ô "Số lệnh chạy cùng lúc" ở trang Tài khoản | Xong |
| Tự đóng hộp thoại phủ (Changelog) trước khi thao tác | Xong |

Mặc định `max_concurrent = 1` (chạy lần lượt như cũ): chỉ tài khoản nào bạn đặt ≥ 2 mới chạy theo phiên.

## 1. Hiện trạng (trước khi có tính năng này)

- Mỗi tài khoản chỉ phục vụ **một job một lúc** (`AccountEntity.leasedJobId`, nhả ngay khi job xong). Một worker Python = một
  job = một lần mở Edge → gửi → chờ → tải → báo.
- Trình duyệt ngồi chờ Google gần hết thời gian của job. Năng suất = số tài khoản.

## 2. Kết quả thử nghiệm trên Flow thật (04/11/2026, tài khoản ULTRA, bố cục tạo trực tiếp, chế độ ẢNH)

Thử trong một dự án Flow mới riêng, ảnh tốn **0 credit** ở gói ULTRA (video 8s/720p tốn 12 credit).

| Điều cần biết | Kết quả |
|---|---|
| Flow có cho gửi lệnh mới khi lệnh trước còn đang chạy? | **Có.** Gửi 3 lệnh liên tiếp không chờ: cả 3 chạy đồng thời, tiến độ tăng song song. |
| Tốc độ | 3 ảnh xong sau khoảng **37 giây** (chạy lần lượt khoảng 105 giây). Các ảnh xong lệch nhau 0–3 giây. |
| Ô đang tạo trông thế nào | Có ngay sau khi bấm Tạo (≤ 300 ms), nằm **trên cùng**. `aria-label` = **nội dung prompt**, chữ "N%" chạy; **chưa có** `data-media-id`. |
| Khi xong | `data-media-id` xuất hiện, `aria-label` đổi thành tên Flow tự đặt (ví dụ "Red apple on table"). |
| Gắn dấu (thuộc tính / thuộc tính JS) lên ô có bền không? | **Không.** Flow vẽ lại ô: chỉ ô mới nhất giữ dấu, các ô khác mất (1/3). Không dùng được. |
| Thứ tự ô lúc đang chạy (không tải lại trang) | Ô mới nhất luôn ở **trên cùng**; ổn định trong mọi lần thử (ảnh, video, trộn cả hai). |
| Thứ tự ô sau khi TẢI LẠI trang | **Khác** thứ tự lúc đang chạy (đã thấy: ô video chen giữa các ô ảnh, không theo thứ tự gửi); nhưng giữa hai lần tải thì giống nhau. ⇒ không được tải lại trang chính khi còn lệnh đang chạy. |
| Lưới có vẽ hết các ô không? | **Không.** Lưới dùng cuộn ảo (`.cdk-virtual-scrollable`): chỉ khoảng **16 ô** nằm trong trang, cuộn thì cửa sổ ô đổi. ⇒ KHÔNG được dựa vào tổng số ô. (Lỗi thật từng gặp: dự án đã có 14 ô, gửi lệnh thứ 3 thì số ô không tăng → app tưởng Flow không nhận lệnh → gửi lại → Flow tạo trùng 3 lần.) |
| Khoá ô | Ảnh: `data-media-id`. Video: không có `data-media-id`, khoá = băm địa chỉ ảnh thu nhỏ — **ổn định giữa các lần tải trang** (đã so hai tab). |
| Ô video đang tạo | Ba giai đoạn: xếp hàng (nhãn = prompt, chưa có %, chưa có ảnh thu nhỏ) → đang tạo (có "N%", nhãn đổi thành tên tự đặt) → xong (hết %, ảnh thu nhỏ đã tải, ô hiện nhãn độ phân giải). "Không có %" chưa chắc là xong: phải có ảnh thu nhỏ. |
| Video có chạy song song không? | **Có.** 2 video gửi liên tiếp cùng chạy, xong cách nhau ~4 giây (~40 s thay vì ~80 s). Giá 360p/4s = 4 credit. |
| Địa chỉ chỉnh sửa của video | Không đọc được từ ô (không có liên kết, mã nằm trong ảnh thu nhỏ bị mã hóa): phải mở ô → đó là tải lại lưới ⇒ làm trên **tab phụ**. |
| Nút Tạo | Tạm vô hiệu ngay sau khi bấm (vì ô prompt trống), bật lại khi gõ prompt mới. Không có thông báo từ chối. |
| Hộp thoại phủ | Flow thỉnh thoảng hiện hộp "Changelog" (iframe trong `.cdk-overlay-container`) chặn mọi cú bấm; driver tự đóng bằng Esc. |

Chưa kiểm tra:
- Tài khoản **PRO** `acc_1` (giao diện tiếng Việt, bố cục "Tác nhân"): chạy song song được mấy lệnh, ảnh tốn bao nhiêu credit.
- Giới hạn số lệnh đồng thời của Flow theo gói (mới thử 3).
- Thông báo hết lượt / vi phạm chính sách khi chạy song song (chưa gặp; xử lý theo chữ trên trang như chế độ cũ).

## 3. Thiết kế

### 3.1 Gắn kết quả với đúng lệnh (điểm khó nhất)
Không dùng dấu gắn lên ô (không bền) và **không dùng tổng số ô** (cuộn ảo). Dùng **vị trí tính từ trên cùng**:
- mỗi lệnh gửi đi có `rank` = 0, 1, 2… (theo thứ tự gửi); khi đã gửi tổng cộng S lệnh, lệnh `rank` k là ô thứ `S − 1 − k` từ trên
  cùng (ô mới nhất ở đầu);
- **trang chính không bao giờ được tải lại** trong phiên, và trước mỗi lần đọc lưới phải cuộn về đầu (bước tải về có cuộn ô vào tầm
  nhìn), để cửa sổ ô ảo luôn chứa các ô mới nhất;
- **xác nhận đã gửi**: sau khi bấm Tạo, chỉ coi là Flow đã nhận khi có ô MỚI ở đầu lưới (số ô tăng, hoặc nhãn ô mới = đầu prompt,
  hoặc ô đầu đang chạy + đổi so với trước + ô prompt đã trống). Không nhận được trong 20 s thì báo lỗi tạm thời;
- **mỏ neo** phát hiện lệch pha: lúc bắt đầu ghi khóa ô mới nhất đang có. Nếu mỏ neo còn trong cửa sổ thì nó phải nằm đúng vị trí S;
  sai 3 lần đọc liên tiếp (ai đó tạo / xóa ô bằng tay) thì phiên **lệch pha**: ngừng nhận việc, các lệnh chưa xong báo lỗi tạm thời
  (tự thử lại). Mỏ neo bị cuộn ảo ẩn đi (phiên rất dài) thì bỏ qua kiểm tra này.
- việc cần tải lại lưới (lấy địa chỉ chỉnh sửa của video) làm ở **tab phụ**, tìm ô theo khóa (khóa ổn định giữa các lần tải).

### 3.2 Phía backend
- `accounts.max_concurrent` (mặc định **1**, đặt ở trang Tài khoản; ULTRA đề xuất 3). Mặc định 1 để không đổi hành vi cũ và vì
  chưa kiểm tra tài khoản PRO / bố cục "Tác nhân".
- Thuê tài khoản theo **số job đang giữ** (thay cho `leasedJobId` đơn): tài khoản `LEASED` khi còn job đang chạy, về `READY` khi
  job cuối xong.
- API mới `POST /api/worker/claim-more { workerId, accountId }`: cấp thêm job cho đúng tài khoản worker đang giữ phiên, nếu
  chưa đủ `max_concurrent`; chọn job theo đúng thứ tự "chạy theo cảnh" hiện có. `204` khi hết việc hoặc đã đủ.
- Heartbeat, hủy, thu hồi job treo vẫn theo **từng job** như cũ.
- Hết lượt (quota) của tài khoản: các job đang chạy vẫn được thu kết quả; không cấp thêm.

### 3.3 Phía worker / driver
`FlowBrowserDriver.run_session(account, first_job, claim_more, report_*)`:
vòng lặp một luồng (Playwright đồng bộ không dùng nhiều luồng):
1. còn chỗ và còn việc → `claim_more` → gửi lệnh mới (tải ảnh tham chiếu, gõ prompt, bấm Tạo, ghi `rank`) — khoảng 10–15 giây;
2. đọc lưới một lần, xem lệnh nào đã có `data-media-id` → tải về → báo `/generated` **ngay** (chuyển "Chờ duyệt");
3. nghỉ 1,5 giây; lặp tới khi không còn lệnh đang chạy và không còn việc → đóng Edge.
Các lệnh cùng phiên dùng chung một lần mở Edge và một dự án Flow của tài khoản đó.

### 3.4 Kiểm chứng
- `FakeDriver` mô phỏng nhiều lệnh đồng thời (độ trễ ngẫu nhiên) để kiểm tra phía backend và vòng lặp phiên mà không cần Flow.
- Unit test chọn job / đếm lease / chống vượt `max_concurrent`.
- Chạy thật: ảnh (0 credit) trước, video sau khi được phép tiêu credit.

## 5. Kết quả kiểm chứng

| Bài thử | Kết quả |
|---|---|
| Driver giả (FAKE) qua backend + worker thật, 8 ảnh, 1 tài khoản ×3 | 8/8 xong sau 22,5 s (lần lượt ~60 s); tối đa 3 lệnh cùng lúc; tài khoản `LEASED` suốt phiên rồi về `READY`; duyệt → tên file đúng; tạo lại → chạy lại đúng |
| Flow thật, `run_session`, 4 ảnh + 2 video trộn trong một phiên | 6/6 xong, 94 s, 0 lỗi; xem từng ảnh / khung hình video: gắn đúng lệnh |
| Flow thật, dự án đã có hơn 16 ô (cuộn ảo), 5 ảnh + 1 video | 6/6 xong, 90 s, 0 lỗi; 6 ô mới nhất ở đầu lưới đúng là 6 lệnh, **không tạo trùng** |
| Flow thật, phiên 4 ảnh + mở tab phụ lấy địa chỉ chỉnh sửa sau mỗi ảnh | 4/4 xong, 72 s; 4/4 lấy được địa chỉ; ảnh vẫn gắn đúng |
| Tên file (backend thật, Excel Tập 6 thật, kết quả đưa vào bằng "kết quả thủ công") | Video `EP06-S1-01`, `EP06-S1-23`, `EP06-S2-01`, `EP06-S3-01`, `EP06-S103-10`; ảnh theo Reference ID (`CHAR-CLARA-1.jpg`) |
| Test tự động | Java 42 ca (JobClaimServiceMoreTest 11, VideoNamingTest 8…), Python 16 ca (có mô phỏng cuộn ảo) |

## 4. Rủi ro và cách giảm
- **Điều khoản / khóa tài khoản:** gửi dồn dập nhiều lệnh một tài khoản có thể bị Google để ý hơn. Giảm: `max_concurrent` do người
  dùng đặt, mặc định 1, giãn 2–3 giây giữa các lần gửi; không thêm kỹ thuật che giấu (xem `ARCHITECTURE-V2.md`).
- **Hết lượt nhanh hơn:** cùng số lệnh nhưng tiêu credit nhanh hơn theo thời gian; tài khoản hết lượt sẽ chuyển trạng thái nghỉ như cũ.
- **Gắn nhầm kết quả:** xem 3.1; luôn có kiểm tra lệch pha.
- **Edge treo giữa phiên:** các lệnh đang chạy mất heartbeat → backend thu hồi như cũ; lệnh đã gửi lên Flow mà chưa tải được sẽ
  được nhận lại nhờ cơ chế `_pending_result` đã có (tải thay vì tạo lại).
