package com.aifilm.automation.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** State machine của job. Mọi chuyển trạng thái đi qua JobTransitions.move(), không set trực tiếp. */
public enum JobStatus {
    PENDING, GENERATING, AWAITING_REVIEW, APPROVED, DOWNLOADING, SUCCESS, FAILED, CANCELLED;

    private static final Map<JobStatus, Set<JobStatus>> NEXT = new EnumMap<>(JobStatus.class);

    static {
        // PENDING / FAILED / CANCELLED → AWAITING_REVIEW: người dùng tự đưa file kết quả (tải tay từ Flow) vào app
        NEXT.put(PENDING, EnumSet.of(GENERATING, CANCELLED, AWAITING_REVIEW));
        NEXT.put(GENERATING, EnumSet.of(AWAITING_REVIEW, PENDING, FAILED, CANCELLED));
        NEXT.put(AWAITING_REVIEW, EnumSet.of(APPROVED, PENDING, CANCELLED));
        NEXT.put(APPROVED, EnumSet.of(DOWNLOADING, PENDING, CANCELLED));
        NEXT.put(DOWNLOADING, EnumSet.of(SUCCESS, APPROVED));
        NEXT.put(SUCCESS, EnumSet.of(PENDING));
        NEXT.put(FAILED, EnumSet.of(PENDING, CANCELLED, AWAITING_REVIEW));
        NEXT.put(CANCELLED, EnumSet.of(PENDING, AWAITING_REVIEW));
    }

    public boolean canMoveTo(JobStatus to) {
        return NEXT.get(this).contains(to);
    }
}
