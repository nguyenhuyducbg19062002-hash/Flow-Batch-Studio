package com.aifilm.automation.job;

import com.aifilm.automation.project.AssetService;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.system.AppPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Chế độ "Tự làm trên Flow": app KHÔNG điều khiển Flow. App bày ra từng cảnh theo đúng thứ tự (prompt, ảnh reference),
 * người dùng tự dán prompt, gắn ảnh, bấm Tạo và Tải xuống trên Flow; app nhận file vừa tải về thư mục Downloads,
 * đặt đúng tên, lưu vào Folder Ảnh / Folder Video và chuyển sang cảnh kế tiếp.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualService {

    static final String DOWNLOADS_SETTING = "manual_downloads_dir";
    static final Set<String> IMAGE_EXT = Set.of(".png", ".jpg", ".jpeg", ".webp");
    static final Set<String> VIDEO_EXT = Set.of(".mp4", ".mov", ".webm");
    /** File sửa đổi gần hơn mức này có thể đang được trình duyệt ghi dở. */
    static final long STABLE_MS = 1500;

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobClaimService claims;
    private final JobQueryService query;
    private final AssetService assets;
    private final ReviewService review;
    private final AppSettingsService settings;

    public record NextScene(JobView job, boolean autoRunning, long remaining, String note) {}

    public record DownloadFile(String path, String name, long size, long modified) {}

    /** Cảnh kế tiếp cần làm. skip: cảnh người dùng bấm "Để sau" trong phiên này. */
    @Transactional(readOnly = true)
    public NextScene next(long projectId, Collection<Long> skip) {
        return next(projectId, skip, false);
    }

    /** imagesOnly: "Chỉ làm ảnh" - làm hết ảnh (nhân vật, bối cảnh, panel) trước rồi mới sang video. */
    @Transactional(readOnly = true)
    public NextScene next(long projectId, Collection<Long> skip, boolean imagesOnly) {
        ProjectEntity p = projects.findById(projectId).orElseThrow();
        long remaining = imagesOnly
                ? jobs.countByProjectIdAndKindAndStatus(projectId, PromptKind.IMAGE, JobStatus.PENDING)
                : jobs.countByProjectIdAndStatus(projectId, JobStatus.PENDING);
        Optional<JobEntity> next = claims.peekNext(p, new HashSet<>(skip), imagesOnly);
        String note = null;
        if (next.isEmpty() && imagesOnly) {
            note = remaining == 0
                    ? "Đã xong hết ảnh của phim. Tắt \"Chỉ làm ảnh\" để sang làm video"
                    : !skip.isEmpty()
                    ? "Hết ảnh chưa làm; còn các ảnh bạn bấm \"Để sau\""
                    : "Các ảnh còn lại cần ảnh khác chưa có (ví dụ panel cần ảnh nhân vật chưa có prompt tạo): thêm ảnh có sẵn ở trang Phim";
        } else if (next.isEmpty()) {
            if (remaining == 0) {
                note = "Phim không còn cảnh nào chờ làm";
            } else if (!skip.isEmpty()) {
                note = "Hết cảnh chưa làm; còn các cảnh bạn bấm \"Để sau\"";
            } else if (p.isSelectedOnlyOn()) {
                note = "Phim đang ở chế độ chỉ chạy cảnh đã chọn và không còn cảnh đã chọn nào";
            } else {
                note = "Các cảnh còn lại đang chờ ảnh reference (ảnh chưa duyệt hoặc chưa có prompt tạo)";
            }
        }
        return new NextScene(next.map(j -> query.view(j.getId())).orElse(null),
                p.getStatus() == ProjectStatus.RUNNING, remaining, note);
    }

    /**
     * Chép ảnh reference của cảnh vào một thư mục riêng (đúng thứ tự, đúng tên ID) rồi mở bằng Explorer:
     * người dùng chọn hết và kéo thả vào Flow. Trả về đường dẫn thư mục.
     */
    @Transactional(readOnly = true)
    public Path prepareReferences(long jobId, boolean open) {
        return prepareReferences(jobId, null, open);
    }

    /** only: chỉ chép các ảnh này (ảnh chưa có trên dự án Flow); null = mọi ảnh của cảnh. */
    @Transactional(readOnly = true)
    public Path prepareReferences(long jobId, Collection<String> only, boolean open) {
        JobView job = query.view(jobId);
        JobEntity entity = jobs.findById(jobId).orElseThrow();
        Path dir = AppPaths.data().resolve("canh-dang-lam").resolve("phim-" + entity.getProjectId());
        try {
            Files.createDirectories(dir);
            try (Stream<Path> old = Files.list(dir)) {
                for (Path f : old.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            int copied = 0;
            for (JobView.RefView r : job.references()) {
                if (only != null && !only.contains(r.id())) {
                    continue;
                }
                Optional<Path> src = assets.file(entity.getProjectId(), r.id());
                if (src.isEmpty() || !Files.isRegularFile(src.get())) {
                    continue;
                }
                Files.copy(src.get(), dir.resolve(r.id() + ReviewService.extensionOf(src.get().getFileName().toString())),
                        StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            if (copied == 0) {
                throw new IllegalStateException("Cảnh này không có ảnh reference nào để gắn");
            }
            if (open) {
                new ProcessBuilder("explorer.exe", dir.toString()).start();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Không chuẩn bị được ảnh của cảnh: " + e.getMessage(), e);
        }
        return dir;
    }

    public Path downloadsDir() {
        String configured = settings.getSetting(DOWNLOADS_SETTING, "");
        return configured.isBlank() ? Path.of(System.getProperty("user.home"), "Downloads") : Path.of(configured);
    }

    /** File ảnh / video tải xong (không còn đang ghi) trong thư mục Downloads, mới hơn `after` (epoch ms), mới nhất trước. */
    public List<DownloadFile> downloads(long after, PromptKind kind) {
        return downloads(downloadsDir(), after, kind, System.currentTimeMillis());
    }

    static List<DownloadFile> downloads(Path dir, long after, PromptKind kind, long now) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Set<String> exts = kind == PromptKind.VIDEO ? VIDEO_EXT : kind == PromptKind.IMAGE ? IMAGE_EXT : union();
        List<DownloadFile> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.toList()) {
                if (!Files.isRegularFile(f) || !exts.contains(ReviewService.extensionOf(f.getFileName().toString()))) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(f).toMillis();
                if (modified <= after || now - modified < STABLE_MS) {
                    continue;
                }
                out.add(new DownloadFile(f.toString(), f.getFileName().toString(), Files.size(f), modified));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được thư mục " + dir + ": " + e.getMessage(), e);
        }
        out.sort(Comparator.comparingLong(DownloadFile::modified).reversed());
        return out;
    }

    private static Set<String> union() {
        Set<String> all = new HashSet<>(IMAGE_EXT);
        all.addAll(VIDEO_EXT);
        return all;
    }

    /** Nhận một file trong thư mục Downloads làm kết quả của cảnh: kiểm tra, lưu đúng tên vào folder phim, đánh dấu xong. */
    public JobEntity importDownload(long jobId, String path) {
        Path dir = downloadsDir().toAbsolutePath().normalize();
        Path f = Path.of(path).toAbsolutePath().normalize();
        if (!dir.equals(f.getParent())) {
            throw new IllegalArgumentException("Chỉ nhận file nằm trong thư mục tải về " + dir);
        }
        JobEntity job = review.manualResult(jobId, f);
        log.info("Job {}: nhận file tự làm trên Flow {}", jobId, f.getFileName());
        return job;
    }
}
