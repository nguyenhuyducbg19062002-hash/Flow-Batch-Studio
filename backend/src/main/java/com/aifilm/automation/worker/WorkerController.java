package com.aifilm.automation.worker;

import com.aifilm.automation.job.JobClaimService;
import com.aifilm.automation.job.JobPayload;
import com.aifilm.automation.job.JobResultService;
import com.aifilm.automation.job.WorkerReports.ClaimMoreRequest;
import com.aifilm.automation.job.WorkerReports.ClaimRequest;
import com.aifilm.automation.job.WorkerReports.FailureReport;
import com.aifilm.automation.job.WorkerReports.FlowProjectReport;
import com.aifilm.automation.job.WorkerReports.GeneratedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** API nội bộ cho worker Python. Bảo vệ bằng WorkerTokenFilter. */
@RestController
@RequestMapping("/api/worker")
@RequiredArgsConstructor
public class WorkerController {

    private final JobClaimService claims;
    private final JobResultService results;

    /** 200 + payload khi có việc; 204 khi chưa có việc. */
    @PostMapping("/claim")
    public ResponseEntity<JobPayload> claim(@RequestBody ClaimRequest req) {
        return claims.claim(req.workerId()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** Chạy song song: cấp thêm một job cho đúng tài khoản worker đang giữ phiên (204 khi hết việc hoặc đã đủ chỗ). */
    @PostMapping("/claim-more")
    public ResponseEntity<JobPayload> claimMore(@RequestBody ClaimMoreRequest req) {
        return claims.claimMore(req.workerId(), req.accountId()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{id}/heartbeat")
    public Map<String, Boolean> heartbeat(@PathVariable long id, @RequestBody ClaimRequest req) {
        return Map.of("cancel", results.heartbeat(id, req.workerId()));
    }

    @PostMapping("/jobs/{id}/generated")
    public void generated(@PathVariable long id, @RequestBody GeneratedReport r) {
        results.onGenerated(id, r);
        results.autoApprove(id);   // sau khi onGenerated đã commit
    }

    /** Driver Flow vừa vào / tạo dự án Flow: nhớ link cho tài khoản này. */
    @PostMapping("/jobs/{id}/flow-project")
    public void flowProject(@PathVariable long id, @RequestBody FlowProjectReport r) {
        results.onFlowProject(id, r.workerId(), r.url());
    }

    @PostMapping("/jobs/{id}/failed")
    public void failed(@PathVariable long id, @RequestBody FailureReport r) {
        results.onFailed(id, r);
    }
}
