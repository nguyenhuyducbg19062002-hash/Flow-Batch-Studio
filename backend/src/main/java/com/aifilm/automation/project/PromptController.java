package com.aifilm.automation.project;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Ảnh reference của từng cảnh: nút "+" thêm ảnh, nút xóa ảnh đã thêm tay. */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final AssetService assetService;

    @PostMapping(value = "/{promptId}/references", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, List<String>> addReferences(@PathVariable long promptId, @RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn ảnh nào");
        }
        return Map.of("added", assetService.addToPrompt(promptId, files));
    }

    @DeleteMapping("/{promptId}/references/{refId}")
    public void removeReference(@PathVariable long promptId, @PathVariable String refId) {
        assetService.removeFromPrompt(promptId, refId);
    }
}
