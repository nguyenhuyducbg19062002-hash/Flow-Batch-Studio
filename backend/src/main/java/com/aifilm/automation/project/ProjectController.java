package com.aifilm.automation.project;

import com.aifilm.automation.project.AssetService.AssetView;
import com.aifilm.automation.project.AssetService.FolderImportResult;
import com.aifilm.automation.project.OmniImportService.ImportResult;
import com.aifilm.automation.project.ProjectService.ProjectForm;
import com.aifilm.automation.project.ProjectService.ProjectView;
import com.aifilm.automation.prompt.PromptKind;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;
    private final OmniImportService importer;
    private final AssetService assetService;

    @GetMapping
    public List<ProjectView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable long id) {
        return service.get(id);
    }

    public record ScanRequest(String path) {}

    /** Quét thư mục tổng của một tập: trả về thư mục Excel / ảnh / video để điền sẵn form (chưa tạo gì trên đĩa). */
    @PostMapping("/scan-folder")
    public ProjectFolderScanner.Scan scanFolder(@RequestBody ScanRequest req) {
        if (req == null || req.path() == null || req.path().isBlank()) {
            throw new IllegalArgumentException("Chưa chọn thư mục");
        }
        return ProjectFolderScanner.scan(Path.of(req.path().trim()));
    }

    @PostMapping
    public ProjectView create(@RequestBody ProjectForm form) {
        ProjectView created = service.create(form);
        // tự nạp prompt + ảnh có sẵn ngay sau khi tạo (phim đã được lưu xong; nạp lỗi không làm hỏng việc tạo phim)
        return service.autoImportAfterCreate(created.project().getId());
    }

    @PutMapping("/{id}")
    public ProjectView update(@PathVariable long id, @RequestBody ProjectForm form) {
        return service.update(id, form);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /** Nạp tất cả file Excel Omni quen thuộc trong thư mục nguồn của phim. */
    @PostMapping("/{id}/import")
    public List<ImportResult> importPrompts(@PathVariable long id) throws IOException {
        List<ImportResult> r = importer.importProject(id);
        service.adoptExistingVideos(id);   // video đã duyệt từ trước vẫn còn trong Folder Video: không tạo lại
        return r;
    }

    /** "Thêm file ảnh" (kind=IMAGE) / "Thêm file video" (kind=VIDEO): tải một file .xlsx hoặc .txt (mỗi dòng 1 prompt) lên. */
    @PostMapping(value = "/{id}/import-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importFile(@PathVariable long id, @RequestParam("kind") String kindName,
                                   @RequestParam("file") MultipartFile file) throws IOException {
        PromptKind kind = kindOf(kindName);
        String name = file.getOriginalFilename() == null ? "upload.xlsx" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            // .txt cần tên file thật (PANEL-<STT> khi tên có "panel"): lưu tạm với đúng tên rồi đọc
            Path tmpDir = java.nio.file.Files.createTempDirectory("afm-txt-");
            Path tmp = tmpDir.resolve(name);
            try {
                file.transferTo(tmp);
                ImportResult r = importer.importTxt(id, kind, tmp);
                service.adoptExistingVideos(id);
                return r;
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
                java.nio.file.Files.deleteIfExists(tmpDir);
            }
        }
        if (!lower.endsWith(".xlsx")) {
            throw new IllegalArgumentException(name + ": chỉ nhận file Excel .xlsx hoặc .txt (mỗi dòng một prompt)");
        }
        try (InputStream in = file.getInputStream()) {
            ImportResult r = importer.importFile(id, kind, name, in);
            service.adoptExistingVideos(id);
            return r;
        }
    }

    /** "Nạp cả thư mục phim": mọi file prompt (.xlsx / .txt) + ảnh có sẵn trong một thư mục, ví dụ D:\Dev\Tập 5. Hà - Cupid. */
    @PostMapping("/{id}/import-folder")
    public OmniImportService.FolderResult importMovieFolder(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) throws IOException {
        OmniImportService.FolderResult r = importer.importFolder(id, pathOf(body));
        service.adoptExistingVideos(id);
        return r;
    }

    /** "Nhập thủ công": dán prompt, mỗi dòng một cảnh. */
    @PostMapping("/{id}/import-text")
    public ImportResult importText(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Object kind = body == null ? null : body.get("kind");
        return importer.importText(id, kindOf(kind == null ? "VIDEO" : kind.toString()),
                body == null || body.get("text") == null ? null : body.get("text").toString());
    }

    /** "Ảnh → Video theo thư mục con". */
    @PostMapping("/{id}/import-image-groups")
    public ImportResult importImageGroups(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) throws IOException {
        return importer.importImageGroups(id, pathOf(body), Boolean.TRUE.equals(body.get("grouped")));
    }

    /** Body JSON {"path": "..."}: thiếu / rỗng thì báo tiếng Việt (400) thay vì lỗi hệ thống. */
    static String pathOf(Map<String, Object> body) {
        Object p = body == null ? null : body.get("path");
        if (p == null || p.toString().isBlank()) {
            throw new IllegalArgumentException("Chưa chọn thư mục");
        }
        return p.toString().trim();
    }

    static PromptKind kindOf(String kind) {
        try {
            return PromptKind.valueOf(kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Loại prompt phải là IMAGE (ảnh) hoặc VIDEO, không phải \"" + kind + "\"");
        }
    }

    @PostMapping("/{id}/start")
    public ProjectView start(@PathVariable long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/pause")
    public ProjectView pause(@PathVariable long id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/retry-failed")
    public Map<String, Integer> retryFailed(@PathVariable long id) {
        return Map.of("requeued", service.retryFailed(id));
    }

    /** Quét file đã duyệt trong Folder Ảnh / Folder Video: thiếu hoặc hỏng thì đưa về hàng đợi tạo lại. */
    @PostMapping("/{id}/verify-files")
    public ProjectService.VerifyResult verifyFiles(@PathVariable long id) {
        return service.verifyFiles(id);
    }

    /* ---------------- kho ảnh ---------------- */

    @GetMapping("/{id}/assets")
    public List<AssetView> assets(@PathVariable long id) {
        return assetService.list(id);
    }

    /** "Thêm ảnh có sẵn": thư mục chứa ảnh gốc đặt tên theo ID (CHAR-CALEB-1.png, LOC-1.jpg…). */
    @PostMapping("/{id}/assets/import-folder")
    public FolderImportResult importFolder(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return assetService.importFolder(id, pathOf(body));
    }

    /** Ảnh của một ID (để xem thu nhỏ / so sánh với prompt). */
    @GetMapping("/{id}/assets/{externalId}/file")
    public ResponseEntity<Resource> assetFile(@PathVariable long id, @PathVariable String externalId) {
        return assetService.file(id, externalId)
                .map(p -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache())
                        .contentType(mediaTypeOf(p))
                        .body((Resource) new FileSystemResource(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    static MediaType mediaTypeOf(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (n.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
