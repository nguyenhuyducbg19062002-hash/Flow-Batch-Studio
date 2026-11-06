package com.aifilm.automation.project;

import com.aifilm.automation.job.JobEntity;
import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobStatus;
import com.aifilm.automation.job.JobTransitions;
import com.aifilm.automation.prompt.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Nạp file Excel prompt của Omni-Flash-Pipeline. Đọc theo TÊN cột, nhận cả hai định dạng Omni đã xuất:
 *
 *  Ảnh (kind IMAGE)
 *    - reference-prompts.xlsx : "STT" | "Reference ID" | "Prompt tạo reference" | (Chú thích ID nhân vật)
 *    - prompt-anh-panel.xlsx  : "Ảnh" | "Nội dung prompt ảnh" | "Reference IDs" | "Số chip" | "Ghi chú"
 *      (mỗi ảnh PANEL được tạo TỪ các ảnh gốc ghi ở cột Reference IDs)
 *  Video (kind VIDEO)
 *    - all-video-prompts.xlsx : "STT segment" | "Nội dung prompt của segment" | "Reference IDs"
 *                               | ("Ghi chú" hoặc "Tóm tắt segment (tiếng Việt)") | (Số chip)
 *
 * Mỗi file được nạp riêng (nút "Thêm Excel ảnh" / "Thêm Excel video"), giữ đúng thứ tự dòng trong file.
 * Reference ID không có prompt tạo ảnh trong phim là "ảnh gốc": người dùng đưa vào bằng "Thêm ảnh có sẵn".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmniImportService {

    /** Tên file Omni hay dùng, để nút "Nạp từ thư mục Omni" tự tìm. */
    static final List<String> IMAGE_FILES = List.of("reference-prompts.xlsx", "prompt-anh-panel.xlsx");
    static final List<String> VIDEO_FILES = List.of("all-video-prompts.xlsx");

    static final List<String> IMAGE_ID_COLS = List.of("Reference ID", "Ảnh");
    static final List<String> IMAGE_PROMPT_COLS = List.of("Prompt tạo reference", "Nội dung prompt ảnh");
    static final List<String> VIDEO_STT_COLS = List.of("STT segment");
    static final List<String> VIDEO_PROMPT_COLS = List.of("Nội dung prompt của segment");
    static final List<String> REFS_COLS = List.of("Reference IDs");
    static final List<String> NOTE_COLS = List.of("Ghi chú", "Tóm tắt segment (tiếng Việt)");
    static final List<String> STT_COLS = List.of("STT");

    /** ID dùng làm tên file trên Windows: chỉ chữ, số, gạch nối, gạch dưới. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$");
    /** Nhập tay ảnh: "ID: prompt" / "ID | prompt", ID gồm chữ HOA / số, có ít nhất một gạch nối. */
    private static final Pattern MANUAL_ID = Pattern.compile("^([A-Z0-9][A-Z0-9_]*(?:-[A-Z0-9_]+)+)\\s*[:|\\t]\\s*(.+)$");

    private final ProjectRepository projects;
    private final PromptRepository prompts;
    private final PromptRevisionRepository revisions;
    private final PromptReferenceRepository references;
    private final ProjectAssetRepository assets;
    private final JobRepository jobs;
    private final JobTransitions transitions;
    private final TransactionTemplate tx;

    /**
     * tagMismatch: số dòng mà cột "Reference IDs" khác danh sách ảnh khai báo trong prompt ("@ID =" ở IMAGE KEY);
     * mismatchExamples: vài ví dụ để người dùng kiểm tra. App dùng cột Reference IDs khi có, tag trong prompt khi không.
     */
    public record ImportResult(String file, String kind, int rows, int added, int updated, int unchanged, int requeued,
                               List<String> missingRefs, int refsFromTags, int tagMismatch, List<String> mismatchExamples) {}

    public record FolderResult(List<ImportResult> files, AssetService.FolderImportResult assets, List<String> skipped) {}

    record Line(int order, String id, String prompt, List<String> refs, String note) {}

    /** Thư mục không có file prompt nào để nạp (lúc tạo phim: không phải lỗi). */
    public static class NoPromptFileException extends IllegalArgumentException {
        NoPromptFileException(String message) {
            super(message);
        }
    }

    private final AssetService assetService;

    /** Nạp một file Excel (tải lên từ giao diện). kind = IMAGE hoặc VIDEO; sai loại file thì báo lỗi rõ ràng. */
    public ImportResult importFile(long projectId, PromptKind kind, String fileName, InputStream in) throws IOException {
        List<Line> rows = kind == PromptKind.IMAGE ? readImages(in, fileName) : readVideos(in, fileName);
        return importLines(projectId, kind, fileName, rows);
    }

    /**
     * Nạp file .txt: mỗi dòng (không rỗng) là một prompt.
     *  - Video: STT lấy từ đầu dòng ("STT - 12. …" / "12. …"), không có thì theo số dòng.
     *  - Ảnh: tên file có "panel" → PANEL-<STT>; còn lại lấy ID theo reference-id-list.txt cùng thư mục.
     * Ảnh cần gắn đọc từ khai báo "@ID =" trong prompt.
     */
    public ImportResult importTxt(long projectId, PromptKind kind, Path file) throws IOException {
        return importLines(projectId, kind, file.getFileName().toString(), txtLines(kind, file));
    }

    /** Các dòng prompt của file .txt. Ảnh gốc: ID lấy theo số thứ tự trong reference-id-list.txt, số dòng hai file phải khớp. */
    static List<Line> txtLines(PromptKind kind, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8).stream()
                .map(s -> s.replace("FEFF", "").trim()).filter(s -> !s.isEmpty()).toList();
        String name = file.getFileName().toString();
        boolean panel = name.toLowerCase(Locale.ROOT).contains("panel");
        Map<Integer, String> idList = Map.of();
        if (kind == PromptKind.IMAGE && !panel) {
            idList = readIdList(file.resolveSibling("reference-id-list.txt"));
            if (idList.isEmpty()) {
                throw new IllegalArgumentException(name + ": không biết ID ảnh. Cần file reference-id-list.txt cùng thư mục, hoặc dùng file Excel.");
            }
            if (idList.size() != lines.size()) {
                throw new IllegalArgumentException(name + " có " + lines.size() + " prompt nhưng reference-id-list.txt có " + idList.size() + " ID: hai file phải khớp nhau");
            }
        }
        List<Line> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String prompt = lines.get(i);
            Integer n = PromptTags.leadingNumber(prompt);
            int order = n != null ? n : i + 1;
            String id;
            if (kind == PromptKind.VIDEO) {
                id = String.valueOf(order);
            } else if (panel) {
                id = "PANEL-" + order;
            } else {
                id = idList.get(order);
                if (id == null) {
                    throw new IllegalArgumentException(name + " dòng " + (i + 1) + ": reference-id-list.txt không có ID số " + order);
                }
            }
            rows.add(new Line(order, id, prompt, List.of(), null));
        }
        return rows;
    }

    /**
     * "Nhập thủ công": dán prompt, mỗi dòng một cảnh.
     *  - Video: STT lấy ở đầu dòng nếu có, không thì nối tiếp sau cảnh cuối cùng của phim.
     *  - Ảnh: "ID: prompt" hoặc "ID | prompt" (ID kiểu CHAR-CALEB-1); không ghi ID thì tự đặt ANH-1, ANH-2…
     */
    public ImportResult importText(long projectId, PromptKind kind, String text) {
        return importLines(projectId, kind, "Nhập tay", textLines(projectId, kind, text));
    }

    List<Line> textLines(long projectId, PromptKind kind, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chưa nhập prompt nào");
        }
        // Có dòng trống ngăn cách: mỗi khối là một prompt (prompt dài viết nhiều dòng). Không có: mỗi dòng một prompt.
        java.util.stream.Stream<String> parts = text.strip().matches("(?s).*\\R[ \\t]*\\R.*")
                ? java.util.Arrays.stream(text.strip().split("\\R[ \\t]*\\R(?:[ \\t]*\\R)*")).map(b -> b.strip().replaceAll("[ \\t]*\\R[ \\t]*", " "))
                : text.lines();
        List<String> lines = parts.map(String::trim).filter(s -> !s.isEmpty()).toList();
        int next = maxSeq(projectId, kind) + 1;
        int autoImage = 1;
        Set<String> existing = new HashSet<>();
        prompts.findByProjectId(projectId).forEach(p -> existing.add(p.getExternalId()));
        // STT ghi sẵn ở đầu các dòng: dòng không ghi STT phải lấy số khác, không trùng
        Set<Integer> used = new HashSet<>();
        if (kind == PromptKind.VIDEO) {
            lines.forEach(l -> Optional.ofNullable(PromptTags.leadingNumber(l)).ifPresent(used::add));
        }
        List<Line> rows = new ArrayList<>();
        for (String line : lines) {
            if (kind == PromptKind.VIDEO) {
                Integer n = PromptTags.leadingNumber(line);
                int order;
                if (n != null) {
                    order = n;
                } else {
                    while (used.contains(next)) {
                        next++;
                    }
                    order = next++;
                    used.add(order);
                }
                rows.add(new Line(order, String.valueOf(order), line, List.of(), null));
            } else {
                // ID kiểu CHAR-CALEB-1 (chữ HOA / số, có gạch nối); "Wide-angle: …" là câu prompt, không phải ID
                java.util.regex.Matcher m = MANUAL_ID.matcher(line);
                String id;
                String prompt;
                if (m.matches()) {
                    id = m.group(1);
                    prompt = m.group(2).trim();
                } else {
                    do {
                        id = "ANH-" + autoImage++;
                    } while (existing.contains(id));
                    prompt = line;
                }
                existing.add(id);
                rows.add(new Line(next++, id, prompt, List.of(), null));
            }
        }
        return rows;
    }

    /**
     * "Nạp cả thư mục phim" (ví dụ D:\Dev\Tập 5. Hà - Cupid\): tự tìm mọi file prompt và ảnh có sẵn bên trong.
     *  - .xlsx: tự nhận là file ảnh hay video theo tên cột; ảnh gốc (reference-prompts) nạp trước ảnh panel.
     *  - .txt (FINAL_VIDEO_PROMPTS.txt, PROMPT-ANH-PANEL.txt, reference-prompts.txt): chỉ dùng khi không có Excel cùng loại.
     *  - Ảnh có tên trùng ID (ở mọi thư mục con, ví dụ "Ảnh tham chiếu/CHAR-CALEB-1.png", "Panels/PANEL-3.png").
     */
    public FolderResult importFolder(long projectId, String folder) throws IOException {
        Path dir = Path.of(folder);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Thư mục không tồn tại: " + folder);
        }
        List<Path> xlsx;
        List<Path> txt;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            List<Path> files = s.filter(Files::isRegularFile).sorted().toList();
            xlsx = files.stream().filter(p -> lower(p).endsWith(".xlsx") && !p.getFileName().toString().startsWith("~$")).toList();
            // .txt chỉ nhận đúng các tên Omni quen thuộc (ghi chú, kịch bản .txt khác không phải prompt)
            txt = files.stream().filter(p -> ProjectFolderScanner.PROMPT_FILES.contains(lower(p)))
                    .sorted(Comparator.comparing((Path p) -> lower(p).contains("reference") ? 0 : 1).thenComparing(Path::toString)).toList();
        }
        List<Path> imageXlsx = new ArrayList<>();
        List<Path> videoXlsx = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path p : xlsx) {
            PromptKind k = detectKind(p);
            if (k == PromptKind.IMAGE) imageXlsx.add(p);
            else if (k == PromptKind.VIDEO) videoXlsx.add(p);
            else skipped.add(p.getFileName() + " (không phải file prompt)");
        }
        // ảnh gốc trước ảnh panel: panel được tạo từ ảnh gốc
        imageXlsx.sort(Comparator.comparing((Path p) -> lower(p).contains("reference") ? 0 : 1).thenComparing(Path::toString));

        // lấy trước khi nạp prompt: prompt đổi thì job về hàng đợi, nhưng file cũ của nó vẫn không phải "ảnh có sẵn"
        Set<Path> generated = assetService.generatedFiles(projectId);
        List<ImportResult> results = new ArrayList<>();
        for (Path p : imageXlsx) {
            try (InputStream in = Files.newInputStream(p)) {
                results.add(importFile(projectId, PromptKind.IMAGE, p.getFileName().toString(), in));
            }
        }
        if (imageXlsx.isEmpty()) {
            for (Path p : txt) {
                if (!lower(p).contains("video")) {
                    results.add(importTxt(projectId, PromptKind.IMAGE, p));
                }
            }
        }
        for (Path p : videoXlsx) {
            try (InputStream in = Files.newInputStream(p)) {
                results.add(importFile(projectId, PromptKind.VIDEO, p.getFileName().toString(), in));
            }
        }
        if (videoXlsx.isEmpty()) {
            for (Path p : txt) {
                if (lower(p).contains("video")) {
                    results.add(importTxt(projectId, PromptKind.VIDEO, p));
                }
            }
        }
        if (results.isEmpty()) {
            throw new NoPromptFileException("Không thấy file prompt nào (Excel .xlsx hoặc .txt của Omni) trong " + dir);
        }
        AssetService.FolderImportResult a = assetService.importFolder(projectId, folder, generated);
        return new FolderResult(results, a, skipped);
    }

    /**
     * "Ảnh → Video theo thư mục con": mỗi thư mục con của {@code folder} là một nhóm ảnh; prompt nằm trong
     * file "[tên-thư-mục].txt" (trong thư mục con hoặc cùng cấp với nó). 1 dòng prompt = dùng chung, nhiều dòng = theo
     * thứ tự ảnh.
     *  - grouped = true ("chế độ tham chiếu"): cả nhóm ảnh thành MỘT cảnh video, ảnh upload theo thứ tự tên file.
     *  - grouped = false: mỗi ảnh thành một video riêng.
     * Ảnh được chép vào Folder Ảnh với ID "<thư-mục>-<tên-ảnh>" để không trùng giữa các nhóm.
     */
    public ImportResult importImageGroups(long projectId, String folder, boolean grouped) throws IOException {
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        Path root = Path.of(folder);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Thư mục không tồn tại: " + folder);
        }
        List<Path> groups;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            groups = s.filter(Files::isDirectory).sorted(Comparator.comparing(p -> naturalKey(p.getFileName().toString()))).toList();
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException(folder + " không có thư mục con nào (mỗi thư mục con = một nhóm ảnh)");
        }
        String sourceName = "Ảnh→Video: " + root.getFileName();
        // chạy lại cùng thư mục: dùng lại STT các cảnh đã nạp từ thư mục này (theo thứ tự), không nhân đôi cảnh
        Iterator<Integer> reuse = prompts.findByProjectId(projectId).stream()
                .filter(p -> p.getKind() == PromptKind.VIDEO && sourceName.equals(p.getSourceName()))
                .map(PromptEntity::getSeqNo).sorted().iterator();
        int next = maxSeq(projectId, PromptKind.VIDEO) + 1;
        List<Line> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (Path g : groups) {
            String groupName = g.getFileName().toString();
            List<Path> images;
            try (java.util.stream.Stream<Path> s = Files.list(g)) {
                images = s.filter(Files::isRegularFile).filter(p -> AssetService.IMAGE_EXT.contains(AssetService.ext(p)))
                        .sorted(Comparator.comparing(p -> naturalKey(p.getFileName().toString()))).toList();
            }
            List<String> groupPrompts = readGroupPrompts(g, groupName);
            if (images.isEmpty() || groupPrompts.isEmpty()) {
                problems.add(groupName + (images.isEmpty() ? ": không có ảnh" : ": thiếu file " + groupName + ".txt (prompt)"));
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (Path img : images) {
                String base = AssetService.sanitizeId(groupName + "-" + AssetService.baseName(img));
                String id = base;
                for (int k = 2; !usedIds.add(id); k++) {
                    id = base + "-" + k;   // 1.png + 1.jpg (hay tên làm sạch trùng nhau): không ghi đè nhau
                }
                Path target = assetService.copyIntoImageDir(project, img, id);
                assetService.upsert(projectId, id, target, ProjectAssetEntity.SOURCE_USER);
                ids.add(id);
            }
            String note = "Ảnh → Video: " + groupName;
            if (grouped) {
                int order = reuse.hasNext() ? reuse.next() : next++;
                rows.add(new Line(order, String.valueOf(order), groupPrompts.get(0), ids, note));
            } else {
                for (int i = 0; i < ids.size(); i++) {
                    String prompt = groupPrompts.size() == 1 ? groupPrompts.get(0) : groupPrompts.get(Math.min(i, groupPrompts.size() - 1));
                    int order = reuse.hasNext() ? reuse.next() : next++;
                    rows.add(new Line(order, String.valueOf(order), prompt, List.of(ids.get(i)), note + " / " + images.get(i).getFileName()));
                }
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Không nhóm nào dùng được:\n" + String.join("\n", problems));
        }
        if (!problems.isEmpty()) {
            log.warn("Ảnh → Video {}: bỏ qua {} nhóm: {}", folder, problems.size(), problems);
        }
        return importLines(projectId, PromptKind.VIDEO, sourceName, rows);
    }

    private static List<String> readGroupPrompts(Path group, String name) throws IOException {
        for (Path candidate : List.of(group.resolve(name + ".txt"), group.resolveSibling(name + ".txt"))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readAllLines(candidate, java.nio.charset.StandardCharsets.UTF_8).stream()
                        .map(s -> s.replace("\uFEFF", "").trim()).filter(s -> !s.isEmpty()).toList();
            }
        }
        return List.of();
    }

    /** Sắp "2.png" trước "10.png", "canh 2" trước "canh 10". */
    private static String naturalKey(String s) {
        java.util.regex.Matcher m = Pattern.compile("\\d+").matcher(s.toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String digits = m.group().replaceFirst("^0+(?=.)", "");
            out.append(s.toLowerCase(Locale.ROOT), last, m.start()).append("0".repeat(Math.max(0, 20 - digits.length()))).append(digits);
            last = m.end();
        }
        return out.append(s.toLowerCase(Locale.ROOT).substring(last)).toString();
    }

    /** Đường xử lý chung cho Excel / .txt / nhập tay / thư mục ảnh: điền ảnh từ tag, kiểm tra, ghi DB. */
    ImportResult importLines(long projectId, PromptKind kind, String sourceName, List<Line> input) {
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        List<Line> rows = new ArrayList<>();
        int fromTags = 0;
        int mismatch = 0;
        List<String> examples = new ArrayList<>();
        Set<String> knownImages = new HashSet<>();   // ảnh đã biết của phim: để nhận ra ID viết trần trong prompt ảnh biến thể
        if (kind == PromptKind.IMAGE) {
            input.forEach(l -> knownImages.add(l.id()));
            prompts.findByProjectId(projectId).stream().filter(p -> p.getKind() == PromptKind.IMAGE)
                    .forEach(p -> knownImages.add(p.getExternalId()));
            assets.findByProjectId(projectId).forEach(a -> knownImages.add(a.getExternalId()));
        }
        for (Line r : input) {
            List<String> tags = PromptTags.imageIds(r.prompt());
            if (r.refs().isEmpty() && tags.isEmpty() && kind == PromptKind.IMAGE) {
                List<String> bare = PromptTags.bareKnownIds(r.prompt(), knownImages, r.id());
                if (!bare.isEmpty()) {
                    rows.add(new Line(r.order(), r.id(), r.prompt(), bare, r.note()));
                    fromTags++;
                    continue;
                }
            }
            if (r.refs().isEmpty() && !tags.isEmpty()) {
                rows.add(new Line(r.order(), r.id(), r.prompt(), tags, r.note()));
                fromTags++;
            } else {
                if (!tags.isEmpty() && !r.refs().equals(tags)) {
                    mismatch++;
                    if (examples.size() < 5) {
                        examples.add((kind == PromptKind.VIDEO ? "STT " : "") + r.id() + ": cột Reference IDs = " + String.join("; ", r.refs())
                                + " | prompt khai báo = " + String.join("; ", tags));
                    }
                }
                rows.add(r);
            }
        }
        validate(rows, sourceName);
        final int tagCount = fromTags;
        final int mismatchCount = mismatch;
        ImportResult r = tx.execute(status -> write(project, kind, sourceName, rows, tagCount, mismatchCount, examples));
        log.info("Import {} {}: {} dòng (mới {}, đổi {}, giữ {}), ảnh từ tag {}, lệch {}, thiếu {} ảnh gốc",
                project.getCode(), sourceName, r.rows(), r.added(), r.updated(), r.unchanged(), fromTags, mismatch, r.missingRefs().size());
        return r;
    }

    /** "Nạp lại tất cả từ thư mục Omni": thư mục nguồn của phim. */
    public List<ImportResult> importProject(long projectId) throws IOException {
        ProjectEntity project = projects.findById(projectId).orElseThrow();
        if (project.getSourceDir() == null || project.getSourceDir().isBlank()) {
            throw new IllegalArgumentException("Phim chưa có thư mục nguồn. Dùng nút \"Nạp cả thư mục phim\" hoặc \"Thêm Excel ảnh / video\".");
        }
        return importFolder(projectId, project.getSourceDir()).files();
    }

    private ImportResult write(ProjectEntity project, PromptKind kind, String fileName, List<Line> rows,
                               int fromTags, int mismatch, List<String> examples) {
        int sourceOrder = sourceOrderOf(project.getId(), fileName);
        int[] counts = new int[4];   // added, updated, unchanged, requeued
        int width = videoIdWidth(project.getId(), rows);
        for (Line r : rows) {
            String extId = kind == PromptKind.VIDEO ? String.format("%0" + width + "d", r.order()) : r.id();
            upsert(project, kind, extId, r, fileName, sourceOrder, counts);
        }
        return new ImportResult(fileName, kind.name(), rows.size(), counts[0], counts[1], counts[2], counts[3],
                missingRefs(project.getId()), fromTags, mismatch, examples);
    }

    /** Độ dài số STT của video (001…): giữ đúng độ dài các video đã có để ID không đổi giữa các lần nạp. */
    private int videoIdWidth(long projectId, List<Line> rows) {
        for (PromptEntity p : prompts.findByProjectId(projectId)) {
            if (p.getKind() == PromptKind.VIDEO && p.getExternalId().chars().allMatch(Character::isDigit)) {
                return p.getExternalId().length();
            }
        }
        return Math.max(3, String.valueOf(rows.stream().mapToInt(Line::order).max().orElse(0)).length());
    }

    int maxSeq(long projectId, PromptKind kind) {
        return prompts.findByProjectId(projectId).stream().filter(p -> p.getKind() == kind)
                .mapToInt(PromptEntity::getSeqNo).max().orElse(0);
    }

    /** Đọc dòng tiêu đề để biết file .xlsx là prompt ảnh, prompt video, hay không phải file prompt. */
    static PromptKind detectKind(Path p) {
        try (InputStream in = Files.newInputStream(p); Workbook wb = WorkbookFactory.create(in)) {
            Row header = wb.getSheetAt(0).getRow(0);
            if (header == null) {
                return null;
            }
            DataFormatter f = new DataFormatter();
            Set<String> names = new HashSet<>();
            for (Cell c : header) {
                names.add(f.formatCellValue(c).trim());
            }
            if (names.containsAll(List.of(VIDEO_STT_COLS.get(0), VIDEO_PROMPT_COLS.get(0)))) {
                return PromptKind.VIDEO;
            }
            if (IMAGE_ID_COLS.stream().anyMatch(names::contains) && IMAGE_PROMPT_COLS.stream().anyMatch(names::contains)) {
                return PromptKind.IMAGE;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** reference-id-list.txt: "12. CHAR-A-1" → 12 → CHAR-A-1; dòng không ghi số thì lấy số dòng. */
    private static Map<Integer, String> readIdList(Path p) throws IOException {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(p)) {
            return out;
        }
        List<String> lines = Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8).stream()
                .map(s -> s.replace("\uFEFF", "").trim()).filter(s -> !s.isEmpty()).toList();
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher m = Pattern.compile("^(\\d+)[.)]\\s*(.+)$").matcher(lines.get(i));
            if (m.matches()) {
                out.put(Integer.parseInt(m.group(1)), m.group(2).trim());
            } else {
                out.put(i + 1, lines.get(i));
            }
        }
        return out;
    }

    private static String lower(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    /** File nạp lần đầu được xếp sau các file đã có; nạp lại cùng tên file giữ nguyên vị trí. */
    private int sourceOrderOf(long projectId, String fileName) {
        int max = -1;
        for (PromptEntity p : prompts.findByProjectId(projectId)) {
            if (fileName.equals(p.getSourceName()) && p.getSourceOrder() != null) {
                return p.getSourceOrder();
            }
            if (p.getSourceOrder() != null) {
                max = Math.max(max, p.getSourceOrder());
            }
        }
        return max + 1;
    }

    /** Reference ID mà phim cần nhưng chưa có prompt tạo ảnh và chưa có ảnh trong kho. */
    public List<String> missingRefs(long projectId) {
        Set<String> have = new HashSet<>();
        for (PromptEntity p : prompts.findByProjectId(projectId)) {
            if (p.getKind() == PromptKind.IMAGE) {
                have.add(p.getExternalId());
            }
        }
        assets.findByProjectId(projectId).forEach(a -> have.add(a.getExternalId()));
        return references.findDistinctRefIds(projectId).stream().filter(id -> !have.contains(id)).sorted().toList();
    }

    private void upsert(ProjectEntity project, PromptKind kind, String extId, Line r, String fileName, int sourceOrder, int[] counts) {
        PromptEntity prompt = prompts.findByProjectIdAndKindAndExternalId(project.getId(), kind, extId).orElse(null);
        if (prompt == null) {
            prompt = new PromptEntity();
            prompt.setProjectId(project.getId());
            prompt.setKind(kind);
            prompt.setExternalId(extId);
            fill(prompt, r, fileName, sourceOrder);
            prompts.save(prompt);
            PromptRevisionEntity rev = revisions.save(new PromptRevisionEntity(prompt.getId(), 1, r.prompt(), PromptRevisionEntity.SOURCE_IMPORT, fileName));
            prompt.setCurrentRevisionId(rev.getId());
            jobs.save(new JobEntity(project.getId(), prompt.getId(), kind));
            saveRefs(prompt, r.refs());
            counts[0]++;
            return;
        }

        fill(prompt, r, fileName, sourceOrder);
        List<String> oldRefs = references.findByVideoPromptIdOrderByOrdinalAsc(prompt.getId()).stream()
                .filter(x -> !x.isUserAdded()).map(PromptReferenceEntity::getRefExternalId).toList();
        saveRefs(prompt, r.refs());
        boolean refsChanged = !oldRefs.equals(r.refs().stream().distinct().toList());
        // So với bản IMPORT gần nhất (không phải bản hiện tại): nguồn không đổi thì giữ nguyên chỗ người dùng đã sửa tay.
        String lastImported = revisions.findFirstByPromptIdAndSourceOrderByRevisionNoDesc(prompt.getId(), PromptRevisionEntity.SOURCE_IMPORT)
                .map(PromptRevisionEntity::getPromptText).orElse("");
        boolean textChanged = !lastImported.equals(r.prompt());
        if (!textChanged && !refsChanged) {
            counts[2]++;
            return;
        }
        if (textChanged) {
            int nextNo = revisions.maxRevisionNo(prompt.getId()) + 1;
            PromptRevisionEntity rev = revisions.save(new PromptRevisionEntity(prompt.getId(), nextNo, r.prompt(),
                    PromptRevisionEntity.SOURCE_IMPORT, "import lại " + fileName + ": prompt đổi"));
            prompt.setCurrentRevisionId(rev.getId());
        }
        counts[1]++;

        // Prompt / ảnh reference đổi ở nguồn: kết quả cũ không còn khớp, đưa job về hàng đợi (trừ job đang chạy)
        JobEntity job = jobs.findByPromptId(prompt.getId()).orElseThrow();
        if (job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.GENERATING || !job.getStatus().canMoveTo(JobStatus.PENDING)) {
            return;
        }
        Optional<ProjectAssetEntity> asset = assets.findByProjectIdAndExternalId(project.getId(), extId);
        if (kind == PromptKind.IMAGE && asset.map(a -> ProjectAssetEntity.SOURCE_USER.equals(a.getSource())).orElse(false)) {
            return;   // ảnh này người dùng đã đưa vào: không tạo lại (như "Thử lại hàng loạt")
        }
        if (kind == PromptKind.IMAGE) {
            asset.ifPresent(assets::delete);   // ảnh đã duyệt theo prompt cũ: rút khỏi kho, cảnh dùng nó chờ ảnh mới
        }
        job.setPreviewPath(null);
        job.setFinalPath(null);
        job.setAttempt(0);
        job.setAccountId(null);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        transitions.move(job, JobStatus.PENDING, null, textChanged ? "import lại: prompt đổi ở nguồn" : "import lại: ảnh reference đổi ở nguồn");
        counts[3]++;
    }

    private static void fill(PromptEntity p, Line r, String fileName, int sourceOrder) {
        p.setSeqNo(r.order());
        p.setSummaryVi(r.note());
        p.setSourceName(fileName);
        p.setSourceOrder(sourceOrder);
    }

    /** Thay các ảnh đến từ Excel; ảnh người dùng thêm bằng "+" giữ lại ở cuối danh sách. */
    private void saveRefs(PromptEntity prompt, List<String> refs) {
        List<PromptReferenceEntity> userAdded = references.findByVideoPromptIdOrderByOrdinalAsc(prompt.getId()).stream()
                .filter(PromptReferenceEntity::isUserAdded).toList();
        references.deleteByVideoPromptId(prompt.getId());
        references.flush();
        int ordinal = 0;
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            if (seen.add(ref)) {
                references.save(new PromptReferenceEntity(prompt.getId(), ordinal++, ref, "IMPORT"));
            }
        }
        for (PromptReferenceEntity u : userAdded) {
            if (seen.add(u.getRefExternalId())) {
                references.save(new PromptReferenceEntity(prompt.getId(), ordinal++, u.getRefExternalId(), "USER"));
            }
        }
    }

    private void validate(List<Line> rows, String fileName) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Line r : rows) {
            if (!SAFE_ID.matcher(r.id()).matches()) {
                errors.add(fileName + " dòng " + r.order() + ": ID \"" + r.id() + "\" không dùng làm tên file được");
            }
            if (!ids.add(r.id())) {
                errors.add(fileName + ": ID trùng " + r.id());
            }
            for (String ref : r.refs()) {
                if (!SAFE_ID.matcher(ref).matches()) {
                    errors.add(fileName + " " + r.id() + ": Reference ID \"" + ref + "\" không hợp lệ");
                }
            }
        }
        if (rows.isEmpty()) {
            errors.add(fileName + ": không có dòng dữ liệu nào");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors.subList(0, Math.min(errors.size(), 20)))
                    + (errors.size() > 20 ? "\n… và " + (errors.size() - 20) + " lỗi khác" : ""));
        }
    }

    List<Line> readImages(InputStream in, String fileName) throws IOException {
        List<Line> out = new ArrayList<>();
        readSheet(in, fileName, (h, row, f, index) -> {
            Integer idCol = pick(h, IMAGE_ID_COLS);
            Integer promptCol = pick(h, IMAGE_PROMPT_COLS);
            if (idCol == null || promptCol == null) {
                throw wrongFile(fileName, "ẢNH", h, "\"Reference ID\" hoặc \"Ảnh\", và \"Prompt tạo reference\" hoặc \"Nội dung prompt ảnh\"",
                        h.containsKey(VIDEO_PROMPT_COLS.get(0)) ? "Đây là file prompt VIDEO: hãy dùng nút \"Thêm Excel video\"." : null);
            }
            String id = text(row, idCol, f);
            String prompt = text(row, promptCol, f);
            if (id.isEmpty() && prompt.isEmpty()) {
                return;
            }
            if (id.isEmpty() || prompt.isEmpty()) {
                throw new IllegalArgumentException(fileName + " dòng " + (row.getRowNum() + 1) + ": thiếu ID hoặc prompt");
            }
            Integer sttCol = pick(h, STT_COLS);
            int order = sttCol != null && !text(row, sttCol, f).isEmpty() ? intOf(text(row, sttCol, f), row, fileName) : index;
            out.add(new Line(order, id, prompt, refsOf(row, h, f), noteOf(row, h, f)));
        });
        return out;
    }

    private List<Line> readVideos(InputStream in, String fileName) throws IOException {
        List<Line> out = new ArrayList<>();
        readSheet(in, fileName, (h, row, f, index) -> {
            Integer sttCol = pick(h, VIDEO_STT_COLS);
            Integer promptCol = pick(h, VIDEO_PROMPT_COLS);
            if (sttCol == null || promptCol == null) {
                throw wrongFile(fileName, "VIDEO", h, "\"STT segment\" và \"Nội dung prompt của segment\"",
                        pick(h, IMAGE_PROMPT_COLS) != null ? "Đây là file prompt ẢNH: hãy dùng nút \"Thêm Excel ảnh\"." : null);
            }
            String prompt = text(row, promptCol, f);
            String stt = text(row, sttCol, f);
            if (prompt.isEmpty() && stt.isEmpty()) {
                return;
            }
            if (prompt.isEmpty()) {
                throw new IllegalArgumentException(fileName + " dòng " + (row.getRowNum() + 1) + ": thiếu prompt");
            }
            int order = intOf(stt, row, fileName);
            out.add(new Line(order, String.valueOf(order), prompt, refsOf(row, h, f), noteOf(row, h, f)));
        });
        return out;
    }

    private static List<String> refsOf(Row row, Map<String, Integer> h, Cells f) {
        Integer col = pick(h, REFS_COLS);
        List<String> refs = new ArrayList<>();
        if (col != null) {
            for (String part : text(row, col, f).split("[;,\\n]")) {
                if (!part.isBlank()) {
                    refs.add(part.trim());
                }
            }
        }
        return refs;
    }

    private static String noteOf(Row row, Map<String, Integer> h, Cells f) {
        Integer col = pick(h, NOTE_COLS);
        String note = col == null ? "" : text(row, col, f);
        return note.isEmpty() ? null : note;
    }

    private static IllegalArgumentException wrongFile(String fileName, String expected, Map<String, Integer> h, String needed, String hint) {
        return new IllegalArgumentException(fileName + " không phải file prompt " + expected + " (cần cột " + needed + "). "
                + (hint != null ? hint + " " : "") + "Các cột đang có: " + h.keySet());
    }

    interface RowHandler {
        void handle(Map<String, Integer> header, Row row, Cells f, int index);
    }

    /** Đọc ô thành chữ; ô công thức lấy giá trị đã tính. */
    record Cells(DataFormatter f, FormulaEvaluator evaluator) {
        String text(Row row, int col) {
            Cell c = row.getCell(col);
            return c == null ? "" : f.formatCellValue(c, evaluator).trim();
        }
    }

    private static void readSheet(InputStream in, String fileName, RowHandler handler) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Cells f = new Cells(new DataFormatter(), wb.getCreationHelper().createFormulaEvaluator());
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException(fileName + ": không có dòng tiêu đề");
            }
            Map<String, Integer> header = new LinkedHashMap<>();
            for (Cell c : headerRow) {
                String name = f.f().formatCellValue(c).trim();
                if (!name.isEmpty()) {
                    header.putIfAbsent(name, c.getColumnIndex());
                }
            }
            int index = 0;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    handler.handle(header, row, f, ++index);
                }
            }
        } catch (org.apache.poi.UnsupportedFileFormatException | org.apache.poi.EmptyFileException e) {
            throw new IllegalArgumentException(fileName + ": không phải file Excel (.xlsx) hợp lệ");
        }
    }

    private static Integer pick(Map<String, Integer> header, List<String> names) {
        for (String n : names) {
            if (header.containsKey(n)) {
                return header.get(n);
            }
        }
        return null;
    }

    private static String text(Row row, int col, Cells f) {
        return f.text(row, col);
    }

    private static int intOf(String s, Row row, String fileName) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fileName + " dòng " + (row.getRowNum() + 1) + ": STT \"" + s + "\" không phải số");
        }
    }
}
