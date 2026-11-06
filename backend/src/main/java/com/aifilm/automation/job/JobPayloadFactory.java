package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.project.FlowLinkEntity;
import com.aifilm.automation.project.FlowLinkRepository;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.prompt.*;
import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.system.AppPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class JobPayloadFactory {

    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final ProjectAssetRepository assets;
    private final AppSettingsService settings;
    private final FlowLinkRepository flowLinks;

    /** Gọi trong transaction của claim: gắn revision hiện tại vào job và gói dữ liệu cho worker. */
    public JobPayload forGenerate(JobEntity job, ProjectEntity project, AccountEntity account) {
        PromptEntity prompt = prompts.findById(job.getPromptId()).orElseThrow();
        PromptRevisionEntity rev = revisions.findById(prompt.getCurrentRevisionId()).orElseThrow();
        job.setRevisionId(rev.getId());

        // Ảnh reference theo đúng thứ tự cột Reference IDs (= thứ tự khai báo trong prompt Omni), rồi tới ảnh thêm bằng "+".
        // Áp dụng cho cả video lẫn ảnh PANEL (panel được tạo từ ảnh gốc).
        // Phim bật "Video dùng ảnh có sẵn": ảnh chưa có (panel chưa tạo…) thì bỏ qua, chỉ gắn ảnh đã có
        boolean skipMissing = false;   // video bắt buộc đủ ảnh tham chiếu
        if (prompt.getKind() == PromptKind.VIDEO && references.findByVideoPromptIdOrderByOrdinalAsc(prompt.getId()).isEmpty()) {
            throw new IllegalStateException("Video chưa có ảnh tham chiếu: phải có ảnh tham chiếu mới tạo được video");
        }
        List<String> refs = references.findByVideoPromptIdOrderByOrdinalAsc(prompt.getId()).stream()
                .map(r -> assets.findByProjectIdAndExternalId(project.getId(), r.getRefExternalId())
                        .map(a -> locate(a, project))
                        .or(() -> {
                            if (skipMissing) {
                                return java.util.Optional.empty();
                            }
                            throw new IllegalStateException("Ảnh reference " + r.getRefExternalId() + " chưa có trong kho ảnh");
                        }))
                .flatMap(java.util.Optional::stream)
                .toList();

        Path staging = AppPaths.staging().resolve("job-" + job.getId()).resolve("rev-" + rev.getId());

        Map<String, Object> acc = new LinkedHashMap<>();   // HashMap: cho phép giá trị null
        acc.put("id", account.getId());
        acc.put("email", account.getEmail());
        acc.put("profileDir", account.getProfileDir());
        acc.put("proxy", account.getProxy());
        acc.put("maxConcurrent", account.effectiveMaxConcurrent());   // chạy song song: số lệnh Flow cùng lúc của tài khoản
        acc.put("credentialFile", account.getCredentialFile());
        acc.put("gcpProject", account.getGcpProject());
        acc.put("gcpLocation", account.getGcpLocation());
        acc.put("apiKey", account.getApiKey());
        acc.put("videoModel", account.getVideoModel());
        acc.put("imageModel", account.getImageModel());

        return new JobPayload(
                job.getId(),
                prompt.getKind().name(),
                project.getProvider().name(),
                project.getRunMode().name(),
                prompt.getExternalId(),
                rev.getPromptText(),
                refs,
                acc,
                staging.toString(),
                // dự án Flow của CHÍNH tài khoản này; chưa có thì dùng link nhập tay của phim (nếu có), không thì tạo mới
                flowLinks.findByProjectIdAndAccountId(project.getId(), account.getId()).map(FlowLinkEntity::getUrl)
                        .orElse(project.getFlowProjectUrl()),
                project.getAspectRatio(),
                settings.getIntSetting("job_timeout_minutes", 15) * 60,
                Boolean.parseBoolean(settings.getSetting("headless", "false")),
                prompt.getKind() == PromptKind.VIDEO ? project.getVideoDuration() : null,
                project.getVideoQuality() == null ? "720p" : project.getVideoQuality(),
                job.getEditInstruction(), job.getEditInstruction() == null ? null : job.getFlowMediaUrl(),
                project.getGenerateAudio());
    }

    /**
     * File của ảnh trong kho. Người dùng chuyển thư mục phim đi chỗ khác thì đường dẫn cũ không còn: tìm file cùng tên
     * trong Folder Ảnh hiện tại của phim (kể cả thư mục con) và sửa luôn đường dẫn trong kho.
     */
    private String locate(ProjectAssetEntity a, ProjectEntity project) {
        Path p = Path.of(a.getFilePath());
        if (Files.isRegularFile(p) || project.getImageDir() == null || project.getImageDir().isBlank()) {
            return a.getFilePath();
        }
        Path dir = Path.of(project.getImageDir());
        Path direct = dir.resolve(p.getFileName());
        Path found = Files.isRegularFile(direct) ? direct : null;
        if (found == null && Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir, 3)) {
                found = walk.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().equalsIgnoreCase(p.getFileName().toString()))
                        .findFirst().orElse(null);
            } catch (IOException ignored) {
                // không đọc được thư mục: giữ đường dẫn cũ, driver sẽ báo thiếu file
            }
        }
        if (found == null) {
            return a.getFilePath();
        }
        a.setFilePath(found.toString());   // đang trong transaction của claim: lưu luôn
        return found.toString();
    }
}
