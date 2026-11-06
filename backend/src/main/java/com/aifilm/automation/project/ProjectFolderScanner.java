package com.aifilm.automation.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Quét "thư mục tổng" của một tập phim (ví dụ D:\Dev\cactap\Tập 5. Hà - Cupid) để điền sẵn form thêm phim:
 * - thư mục chứa Excel / txt prompt của Omni-Flash-Pipeline (chính thư mục tổng nếu có các file quen thuộc);
 * - thư mục ảnh tham chiếu và thư mục video: dùng thư mục con đã có (tên như "Ảnh tham chiếu", "phim", "video"…),
 *   chưa có thì đề xuất đường dẫn mới; thư mục sẽ được tạo khi lưu phim (ProjectService), không phải ở bước quét.
 * Không ghi gì lên đĩa.
 */
public final class ProjectFolderScanner {

    static final String DEFAULT_IMAGE_DIR = "Ảnh tham chiếu";
    static final String DEFAULT_VIDEO_DIR = "phim";

    private static final List<String> IMAGE_EXACT = List.of("anhthamchieu", "thamchieu", "anh", "anhgoc", "images", "image",
            "references", "reference", "refs", "ref");
    private static final List<String> IMAGE_CONTAINS = List.of("thamchieu", "reference", "image", "anh");
    private static final List<String> VIDEO_EXACT = List.of("phim", "video", "videos", "clip", "clips", "movie", "movies");
    private static final List<String> VIDEO_CONTAINS = List.of("video", "phim", "clip", "movie");

    static final Set<String> PROMPT_FILES = Set.of("reference-prompts.xlsx", "prompt-anh-panel.xlsx",
            "all-video-prompts.xlsx", "reference-prompts.txt", "prompt-anh-panel.txt", "final_video_prompts.txt");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "webm", "mkv");

    public record Scan(String root, String name, String sourceDir, List<String> promptFiles,
                       String imageDir, boolean imageExists, int imageCount,
                       String videoDir, boolean videoExists, int videoCount) {}

    public record Dirs(String imageDir, String videoDir) {}

    /**
     * Thư mục ảnh / video của phim. Người dùng điền thì giữ nguyên; để trống thì tìm trong Thư mục kết quả của Omni-Flash-Pipeline
     * (không phân biệt hoa thường, đủ dấu hay thiếu dấu), không có thì đề xuất tạo mới ngay trong thư mục đó (việc tạo do
     * ProjectService làm khi lưu phim).
     */
    public static Dirs resolveDirs(String sourceDir, String imageDir, String videoDir) {
        boolean needImage = imageDir == null || imageDir.isBlank();
        boolean needVideo = videoDir == null || videoDir.isBlank();
        if (!needImage && !needVideo) {
            return new Dirs(imageDir.trim(), videoDir.trim());
        }
        if (sourceDir == null || sourceDir.isBlank()) {
            throw new IllegalArgumentException((needImage ? "Folder Ảnh" : "Folder Video")
                    + " không được để trống: điền thư mục, hoặc chọn \"Thư mục kết quả của Omni-Flash-Pipeline\" để app tự tìm / tạo");
        }
        Scan s = scan(Path.of(sourceDir.trim()));
        return new Dirs(needImage ? s.imageDir() : imageDir.trim(), needVideo ? s.videoDir() : videoDir.trim());
    }

    private ProjectFolderScanner() {}

    public static Scan scan(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Thư mục không tồn tại: " + root);
        }
        List<Path> subDirs = list(root, true);
        Path image = pick(subDirs, IMAGE_EXACT, IMAGE_CONTAINS, null);
        Path video = pick(subDirs, VIDEO_EXACT, VIDEO_CONTAINS, image);
        boolean imageExists = image != null;
        boolean videoExists = video != null;
        if (!imageExists) image = root.resolve(DEFAULT_IMAGE_DIR);
        if (!videoExists) video = root.resolve(DEFAULT_VIDEO_DIR);

        List<String> prompts = new ArrayList<>();
        for (Path f : list(root, false)) {
            String n = f.getFileName().toString();
            if (PROMPT_FILES.contains(n.toLowerCase(Locale.ROOT))) {
                prompts.add(n);
            }
        }
        Path name = root.getFileName();
        return new Scan(root.toString(), name == null ? root.toString() : name.toString(),
                prompts.isEmpty() ? "" : root.toString(), prompts,
                image.toString(), imageExists, imageExists ? countFiles(image, IMAGE_EXT) : 0,
                video.toString(), videoExists, videoExists ? countFiles(video, VIDEO_EXT) : 0);
    }

    /** Thư mục con (dirs = true) hoặc file (dirs = false) trực tiếp trong root; bỏ qua mục ẩn và thư mục phụ bắt đầu bằng "_". */
    private static List<Path> list(Path root, boolean dirs) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> dirs ? Files.isDirectory(p) : Files.isRegularFile(p))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith(".") && !n.startsWith("~$") && !(dirs && n.startsWith("_"));
                    })
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được thư mục " + root, e);
        }
    }

    private static Path pick(List<Path> dirs, List<String> exact, List<String> contains, Path exclude) {
        List<Path> candidates = dirs.stream().filter(d -> !d.equals(exclude)).toList();
        for (String key : exact) {
            for (Path d : candidates) {
                if (norm(d.getFileName().toString()).equals(key)) return d;
            }
        }
        for (String key : contains) {
            for (Path d : candidates) {
                if (hasWord(d.getFileName().toString(), key)) return d;
            }
        }
        return null;
    }

    /**
     * Tên có từ khóa ở ĐẦU một từ: "Ảnh gốc", "hinh-anh", "tham chiếu 2" khớp "anh" / "thamchieu";
     * "Thành phẩm", "Cảnh quay", "Doanh thu" thì không (chỉ chứa "anh" giữa từ).
     */
    static boolean hasWord(String name, String key) {
        String t = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        StringBuilder joined = new StringBuilder();
        Set<Integer> starts = new java.util.HashSet<>();
        boolean inWord = false;
        for (char c : t.toCharArray()) {
            boolean word = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (word && !inWord) {
                starts.add(joined.length());
            }
            if (word) {
                joined.append(c);
            }
            inWord = word;
        }
        for (int i = joined.indexOf(key); i >= 0; i = joined.indexOf(key, i + 1)) {
            if (starts.contains(i)) return true;
        }
        return false;
    }

    private static int countFiles(Path dir, Set<String> exts) {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString();
                int dot = n.lastIndexOf('.');
                return dot >= 0 && exts.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
            }).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** "Ảnh tham chiếu" → "anhthamchieu": bỏ dấu, chữ thường, chỉ giữ chữ và số. */
    static String norm(String s) {
        String t = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
