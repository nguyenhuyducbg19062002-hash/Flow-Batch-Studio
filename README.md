# Flow Batch Studio

Tạo ảnh và video **hàng loạt** trên Google Flow, tách thành hai phần riêng (**Tạo ảnh**, **Tạo video**) chạy cùng một quy trình:

1. **Chạy**: app tạo hết, tải từng kết quả về **thư mục chờ duyệt**, đặt tên theo số thứ tự đúng thứ tự file prompt (`001.png`, `002.png`… / `001.mp4`…).
2. **Duyệt trong thư mục**: nhân viên mở thư mục xem; số nào sai thì nhập vào ô "Số bị sai" (ví dụ `5, 12, 30-33`, kèm ghi chú cần sửa nếu muốn) → app tạo lại đúng các số đó. File cũ được cất vào `_lam-lai`, bản mới về đúng tên số cũ.
3. **Đổi tên**: ổn hết thì bấm "Đổi tên N file": mọi file được đổi sang tên đúng và chuyển vào thư mục đích:
   - ảnh → Folder Ảnh, tên = Reference ID (`CHAR-CLARA-1.png`, `LOC-1.png`);
   - video → Folder Video, tên = tên cảnh + số trong cảnh (`EP06-S1-01.mp4`), lấy từ cột Ghi chú "Cảnh EP06-S1".

Phát triển từ AI-Video-2 (AI Video Studio): giữ tài khoản, xoay vòng tài khoản, extension Flow, đóng gói 1-click.
Thiết kế gốc (state machine, DB, xoay vòng tài khoản): [docs/ARCHITECTURE-V2.md](docs/ARCHITECTURE-V2.md).

## Cách dùng (người dùng cuối)

1. Cài bằng `FlowBatchStudio-Setup-<ver>.exe` (mặc định vào `C:\FlowBatchStudio`), rồi bấm đúp biểu tượng **Flow Batch Studio**. App mở trong cửa sổ riêng, cổng 8082 (chạy song song được với AI Video Studio ở cổng 8081).
2. **Tài khoản** → thêm tài khoản Google Flow và đăng nhập (giống app cũ).
3. **Phim** → Thêm phim: chọn Folder Ảnh (ví dụ `KB Hà. Tập 6\Ảnh tham chiếu`) và Folder Video (ví dụ `KB Hà. Tập 6\phim`).
   Thư mục chờ duyệt mặc định nằm cạnh hai folder đó: `Ảnh chờ duyệt`, `Video chờ duyệt` (đổi được trong "Sửa phim" / API).
   "Nạp cả thư mục phim" nạp một lần cả Excel ảnh, Excel video và ảnh có sẵn.
4. **Tạo ảnh**:
   - Nạp `reference-prompts.xlsx` (cột Reference ID + Prompt tạo reference) hoặc **Dán prompt** (`ID: prompt`, mỗi dòng / mỗi khối cách dòng trống một ảnh).
   - Ảnh đổi đồ (OUTFIT VARIANT, ví dụ CHAR-CLARA-2 tạo từ CHAR-CLARA-1) tự gắn ảnh gốc nhắc trong prompt; ảnh gốc được tạo trước và dùng ngay bản trong thư mục chờ duyệt. Thêm ảnh tham chiếu tay bằng nút "+" ở từng dòng (mở "Chi tiết").
   - ▶ Chạy tạo ảnh → duyệt thư mục → làm lại số sai → **Đổi tên**.
5. **Tạo video**:
   - Nạp `all-video-prompts.xlsx` hoặc dán prompt. Mỗi `@ID` trong prompt được upload từ Folder Ảnh và gắn thành chip ảnh trên Flow, đúng thứ tự khai báo.
   - Video **chỉ dùng ảnh đã Đổi tên sang Folder Ảnh** (hoặc ảnh có sẵn), không dùng ảnh còn chờ duyệt. Video thiếu ảnh thì chờ; phần video tự dừng và báo "N video chưa chạy được vì thiếu ảnh tham chiếu".
   - ▶ Chạy tạo video → duyệt thư mục → làm lại số sai → **Đổi tên**.
6. Mỗi phần có nút Chạy / Tạm dừng riêng; chạy xong hết thì phần đó tự dừng và nhắc bước tiếp theo. Ở danh sách, mỗi dòng hiện số ↔ tên sau khi đổi, trạng thái, ảnh tham chiếu; "Chi tiết" để sửa prompt rồi "Lưu & tạo lại".
7. **Kiểm tra file thiếu/hỏng** (trang Phim): file đã đổi tên mà bị mất / hỏng thì app tạo lại đúng mục đó.

Dữ liệu (DB, profile tài khoản, staging, log) nằm ở `%LOCALAPPDATA%\FlowBatchStudio`. Gỡ cài đặt không xóa thư mục này.

**Lưu ý:** extension "Flow Batch Studio – Flow Bridge" dùng chung cổng cầu nối 18321–18323 với extension của AI Video Studio: mỗi lúc chỉ chạy một app ở chế độ Flow (Extension).

## Selector Google Flow

Google Flow không có API công khai, nên tool thao tác trên giao diện web. Các selector nằm trong `engine/selectors.json` (sau khi cài: `C:\FlowBatchStudio\engine\selectors.json`). Flow đổi giao diện thì sửa file này, không cần build lại.

