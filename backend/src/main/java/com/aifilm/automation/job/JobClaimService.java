package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import com.aifilm.automation.prompt.ProjectAssetRepository;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.prompt.PromptReferenceEntity;
import com.aifilm.automation.prompt.PromptReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobClaimService {

    private final JobRepository jobs;
    private final AccountRepository accounts;
    /** Tự tạo tài khoản nội bộ cho FLOW_EXTENSION (null trong unit test dựng tay). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aifilm.automation.account.FlowExtensionAccount flowExtensionAccount;
    private final ProjectRepository projects;
    private final JobTransitions transitions;
    private final JobPayloadFactory payloads;
    private final ProjectAssetRepository assets;
    private final PromptReferenceRepository references;
    private final TransactionTemplate tx;
    private final ReentrantLock claimLock = new ReentrantLock();

    /**
     * Giao một job cho worker và thuê một tài khoản cho job đó.
     * Chỉ có một backend nên khóa trong JVM bọc NGOÀI transaction là đủ để hai worker không giành cùng job/tài khoản;
     * khóa được nhả sau khi commit. Một tài khoản chỉ phục vụ một job một lúc (một profile Edge chỉ mở được một lần).
     */
    public Optional<JobPayload> claim(String workerId) {
        claimLock.lock();
        try {
            return tx.execute(status -> claimInTx(workerId));
        } finally {
            claimLock.unlock();
        }
    }

    /**
     * Chạy song song: worker đang giữ một phiên Flow (một tài khoản, một lần mở Edge) xin thêm một job cho CHÍNH tài khoản đó
     * trong lúc các job trước còn đang tạo. Chỉ cấp khi:
     *  - tài khoản còn đang LEASED và mọi job đang chạy của nó thuộc đúng worker này (phiên của worker khác thì không đụng);
     *  - chưa đủ số lệnh cùng lúc của tài khoản (maxConcurrent) và của phim (Song song);
     *  - job kế tiếp cùng phim (một phiên = một dự án Flow của tài khoản cho phim đó), không phải việc "Sửa trên Flow".
     */
    public Optional<JobPayload> claimMore(String workerId, long accountId) {
        claimLock.lock();
        try {
            return tx.execute(status -> claimMoreInTx(workerId, accountId));
        } finally {
            claimLock.unlock();
        }
    }

    Optional<JobPayload> claimMoreInTx(String workerId, long accountId) {
        AccountEntity a = accounts.findById(accountId).orElse(null);
        if (a == null || a.getStatus() != com.aifilm.automation.account.AccountStatus.LEASED) {
            return Optional.empty();
        }
        List<JobEntity> mine = jobs.findByAccountIdAndStatus(accountId, JobStatus.GENERATING);
        if (mine.isEmpty() || mine.size() >= a.effectiveMaxConcurrent()
                || mine.stream().anyMatch(j -> !workerId.equals(j.getWorkerId()))) {
            return Optional.empty();
        }
        ProjectEntity p = projects.findById(mine.get(0).getProjectId()).orElse(null);
        if (p == null || p.getStatus() != ProjectStatus.RUNNING
                || p.getRunMode() != com.aifilm.automation.project.RunMode.BACKGROUND
                || (p.getProvider() != com.aifilm.automation.project.Provider.FLOW_BROWSER
                    && p.getProvider() != com.aifilm.automation.project.Provider.FLOW_EXTENSION
                    && p.getProvider() != com.aifilm.automation.project.Provider.FAKE)   // FAKE: để kiểm thử cả vòng không cần Google
                // giới hạn của phim; tài khoản đặt "Số lệnh chạy cùng lúc" cao hơn thì theo tài khoản
                || jobs.countByProjectIdAndStatus(p.getId(), JobStatus.GENERATING)
                    >= Math.max(p.effectiveParallel(), a.effectiveMaxConcurrent())) {
            return Optional.empty();
        }
        Optional<JobEntity> next = pickNext(p, new HashSet<>(Set.of(-1L)));
        if (next.isEmpty() || next.get().getEditInstruction() != null || next.get().isCancelRequested()) {
            return Optional.empty();
        }
        return Optional.of(startJob(next.get(), a, p, workerId, LocalDateTime.now()));
    }

    /**
     * Mục kế tiếp của phim theo đúng thứ tự worker sẽ chạy, nhưng KHÔNG nhận job, không thuê tài khoản, không đổi trạng thái
     * phim. Dùng cho chế độ "Tự làm trên Flow": app chỉ bày mục ra, người dùng tự tạo trên Flow. Gọi trong transaction.
     */
    public Optional<JobEntity> peekNext(ProjectEntity p, Set<Long> skip) {
        return peekNext(p, skip, false);
    }

    /** imagesOnly: "Chỉ làm ảnh". Không thì ảnh trước, video sau (video chỉ khi ảnh tham chiếu đã đổi tên sang Folder Ảnh). */
    public Optional<JobEntity> peekNext(ProjectEntity p, Set<Long> skip, boolean imagesOnly) {
        Set<Long> s = new HashSet<>(skip);
        s.add(-1L);   // NOT IN không được rỗng
        return jobs.findNextBatch(p.getId(), 1, imagesOnly ? 0 : 1, 0, s);
    }

    /**
     * Việc kế tiếp của một phim: chỉ trong các phần đang bật (Tạo ảnh / Tạo video chạy riêng).
     * skip: job đã xét trong lượt này mà không giao được (phải chứa ít nhất một phần tử: NOT IN không được rỗng).
     * Một phần hết việc (không còn mục chờ chạy được và không còn mục đang tạo) thì tự tắt phần đó.
     */
    private Optional<JobEntity> pickNext(ProjectEntity p, Set<Long> skip) {
        int img = p.isRunning(PromptKind.IMAGE) ? 1 : 0;
        int vid = p.isRunning(PromptKind.VIDEO) ? 1 : 0;
        if (img == 0 && vid == 0) {
            p.pause("Chưa bật phần Tạo ảnh hay Tạo video");   // phim RUNNING mà không phần nào bật (dữ liệu cũ)
            return Optional.empty();
        }
        int sel = p.isSelectedOnlyOn() ? 1 : 0;
        Optional<JobEntity> next = jobs.findNextBatch(p.getId(), img, vid, sel, skip);
        if (next.isEmpty() && skip.size() <= 1) {
            for (PromptKind k : PromptKind.values()) {
                if (p.isRunning(k) && jobs.countByProjectIdAndKindAndStatusIn(p.getId(), k, List.of(JobStatus.GENERATING)) == 0) {
                    stopWhenDone(p, k);
                }
            }
        }
        return next;
    }

    /** Phần ảnh / video không còn gì chạy được: tắt phần đó và ghi lý do (xong hết, hay còn mục chờ ảnh tham chiếu). */
    private void stopWhenDone(ProjectEntity p, PromptKind k) {
        String part = k == PromptKind.IMAGE ? "ảnh" : "video";
        long waiting = jobs.countByProjectIdAndKindAndStatusIn(p.getId(), k, List.of(JobStatus.PENDING));
        String reason;
        if (p.isSelectedOnlyOn() && waiting > 0) {
            reason = "Đã tạo xong các " + part + " bạn chọn làm lại: kiểm tra thư mục chờ duyệt";
            p.setSelectedOnly(false);
        } else if (waiting > 0) {
            reason = waiting + " " + part + (k == PromptKind.VIDEO
                    ? " chưa chạy được vì thiếu ảnh tham chiếu (ảnh phải được \"Đổi tên\" sang Folder Ảnh trước)"
                    : " (ảnh đổi đồ) đang chờ ảnh gốc: duyệt rồi bấm \"Đổi tên\" để ảnh gốc vào Folder Ảnh, app tự chạy tiếp");
        } else {
            reason = "Đã tạo xong phần " + part + ": kiểm tra thư mục chờ duyệt, báo số sai để làm lại, rồi bấm \"Đổi tên\"";
        }
        p.setRunning(k, false, reason);
        if (p.getStatus() == ProjectStatus.RUNNING) {
            p.setPausedReason(reason);   // phần kia còn chạy: vẫn ghi lại để giao diện báo
        }
        log.info("Phim {}: tắt phần {} ({})", p.getCode(), part, reason);
    }

    private JobPayload startJob(JobEntity job, AccountEntity a, ProjectEntity p, String workerId, LocalDateTime now) {
        a.lease(job.getId(), now);
        job.setAccountId(a.getId());
        job.setWorkerId(workerId);
        job.setStartedAt(now);
        job.setHeartbeatAt(now);
        job.setCancelRequested(false);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        transitions.move(job, JobStatus.GENERATING, a.getId(), "claim bởi " + workerId + " trên " + a.getEmail());
        log.info("Job {} ({}) → {} trên {}", job.getId(), job.getKind(), workerId, a.getEmail());
        return payloads.forGenerate(job, p, a);
    }

    private Optional<JobPayload> claimInTx(String workerId) {
        LocalDateTime now = LocalDateTime.now();
        for (ProjectEntity p : projects.findByStatusOrderByIdAsc(ProjectStatus.RUNNING)) {
            if (jobs.countByProjectIdAndStatus(p.getId(), JobStatus.GENERATING) >= p.effectiveParallel()) {
                continue;
            }
            Set<Long> skip = new HashSet<>(Set.of(-1L));   // job đã xét trong lượt này (-1: để NOT IN không rỗng)
            for (int guard = 0; guard < 50; guard++) {
                Optional<JobEntity> next = pickNext(p, skip);
                if (next.isEmpty()) {
                    break;
                }
                JobEntity candidate = next.get();
                skip.add(candidate.getId());
                if (candidate.isCancelRequested()) {
                    candidate.setCancelRequested(false);
                    candidate.setRunPriority(null);
                    transitions.move(candidate, JobStatus.CANCELLED, null, "người dùng hủy");
                    continue;
                }
                Optional<AccountEntity> acc;
                if (candidate.getEditInstruction() != null && candidate.getFlowAccountId() != null) {
                    // "Sửa trên Flow": video nằm trong dự án Flow của đúng tài khoản đã tạo nó
                    Optional<AccountEntity> owner = accounts.findById(candidate.getFlowAccountId());
                    if (owner.isEmpty() || owner.get().getStatus() == AccountStatus.DISABLED) {
                        dropFlowEdit(candidate, owner.isEmpty());
                        continue;
                    }
                    acc = owner.filter(o -> o.getStatus() == AccountStatus.READY
                            || (o.getStatus() == AccountStatus.COOLDOWN && o.getCooldownUntil() != null && !o.getCooldownUntil().isAfter(now)));
                    if (acc.isEmpty()) {
                        continue;   // tài khoản đích đang bận / cần đăng nhập: job này chờ, lấy job kế tiếp
                    }
                } else {
                    if (p.getProvider() == com.aifilm.automation.project.Provider.FLOW_EXTENSION && flowExtensionAccount != null) {
                        flowExtensionAccount.ensure();   // không cần người dùng thêm tài khoản
                    }
                    acc = accounts.findNextAvailable(p.getProvider().name(), now);
                    if (acc.isEmpty()) {
                        break;   // job giữ PENDING; UI hiển thị "hết tài khoản"
                    }
                }
                return Optional.of(startJob(candidate, acc.get(), p, workerId, now));
            }
        }
        return Optional.empty();
    }

    /** Tài khoản đã tạo video trên Flow không còn dùng được: hủy lượt sửa, báo rõ cho người dùng thay vì chặn cả phim. */
    private void dropFlowEdit(JobEntity job, boolean deleted) {
        String msg = deleted
                ? "Tài khoản tạo video này trên Flow đã bị xóa: đã hủy lượt \"Sửa trên Flow\". Dùng \"Tạo lại\""
                : "Tài khoản tạo video này trên Flow đang tắt: đã hủy lượt \"Sửa trên Flow\". Bật lại tài khoản rồi sửa lại, hoặc dùng \"Tạo lại\"";
        if (deleted) {
            job.setFlowMediaUrl(null);
            job.setFlowAccountId(null);
        }
        job.setEditInstruction(null);
        job.setRunPriority(null);
        job.setErrorMessage(msg);
        transitions.move(job, JobStatus.CANCELLED, null, msg);
        log.warn("Job {}: {}", job.getId(), msg);
    }
}
