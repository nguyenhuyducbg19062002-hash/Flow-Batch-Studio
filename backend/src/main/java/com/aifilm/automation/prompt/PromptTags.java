package com.aifilm.automation.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc danh sách ảnh cần gắn TRỰC TIẾP từ prompt Omni.
 *
 * Prompt Omni mở đầu bằng khối khai báo, mỗi ảnh khai báo một lần theo đúng thứ tự gắn ảnh:
 *   "IMAGE KEY (...): @PANEL-1 = THE STILLS: ... @CHAR-CALEB-1 = CALEB: ... @CHAR-BRIANNA-1 = BRIANNA: ..."
 * hoặc bản cũ "REFERENCE BINDING @CHAR-DOMINIC-1 = Dominic (...)".
 * Thứ tự các khai báo "@ID =" chính là thứ tự upload ảnh lên Flow. Nếu prompt không có khai báo nào thì lấy mọi
 * tag @ID xuất hiện, theo thứ tự gặp đầu tiên.
 */
public final class PromptTags {

    private PromptTags() {}

    /** "@ID =" : khai báo một ảnh. ID kiểu CHAR-CALEB-1, LOC-1, PANEL-12, PROP-3. */
    private static final Pattern DECLARATION = Pattern.compile("@([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+)\\s*=");
    /** Mọi tag @ID (để dự phòng khi prompt không có khai báo). */
    private static final Pattern ANY_TAG = Pattern.compile("@([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+)");
    /** Số thứ tự đầu prompt: "STT - 12. IMAGE KEY …" hoặc "12. GENERATION GOAL …". */
    private static final Pattern LEADING_STT = Pattern.compile("^\\s*(?:STT\\s*[-–:]\\s*)?(\\d{1,6})\\s*[.)]\\s+");

    public static List<String> imageIds(String prompt) {
        if (prompt == null) {
            return List.of();
        }
        Set<String> ids = collect(DECLARATION, prompt);
        if (ids.isEmpty()) {
            ids = collect(ANY_TAG, prompt);
        }
        return new ArrayList<>(ids);
    }

    /** ID viết trần, không có "@": "…the supplied reference image CHAR-CLARA-1, in the outfit…". */
    private static final Pattern BARE_ID = Pattern.compile("(?<![@A-Za-z0-9_-])([A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)+)(?![A-Za-z0-9_-])");

    /**
     * Prompt ảnh "biến thể trang phục" nhắc ảnh gốc bằng ID trần (không @, không cột Reference IDs). Chỉ lấy những ID là ảnh đã biết
     * của phim (known) và khác chính ảnh đang tạo, theo thứ tự gặp đầu tiên.
     */
    public static List<String> bareKnownIds(String prompt, Set<String> known, String selfId) {
        if (prompt == null || known.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = BARE_ID.matcher(prompt);
        while (m.find()) {
            String id = m.group(1);
            if (known.contains(id) && !id.equals(selfId)) {
                out.add(id);
            }
        }
        return new ArrayList<>(out);
    }

    /** Số STT ở đầu prompt, hoặc null nếu không có. */
    public static Integer leadingNumber(String prompt) {
        if (prompt == null) {
            return null;
        }
        Matcher m = LEADING_STT.matcher(prompt);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static Set<String> collect(Pattern p, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String id = m.group(1);
            // bỏ dấu chấm/gạch dính ở cuối câu: "@LOC-1." đã không khớp vì '.' không thuộc ID
            out.add(id);
        }
        return out;
    }
}
