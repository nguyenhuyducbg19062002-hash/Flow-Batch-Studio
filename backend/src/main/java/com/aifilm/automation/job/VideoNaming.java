package com.aifilm.automation.job;

import com.aifilm.automation.prompt.PromptEntity;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tên file video khi duyệt: TÊN CẢNH + số thứ tự trong cảnh, ví dụ cảnh EP06-S1 có 24 video → EP06-S1-01 … EP06-S1-24.
 * Tên cảnh lấy ở dòng đầu cột "Ghi chú" của all-video-prompts.xlsx ("Cảnh EP06-S1" rồi mới tới mô tả); số thứ tự đếm
 * theo ĐÚNG thứ tự các dòng trong file (STT), bắt đầu từ 1 ở mỗi cảnh. Số được đệm 0 cho đủ ít nhất 2 chữ số (cảnh có từ 100
 * video trở lên thì 3 chữ số) để sắp xếp theo tên file cũng đúng thứ tự.
 * Không đọc được tên cảnh (file prompt cũ, nhập tay…) thì trả về rỗng: ReviewService dùng cách đặt tên cũ (mã phim + STT).
 */
public final class VideoNaming {

    // "Cảnh EP06-S1\n1. …"  (chấp nhận "Canh" không dấu, hoa / thường, dấu ở dạng dựng sẵn hay tách rời)
    private static final Pattern SCENE = Pattern.compile("^\\s*(?:C[ảa]nh)\\s+([A-Za-z0-9][A-Za-z0-9._-]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private VideoNaming() {}

    /** Tên cảnh ở đầu mô tả ("Cảnh EP06-S1\n…" → "EP06-S1"), rỗng nếu không có. */
    public static Optional<String> sceneOf(String summary) {
        if (summary == null || summary.isBlank()) {
            return Optional.empty();
        }
        String text = Normalizer.normalize(summary, Normalizer.Form.NFC);
        Matcher m = SCENE.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String scene = m.group(1).replaceAll("[._-]+$", "");   // bỏ dấu câu dính ở cuối ("EP06-S1." → "EP06-S1")
        return scene.isEmpty() ? Optional.empty() : Optional.of(scene);
    }

    /**
     * Tên (không có đuôi file) của một video, hoặc rỗng nếu không xác định được cảnh.
     * @param videos mọi prompt VIDEO của phim (cùng thứ tự hay không đều được: hàm tự sắp theo file Excel rồi STT).
     */
    public static Optional<String> nameFor(PromptEntity video, Collection<PromptEntity> videos) {
        Optional<String> scene = sceneOf(video.getSummaryVi());
        if (scene.isEmpty()) {
            return Optional.empty();
        }
        List<PromptEntity> sameScene = videos.stream()
                .filter(p -> sceneOf(p.getSummaryVi()).filter(scene.get()::equals).isPresent())
                .sorted(Comparator.comparing((PromptEntity p) -> p.getSourceOrder() == null ? 0 : p.getSourceOrder())
                        .thenComparingInt(PromptEntity::getSeqNo))
                .toList();
        int index = 0;
        for (int i = 0; i < sameScene.size(); i++) {
            if (sameScene.get(i).getId() != null && sameScene.get(i).getId().equals(video.getId())
                    || sameScene.get(i) == video) {
                index = i + 1;
                break;
            }
        }
        if (index == 0) {
            return Optional.empty();
        }
        int width = Math.max(2, String.valueOf(sameScene.size()).length());
        return Optional.of(scene.get() + "-" + String.format("%0" + width + "d", index));
    }
}
