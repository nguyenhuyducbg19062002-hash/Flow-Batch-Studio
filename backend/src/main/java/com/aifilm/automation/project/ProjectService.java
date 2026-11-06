package com.aifilm.automation.project;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.*;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final AccountRepository accounts;
    private final ProjectAssetRepository assets;
    private final JobTransitions transitions;
    private final OmniImportService importer;
    private final AssetService assetService;
    private final FlowLinkRepository flowLinks;

    public record ProjectForm(String name, String code, String sourceDir, String imageDir, String videoDir,
                              RunMode runMode, Provider provider, String flowProjectUrl, String aspectRatio,
                              Integer maxParallel, Integer maxAttempts, Boolean waitAllImages,
                              Integer videoDuration, String videoQuality, Boolean skipMissingRefs,
                              Boolean autoApproveImages, Boolean generateAudio,
                              String imageReviewDir, String videoReviewDir) {}

    /** missingRefs: Reference ID phim cần mà chưa có prompt tạo ảnh và chưa có ảnh trong kho (ảnh gốc cần "Thêm ảnh có sẵn"). */
    /**
     * kindCounts: số mục theo trạng thái, riêng cho "IMAGE" và "VIDEO" (hai màn Tạo ảnh / Tạo video).
     * reviewDirs: thư mục chờ duyệt thực dùng của từng phần.
     */
    public record ProjectView(ProjectEntity project, Map<String, Long> counts, long total, long imagesTotal,
                              long imagesDone, long readyAccounts, long assetsCount, List<String> missingRefs,
                              List<String> autoImport, Map<String, Map<String, Long>> kindCounts,
                              Map<String, String> reviewDirs) {
        /** autoImport: các dòng tóm tắt việc tự nạp lúc tạo phim (rỗng ở mọi chỗ khác). */
        ProjectView withAutoImport(List<String> lines) {
            return new ProjectView(project, counts, total, imagesTotal, imagesDone, readyAccounts, assetsCount, missingRefs, lines,
                    kindCounts, reviewDirs);
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list() {
        return projects.findAllByOrderByIdDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ProjectView get(long id) {
        return view(projects.findById(id).orElseThrow());
    }

    @Transactional
    public ProjectView create(ProjectForm f) {
        require(f.name(), "Tên phim");
        String code = f.code() == null || f.code().isBlank() ? slug(f.name()) : slug(f.code());
        if (projects.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Mã phim \"" + code + "\" đã tồn tại");
        }
        ProjectEntity p = new ProjectEntity();
        apply(p, f);   // kiểm trùng mã xong mới tạo thư mục
        p.setCode(code);
        return view(projects.save(p));
    }

    /**
     * Ngay sau khi tạo phim: tự nạp các mục của phim, người dùng khỏi bấm "Import prompt" / "Thêm ảnh có sẵn".
     *  - Thư mục kết quả Omni có file prompt (Excel / txt) thì nạp prompt + gán ảnh có sẵn (ở mọi thư mục con, kể cả Folder Ảnh).
     *  - Folder Ảnh nằm ngoài thư mục Omni thì gán thêm ảnh có sẵn trong đó.
     *  - Không có gì để nạp thì bỏ qua, không báo lỗi. Nạp lỗi cũng KHÔNG làm hỏng việc tạo phim: chỉ ghi chú lại.
     * Không đánh dấu @Transactional: phiên nạp tự mở transaction của nó, phim đã được lưu xong ở bước create.
     */
    public ProjectView autoImportAfterCreate(long id) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        List<String> notes = new ArrayList<>();
        String src = p.getSourceDir() == null ? "" : p.getSourceDir().trim();
        Path srcDir = null;
        try {
            if (!src.isEmpty() && Files.isDirectory(Path.of(src))) {
                srcDir = Path.of(src).toAbsolutePath().normalize();
                try {
                    notes.addAll(AutoImportSummary.lines(importer.importFolder(id, src)));
                } catch (OmniImportService.NoPromptFileException e) {
                    // thư mục chưa có file prompt nào: không phải lỗi, chỉ là chưa có gì để nạp
                } catch (IllegalArgumentException e) {
                    notes.add("Không nạp tự động được: " + e.getMessage());
                }
            }
            Path img = Path.of(p.getImageDir()).toAbsolutePath().normalize();
            if (!scannedWith(srcDir, img) && Files.isDirectory(img) && !prompts.findByProjectId(id).isEmpty()) {
                var a = assetService.importFolder(id, img.toString());
                int n = a.added() + a.replaced();
                if (n > 0) {
                    notes.add("✓ " + n + " ảnh có sẵn trong Folder Ảnh đã gán vào phim"
                            + (a.skippedJobs() > 0 ? " (" + a.skippedJobs() + " ảnh khỏi phải tạo)" : ""));
                }
                notes.addAll(AutoImportSummary.warnings(a));
            }
            int adopted = adoptExistingVideos(id);
            if (adopted > 0) {
                notes.add("✓ " + adopted + " video đã có sẵn trong Folder Video: đánh dấu xong, không tạo lại");
            }
        } catch (Exception e) {
            log.warn("Tự nạp phim {} lỗi: {}", p.getCode(), e.toString());
            notes.add("Không nạp tự động được (" + e.getMessage() + "): bấm \"Import prompt\" để thử lại.");
        }
        ProjectView v = view(projects.findById(id).orElseThrow());
        AutoImportSummary.missing(v.missingRefs()).ifPresent(notes::add);
        return v.withAutoImport(notes);
    }

    /** Folder Ảnh đã được quét chung khi nạp thư mục Omni (nằm trong đó và không thuộc thư mục phụ "_…" / ".…" bị bỏ qua). */
    static boolean scannedWith(Path srcDir, Path img) {
        return srcDir != null && img.startsWith(srcDir) && !AssetService.sideFolder(srcDir.relativize(img));
    }

    @Transactional
    public ProjectView update(long id, ProjectForm f) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        apply(p, f);
        return view(p);
    }

    @Transactional
    public ProjectView start(long id) {
        return start(id, null);
    }

    /** Bật phần Tạo ảnh / Tạo video (kind = null: cả hai). */
    @Transactional
    public ProjectView start(long id, PromptKind kind) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        List<JobEntity> all = jobs.findByProjectId(id);
        if (all.stream().noneMatch(j -> kind == null || j.getKind() == kind)) {
            throw new IllegalStateException(kind == PromptKind.VIDEO ? "Chưa có prompt video nào: hãy nạp Excel / dán prompt video trước"
                    : kind == PromptKind.IMAGE ? "Chưa có prompt ảnh nào: hãy nạp Excel / dán prompt ảnh trước"
                    : "Phim chưa có prompt nào: hãy nạp prompt trước");
        }
        for (PromptKind k : PromptKind.values()) {
            if (kind == null || kind == k) {
                p.setRunning(k, true, null);
            }
        }
        p.setPausedReason(null);
        p.setSelectedOnly(false);   // "Chạy" = chạy cả phần
        verifyFiles(id);   // file đã duyệt mà bị mất/hỏng thì cho chạy lại luôn trong lượt này
        return view(p);
    }

    public record VerifyResult(int checked, int missing, int broken, int requeued, List<String> details) {}

    /**
     * Bước 6 của quy trình: tìm ảnh/video ĐÃ DUYỆT mà file trong Folder Ảnh / Folder Video bị mất hoặc hỏng,
     * rồi đưa đúng những mục đó về hàng đợi để tạo lại. Video phụ thuộc vào một ảnh bị tạo lại sẽ tự chờ ảnh mới.
     */
    @Transactional
    public VerifyResult verifyFiles(long id) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        int checked = 0, missing = 0, broken = 0;
        List<String> details = new ArrayList<>();
        int adopted = adoptExistingVideos(id);
        if (adopted > 0) {
            details.add(adopted + " video đã có sẵn trong Folder Video: đánh dấu xong");
        }
        for (JobEntity j : jobs.findByProjectIdAndStatus(id, JobStatus.SUCCESS)) {
            checked++;
            Path file = j.getFinalPath() == null ? null
                    : relocate(Path.of(j.getFinalPath()), j.getKind() == PromptKind.IMAGE ? p.getImageDir() : p.getVideoDir());
            if (file != null && !file.toString().equals(j.getFinalPath())) {
                j.setFinalPath(file.toString());
                if (j.getKind() == PromptKind.IMAGE) {
                    approvedAsset(id, j).ifPresent(a -> a.setFilePath(file.toString()));
                }
            }
            Optional<String> problem = MediaCheck.problem(file);
            if (problem.isEmpty()) {
                continue;
            }
            boolean gone = file == null || !Files.exists(file);
            if (gone) missing++; else broken++;
            String name = file == null ? "job " + j.getId() : file.getFileName().toString();
            details.add(name + ": " + problem.get());
            j.setPreviewPath(null);
            j.setFinalPath(null);
            j.setAttempt(0);
            j.setAccountId(null);
            j.setErrorCode(null);
            j.setErrorMessage((gone ? "File đã duyệt bị mất" : "File đã duyệt bị hỏng") + ", tạo lại tự động");
            transitions.move(j, JobStatus.PENDING, null, "kiểm tra file: " + problem.get() + " → tạo lại");
            if (j.getKind() == PromptKind.IMAGE) {
                // rút ảnh khỏi kho: panel / video dùng ảnh này chờ ảnh mới
                approvedAsset(id, j).ifPresent(assets::delete);
            }
        }
        int requeued = missing + broken;
        // Ảnh có sẵn (người dùng đưa vào) bị mất/hỏng: không tạo lại được, rút khỏi kho và báo để người dùng thêm lại
        for (ProjectAssetEntity a : assets.findByProjectId(id)) {
            if (!ProjectAssetEntity.SOURCE_USER.equals(a.getSource())) {
                continue;
            }
            checked++;
            Path file = relocate(Path.of(a.getFilePath()), p.getImageDir());
            if (!file.toString().equals(a.getFilePath())) {
                a.setFilePath(file.toString());
            }
            Optional<String> problem = MediaCheck.problem(file);
            if (problem.isPresent()) {
                if (Files.exists(file)) broken++; else missing++;
                details.add(a.getExternalId() + " (ảnh có sẵn): " + problem.get() + " → cần thêm lại");
                assets.delete(a);
            }
        }
        if (missing + broken > 0) {
            log.warn("Phim {}: {} file mất, {} file hỏng", id, missing, broken);
        }
        return new VerifyResult(checked, missing, broken, requeued, details.subList(0, Math.min(details.size(), 20)));
    }

    static final List<String> VIDEO_EXT = List.of(".mp4", ".mov", ".webm");

    /**
     * Video đã có sẵn trong Folder Video với đúng tên app đặt khi duyệt (ví dụ EP06-S1-03.mp4): cảnh đó coi như đã xong,
     * không tạo lại. Trường hợp hay gặp: xoá phim rồi tạo lại (hoặc nạp lại prompt) mà video đã duyệt từ hôm trước vẫn còn
     * trong folder. Chỉ đụng tới cảnh chưa có kết quả (chờ chạy / lỗi / đã huỷ); file hỏng thì bỏ qua để app tạo lại.
     * Trả về số cảnh được đánh dấu xong.
     */
    @Transactional
    public int adoptExistingVideos(long id) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        if (p.getVideoDir() == null || p.getVideoDir().isBlank() || !Files.isDirectory(Path.of(p.getVideoDir()))) {
            return 0;
        }
        Path dir = Path.of(p.getVideoDir());
        List<PromptEntity> videos = prompts.findByProjectId(id).stream().filter(pr -> pr.getKind() == PromptKind.VIDEO).toList();
        Map<Long, PromptEntity> byId = new HashMap<>();
        videos.forEach(v -> byId.put(v.getId(), v));
        int adopted = 0;
        for (JobEntity j : jobs.findByProjectId(id)) {
            PromptEntity prompt = byId.get(j.getPromptId());
            if (prompt == null || !EnumSet.of(JobStatus.PENDING, JobStatus.FAILED, JobStatus.CANCELLED).contains(j.getStatus())) {
                continue;
            }
            Optional<Path> found = Optional.empty();
            for (String ext : VIDEO_EXT) {
                Path f = dir.resolve(ReviewService.finalFileName(p, prompt, ext, videos));
                if (Files.isRegularFile(f) && MediaCheck.problem(f).isEmpty()) {
                    found = Optional.of(f);
                    break;
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            String why = "đã có video " + found.get().getFileName() + " trong Folder Video";
            j.setRunPriority(null);
            j.setAccountId(null);
            j.setWorkerId(null);
            j.setErrorCode(null);
            j.setErrorMessage(null);
            j.setCancelRequested(false);
            transitions.move(j, JobStatus.AWAITING_REVIEW, null, why);
            transitions.move(j, JobStatus.APPROVED, null, why);
            transitions.move(j, JobStatus.DOWNLOADING, null, why);
            j.setFinalPath(found.get().toString());
            j.setCompletedAt(java.time.LocalDateTime.now());
            transitions.move(j, JobStatus.SUCCESS, null, why);
            jobs.save(j);   // gọi cả từ chỗ không có transaction (tự nạp lúc tạo phim): lưu tường minh
            adopted++;
        }
        if (adopted > 0) {
            log.info("Phim {}: {} video đã có sẵn trong Folder Video, đánh dấu xong", p.getCode(), adopted);
        }
        return adopted;
    }

    private Optional<ProjectAssetEntity> approvedAsset(long projectId, JobEntity j) {
        return prompts.findById(j.getPromptId()).flatMap(pr -> assets.findByProjectIdAndExternalId(projectId, pr.getExternalId()))
                .filter(a -> ProjectAssetEntity.SOURCE_APPROVED.equals(a.getSource()));
    }

    /**
     * File không còn ở đường dẫn cũ (thư mục phim đã dời đi chỗ khác): tìm file cùng tên trong thư mục hiện tại của phim,
     * kể cả thư mục con (như JobPayloadFactory.locate). Không thấy thì trả về đường dẫn cũ.
     */
    static Path relocate(Path file, String dir) {
        if (Files.isRegularFile(file) || dir == null || dir.isBlank() || !Files.isDirectory(Path.of(dir))) {
            return file;
        }
        String name = file.getFileName().toString();
        Path direct = Path.of(dir).resolve(name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(Path.of(dir), 3)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(file);
        } catch (java.io.IOException e) {
            return file;
        }
    }

    /** Quét định kỳ các phim đang chạy (người dùng có thể xóa/ghi đè file trong folder bất cứ lúc nào). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    @Transactional
    public void verifyRunningProjects() {
        for (ProjectEntity p : projects.findByStatusOrderByIdAsc(ProjectStatus.RUNNING)) {
            verifyFiles(p.getId());
        }
    }

    @Transactional
    public ProjectView pause(long id) {
        return pause(id, null);
    }

    /** Tắt phần Tạo ảnh / Tạo video (kind = null: cả hai). Mục đang tạo chạy nốt. */
    @Transactional
    public ProjectView pause(long id, PromptKind kind) {
        ProjectEntity p = projects.findById(id).orElseThrow();
        for (PromptKind k : PromptKind.values()) {
            if (kind == null || kind == k) {
                p.setRunning(k, false, "Bạn đã tạm dừng");
            }
        }
        return view(p);
    }

    /** Đưa mọi job FAILED / CANCELLED về hàng đợi. */
    @Transactional
    public int retryFailed(long id) {
        int n = 0;
        Set<String> provided = new HashSet<>();
        assets.findByProjectId(id).stream().filter(a -> ProjectAssetEntity.SOURCE_USER.equals(a.getSource()))
                .forEach(a -> provided.add(a.getExternalId()));
        for (JobStatus s : List.of(JobStatus.FAILED, JobStatus.CANCELLED)) {
            for (JobEntity j : jobs.findByProjectIdAndStatus(id, s)) {
                if (j.getKind() == PromptKind.IMAGE && prompts.findById(j.getPromptId())
                        .map(pr -> provided.contains(pr.getExternalId())).orElse(false)) {
                    continue;   // ảnh này đã có sẵn (người dùng đưa vào): không tạo lại
                }
                j.setAttempt(0);
                j.setErrorCode(null);
                j.setErrorMessage(null);
                j.setAccountId(null);
                transitions.move(j, JobStatus.PENDING, null, "thử lại hàng loạt");
                n++;
            }
        }
        return n;
    }

    @Transactional
    public void delete(long id) {
        if (jobs.countByProjectIdAndStatus(id, JobStatus.GENERATING) > 0) {
            throw new IllegalStateException("Phim đang có job chạy: tạm dừng và chờ các job đang chạy xong trước khi xóa");
        }
        events.deleteByProjectId(id);
        flowLinks.deleteByProjectId(id);
        assets.deleteByProjectId(id);   // chỉ xóa bản ghi kho; file ảnh/video trên đĩa giữ nguyên
        jobs.deleteByProjectId(id);
        references.deleteByProjectId(id);
        revisions.deleteByProjectId(id);
        prompts.deleteByProjectId(id);
        projects.deleteById(id);
    }

    private void apply(ProjectEntity p, ProjectForm f) {
        require(f.name(), "Tên phim");
        // Thư mục nguồn Omni là tùy chọn: có thể nạp từng file Excel bằng "Thêm Excel ảnh" / "Thêm Excel video"
        p.setSourceDir(f.sourceDir() == null ? "" : f.sourceDir().trim());
        // Folder Ảnh / Video để trống thì tự tìm trong thư mục nguồn (không phân biệt hoa thường, dấu), không có thì tạo mới ở đó
        var dirs = ProjectFolderScanner.resolveDirs(p.getSourceDir(), f.imageDir(), f.videoDir());
        p.setName(f.name().trim());
        p.setImageDir(dirs.imageDir());
        p.setVideoDir(dirs.videoDir());
        // thư mục chưa có thì tạo luôn, người dùng không phải tự tay tạo rồi gán
        ensureDir(p.getImageDir(), "Folder Ảnh");
        ensureDir(p.getVideoDir(), "Folder Video");
        if (f.runMode() != null) p.setRunMode(f.runMode());
        if (f.provider() != null) p.setProvider(f.provider());
        // Không còn link dự án Flow chung cho cả phim: mỗi tài khoản có dự án Flow riêng, link được nhớ theo (phim, tài khoản)
        // lúc tool vào / tạo dự án (flow_links). Xóa giá trị cũ để không bị dùng làm dự phòng cho tài khoản khác.
        p.setFlowProjectUrl(null);
        if (f.aspectRatio() != null && !f.aspectRatio().isBlank()) p.setAspectRatio(f.aspectRatio().trim());
        if (f.maxParallel() != null) p.setMaxParallel(Math.max(1, f.maxParallel()));
        if (f.maxAttempts() != null) p.setMaxAttempts(Math.max(1, f.maxAttempts()));
        if (f.waitAllImages() != null) p.setWaitAllImages(f.waitAllImages());
        if (f.autoApproveImages() != null) p.setAutoApproveImages(f.autoApproveImages());
        if (f.generateAudio() != null) p.setGenerateAudio(f.generateAudio());
        p.setImageReviewDir(blankToNull(f.imageReviewDir()));
        p.setVideoReviewDir(blankToNull(f.videoReviewDir()));
        p.setVideoDuration(f.videoDuration() == null || f.videoDuration() <= 0 ? null : f.videoDuration());
        if (f.videoQuality() != null) {
            String q = f.videoQuality().trim().toLowerCase(Locale.ROOT);
            if (!List.of("480p", "720p", "1080p").contains(q)) {
                throw new IllegalArgumentException("Chất lượng video phải là 480p, 720p hoặc 1080p");
            }
            p.setVideoQuality(q);
        }
    }

    private ProjectView view(ProjectEntity p) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            counts.put(s.name(), 0L);
        }
        long total = 0;
        for (Object[] row : jobs.countByStatus(p.getId())) {
            counts.put(((JobStatus) row[0]).name(), (Long) row[1]);
            total += (Long) row[1];
        }
        List<JobEntity> imageJobs = jobs.findByProjectId(p.getId()).stream()
                .filter(j -> j.getKind() == com.aifilm.automation.prompt.PromptKind.IMAGE).toList();
        // ảnh đã hủy (thường vì đã có ảnh có sẵn) không chặn giai đoạn video
        long imagesDone = imageJobs.stream().filter(j -> j.getStatus() == JobStatus.SUCCESS || j.getStatus() == JobStatus.CANCELLED).count();
        long ready = accounts.findAll().stream()
                .filter(a -> a.getProvider() == p.getProvider())
                .filter(a -> a.getStatus() == AccountStatus.READY || a.getStatus() == AccountStatus.LEASED
                        || (a.getStatus() == AccountStatus.COOLDOWN && a.getCooldownUntil() != null
                            && a.getCooldownUntil().isBefore(java.time.LocalDateTime.now())))
                .count();
        Map<String, Map<String, Long>> kindCounts = new LinkedHashMap<>();
        Map<String, String> reviewDirs = new LinkedHashMap<>();
        List<JobEntity> all = jobs.findByProjectId(p.getId());
        for (PromptKind k : PromptKind.values()) {
            Map<String, Long> c = new LinkedHashMap<>();
            for (JobStatus s : JobStatus.values()) {
                c.put(s.name(), 0L);
            }
            all.stream().filter(j -> j.getKind() == k).forEach(j -> c.merge(j.getStatus().name(), 1L, Long::sum));
            c.put("TOTAL", all.stream().filter(j -> j.getKind() == k).count());
            kindCounts.put(k.name(), c);
            try {
                reviewDirs.put(k.name(), p.reviewDir(k).toString());
            } catch (RuntimeException e) {
                reviewDirs.put(k.name(), "");
            }
        }
        return new ProjectView(p, counts, total, imageJobs.size(), imagesDone, ready,
                assets.findByProjectId(p.getId()).size(), importer.missingRefs(p.getId()), List.of(), kindCounts, reviewDirs);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void ensureDir(String dir, String label) {
        try {
            Path path = Path.of(dir);
            if (!Files.isDirectory(path)) {
                Files.createDirectories(path);
                log.info("Đã tạo {}: {}", label, path);
            }
        } catch (java.io.IOException | java.nio.file.InvalidPathException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("Không tạo được " + label + " \"" + dir + "\": " + e.getMessage());
        }
    }

    private static void require(String v, String label) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(label + " không được để trống");
        }
    }

    /** "Tập 5. Hà - Cupid" → "tap-5-ha-cupid". */
    static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Tên phim phải có ít nhất một chữ hoặc số");
        }
        return n.length() > 80 ? n.substring(0, 80) : n;
    }
}
