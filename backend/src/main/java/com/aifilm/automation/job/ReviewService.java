package com.aifilm.automation.job;

import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final JobRepository jobs;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final ProjectRepository projects;
    private final ProjectAssetRepository assets;
    private final JobTransitions transitions;
    private final com.aifilm.automation.account.AccountRepository accounts;

    /**
     * Duyệt: chép file staging vào Folder Ảnh / Folder Video, tên file = external_id.
     * Lỗi ghi file → transaction rollback, job vẫn AWAITING_REVIEW, người dùng thấy thông báo lỗi.
     */
    @Transactional
    public JobEntity approve(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.AWAITING_REVIEW) {
            throw new IllegalStateException("Job " + jobId + " đang ở " + job.getStatus() + ", không ở trạng thái chờ duyệt");
        }
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        ProjectEntity project = projects.findById(job.getProjectId()).orElseThrow();

        transitions.move(job, JobStatus.APPROVED, job.getAccountId(), "người dùng duyệt");
        job.setReviewedAt(LocalDateTime.now());
        transitions.move(job, JobStatus.DOWNLOADING, job.getAccountId(), "chép vào folder đích");

        // Lấy đúng file người duyệt đã xem trong thư mục chờ duyệt (001.png…); chưa có thì lấy bản trong staging
        Path review = job.getReviewPath() == null ? null : Path.of(job.getReviewPath());
        Path staged = review != null && Files.isRegularFile(review) ? review
                : StagingGuard.requireInside(Path.of(job.getPreviewPath()));
        Path dir = Path.of(prompt.getKind() == PromptKind.IMAGE ? project.getImageDir() : project.getVideoDir());
        Collection<PromptEntity> videos = prompt.getKind() == PromptKind.VIDEO
                ? prompts.findByProjectId(project.getId()).stream().filter(p -> p.getKind() == PromptKind.VIDEO).toList()
                : List.of();
        Path target = dir.resolve(finalFileName(project, prompt, extensionOf(staged.getFileName().toString()), videos));
        Path part = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(dir);
            Files.copy(staged, part);
            if (Files.exists(target) && !target.toString().equals(job.getFinalPath())) {
                // file cùng tên không do chính cảnh này lưu (ví dụ ảnh người dùng): giữ lại dưới tên .bak, không ghi đè
                Path bak = target.resolveSibling(target.getFileName() + "."
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".bak");
                Files.move(target, bak);
                log.info("Job {}: file cũ {} được giữ lại thành {}", jobId, target.getFileName(), bak.getFileName());
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // file tạm còn sót không ảnh hưởng kết quả
            }
            throw new UncheckedIOException("Không ghi được " + target + ": " + e.getMessage(), e);
        }

        if (staged.equals(review)) {
            try {
                Files.deleteIfExists(review);   // đã chuyển sang thư mục đích với tên đúng
            } catch (IOException e) {
                log.warn("Job {}: không xóa được {} ({})", jobId, review, e.getMessage());
            }
        }
        job.setReviewPath(null);
        job.setFinalPath(target.toString());
        job.setCompletedAt(LocalDateTime.now());
        if (prompt.getKind() == PromptKind.IMAGE) {
            // Ảnh đã duyệt vào kho: các cảnh (panel / video) cần ảnh này được phép chạy
            ProjectAssetEntity a = assets.findByProjectIdAndExternalId(project.getId(), prompt.getExternalId())
                    .orElseGet(() -> new ProjectAssetEntity(project.getId(), prompt.getExternalId(), target.toString(), ProjectAssetEntity.SOURCE_APPROVED));
            a.setFilePath(target.toString());
            a.setSource(ProjectAssetEntity.SOURCE_APPROVED);
            assets.save(a);
        }
        transitions.move(job, JobStatus.SUCCESS, job.getAccountId(), "đã lưu " + target.getFileName());
        log.info("Job {} đã duyệt → {}", jobId, target);
        return job;
    }

    /**
     * Sửa prompt (nếu có chữ mới) rồi tạo lại. Một prompt mới = một revision mới; lịch sử không bị ghi đè.
     * Dùng cho cả "Thử lại" một job FAILED/CANCELLED (không gửi chữ mới).
     */
    /**
     * Nút "Tạo lại" của người dùng: tạo lại và CHẠY LUÔN cảnh đó (lên đầu hàng). Phim đang dừng thì chỉ chạy cảnh này.
     * Có ghi chú mà không sửa prompt: ghi chú được thêm vào cuối prompt (bản prompt mới) để Flow biết cần đổi gì.
     */
    @Transactional
    public JobEntity regenerateAndRun(long jobId, String newPromptText, String note) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        String text = newPromptText;
        if ((text == null || text.isBlank()) && note != null && !note.isBlank()) {
            PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
            String current = revisions.findById(prompt.getCurrentRevisionId()).map(PromptRevisionEntity::getPromptText).orElse("");
            text = current + "\n\nREVISION NOTE FROM THE DIRECTOR (apply this change, keep everything else exactly as described above): "
                    + note.trim();
        }
        regenerate(jobId, text, note);
        job.setRunPriority(jobs.maxRunPriority(job.getProjectId()) + 1);
        projects.findById(job.getProjectId()).ifPresent(p -> {
            if (p.getStatus() != com.aifilm.automation.project.ProjectStatus.RUNNING) {
                p.setSelectedOnly(true);   // phim đang dừng: chỉ chạy cảnh vừa bấm Tạo lại
            }
            p.setRunning(job.getKind(), true, null);
            p.setPausedReason(null);
        });
        log.info("Job {}: tạo lại và chạy ngay{}", jobId, note == null || note.isBlank() ? "" : " (" + note.trim() + ")");
        return job;
    }

    @Transactional
    public JobEntity regenerate(long jobId, String newPromptText, String note) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() == JobStatus.GENERATING) {
            throw new IllegalStateException("Job " + jobId + " đang chạy; hãy hủy trước rồi tạo lại");
        }
        if (job.getKind() == PromptKind.IMAGE && job.getStatus() == JobStatus.SUCCESS) {
            // Làm lại một ảnh đã duyệt: rút ảnh cũ khỏi kho để các cảnh dùng ảnh này chờ ảnh mới
            prompts.findById(job.getPromptId()).flatMap(p -> assets.findByProjectIdAndExternalId(p.getProjectId(), p.getExternalId()))
                    .filter(a -> ProjectAssetEntity.SOURCE_APPROVED.equals(a.getSource()))
                    .ifPresent(assets::delete);
        }
        if (job.getKind() == PromptKind.IMAGE) {
            // ảnh trong thư mục chờ duyệt bị báo sai: các ảnh đổi đồ chưa chạy phải chờ bản mới
            prompts.findById(job.getPromptId()).flatMap(p -> assets.findByProjectIdAndExternalId(p.getProjectId(), p.getExternalId()))
                    .filter(a -> ProjectAssetEntity.SOURCE_REVIEW.equals(a.getSource()))
                    .ifPresent(assets::delete);
        }
        moveAsideReviewFile(job);
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        String current = revisions.findById(prompt.getCurrentRevisionId()).map(PromptRevisionEntity::getPromptText).orElse("");

        if (newPromptText != null && !newPromptText.isBlank() && !newPromptText.equals(current)) {
            int nextNo = revisions.maxRevisionNo(prompt.getId()) + 1;
            PromptRevisionEntity rev = revisions.save(new PromptRevisionEntity(
                    prompt.getId(), nextNo, newPromptText, PromptRevisionEntity.SOURCE_USER, note));
            prompt.setCurrentRevisionId(rev.getId());
        }
        job.setRunPriority(null);
        job.setEditInstruction(null);
        job.setPreviewPath(null);   // file staging cũ được giữ lại tới khi dọn theo staging_retention_days
        job.setRemoteRef(null);
        job.setAttempt(0);
        job.setAccountId(null);
        job.setReviewNote(note);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setCancelRequested(false);
        if (job.getStatus() != JobStatus.PENDING) {   // đang chờ chạy: chỉ cập nhật prompt, không cần chuyển trạng thái
            transitions.move(job, JobStatus.PENDING, null, note == null || note.isBlank() ? "tạo lại" : "tạo lại: " + note);
        }
        return job;
    }

    /**
     * "Chạy cảnh này": đưa cảnh lên đầu hàng đợi và cho phim chạy. Cảnh đã xong / lỗi / hủy thì tạo lại.
     * Cảnh vẫn phải đủ ảnh reference mới chạy được (ví dụ video cần ảnh PANEL đã duyệt).
     */
    @Transactional
    public JobEntity runNow(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() == JobStatus.GENERATING) {
            throw new IllegalStateException("Cảnh này đang được tạo");
        }
        if (job.getStatus() == JobStatus.AWAITING_REVIEW) {
            throw new IllegalStateException("Cảnh này đang chờ duyệt: duyệt hoặc bấm Tạo lại");
        }
        if (job.getStatus() != JobStatus.PENDING) {
            regenerate(jobId, null, "chạy lại theo yêu cầu");
        }
        job.setRunPriority(jobs.maxRunPriority(job.getProjectId()) + 1);
        projects.findById(job.getProjectId()).ifPresent(p -> {
            p.setSelectedOnly(false);
            p.setRunning(job.getKind(), true, null);
            p.setPausedReason(null);
        });
        log.info("Job {}: chạy ngay theo yêu cầu (ưu tiên {})", jobId, job.getRunPriority());
        return job;
    }

    /**
     * "Đưa file đã tải về vào app": người dùng tự tải ảnh / video từ Flow (hoặc có sẵn), chọn file đó cho cảnh này.
     * File được kiểm tra (đúng loại, không hỏng), rồi DUYỆT LUÔN: lưu vào Folder Ảnh / Folder Video với tên đúng
     * (ảnh: ID; video: mã phim_STT). Cảnh đang được worker tạo thì dừng lượt đó (worker nhận 409 và đóng trình duyệt).
     */
    @Transactional
    public JobEntity manualResult(long jobId, org.springframework.web.multipart.MultipartFile file) {
        return manualResult(jobId, file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), file::transferTo);
    }

    /** Như trên nhưng lấy file có sẵn trên máy (chế độ "Tự làm trên Flow": file vừa tải về thư mục Downloads). File gốc giữ nguyên. */
    @Transactional
    public JobEntity manualResult(long jobId, Path source) {
        return manualResult(jobId, source.getFileName().toString(), dest -> Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING));
    }

    interface FileWriter {
        void writeTo(Path dest) throws IOException;
    }

    private JobEntity manualResult(long jobId, String original, FileWriter file) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        String ext = extensionOf(original);
        boolean video = prompt.getKind() == PromptKind.VIDEO;
        if (video ? !List.of(".mp4", ".mov", ".webm").contains(ext) : !List.of(".png", ".jpg", ".jpeg", ".webp").contains(ext)) {
            throw new IllegalArgumentException(video ? "Cảnh video cần file .mp4 / .mov / .webm" : "Cảnh ảnh cần file .png / .jpg / .webp");
        }
        Path staged = com.aifilm.automation.system.AppPaths.staging().resolve("job-" + jobId).resolve("manual")
                .resolve(prompt.getExternalId() + ext);
        // ghi ra tên tạm, kiểm tra xong mới đổi tên: file hỏng không đè lên bản xem trước cũ
        Path upload = staged.resolveSibling(staged.getFileName() + "." + UUID.randomUUID() + ".upload");
        try {
            Files.createDirectories(staged.getParent());
            file.writeTo(upload);
            var problem = MediaCheck.problem(upload).or(() -> MediaCheck.wrongKind(upload, video));
            if (problem.isPresent()) {
                throw new IllegalArgumentException("File không dùng được: " + problem.get());
            }
            Files.move(upload, staged, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Không lưu được file tải lên: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(upload);
            } catch (IOException ignored) {
                // file tạm còn sót được dọn cùng staging
            }
        }
        if (job.getStatus() == JobStatus.SUCCESS || job.getStatus() == JobStatus.APPROVED || job.getStatus() == JobStatus.DOWNLOADING) {
            regenerate(jobId, null, "thay bằng file người dùng đưa vào");
        }
        if (job.getAccountId() != null && job.getStatus() == JobStatus.GENERATING) {
            accounts.findById(job.getAccountId()).ifPresent(com.aifilm.automation.account.AccountEntity::releaseOk);
        }
        job.setWorkerId(null);
        job.setRunPriority(null);
        job.setPreviewPath(staged.toString());
        job.setGeneratedAt(LocalDateTime.now());
        job.setErrorCode(null);
        job.setErrorMessage(null);
        if (job.getStatus() != JobStatus.AWAITING_REVIEW) {
            transitions.move(job, JobStatus.AWAITING_REVIEW, job.getAccountId(), "người dùng đưa file " + original + " vào");
        }
        return deliverToReview(job);
    }

    /**
     * "Sửa trên Flow": không tạo lại từ đầu mà mở đúng video cũ trên Flow (màn chỉnh sửa, đúng tài khoản đã tạo),
     * gửi yêu cầu sửa ("cho Caleb cười", "bỏ người đi đường"…), rồi tải bản mới về chờ duyệt. Chạy riêng cảnh này.
     */
    @Transactional
    public JobEntity flowEdit(long jobId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Ghi rõ cần Flow sửa gì trong video");
        }
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getKind() != PromptKind.VIDEO) {
            throw new IllegalArgumentException("\"Sửa trên Flow\" hiện chỉ dùng cho video");
        }
        if (job.getFlowMediaUrl() == null || job.getFlowAccountId() == null) {
            throw new IllegalStateException("Video này không có địa chỉ trên Flow (tải tay hoặc tạo bằng bản cũ): dùng \"Tạo lại\"");
        }
        if (job.getStatus() == JobStatus.GENERATING) {
            throw new IllegalStateException("Cảnh này đang được tạo");
        }
        String url = job.getFlowMediaUrl();
        Long acc = job.getFlowAccountId();
        if (job.getStatus() != JobStatus.PENDING) {
            regenerate(jobId, null, "sửa trên Flow: " + instruction.trim());
        }
        job.setFlowMediaUrl(url);
        job.setFlowAccountId(acc);
        job.setEditInstruction(instruction.trim());
        job.setRunPriority(jobs.maxRunPriority(job.getProjectId()) + 1);
        projects.findById(job.getProjectId()).ifPresent(p -> {
            if (p.getStatus() != com.aifilm.automation.project.ProjectStatus.RUNNING) {
                p.setSelectedOnly(true);   // phim đang dừng: chỉ chạy lượt sửa này
            }
            p.setRunning(job.getKind(), true, null);
            p.setPausedReason(null);
        });
        log.info("Job {}: sửa trên Flow ({})", jobId, instruction.trim());
        return job;
    }

    public record RunSelectedResult(int queued, List<String> skipped) {}

    /**
     * Chạy các cảnh người dùng chọn (ở "Phim & hàng đợi" hoặc "Danh sách cảnh"), theo đúng thứ tự đã chọn.
     * only = true: phim CHỈ chạy các cảnh này (cảnh khác giữ "Chờ chạy"), chạy xong thì tự tạm dừng.
     * Cảnh đang tạo / chờ duyệt / đã xong / ảnh có sẵn: bỏ qua (báo lại). Cảnh lỗi / đã hủy: tạo lại.
     */
    @Transactional
    public RunSelectedResult runSelected(long projectId, List<Long> jobIds, boolean only, boolean append) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn cảnh nào");
        }
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        List<String> skipped = new ArrayList<>();
        List<JobEntity> picked = new ArrayList<>();
        for (Long id : jobIds) {
            JobEntity job = jobs.findById(id).orElse(null);
            if (job == null || !job.getProjectId().equals(projectId)) {
                continue;
            }
            String name = prompts.findById(job.getPromptId()).map(PromptEntity::getExternalId).orElse("#" + id);
            boolean provided = job.getKind() == PromptKind.IMAGE && job.getStatus() == JobStatus.CANCELLED
                    && assets.findByProjectIdAndExternalId(projectId, name).isPresent();
            switch (job.getStatus()) {
                case GENERATING -> skipped.add(name + " (đang tạo)");
                case AWAITING_REVIEW -> skipped.add(name + " (đang chờ duyệt)");
                case SUCCESS, APPROVED, DOWNLOADING -> skipped.add(name + " (đã xong: dùng \"Tạo lại\" nếu muốn làm lại)");
                default -> {
                    if (provided) {
                        skipped.add(name + " (ảnh có sẵn)");
                    } else {
                        if (job.getStatus() != JobStatus.PENDING) {
                            regenerate(job.getId(), null, "chạy lại theo lựa chọn");
                        }
                        picked.add(job);
                    }
                }
            }
        }
        if (only && !append && !picked.isEmpty()) {
            // chỉ chạy nhóm mới chọn (nút ở từng cảnh: append = cộng thêm vào nhóm đang chạy): bỏ dấu "đã chọn" của các lần chọn trước
            jobs.findByProjectId(projectId).stream().filter(j -> j.getRunPriority() != null).forEach(j -> j.setRunPriority(null));
        }
        int top = jobs.maxRunPriority(projectId) + picked.size();
        for (int i = 0; i < picked.size(); i++) {
            picked.get(i).setRunPriority(top - i);   // cảnh chọn trước chạy trước
        }
        if (!picked.isEmpty()) {
            project.setSelectedOnly(only);
            picked.forEach(j -> project.setRunning(j.getKind(), true, null));
            project.setPausedReason(null);
        }
        log.info("Phim {}: chạy {} cảnh đã chọn{}, bỏ qua {}", project.getCode(), picked.size(), only ? " (chỉ các cảnh này)" : "", skipped.size());
        return new RunSelectedResult(picked.size(), skipped);
    }

    @Transactional
    public JobEntity cancel(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() == JobStatus.SUCCESS || job.getStatus() == JobStatus.CANCELLED) {
            return job;   // đã xong / đã hủy: không còn gì để hủy
        }
        if (job.getStatus() == JobStatus.GENERATING) {
            job.setCancelRequested(true);   // worker dừng ở heartbeat kế tiếp và báo CANCELLED
        } else {
            job.setRunPriority(null);
            transitions.move(job, JobStatus.CANCELLED, null, "người dùng hủy");
        }
        return job;
    }

    /**
     * Kết quả vừa tạo xong (AWAITING_REVIEW): chép vào thư mục chờ duyệt với tên là số thứ tự (5.png, 12.mp4) để người duyệt
     * mở thư mục xem. Ảnh ở đây CHƯA được dùng để tag @: chỉ sau khi duyệt và "Đổi tên" sang Folder Ảnh mới dùng được.
     */
    @Transactional
    public JobEntity deliverToReview(long jobId) {
        return deliverToReview(jobs.findById(jobId).orElseThrow());
    }

    JobEntity deliverToReview(JobEntity job) {
        if (job.getStatus() != JobStatus.AWAITING_REVIEW || job.getPreviewPath() == null) {
            return job;
        }
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        ProjectEntity project = projects.findById(job.getProjectId()).orElseThrow();
        java.util.Map<Long, Integer> numbers = BatchNumbering.numbers(prompts.findByProjectId(project.getId()), prompt.getKind());
        int no = numbers.getOrDefault(prompt.getId(), prompt.getSeqNo());
        Path staged = StagingGuard.requireInside(Path.of(job.getPreviewPath()));
        Path dir = project.reviewDir(prompt.getKind());
        Path target = dir.resolve(BatchNumbering.baseName(no, numbers.size()) + extensionOf(staged.getFileName().toString()));
        Path part = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(dir.resolve(ERROR_DIR));   // thư mục "lỗi" có sẵn để người duyệt kéo file sai vào
            Files.copy(staged, part);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (job.getReviewPath() != null && !Path.of(job.getReviewPath()).equals(target)) {
                Files.deleteIfExists(Path.of(job.getReviewPath()));   // bản cũ khác đuôi (.jpg → .png)
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // file tạm còn sót không ảnh hưởng kết quả
            }
            throw new UncheckedIOException("Không chép được kết quả vào " + target + ": " + e.getMessage(), e);
        }
        job.setReviewPath(target.toString());
        job.setReviewNo(no);
        log.info("Job {}: kết quả → {}", job.getId(), target);
        return job;
    }

    /** Nội dung prompt hiện tại của một mục. */
    String currentPrompt(JobEntity job) {
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        return revisions.findById(prompt.getCurrentRevisionId()).map(PromptRevisionEntity::getPromptText).orElse("");
    }

    /** Thư mục con "lỗi" trong thư mục chờ duyệt: người duyệt kéo file sai vào đây, app tự tạo lại số đó. */
    public static final String ERROR_DIR = "lỗi";
    /** Trong "lỗi": file đã được app nhận để tạo lại (không xử lý lại lần nữa). */
    public static final String ERROR_DONE_DIR = "đã xử lý";

    /** File trong thư mục chờ duyệt của một mục sắp tạo lại: cất vào "lỗi/đã xử lý" để người duyệt không nhầm với bản mới. */
    private void moveAsideReviewFile(JobEntity job) {
        if (job.getReviewPath() == null) {
            return;
        }
        Path f = Path.of(job.getReviewPath());
        try {
            if (Files.isRegularFile(f)) {
                Path aside = f.resolveSibling(ERROR_DIR).resolve(ERROR_DONE_DIR);
                Files.createDirectories(aside);
                String name = f.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Files.move(f, aside.resolve((dot > 0 ? name.substring(0, dot) : name) + "_" + stamp + (dot > 0 ? name.substring(dot) : "")),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Job {}: không cất được {} ({})", job.getId(), f, e.getMessage());
        }
        job.setReviewPath(null);
    }

    /**
     * Tên file cuối cùng:
     *  - ảnh:  đúng Reference ID của file Excel, ví dụ CHAR-CLARA-1.png (Omni và worker tìm ảnh theo đúng tên này);
     *  - video: tên cảnh + số thứ tự trong cảnh theo đúng thứ tự file all-video-prompts, ví dụ EP06-S1-01.mp4 … EP06-S1-24.mp4
     *    (xem VideoNaming). Không đọc được tên cảnh thì dùng cách cũ: mã phim + STT, ví dụ tap-5-ha-cupid_042.mp4.
     * @param videos mọi prompt VIDEO của phim (chỉ dùng cho video).
     */
    public static String finalFileName(ProjectEntity project, PromptEntity prompt, String ext, Collection<PromptEntity> videos) {
        if (prompt.getKind() == PromptKind.IMAGE) {
            return prompt.getExternalId() + ext;
        }
        return VideoNaming.nameFor(prompt, videos).orElseGet(() -> project.getCode() + "_" + prompt.getExternalId()) + ext;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
}
