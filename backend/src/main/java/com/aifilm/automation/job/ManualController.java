package com.aifilm.automation.job;

import com.aifilm.automation.prompt.PromptKind;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Chế độ "Tự làm trên Flow": xem ManualService. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ManualController {

    private final ManualService manual;
    private final JobQueryService query;

    @GetMapping("/projects/{projectId}/manual/next")
    public ManualService.NextScene next(@PathVariable long projectId, @RequestParam(required = false) List<Long> skip,
                                       @RequestParam(defaultValue = "false") boolean imagesOnly) {
        return manual.next(projectId, skip == null ? List.of() : skip, imagesOnly);
    }

    @PostMapping("/jobs/{id}/manual/open-refs")
    public Map<String, String> openRefs(@PathVariable long id, @RequestParam(required = false) List<String> only) {
        return Map.of("path", manual.prepareReferences(id, only, true).toString());
    }

    @GetMapping("/manual/downloads")
    public Map<String, Object> downloads(@RequestParam(defaultValue = "0") long after, @RequestParam(required = false) PromptKind kind) {
        return Map.of("dir", manual.downloadsDir().toString(), "files", manual.downloads(after, kind));
    }

    public record ImportRequest(String path) {}

    @PostMapping("/jobs/{id}/manual/import")
    public JobView importDownload(@PathVariable long id, @RequestBody ImportRequest r) {
        return query.view(manual.importDownload(id, r.path()).getId());
    }
}
