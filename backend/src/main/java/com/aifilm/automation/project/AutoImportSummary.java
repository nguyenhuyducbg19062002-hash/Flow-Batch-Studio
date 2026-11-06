package com.aifilm.automation.project;

import com.aifilm.automation.project.AssetService.FolderImportResult;
import com.aifilm.automation.project.OmniImportService.FolderResult;
import com.aifilm.automation.project.OmniImportService.ImportResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tóm tắt (từng dòng, tiếng Việt) những gì đã tự nạp khi tạo phim, để hiện cho người dùng. */
final class AutoImportSummary {

    private AutoImportSummary() {}

    static List<String> lines(FolderResult r) {
        List<String> out = new ArrayList<>();
        for (ImportResult f : r.files()) {
            out.add("✓ " + f.file() + ": " + f.rows() + ("IMAGE".equals(f.kind()) ? " ảnh" : " video"));
        }
        if (r.assets() != null) {
            int n = r.assets().added() + r.assets().replaced();
            if (n > 0) {
                out.add("✓ " + n + " ảnh có sẵn đã gán vào phim"
                        + (r.assets().skippedJobs() > 0 ? " (" + r.assets().skippedJobs() + " ảnh khỏi phải tạo)" : ""));
            }
            out.addAll(warnings(r.assets()));
        }
        return out;
    }

    static List<String> warnings(FolderImportResult a) {
        List<String> w = a.warnings() == null ? List.of() : a.warnings();
        List<String> out = new ArrayList<>();
        for (String s : w.subList(0, Math.min(5, w.size()))) {
            out.add("⚠ " + s);
        }
        if (w.size() > 5) {
            out.add("⚠ … và " + (w.size() - 5) + " file khác bị bỏ qua");
        }
        return out;
    }

    /** Dòng "Còn thiếu…" tính SAU khi đã gán ảnh có sẵn (missingRefs hiện tại của phim). */
    static Optional<String> missing(List<String> m) {
        if (m == null || m.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Còn thiếu " + m.size() + " ảnh gốc chưa có prompt tạo và chưa có ảnh: "
                + String.join(", ", m.subList(0, Math.min(5, m.size()))) + (m.size() > 5 ? "…" : ""));
    }
}