Cách tool làm một cảnh (đã chạy thật trên Flow ngày 03/11/2026, tạo ảnh PANEL-1):
1. Vào dự án Flow của phim **trên tài khoản đang chạy** (mỗi tài khoản Google có dự án riêng; chưa có hoặc dự án đã bị xóa thì tự bấm "Dự án mới", link được nhớ ngay cho các cảnh sau), đặt số bản tạo = x1. Chạy được cả giao diện tiếng Việt / tiếng Anh, cả bố cục "Tác nhân" lẫn bố cục tạo trực tiếp.
2. Tải lên dự án những ảnh reference **chưa có** (Flow giữ nguyên tên file, ví dụ `CHAR-CALEB-1.png`).
3. Gõ prompt; mỗi `@ID` được thay bằng chip ảnh của Flow (gõ `@`, tìm ID, bấm đúng tên file).
4. Bấm Tạo. Ô prompt của Flow là chat với **Tác nhân**; nếu Tác nhân hỏi lại "proceed?", tool tự trả lời đồng ý (`agent_auto_reply`).
5. Chờ ô kết quả mới, rồi ⋮ → Tải xuống (ảnh 1K, video theo chất lượng của phim) và lưu với tên đúng ID.

- **Chạy ngầm**: tool tự làm cả 5 bước.
- **Mở Flow**: cửa sổ Flow hiện ra để bạn xem. **Ảnh**: tool tự làm cả 5 bước (nếu gắn được đủ ảnh @tag). **Video**: tool làm bước 1–3, bạn kiểm tra rồi tự bấm Tạo và Tải xuống. Mỗi tài khoản một cửa sổ, chạy song song theo ô "Song song" của phim. Đóng cửa sổ hoặc bấm "Tạm dừng phim" thì phim tạm dừng; nút "Tài khoản hết lượt" đổi sang tài khoản khác.
- Chưa kiểm tra trên Flow thật: chọn độ dài và chất lượng video, nội dung thông báo hết lượt / vi phạm chính sách (`TODO` trong file).
- Ghi lại selector bằng: `python -m playwright codegen --channel chrome https://flow.google.com/`

## Phát triển

Cần JDK 17+, Maven, Node.js 18+, Python 3.13 có `playwright` và `requests`.

> **Đặt dự án ở đường dẫn KHÔNG DẤU** (ví dụ `D:\Dev\AI-Video`). Với đường dẫn như `D:\Dev\Dự án\...`, IntelliJ, Maven và jpackage đều báo `InvalidPathException: Illegal char <?>`.

**1. Backend trong IntelliJ**
- Mở thư mục gốc dự án bằng IntelliJ.
- Chuột phải `backend/pom.xml` → **Add as Maven Project** (nếu IntelliJ chưa tự nhận).
- File → Project Structure → SDK = JDK 17.
- Chọn cấu hình chạy **Backend** (có sẵn trong `.run/`) → Run.
  - Working directory = thư mục gốc, để backend tìm thấy `engine\` và bật worker Python.
  - Dữ liệu thử nghiệm nằm ở `data\` của dự án, tách khỏi dữ liệu của bản exe.
- Kiểm tra: http://127.0.0.1:8080/api/system/info → `"workersAlive": 3`.

**2. Frontend riêng (hot reload)**
```bat
cd frontend
npm install
npm run dev
```
Mở http://localhost:5173. Các lời gọi `/api` được proxy sang backend `127.0.0.1:8080`.

**3. Chạy backend bằng dòng lệnh (không cần IntelliJ, không cần cài Maven)**
```bat
cd backend
mvnw.cmd -q package -DskipTests
cd ..
java -Dafm.data.dir=data -jar backend\target\app.jar
```
Muốn có giao diện ở cổng 8080 thì phải `npm run build` trong `frontend\` trước khi package.

Tùy chọn JVM hữu ích khi phát triển:

| Tùy chọn | Tác dụng |
|---|---|
| `-Dafm.data.dir=<dir>` | Dùng thư mục dữ liệu riêng |
| `-Dafm.noBrowser=true` | Không tự mở trình duyệt |
| `-Dafm.noWorkers=true` | Không bật worker (để chạy worker tay khi debug) |

Muốn chạy thử cả vòng mà không cần Google: tạo tài khoản và phim với nền tảng **Chạy thử (FAKE)**.
- Tài khoản có email bắt đầu bằng `quota` luôn báo hết quota, dùng để thử xoay vòng tài khoản.
- Prompt chứa `[fake:reject]` sẽ bị từ chối.

## Đóng gói

```bat
build_release.bat
```

Script build trong `C:\fbs-build`, vì `jlink`/`jpackage` không nhận đường dẫn có dấu như "Dự án". Kết quả:
- `C:\fbs-build\image\FlowBatchStudio\FlowBatchStudio.exe`: bản chạy ngay, gồm JRE thu gọn + Python embeddable.
- `dist\FlowBatchStudio-Setup-<ver>.exe`: bộ cài, tạo khi máy có Inno Setup 6.3+.

**Lưu ý:** launcher của jpackage (JDK 17) **không chạy được trong thư mục có chữ có dấu**. Bộ cài mặc định `C:\FlowBatchStudio` và chặn chọn thư mục có dấu. Bản chạy ngay cũng phải đặt ở đường dẫn ASCII.
