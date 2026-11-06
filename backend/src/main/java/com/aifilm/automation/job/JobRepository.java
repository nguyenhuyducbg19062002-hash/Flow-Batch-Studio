package com.aifilm.automation.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    /**
     * Job PENDING kế tiếp của một project: ảnh trước video; trong mỗi loại theo thứ tự file Excel rồi thứ tự dòng.
     * Một cảnh (ảnh PANEL hay video) chỉ được chọn khi MỌI Reference ID của nó đã có trong kho ảnh (project_assets):
     * ảnh có sẵn người dùng đưa vào, ảnh thêm bằng "+", hoặc ảnh do app tạo đã được duyệt.
     * imagesOnly = 1: chỉ chọn ảnh (phim bật "duyệt xong hết ảnh rồi mới tạo video" và vẫn còn ảnh chưa xong).
     * Cảnh người dùng chọn bằng "Chạy cảnh này" (run_priority > 0) đi trước, và không bị chặn bởi imagesOnly
     * (nhưng vẫn phải đủ ảnh reference).
     */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status = 'PENDING' AND j.id NOT IN (:skip)
              AND (:imagesOnly = 0 OR p.kind = 'IMAGE' OR COALESCE(j.run_priority, 0) > 0)
              AND (:selectedOnly = 0 OR COALESCE(j.run_priority, 0) > 0)
              AND (:skipMissing = 0 OR p.kind = 'VIDEO' OR COALESCE(j.run_priority, 0) > 0)
              AND (p.kind <> 'VIDEO' OR EXISTS (SELECT 1 FROM prompt_references r0 WHERE r0.video_prompt_id = p.id))
              AND ((:skipMissing = 1 AND p.kind = 'VIDEO') OR NOT EXISTS (
                  SELECT 1 FROM prompt_references r
                  WHERE r.video_prompt_id = p.id
                    AND NOT EXISTS (SELECT 1 FROM project_assets a
                                    WHERE a.project_id = p.project_id AND a.external_id = r.ref_external_id)))
            ORDER BY COALESCE(j.run_priority, 0) DESC, CASE p.kind WHEN 'IMAGE' THEN 0 ELSE 1 END,
                     COALESCE(p.source_order, 0), p.seq_no
            LIMIT 1""", nativeQuery = true)
    Optional<JobEntity> findNextRunnable(@Param("projectId") long projectId, @Param("imagesOnly") int imagesOnly,
                                         @Param("selectedOnly") int selectedOnly, @Param("skipMissing") int skipMissing,
                                         @Param("skip") Collection<Long> skip);

    /**
     * Việc kế tiếp của một phim khi ảnh và video chạy RIÊNG (images / videos = 1: phần đó đang bật).
     * Mục được làm lại / chạy riêng (run_priority > 0) đi trước, rồi theo đúng thứ tự file (= số thứ tự trong thư mục chờ duyệt).
     *  - Ảnh tag @ (ảnh gốc của ảnh đổi đồ, ảnh tham chiếu của video) phải đã được duyệt và "Đổi tên" sang Folder Ảnh
     *    (hoặc là ảnh có sẵn): file mang đúng tên ID, Flow giữ tên file nên mới tìm được để gắn chip @ID.
     *    Ảnh còn trong thư mục chờ duyệt (1.png, 2.png…) KHÔNG được dùng.
     *  - Video: phải có ảnh tham chiếu.
     * selectedOnly = 1: chỉ các mục được chọn chạy riêng.
     */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status = 'PENDING' AND j.id NOT IN (:skip)
              AND ((p.kind = 'IMAGE' AND :images = 1) OR (p.kind = 'VIDEO' AND :videos = 1))
              AND (:selectedOnly = 0 OR COALESCE(j.run_priority, 0) > 0)
              AND (p.kind <> 'VIDEO' OR EXISTS (SELECT 1 FROM prompt_references r0 WHERE r0.video_prompt_id = p.id))
              AND NOT EXISTS (
                  SELECT 1 FROM prompt_references r
                  WHERE r.video_prompt_id = p.id
                    AND NOT EXISTS (SELECT 1 FROM project_assets a
                                    WHERE a.project_id = p.project_id AND a.external_id = r.ref_external_id
                                      AND a.source <> 'REVIEW'))
            ORDER BY COALESCE(j.run_priority, 0) DESC, CASE p.kind WHEN 'IMAGE' THEN 0 ELSE 1 END,
                     COALESCE(p.source_order, 0), p.seq_no
            LIMIT 1""", nativeQuery = true)
    Optional<JobEntity> findNextBatch(@Param("projectId") long projectId, @Param("images") int images,
                                      @Param("videos") int videos, @Param("selectedOnly") int selectedOnly,
                                      @Param("skip") Collection<Long> skip);

    long countByProjectIdAndKindAndStatusIn(Long projectId, com.aifilm.automation.prompt.PromptKind kind,
                                            java.util.Collection<JobStatus> statuses);

    /** Các video PENDING của phim theo thứ tự cảnh (thứ tự file Excel rồi thứ tự dòng). Dùng cho chế độ chạy theo cảnh. */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status = 'PENDING' AND p.kind = 'VIDEO'
            ORDER BY COALESCE(p.source_order, 0), p.seq_no""", nativeQuery = true)
    List<JobEntity> findPendingVideoJobsInOrder(@Param("projectId") long projectId);

    /** Video chưa xong (đang chờ hoặc đang tạo) theo thứ tự cảnh: dùng để chạy lần lượt từng cảnh, xong cảnh này mới sang cảnh sau. */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status IN ('PENDING', 'GENERATING') AND p.kind = 'VIDEO'
            ORDER BY COALESCE(p.source_order, 0), p.seq_no""", nativeQuery = true)
    List<JobEntity> findOpenVideoJobsInOrder(@Param("projectId") long projectId);

    /** Như trên nhưng chỉ video người dùng đã chọn chạy (run_priority > 0), cảnh bấm trước chạy trước. */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status IN ('PENDING', 'GENERATING') AND p.kind = 'VIDEO'
              AND COALESCE(j.run_priority, 0) > 0
            ORDER BY j.run_priority DESC, COALESCE(p.source_order, 0), p.seq_no""", nativeQuery = true)
    List<JobEntity> findOpenSelectedVideoJobsInOrder(@Param("projectId") long projectId);

    /** Video PENDING mà người dùng đã chọn chạy (run_priority > 0), cảnh bấm sau chạy sau. Dùng để tìm ảnh còn thiếu của chúng. */
    @Query(value = """
            SELECT j.* FROM jobs j
            JOIN prompts p ON p.id = j.prompt_id
            WHERE j.project_id = :projectId AND j.status = 'PENDING' AND p.kind = 'VIDEO' AND COALESCE(j.run_priority, 0) > 0
            ORDER BY j.run_priority DESC, COALESCE(p.source_order, 0), p.seq_no""", nativeQuery = true)
    List<JobEntity> findSelectedPendingVideoJobsInOrder(@Param("projectId") long projectId);

    @Query("select count(j) from JobEntity j where j.projectId = :projectId and j.runPriority > 0 "
            + "and j.status in (com.aifilm.automation.job.JobStatus.PENDING, com.aifilm.automation.job.JobStatus.GENERATING)")
    long countSelectedLeft(@Param("projectId") Long projectId);

    long countByProjectIdAndStatus(Long projectId, JobStatus status);

    /** Chạy song song: số job đang tạo của một tài khoản (nguồn sự thật cho việc tài khoản còn bận hay không). */
    long countByAccountIdAndStatus(Long accountId, JobStatus status);

    long countByAccountIdAndStatusAndIdNot(Long accountId, JobStatus status, Long id);

    List<JobEntity> findByAccountIdAndStatus(Long accountId, JobStatus status);

    @Query("select coalesce(max(j.runPriority), 0) from JobEntity j where j.projectId = :projectId")
    int maxRunPriority(@Param("projectId") Long projectId);

    /** Số ảnh của phim chưa xong (bỏ qua ảnh đã hủy: người dùng chủ động bỏ thì không chặn cả phim). */
    long countByProjectIdAndKindAndStatus(Long projectId, com.aifilm.automation.prompt.PromptKind kind, JobStatus status);

    long countByProjectIdAndKindAndStatusNotIn(Long projectId, com.aifilm.automation.prompt.PromptKind kind,
                                               java.util.Collection<JobStatus> statuses);

    List<JobEntity> findByProjectId(Long projectId);

    List<JobEntity> findByProjectIdAndStatus(Long projectId, JobStatus status);

    List<JobEntity> findByStatus(JobStatus status);

    List<JobEntity> findByStatusAndHeartbeatAtBefore(JobStatus status, LocalDateTime limit);

    List<JobEntity> findByStatusAndWorkerId(JobStatus status, String workerId);

    boolean existsByAccountIdAndStatusIn(Long accountId, Collection<JobStatus> statuses);

    Optional<JobEntity> findByPromptId(Long promptId);

    List<JobEntity> findByPromptIdIn(Collection<Long> promptIds);

    @Query("""
            select j from JobEntity j, com.aifilm.automation.prompt.PromptEntity p
            where p.id = j.promptId and p.projectId = :projectId
              and p.kind = com.aifilm.automation.prompt.PromptKind.IMAGE and p.externalId = :externalId""")
    Optional<JobEntity> findImageJob(@Param("projectId") Long projectId, @Param("externalId") String externalId);

    @Query("select j.status, count(j) from JobEntity j where j.projectId = :projectId group by j.status")
    List<Object[]> countByStatus(@Param("projectId") Long projectId);

    /**
     * Heartbeat: cập nhật thẳng bằng câu UPDATE (không tăng @Version) để không đụng độ optimistic lock với
     * lần báo kết quả của cùng worker chạy song song trên luồng khác.
     */
    @Modifying
    @Query("""
            update JobEntity j set j.heartbeatAt = :now
            where j.id = :id and j.workerId = :workerId and j.status = com.aifilm.automation.job.JobStatus.GENERATING""")
    int touchHeartbeat(@Param("id") Long id, @Param("workerId") String workerId, @Param("now") LocalDateTime now);

    @Query("select j.cancelRequested from JobEntity j where j.id = :id")
    Optional<Boolean> isCancelRequested(@Param("id") Long id);

    @Modifying
    @Query("delete from JobEntity j where j.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
