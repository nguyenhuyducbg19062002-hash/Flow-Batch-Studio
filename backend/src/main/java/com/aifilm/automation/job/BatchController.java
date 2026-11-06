package com.aifilm.automation.job;

import com.aifilm.automation.project.ProjectService;
import com.aifilm.automation.project.ProjectService.ProjectView;
import com.aifilm.automation.prompt.PromptKind;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/** Hai màn "Tạo ảnh" và "Tạo video": cùng một quy trình, kind = image | video. */
@RestController
@RequestMapping("/api/projects/{id}/batch/{kind}")
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batch;
    private final ProjectService projects;

    public record RedoRequest(String numbers, String note) {}

    @PostMapping("/start")
    public ProjectView start(@PathVariable long id, @PathVariable String kind) {
        return projects.start(id, kind(kind));
    }

    @PostMapping("/pause")
    public ProjectView pause(@PathVariable long id, @PathVariable String kind) {
        return projects.pause(id, kind(kind));
    }

    /** Làm lại các số người duyệt báo sai: {"numbers": "5, 12, 30-33", "note": "áo màu đỏ"} */
    @PostMapping("/redo")
    public BatchService.RedoResult redo(@PathVariable long id, @PathVariable String kind, @RequestBody RedoRequest r) {
        return batch.redo(id, kind(kind), r.numbers(), r.note());
    }

    /** Đổi tên toàn bộ file trong thư mục chờ duyệt sang tên đúng và chuyển vào Folder Ảnh / Folder Video. */
    @PostMapping("/finalize")
    public BatchService.FinalizeResult finalizeAll(@PathVariable long id, @PathVariable String kind) {
        return batch.finalizeAll(id, kind(kind));
    }

    /** Mở thư mục chờ duyệt bằng Explorer (tạo nếu chưa có). */
    @PostMapping("/open-review")
    public Map<String, String> openReview(@PathVariable long id, @PathVariable String kind) throws IOException {
        var dir = batch.reviewDir(id, kind(kind));
        new ProcessBuilder("explorer.exe", dir.toString()).start();
        return Map.of("path", dir.toString());
    }

    static PromptKind kind(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "image", "images", "anh" -> PromptKind.IMAGE;
            case "video", "videos" -> PromptKind.VIDEO;
            default -> throw new IllegalArgumentException("Phần không hợp lệ: " + s);
        };
    }
}
