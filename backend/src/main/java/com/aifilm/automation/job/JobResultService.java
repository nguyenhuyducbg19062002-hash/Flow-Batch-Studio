package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.WorkerReports.FailureReport;
import com.aifilm.automation.job.WorkerReports.GeneratedReport;
import com.aifilm.automation.project.FlowLinkEntity;
import com.aifilm.automation.project.FlowLinkRepository;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.service.AppSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobResultService {

    private final JobRepository jobs;
    private final AccountRepository accounts;
    private final ProjectRepository projects;
    private final JobTransitions transitions;
    private final AppSettingsService settings;
    private final FlowLinkRepository flowLinks;
    private final ReviewService review;
    private final TransactionTemplate tx;

    @Transactional
    public void onGenerated(long jobId, GeneratedReport r) {
        JobEntity job = owned(jobId, r.workerId());
        if (job.isCancelRequested()) {
            boolean others = otherJobsRunning(job);
            accounts.findById(job.getAccountId()).ifPresent(a -> a.releaseOk(others));
            cancelled(job, job.getAccountId());
            return;
        }
        Path staged = StagingGuard.requireInside(Path.of(r.stagedFile()));
        // File tải về rỗng / tải dở / không phải ảnh-video: không đưa người duyệt, tự tạo lại như một lỗi tạm thời
        var broken = MediaCheck.problem(staged)
                .or(() -> MediaCheck.wrongKind(staged, job.getKind() == com.aifilm.automation.prompt.PromptKind.VIDEO));
        if (broken.isPresent()) {
            log.warn("Job {}: file tải về bị hỏng ({}), tạo lại", jobId, broken.get());
            onFailed(jobId, new FailureReport(r.workerId(), ErrorCode.TRANSIENT.name(), "File tải về bị hỏng: " + broken.get()));
            return;
        }
        job.setPreviewPath(staged.toString());
        job.setRemoteRef(r.remoteRef());
        if (r.mediaUrl() != null && r.mediaUrl().contains("/edit/")) {
            job.setFlowMediaUrl(r.mediaUrl());
            job.setFlowAccountId(job.getAccountId());
        }
        job.setEditInstruction(null);
        rememberFlowProject(job, r.remoteRef());
        job.setGeneratedAt(LocalDateTime.now());
        job.setRunPriority(null);   // cảnh đã chọn: xong lượt chạy
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setWorkerId(null);
        transitions.move(job, JobStatus.AWAITING_REVIEW, job.getAccountId(), "có kết quả, chờ duyệt");
        // chạy song song: tài khoản còn job khác đang tạo thì vẫn giữ LEASED, chỉ job cuối xong mới về READY
        boolean busy = otherJobsRunning(job);
        accounts.findById(job.getAccountId()).ifPresent(a -> a.releaseOk(busy));
    }

    /**
     * Kết quả vừa tạo xong được chép vào thư mục chờ duyệt, tên là số thứ tự (005.png, 012.mp4). Người duyệt xem trong
     * thư mục, báo số sai để tạo lại; xong hết thì bấm "Đổi tên" để chuyển sang Folder Ảnh / Folder Video với tên đúng.
     * Chạy SAU khi onGenerated đã commit, trong transaction riêng: lỗi ghi file chỉ để mục ở trạng thái chờ duyệt, không mất
     * kết quả (lần mở app sau sẽ chép lại).
     */
    public void autoApprove(long jobId) {
        try {
            JobEntity job = jobs.findById(jobId).orElse(null);
            if (job != null && job.getStatus() == JobStatus.AWAITING_REVIEW) {
                review.deliverToReview(jobId);
            }
        } catch (Exception e) {
            log.warn("Job {}: không chép được kết quả vào thư mục chờ duyệt ({})", jobId, e.getMessage());
        }
    }

    /** Kết quả đã tạo nhưng chưa chép được vào thư mục chờ duyệt (lỗi ghi file lần trước): chép lại khi mở app. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void saveLeftoverResults() {
        List<JobEntity> left = jobs.findByStatus(JobStatus.AWAITING_REVIEW).stream()
                .filter(j -> j.getReviewPath() == null).toList();
        left.forEach(j -> autoApprove(j.getId()));
        if (!left.isEmpty()) {
            log.info("Mở app: chép {} kết quả vào thư mục chờ duyệt", left.size());
        }
    }

    /** Tài khoản của job này còn job KHÁC đang tạo không (dữ liệu thật trong DB, không dùng bộ đếm để khỏi bị lệch). */
    private boolean otherJobsRunning(JobEntity job) {
        return job.getAccountId() != null
                && jobs.countByAccountIdAndStatusAndIdNot(job.getAccountId(), JobStatus.GENERATING, job.getId()) > 0;
    }

    @Transactional
    public void onFailed(long jobId, FailureReport r) {
        JobEntity job = owned(jobId, r.workerId());
        ErrorCode code = ErrorCode.parse(r.errorCode());
        job.setErrorCode(code.name());
        job.setErrorMessage(r.message());
        boolean busy = otherJobsRunning(job);   // phải tính trước khi job bị requeue / đổi trạng thái
        AccountEntity acc = job.getAccountId() == null ? null : accounts.findById(job.getAccountId()).orElse(null);
        if (acc == null) {
            requeue(job, null, "tài khoản không còn → xoay sang tài khoản khác");
            log.info("Job {} báo lỗi {} → {} (tài khoản không còn)", jobId, code, job.getStatus());
            return;
        }

        switch (code) {
            case QUOTA_EXCEEDED -> {
                int minutes = settings.getIntSetting("quota_cooldown_minutes", 360);
                acc.cooldown(LocalDateTime.now().plusMinutes(minutes), r.message());
                requeue(job, acc.getId(), "hết quota trên " + acc.getEmail() + " → xoay sang tài khoản khác");   // không tính attempt
            }
            case SUSPICIOUS -> {
                // Flow nghi ngờ: KHÔNG cố gửi tiếp trên tài khoản này. Cho nghỉ rồi để tài khoản khác (hoặc chính nó sau giờ nghỉ) làm.
                int minutes = settings.getIntSetting("suspicious_cooldown_minutes", 30);
                acc.cooldown(LocalDateTime.now().plusMinutes(minutes), r.message());
                requeue(job, acc.getId(), "Flow nghi ngờ hoạt động bất thường trên " + acc.getEmail() + " → nghỉ " + minutes + " phút, xoay sang tài khoản khác");
            }
            case AUTH_REQUIRED -> {
                acc.needLogin(r.message());
                requeue(job, acc.getId(), "phiên đăng nhập của " + acc.getEmail() + " hỏng → xoay sang tài khoản khác");
            }
            case TRANSIENT -> {
                acc.releaseAfterError(r.message(), busy);
                job.setAttempt(job.getAttempt() + 1);
                int max = projects.findById(job.getProjectId()).map(ProjectEntity::getMaxAttempts).orElse(3);
                if (job.getAttempt() < max) {
                    requeue(job, acc.getId(), "lỗi tạm thời, thử lại " + job.getAttempt() + "/" + max);
                } else {
                    fail(job, acc.getId(), "hết lượt thử (" + max + ")");
                }
            }
            case REJECTED -> {
                acc.releaseOk(busy);
                fail(job, acc.getId(), "nền tảng từ chối: cần sửa prompt");
            }
            case CONFIG_ERROR -> {
                acc.releaseOk(busy);
                requeue(job, acc.getId(), "lỗi cấu hình worker");
                projects.findById(job.getProjectId()).ifPresent(p -> p.pause("CONFIG_ERROR: " + r.message()));
            }
            case PAUSED -> {
                // người dùng chủ động dừng (đóng cửa sổ Flow / nút "Tạm dừng phim"): không phải lỗi
                acc.releaseOk(busy);
                job.setErrorCode(null);
                job.setErrorMessage(null);
                requeue(job, acc.getId(), "người dùng tạm dừng: " + r.message());
                projects.findById(job.getProjectId()).ifPresent(p -> p.pause(r.message()
                        + ". Chọn cảnh ở trang Danh sách cảnh (nút \"Chạy cảnh này\") hoặc bấm Chạy để làm tiếp."));
            }
            case ACCOUNT_BROKEN -> {
                // Lỗi của riêng tài khoản (proxy…): thử lại trên chính tài khoản này vô ích. Tắt nó, không tính lượt thử của job.
                acc.setLeasedJobId(null);
                acc.setStatus(com.aifilm.automation.account.AccountStatus.DISABLED);
                acc.setLastErrorMessage(r.message() + " → sửa tài khoản rồi bấm \"Bật lại\"");
                requeue(job, acc.getId(), "tài khoản " + acc.getEmail() + " lỗi cấu hình (" + r.message() + ") → tắt tài khoản, xoay sang tài khoản khác");
            }
            case CANCELLED -> {
                acc.releaseOk(busy);
                cancelled(job, acc.getId());
            }
        }
        log.info("Job {} báo lỗi {} → {} ({})", jobId, code, job.getStatus(), r.message());
    }

    /** Trả về true nếu người dùng đã yêu cầu hủy job. */
    @Transactional
    public boolean heartbeat(long jobId, String workerId) {
        if (jobs.touchHeartbeat(jobId, workerId, LocalDateTime.now()) == 0) {
            throw new JobNotOwnedException(jobId, workerId);
        }
        return jobs.isCancelRequested(jobId).orElse(false);
    }

    /** Worker im lặng quá 5 phút (crash, máy ngủ): coi như lỗi tạm thời để job và tài khoản không kẹt mãi. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reapStaleJobs() {
        LocalDateTime limit = LocalDateTime.now().minusMinutes(5);
        for (JobEntity job : jobs.findByStatusAndHeartbeatAtBefore(JobStatus.GENERATING, limit)) {
            log.warn("Job {}: worker {} mất liên lạc, thu hồi", job.getId(), job.getWorkerId());
            try {
                // mỗi job một transaction: một job lỗi không làm hỏng cả lô
                tx.executeWithoutResult(s -> onFailed(job.getId(), new FailureReport(job.getWorkerId(), "TRANSIENT", "Worker mất liên lạc quá 5 phút")));
            } catch (RuntimeException e) {
                log.error("Job {}: không thu hồi được: {}", job.getId(), e.toString());
            }
        }
    }

    /** Lúc khởi động: mọi việc dở dang từ lần chạy trước được trả về hàng đợi (chạy trước khi bật worker). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void recoverAfterRestart() {
        for (JobEntity job : jobs.findByStatus(JobStatus.GENERATING)) {
            requeue(job, null, "khôi phục sau khi khởi động lại");
        }
        for (JobEntity job : jobs.findByStatus(JobStatus.DOWNLOADING)) {
            transitions.move(job, JobStatus.APPROVED, null, "khôi phục sau khi khởi động lại");
        }
        accounts.findByStatus(AccountStatus.LEASED).forEach(AccountEntity::releaseOk);
        // Mở app không tự chạy gì: phim đang chạy lúc tắt app thì về "Tạm dừng", người dùng bấm Chạy mới chạy
        for (ProjectEntity p : projects.findByStatusOrderByIdAsc(com.aifilm.automation.project.ProjectStatus.RUNNING)) {
            p.pause("Vừa mở app: phim đang tạm dừng. Bấm \"Chạy\" (hoặc \"Chạy riêng\" ở một cảnh) để làm tiếp");
            log.info("Phim {}: tạm dừng khi mở app", p.getCode());
        }
    }

    /** Worker bị tắt / chết: trả ngay job nó đang làm về hàng đợi (không tính lượt thử) và nhả tài khoản. */
    @Transactional
    public void releaseWorkerJobs(String workerId) {
        for (JobEntity job : jobs.findByStatusAndWorkerId(JobStatus.GENERATING, workerId)) {
            Long accId = job.getAccountId();
            if (accId != null) {
                accounts.findById(accId).filter(a -> a.getStatus() == AccountStatus.LEASED && Objects.equals(a.getLeasedJobId(), job.getId()))
                        .ifPresent(AccountEntity::releaseOk);
            }
            requeue(job, accId, "worker " + workerId + " dừng → trả về hàng đợi");
        }
    }

    /** Người dùng đã bấm hủy thì không đưa job về hàng đợi nữa mà kết thúc là CANCELLED. */
    private void requeue(JobEntity job, Long accId, String why) {
        if (job.isCancelRequested()) {
            cancelled(job, accId);
            return;
        }
        job.setAccountId(null);
        job.setWorkerId(null);
        transitions.move(job, JobStatus.PENDING, accId, why);
    }

    private void fail(JobEntity job, Long accId, String why) {
        if (job.isCancelRequested()) {
            cancelled(job, accId);
            return;
        }
        job.setWorkerId(null);
        job.setRunPriority(null);
        transitions.move(job, JobStatus.FAILED, accId, why);
    }

    private void cancelled(JobEntity job, Long accId) {
        job.setWorkerId(null);
        job.setRunPriority(null);
        job.setCancelRequested(false);
        transitions.move(job, JobStatus.CANCELLED, accId, "người dùng hủy");
    }

    /** Worker vừa vào / tạo một dự án Flow cho cảnh đang chạy: nhớ ngay (kể cả khi người dùng đóng cửa sổ sau đó). */
    @Transactional
    public void onFlowProject(long jobId, String workerId, String url) {
        rememberFlowProject(owned(jobId, workerId), url);
    }

    /** Nhớ dự án Flow theo cặp (phim, tài khoản): dự án Flow chỉ tài khoản tạo ra nó mới mở được. */
    private void rememberFlowProject(JobEntity job, String url) {
        if (!FlowLinkRepository.isProjectUrl(url) || job.getAccountId() == null) {
            return;
        }
        // Flow mở /project/<uuid>/edit/<media> sau khi tạo video: chỉ nhớ phần /project/<uuid>
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https://flow\\.google\\.com/project/[0-9a-fA-F-]{36})")
                .matcher(url.split("[?#]")[0]);
        String clean = m.find() ? m.group(1) : url.split("[?#]")[0];
        FlowLinkEntity link = flowLinks.findByProjectIdAndAccountId(job.getProjectId(), job.getAccountId())
                .orElseGet(() -> new FlowLinkEntity(job.getProjectId(), job.getAccountId(), clean));
        if (link.getId() == null || !clean.equals(link.getUrl())) {
            link.setUrl(clean);
            flowLinks.save(link);
            log.info("Phim {} / tài khoản {}: dùng dự án Flow {}", job.getProjectId(), job.getAccountId(), clean);
        }
    }

    private JobEntity owned(long jobId, String workerId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.GENERATING || !Objects.equals(job.getWorkerId(), workerId)) {
            throw new JobNotOwnedException(jobId, workerId);
        }
        return job;
    }
}
