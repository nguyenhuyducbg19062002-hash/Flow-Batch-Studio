package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final JobRepository jobs;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final AccountRepository accounts;
    private final JobEventRepository events;
    private final ProjectAssetRepository assets;
    private final com.aifilm.automation.project.ProjectRepository projects;

    /**
     * Danh sách job của project theo đúng thứ tự file. full=false bỏ nội dung prompt (một phim panel có 1.200 cảnh,
     * prompt dài tới 14.000 ký tự: kèm prompt thì mỗi lần làm mới nặng vài MB); giao diện lấy prompt khi mở một cảnh.
     */
    @Transactional(readOnly = true)
    public List<JobView> list(long projectId, JobStatus status, boolean full) {
        List<JobEntity> list = status == null ? jobs.findByProjectId(projectId) : jobs.findByProjectIdAndStatus(projectId, status);
        return views(list, full);
    }

    @Transactional(readOnly = true)
    public JobView view(long jobId) {
        return views(List.of(jobs.findById(jobId).orElseThrow()), true).stream().findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Path> previewPath(long jobId) {
        return jobs.findById(jobId).map(JobEntity::getPreviewPath).map(Path::of);
    }

    /** Lịch sử chuyển trạng thái + các bản prompt của một job. */
    @Transactional(readOnly = true)
    public Map<String, Object> history(long jobId) {
        JobEntity job = jobs.findById(jobId).orElseThrow();
        List<Map<String, Object>> evts = new ArrayList<>();
        for (JobEventEntity e : events.findByJobIdOrderByIdDesc(jobId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.getCreatedAt());
            m.put("from", e.getFromStatus());
            m.put("to", e.getToStatus());
            m.put("message", e.getMessage());
            evts.add(m);
        }
        List<Map<String, Object>> revs = new ArrayList<>();
        for (PromptRevisionEntity r : revisions.findByPromptIdOrderByRevisionNoDesc(job.getPromptId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revisionNo", r.getRevisionNo());
            m.put("source", r.getSource());
            m.put("note", r.getNote());
            m.put("createdAt", r.getCreatedAt());
            m.put("promptText", r.getPromptText());
            revs.add(m);
        }
        return Map.of("events", evts, "revisions", revs);
    }

    static String previewType(String path) {
        if (path == null) {
            return null;
        }
        String ext = ReviewService.extensionOf(path);
        return switch (ext) {
            case ".mp4", ".webm", ".mov" -> "video";
            default -> "image";
        };
    }

    private List<JobView> views(List<JobEntity> list, boolean full) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, PromptEntity> promptById = prompts.findAllById(list.stream().map(JobEntity::getPromptId).toList())
                .stream().collect(Collectors.toMap(PromptEntity::getId, Function.identity()));
        Map<Long, PromptRevisionEntity> revById = revisions.findAllById(promptById.values().stream()
                        .map(PromptEntity::getCurrentRevisionId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(PromptRevisionEntity::getId, Function.identity()));
        Map<Long, List<PromptReferenceEntity>> refsByPrompt = references.findByVideoPromptIdIn(promptById.keySet()).stream()
                .sorted(Comparator.comparingInt(PromptReferenceEntity::getOrdinal))
                .collect(Collectors.groupingBy(PromptReferenceEntity::getVideoPromptId, LinkedHashMap::new, Collectors.toList()));
        Set<String> inStore = new HashSet<>();   // ảnh đã đổi tên sang Folder Ảnh / có sẵn
        Set<String> inReview = new HashSet<>();  // ảnh còn trong thư mục chờ duyệt (dùng được cho ảnh đổi đồ, chưa cho video)
        // ảnh mà app sẽ tạo (có prompt ở tab Tạo ảnh): ID → trạng thái job tạo ảnh đó
        Map<String, JobStatus> producerStatus = new HashMap<>();
        Map<Long, Integer> numberByPrompt = new HashMap<>();
        Map<Long, String> finalNameByPrompt = new HashMap<>();
        promptById.values().stream().map(PromptEntity::getProjectId).distinct().forEach(pid -> {
            assets.findByProjectId(pid).forEach(a -> (ProjectAssetEntity.SOURCE_REVIEW.equals(a.getSource()) ? inReview : inStore)
                    .add(a.getExternalId()));
            List<PromptEntity> all = prompts.findByProjectId(pid);
            String code = projects.findById(pid).map(com.aifilm.automation.project.ProjectEntity::getCode).orElse("phim");
            List<PromptEntity> videos = all.stream().filter(p -> p.getKind() == PromptKind.VIDEO).toList();
            for (PromptKind k : PromptKind.values()) {
                numberByPrompt.putAll(BatchNumbering.numbers(all, k));
            }
            for (PromptEntity p : all) {
                finalNameByPrompt.put(p.getId(), p.getKind() == PromptKind.IMAGE ? p.getExternalId()
                        : VideoNaming.nameFor(p, videos).orElse(code + "_" + p.getExternalId()));
            }
            Map<Long, String> imageIdByPrompt = new HashMap<>();
            all.stream().filter(p -> p.getKind() == PromptKind.IMAGE)
                    .forEach(p -> imageIdByPrompt.put(p.getId(), p.getExternalId()));
            jobs.findByPromptIdIn(imageIdByPrompt.keySet())
                    .forEach(j -> producerStatus.put(imageIdByPrompt.get(j.getPromptId()), j.getStatus()));
        });
        Map<Long, String> emailById = accounts.findAll().stream()
                .collect(Collectors.toMap(AccountEntity::getId, AccountEntity::getEmail));

        return list.stream()
                .filter(j -> promptById.containsKey(j.getPromptId()))   // prompt đã bị xóa: bỏ qua job mồ côi
                .map(j -> {
                    PromptEntity p = promptById.get(j.getPromptId());
                    PromptRevisionEntity rev = revById.get(p.getCurrentRevisionId());
                    List<PromptReferenceEntity> refs = refsByPrompt.getOrDefault(p.getId(), List.of());
                    return new JobView(
                            j.getId(), p.getId(), p.getKind().name(), p.getExternalId(), p.getSeqNo(), j.getStatus(),
                            !full || rev == null ? null : rev.getPromptText(), rev == null ? 0 : rev.getRevisionNo(),
                            p.getSummaryVi(), refs.stream().map(PromptReferenceEntity::getRefExternalId).toList(),
                            refs.stream().map(r -> new JobView.RefView(r.getRefExternalId(), inStore.contains(r.getRefExternalId())
                                            || (p.getKind() == PromptKind.IMAGE && inReview.contains(r.getRefExternalId())), r.isUserAdded(),
                                    Optional.ofNullable(producerStatus.get(r.getRefExternalId())).map(Enum::name).orElse(null))).toList(),
                            p.getSourceName(), p.getSourceOrder() == null ? 0 : p.getSourceOrder(),
                            p.getKind() == PromptKind.IMAGE && inStore.contains(p.getExternalId()),
                            j.getAccountId() == null ? null : emailById.get(j.getAccountId()),
                            j.getAttempt(), j.getErrorCode(), j.getErrorMessage(), j.getPreviewPath() != null,
                            previewType(j.getPreviewPath()),
                            j.getFinalPath(), j.getRemoteRef(), j.isCancelRequested(),
                            p.getKind() == PromptKind.VIDEO && j.getFlowMediaUrl() != null && j.getFlowAccountId() != null,
                            j.getEditInstruction(), j.getGeneratedAt(), j.getUpdatedAt(),
                            numberByPrompt.getOrDefault(p.getId(), p.getSeqNo()),
                            j.getReviewPath() == null ? null : Path.of(j.getReviewPath()).getFileName().toString(),
                            finalNameByPrompt.get(p.getId()));
                })
                // đúng thứ tự trong file: ảnh trước video, rồi thứ tự file Excel, rồi thứ tự dòng
                .sorted(Comparator.comparing((JobView v) -> v.kind().equals("IMAGE") ? 0 : 1)
                        .thenComparingInt(JobView::sourceOrder).thenComparingInt(JobView::seqNo))
                .toList();
    }
}
