package com.aifilm.automation.job;

/** Worker báo kết quả cho một job không còn thuộc về nó (ví dụ reaper đã thu hồi vì mất heartbeat). */
public class JobNotOwnedException extends RuntimeException {
    public JobNotOwnedException(long jobId, String workerId) {
        super("Job " + jobId + " không còn thuộc worker " + workerId);
    }
}
