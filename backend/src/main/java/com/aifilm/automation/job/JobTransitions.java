package com.aifilm.automation.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobTransitions {

    private final JobEventRepository events;

    /** Mọi thay đổi trạng thái đi qua đây: chặn chuyển sai và ghi nhật ký. Gọi bên trong một transaction. */
    public void move(JobEntity job, JobStatus to, Long accountId, String message) {
        JobStatus from = job.getStatus();
        if (!from.canMoveTo(to)) {
            throw new IllegalStateException("Job " + job.getId() + ": không thể chuyển " + from + " → " + to);
        }
        job.setStatus(to);
        events.save(new JobEventEntity(job.getId(), from, to, accountId, message));
    }
}
