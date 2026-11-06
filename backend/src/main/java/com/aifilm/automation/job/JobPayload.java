package com.aifilm.automation.job;

import java.util.List;
import java.util.Map;

/** Toàn bộ dữ liệu worker Python cần cho một lần tạo, gói một lần. Python không đọc DB. */
public record JobPayload(
        long jobId,
        String kind,
        String provider,
        String runMode,
        String externalId,
        String prompt,
        List<String> referenceImages,
        Map<String, Object> account,
        String stagingDir,
        String flowProjectUrl,
        String aspectRatio,
        int timeoutSeconds,
        boolean headless,
        /** Độ dài video (giây) cần chọn trên nền tảng; null = để mặc định / theo prompt. Chỉ dùng cho VIDEO. */
        Integer durationSeconds,
        /** Chất lượng tải video về: 480p | 720p | 1080p. */
        String videoQuality,
        /** "Sửa trên Flow": yêu cầu sửa + địa chỉ màn chỉnh sửa của kết quả cũ. null = tạo mới. */
        String editInstruction,
        String editSourceUrl,
        /** Seedance: tạo kèm âm thanh; null = theo mặc định (bật). */
        Boolean generateAudio) {
}
