package com.aifilm.automation.system;

import com.aifilm.automation.job.JobNotOwnedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/** Mọi lỗi trả về dạng {"error": "..."} để giao diện hiện đúng thông báo tiếng Việt. */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Không tìm thấy dữ liệu"));
    }

    @ExceptionHandler(JobNotOwnedException.class)
    public ResponseEntity<Map<String, String>> notOwned(JobNotOwnedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> conflict(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Dữ liệu vừa được thay đổi ở nơi khác, hãy tải lại và thử lại"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noResource(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Không tìm thấy"));
    }

    /** Trình duyệt ngắt luồng video / ảnh giữa chừng (người dùng chuyển mục khi đang xem): không phải lỗi. */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void clientGone() {
        // bỏ qua
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("Lỗi không lường trước", e);
        return ResponseEntity.internalServerError().body(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
    }
}
