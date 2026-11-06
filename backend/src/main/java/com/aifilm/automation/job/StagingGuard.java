package com.aifilm.automation.job;

import com.aifilm.automation.system.AppPaths;

import java.nio.file.Path;

/** File xem trước chỉ được nằm trong thư mục staging: chặn API preview đọc file tùy ý trên máy. */
public final class StagingGuard {

    private StagingGuard() {}

    public static Path requireInside(Path p) {
        Path normalized = p.toAbsolutePath().normalize();
        if (!normalized.startsWith(AppPaths.staging().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Đường dẫn nằm ngoài thư mục staging: " + p);
        }
        return normalized;
    }
}
