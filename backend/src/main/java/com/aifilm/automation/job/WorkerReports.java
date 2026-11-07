package com.aifilm.automation.job;

/** Các gói tin worker Python gửi về. */
public final class WorkerReports {

    private WorkerReports() {}

    public record ClaimRequest(String workerId) {}

    /** Chạy song song: xin thêm job cho tài khoản mà worker đang giữ phiên Flow. */
    public record ClaimMoreRequest(String workerId, long accountId) {}

    /** mediaUrl: địa chỉ màn chỉnh sửa kết quả trên Flow (để "Sửa trên Flow" sau này). */
    public record GeneratedReport(String workerId, String stagedFile, String remoteRef, String mediaUrl) {}

    public record FlowProjectReport(String workerId, String url) {}

    public record FailureReport(String workerId, String errorCode, String message) {}

    /** Các Chrome có extension Flow Bridge đang nối app: email Google mỗi Chrome; anonymous = có extension chưa rõ email. */
    public record FlowExtensionsReport(String workerId, java.util.List<String> emails, boolean anonymous) {}
}
