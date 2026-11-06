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
        Boolean generateAudio,
        /** Nhịp thao tác như người thật trên Flow (Cài đặt human_pace): 0 = không dừng, 1 = bình thường, 2 = chậm gấp đôi. */
        Double humanPace,
        /** Model cần chọn trong bảng cài đặt Flow (Cài đặt flow_image_model / flow_video_model); rỗng = giữ model hiện tại. */
        String flowModel,
        /** Số lần bấm ↻ trên ô lỗi của Flow trước khi bỏ cuộc (Cài đặt error_tile_retries, mặc định 10). */
        Integer errorTileRetries,
        /** Thinking level của ảnh trên Flow: low | medium | high; rỗng = giữ nguyên. */
        String flowThinking) {
}
