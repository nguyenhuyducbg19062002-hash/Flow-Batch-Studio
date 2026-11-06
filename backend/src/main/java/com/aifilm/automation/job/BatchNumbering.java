package com.aifilm.automation.job;

import com.aifilm.automation.prompt.PromptEntity;
import com.aifilm.automation.prompt.PromptKind;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Số thứ tự của từng mục trong thư mục chờ duyệt: 1.png, 2.png… (video: 1.mp4…).
 * Đánh theo đúng thứ tự trong file Excel (thứ tự file đã nạp, rồi STT / thứ tự dòng), bắt đầu từ 1, riêng cho ảnh và video.
 * Người duyệt mở thư mục, thấy số nào sai thì báo lại đúng số đó để app tạo lại.
 */
public final class BatchNumbering {

    private BatchNumbering() {}

    /** Các prompt cùng loại theo đúng thứ tự đánh số. */
    public static List<PromptEntity> ordered(Collection<PromptEntity> all, PromptKind kind) {
        return all.stream().filter(p -> p.getKind() == kind)
                .sorted(Comparator.comparingInt((PromptEntity p) -> p.getSourceOrder() == null ? 0 : p.getSourceOrder())
                        .thenComparingInt(PromptEntity::getSeqNo)
                        .thenComparing(PromptEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** promptId → số thứ tự (1..N). */
    public static Map<Long, Integer> numbers(Collection<PromptEntity> all, PromptKind kind) {
        List<PromptEntity> list = ordered(all, kind);
        Map<Long, Integer> out = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            out.put(list.get(i).getId(), i + 1);
        }
        return out;
    }

    /** Số chữ số: ít nhất 3 (001) để sắp theo tên file trong Explorer cũng đúng thứ tự. */
    public static int width(int total) {
        return Math.max(3, String.valueOf(Math.max(total, 1)).length());
    }

    /** Tên file (không đuôi) của số no: 5 → "5" (Explorer sắp 1, 2, …, 10 đúng thứ tự). */
    public static String baseName(int no, int total) {
        return String.valueOf(no);
    }
}
