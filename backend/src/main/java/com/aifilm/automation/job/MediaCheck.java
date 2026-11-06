package com.aifilm.automation.job;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Kiểm tra nhanh một file ảnh/video có "trông như thật" không: tồn tại, không quá nhỏ, và vài byte đầu khớp định dạng.
 * Không giải mã cả file (không cần ffmpeg); đủ để bắt file rỗng, tải dở, hoặc trang lỗi HTML bị lưu nhầm thành .mp4.
 */
public final class MediaCheck {

    private MediaCheck() {}

    /** File nhỏ hơn mức này chắc chắn không phải ảnh/video dùng được. */
    static final long MIN_BYTES = 100;

    /** Trả về lý do hỏng, hoặc rỗng nếu file ổn. */
    public static Optional<String> problem(Path p) {
        try {
            if (p == null || !Files.isRegularFile(p)) {
                return Optional.of("không tìm thấy file");
            }
            long size = Files.size(p);
            if (size < MIN_BYTES) {
                return Optional.of("file quá nhỏ (" + size + " byte)");
            }
            byte[] head = new byte[16];
            int n;
            try (InputStream in = Files.newInputStream(p)) {
                n = in.readNBytes(head, 0, head.length);
            }
            if (n < 12) {
                return Optional.of("file quá ngắn");
            }
            return knownFormat(head) ? Optional.empty() : Optional.of("không phải ảnh/video hợp lệ (định dạng lạ)");
        } catch (IOException e) {
            return Optional.of("không đọc được file: " + e.getMessage());
        }
    }

    /** Cảnh video phải nhận video, cảnh ảnh phải nhận ảnh. */
    public static Optional<String> wrongKind(Path p, boolean expectVideo) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] h = new byte[16];
            if (in.readNBytes(h, 0, h.length) < 12) {
                return Optional.empty();
            }
            boolean video = isMp4(h) || isWebm(h);
            if (expectVideo && !video) {
                return Optional.of("Flow trả về ẢNH cho cảnh video");
            }
            if (!expectVideo && video) {
                return Optional.of("Flow trả về VIDEO cho cảnh ảnh");
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static boolean knownFormat(byte[] h) {
        return isMp4(h) || isWebm(h) || isPng(h) || isJpeg(h) || isWebp(h);
    }

    /** MP4 / MOV: hộp đầu tiên là "ftyp" ở byte 4..7. */
    static boolean isMp4(byte[] h) {
        return h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
    }

    static boolean isWebm(byte[] h) {
        return (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
    }

    static boolean isPng(byte[] h) {
        return (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
    }

    static boolean isJpeg(byte[] h) {
        return (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }

    static boolean isWebp(byte[] h) {
        return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
    }
}
