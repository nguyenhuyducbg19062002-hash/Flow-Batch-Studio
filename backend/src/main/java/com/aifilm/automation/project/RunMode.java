package com.aifilm.automation.project;

public enum RunMode {
    /** Worker tự điền prompt, bấm tạo, chờ và tải kết quả về staging. */
    BACKGROUND,
    /** Mở Edge có giao diện, điền sẵn prompt + ảnh; người dùng tự bấm tạo; tool bắt file khi người dùng bấm Download. */
    FOREGROUND
}
