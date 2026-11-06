package com.aifilm.automation.system;

import com.aifilm.automation.project.Provider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hibernate (ddl-auto: update) tạo cột enum kèm CHECK (cot in ('A','B')) lúc tạo bảng lần đầu, nhưng không bao giờ sửa lại:
 * thêm giá trị enum mới (ví dụ nền tảng SEEDANCE_API) thì database cũ từ chối ghi ("A CHECK constraint failed").
 * SQLite không đổi được CHECK bằng ALTER, nên dựng lại bảng với danh sách mới rồi chép nguyên dữ liệu, trong một transaction.
 * Chạy sau khi Hibernate cập nhật bảng, trước khi app dùng tới.
 */
@Component
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
@Slf4j
public class EnumCheckMigration {

    /** bảng → cột → enum mà cột đó lưu. */
    static final Map<String, Map<String, Class<? extends Enum<?>>>> COLUMNS = Map.of(
            "accounts", Map.of("provider", Provider.class),
            "projects", Map.of("provider", Provider.class));

    private final DataSource dataSource;

    @PostConstruct
    void migrate() {
        try (Connection c = dataSource.getConnection()) {
            for (var t : COLUMNS.entrySet()) {
                migrateTable(c, t.getKey(), t.getValue());
            }
        } catch (SQLException e) {
            // không nâng được thì app vẫn chạy như cũ; chỉ giá trị enum mới bị từ chối
            log.error("Không cập nhật được ràng buộc CHECK của database: {}", e.getMessage(), e);
        }
    }

    static void migrateTable(Connection c, String table, Map<String, Class<? extends Enum<?>>> columns) throws SQLException {
        String sql = tableSql(c, table);
        if (sql == null) {
            return;
        }
        String fixed = sql;
        for (var col : columns.entrySet()) {
            fixed = widenCheck(fixed, col.getKey(), col.getValue());
        }
        if (fixed.equals(sql)) {
            return;
        }
        List<String> indexes = new ArrayList<>();
        try (var ps = c.prepareStatement("SELECT sql FROM sqlite_master WHERE type IN ('index','trigger') AND tbl_name = ? AND sql IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getString(1));
                }
            }
        }
        String tmp = table + "__afm_new";
        String create = fixed.replaceFirst("(?i)^CREATE TABLE\\s+\"?" + Pattern.quote(table) + "\"?", "CREATE TABLE " + tmp);
        boolean auto = c.getAutoCommit();
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");   // phải đặt ngoài transaction
            c.setAutoCommit(false);
            try {
                st.execute("DROP TABLE IF EXISTS " + tmp);
                st.execute(create);
                st.execute("INSERT INTO " + tmp + " SELECT * FROM " + table);
                st.execute("DROP TABLE " + table);
                st.execute("ALTER TABLE " + tmp + " RENAME TO " + table);
                for (String idx : indexes) {
                    st.execute(idx);
                }
                c.commit();
                log.info("Đã cập nhật ràng buộc CHECK của bảng {} cho giá trị enum mới", table);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
                st.execute("PRAGMA foreign_keys=ON");
            }
        }
    }

    private static String tableSql(Connection c, String table) throws SQLException {
        try (var ps = c.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Thêm các giá trị enum còn thiếu vào "check (cot in (...))"; giữ nguyên giá trị cũ. Không có CHECK thì để nguyên. */
    static String widenCheck(String sql, String column, Class<? extends Enum<?>> type) {
        Pattern p = Pattern.compile("(?i)(check\\s*\\(\\s*" + Pattern.quote(column) + "\\s+in\\s*\\()([^)]*)(\\)\\s*\\))");
        Matcher m = p.matcher(sql);
        if (!m.find()) {
            return sql;
        }
        Set<String> values = Arrays.stream(m.group(2).split(","))
                .map(s -> s.trim().replaceAll("^'|'$", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed = false;
        for (Enum<?> e : type.getEnumConstants()) {
            changed |= values.add(e.name());
        }
        if (!changed) {
            return sql;
        }
        String list = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(","));
        return sql.substring(0, m.start()) + m.group(1) + list + m.group(3) + sql.substring(m.end());
    }
}
