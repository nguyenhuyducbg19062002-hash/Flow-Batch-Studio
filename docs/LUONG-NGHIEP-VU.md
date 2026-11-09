# Luồng nghiệp vụ Flow Batch Studio

Sơ đồ viết bằng [Mermaid](https://mermaid.js.org/): GitHub, GitLab, VS Code (extension *Markdown Preview Mermaid Support*) và IntelliJ tự vẽ khi mở file này.

## 1. Luồng tổng thể một tập phim

```mermaid
flowchart TD
    A([Bắt đầu]) --> B[Thêm tài khoản Google<br/>và đăng nhập]
    B --> C[Thêm phim:<br/>Folder Ảnh + Folder Video]
    C --> D{Nạp dữ liệu}
    D -->|Excel ảnh| E1[reference-prompts.xlsx]
    D -->|Excel video| E2[all-video-prompts.xlsx]
    D -->|Dán tay| E3[ID: prompt]
    D -->|Nạp cả thư mục phim| E4[Excel ảnh + video<br/>+ ảnh có sẵn]

    E1 & E3 & E4 --> IMG
    E2 & E4 --> VID

    subgraph IMG [Phần 1 - Tạo ảnh tham chiếu]
        I1[▶ Chạy tạo ảnh<br/>lượt 1: ảnh gốc] --> I2[Tải về Ảnh chờ duyệt<br/>1.png, 2.png ...]
        I2 --> R1{{Duyệt ảnh}}
        R1 -->|Ảnh hỏng| I3[Kéo vào thư mục lỗi<br/>hoặc nhập số sai]
        I3 -->|app quét 20 giây| I4[Cất bản cũ vào<br/>lỗi/đã xử lý]
        I4 --> I1
        R1 -->|Ảnh ổn| I5[Đổi tên N file<br/>→ CHAR-CLARA-1.png]
        I5 --> I6{Còn ảnh đổi đồ<br/>đang chờ ảnh gốc?}
        I6 -->|Có| I1
    end

    I6 -->|Không| G{Video đủ<br/>ảnh tham chiếu?}

    subgraph VID [Phần 2 - Tạo video]
        G -->|Thiếu| W[Video chờ<br/>báo: N video thiếu ảnh]
        G -->|Đủ| V1[▶ Chạy tạo video<br/>gắn @ID thành ảnh tham chiếu]
        V1 --> V2[Tải về Video chờ duyệt<br/>1.mp4, 2.mp4 ...]
        V2 --> R2{{Duyệt video}}
        R2 -->|Video hỏng| V3[Kéo vào thư mục lỗi<br/>hoặc nhập số sai]
        V3 --> V1
        R2 -->|Video ổn| V4[Đổi tên N file<br/>→ EP06-S1-01.mp4]
    end

    W -.->|sau khi Đổi tên ảnh| G
    V4 --> K[Kiểm tra file thiếu/hỏng<br/>trang Phim]
    K -->|Mất / hỏng| V1
    K -->|Đủ| Z([Đưa vào dựng phim])
```

## 2. Một job đi qua backend và engine

```mermaid
sequenceDiagram
    actor NV as Nhân viên
    participant UI as Frontend (Vue)
    participant BE as Backend (Spring)
    participant W as Worker Python
    participant G as Nền tảng tạo<br/>(Flow / Vertex / AI Studio / Seedance)
    participant FS as Thư mục phim

    NV->>UI: Nạp Excel, bấm ▶ Chạy
    UI->>BE: Tạo job (PENDING)
    loop Mỗi job
        W->>BE: Nhận job + chọn tài khoản còn lượt
        BE-->>W: Prompt, ảnh tham chiếu, cấu hình
        W->>G: Gửi prompt + ảnh
        G-->>W: Ảnh / video kết quả
        W->>FS: Lưu vào thư mục chờ duyệt (số thứ tự)
        W->>BE: Báo kết quả (AWAITING_REVIEW)
        alt Hết lượt tài khoản
            BE->>BE: Đổi sang tài khoản khác, trả job về PENDING
        end
    end
    NV->>FS: Xem file, kéo file sai vào lỗi
    BE->>FS: Quét 20 giây, phát hiện file lỗi
    BE->>BE: Đưa job về PENDING để tạo lại
    NV->>UI: Bấm Đổi tên N file
    UI->>BE: Duyệt + đổi tên
    BE->>FS: Chuyển sang Folder Ảnh / Folder Video (SUCCESS)
```

## 3. Trạng thái của một job (`JobStatus`)

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> GENERATING: worker nhận job
    PENDING --> AWAITING_REVIEW: đưa file tải tay vào
    GENERATING --> AWAITING_REVIEW: tạo xong, về thư mục chờ duyệt
    GENERATING --> PENDING: lỗi tạm thời / đổi tài khoản
    GENERATING --> FAILED: lỗi hẳn / bị từ chối
    AWAITING_REVIEW --> APPROVED: Đổi tên
    AWAITING_REVIEW --> PENDING: file vào thư mục lỗi
    APPROVED --> DOWNLOADING: chuyển sang folder đích
    APPROVED --> PENDING: làm lại
    DOWNLOADING --> SUCCESS
    DOWNLOADING --> APPROVED: chuyển lỗi, thử lại
    SUCCESS --> PENDING: file mất / hỏng, tạo lại
    FAILED --> PENDING: thử lại
    FAILED --> AWAITING_REVIEW: đưa file tải tay vào
    CANCELLED --> PENDING
    CANCELLED --> AWAITING_REVIEW
    PENDING --> CANCELLED
    GENERATING --> CANCELLED
    AWAITING_REVIEW --> CANCELLED
    APPROVED --> CANCELLED
    FAILED --> CANCELLED
```
