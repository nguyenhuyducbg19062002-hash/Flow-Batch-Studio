package com.aifilm.automation.job;

import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import com.aifilm.automation.prompt.PromptEntity;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.prompt.PromptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quy trình hàng loạt của một phần (Tạo ảnh hoặc Tạo video), hai phần giống hệt nhau:
 *  1. Chạy: tạo hết, mỗi kết quả tải về thư mục chờ duyệt với tên là số thứ tự (001.png…).
 *  2. Người duyệt xem thư mục, báo các số sai ("5, 12, 30-33"): app tạo lại đúng các số đó (có thể kèm ghi chú / prompt mới).
 *  3. Ổn hết: "Đổi tên" chuyển mọi file sang Folder Ảnh / Folder Video với tên đúng (CHAR-CLARA-1.png, EP06-S1-01.mp4).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchService {

    private final JobRepository jobs;
    private final PromptRepository prompts;
    private final ProjectRepository projects;
    private final ReviewService review;

    public record RedoResult(int queued, List<Integer> notFound, List<String> skipped) {}

    public record FinalizeResult(int renamed, List<String> errors, long notReady, String targetDir) {}

    /**
     * "Làm lại các số": đưa các số người duyệt báo sai về hàng đợi, lên đầu hàng, và bật phần này chạy.
     * note: ghi chú cần sửa gì (thêm vào cuối prompt). Phần đang tắt thì chỉ chạy các số này rồi tự dừng.
     */
    @Transactional
    public RedoResult redo(long projectId, PromptKind kind, String spec, String note) {
        TreeSet<Integer> numbers = parseNumbers(spec);
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        Map<Integer, PromptEntity> byNo = byNumber(projectId, kind);
        Map<Long, JobEntity> jobByPrompt = jobs.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(JobEntity::getPromptId, Function.identity(), (a, b) -> a));
        List<Integer> notFound = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<JobEntity> picked = new ArrayList<>();
        for (int no : numbers) {
            PromptEntity prompt = byNo.get(no);
            JobEntity job = prompt == null ? null : jobByPrompt.get(prompt.getId());
            if (job == null) {
                notFound.add(no);
                continue;
            }
            if (job.getStatus() == JobStatus.GENERATING) {
                skipped.add(no + " (đang tạo)");
                continue;
            }
            String text = null;
            if (note != null && !note.isBlank()) {
                text = review.currentPrompt(job) + "\n\nREVISION NOTE FROM THE DIRECTOR (apply this change, keep everything else exactly as described above): "
                        + note.trim();
            }
            review.regenerate(job.getId(), text, note == null || note.isBlank() ? "làm lại số " + no : note.trim());
            picked.add(job);
        }
        if (!picked.isEmpty()) {
            boolean wasRunning = project.isRunning(kind);
            int top = jobs.maxRunPriority(projectId) + picked.size();
            for (int i = 0; i < picked.size(); i++) {
                picked.get(i).setRunPriority(top - i);   // số nhỏ chạy trước
            }
            if (!wasRunning && project.getStatus() != ProjectStatus.RUNNING) {
                project.setSelectedOnly(true);   // phần đang tắt: chỉ chạy các số này
            }
            project.setRunning(kind, true, null);
        }
        log.info("Phim {}: làm lại {} {} ({}), không có số {}", project.getCode(), picked.size(),
                kind == PromptKind.IMAGE ? "ảnh" : "video", spec, notFound);
        return new RedoResult(picked.size(), notFound, skipped);
    }

    /**
     * "Đổi tên": chuyển mọi file trong thư mục chờ duyệt của phần này sang Folder Ảnh / Folder Video với tên đúng.
     * Mỗi mục một transaction (một file lỗi không chặn các file khác). Mục chưa tạo xong được giữ nguyên.
     */
    public FinalizeResult finalizeAll(long projectId, PromptKind kind) {
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        List<JobEntity> ready = jobs.findByProjectIdAndStatus(projectId, JobStatus.AWAITING_REVIEW).stream()
                .filter(j -> j.getKind() == kind)
                .sorted(Comparator.comparing(JobEntity::getReviewNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int renamed = 0;
        List<String> errors = new ArrayList<>();
        for (JobEntity j : ready) {
            try {
                review.approve(j.getId());
                renamed++;
            } catch (RuntimeException e) {
                errors.add((j.getReviewNo() == null ? "#" + j.getId() : String.valueOf(j.getReviewNo())) + ": " + e.getMessage());
            }
        }
        long notReady = jobs.countByProjectIdAndKindAndStatusIn(projectId, kind,
                List.of(JobStatus.PENDING, JobStatus.GENERATING, JobStatus.FAILED));
        String target = kind == PromptKind.IMAGE ? project.getImageDir() : project.getVideoDir();
        log.info("Phim {}: đổi tên {} {} → {}, lỗi {}", project.getCode(), renamed, kind, target, errors.size());
        return new FinalizeResult(renamed, errors, notReady, target);
    }

    /** Thư mục chờ duyệt của phần này (tạo nếu chưa có), để mở bằng Explorer. */
    public Path reviewDir(long projectId, PromptKind kind) {
        Path dir = projects.findById(projectId).orElseThrow().reviewDir(kind);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Không tạo được thư mục " + dir + ": " + e.getMessage(), e);
        }
        return dir;
    }

    private Map<Integer, PromptEntity> byNumber(long projectId, PromptKind kind) {
        List<PromptEntity> ordered = BatchNumbering.ordered(prompts.findByProjectId(projectId), kind);
        Map<Integer, PromptEntity> out = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            out.put(i + 1, ordered.get(i));
        }
        return out;
    }

    /** "5, 12, 030-033 ; 40" → {5, 12, 30, 31, 32, 33, 40}. */
    static TreeSet<Integer> parseNumbers(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Chưa nhập số nào (ví dụ: 5, 12, 30-33)");
        }
        TreeSet<Integer> out = new TreeSet<>();
        for (String part : spec.trim().replaceAll("\\s*[-–]\\s*", "-").split("[,;\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            String clean = part.replaceAll("\\.(png|jpe?g|webp|mp4|mov|webm)$", "");   // dán cả tên file "005.png" cũng được
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,6})(?:\\s*[-–]\\s*(\\d{1,6}))?$").matcher(clean);
            if (!m.matches()) {
                throw new IllegalArgumentException("Không hiểu \"" + part + "\": nhập số hoặc khoảng số, ví dụ 5, 12, 30-33");
            }
            int a = Integer.parseInt(m.group(1));
            int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
            if (b < a) {
                int t = a;
                a = b;
                b = t;
            }
            if (b - a > 5000) {
                throw new IllegalArgumentException("Khoảng " + part + " quá lớn");
            }
            for (int i = a; i <= b; i++) {
                out.add(i);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Chưa nhập số nào (ví dụ: 5, 12, 30-33)");
        }
        return out;
    }
}
