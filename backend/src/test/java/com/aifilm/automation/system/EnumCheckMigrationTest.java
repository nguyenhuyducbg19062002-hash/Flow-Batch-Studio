package com.aifilm.automation.system;

import com.aifilm.automation.project.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumCheckMigrationTest {

    @TempDir
    Path dir;

    @Test
    void widenCheck_addsMissingValuesOnly() {
        String sql = "CREATE TABLE accounts (id integer, provider varchar(255) not null check (provider in ('FLOW_BROWSER','VERTEX_API','FAKE')), "
                + "status varchar(255) not null check (status in ('READY','LEASED')), primary key (id))";
        String out = EnumCheckMigration.widenCheck(sql, "provider", Provider.class);
        assertThat(out).contains("check (provider in ('FLOW_BROWSER','VERTEX_API','FAKE','FLOW_EXTENSION','SEEDANCE_API','GOOGLE_AI_STUDIO'))")
                .contains("check (status in ('READY','LEASED'))");
        assertThat(EnumCheckMigration.widenCheck(out, "provider", Provider.class)).isEqualTo(out);
    }

    @Test
    void oldDatabase_acceptsNewProviderAndKeepsRows() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("t.db"))) {
            try (var st = c.createStatement()) {
                st.execute("CREATE TABLE accounts (id integer, email varchar(255) not null unique, "
                        + "provider varchar(255) not null check (provider in ('FLOW_BROWSER','VERTEX_API','FAKE')), primary key (id))");
                st.execute("CREATE INDEX idx_acc_email ON accounts(email)");
                st.execute("INSERT INTO accounts VALUES (1, 'a@x', 'FLOW_BROWSER')");
                assertThatThrownBy(() -> st.execute("INSERT INTO accounts VALUES (2, 'seedance-1', 'SEEDANCE_API')"))
                        .hasMessageContaining("CHECK");
            }

            EnumCheckMigration.migrateTable(c, "accounts", Map.of("provider", Provider.class));
            EnumCheckMigration.migrateTable(c, "accounts", Map.of("provider", Provider.class));   // lần 2: không làm gì

            try (var st = c.createStatement()) {
                st.execute("INSERT INTO accounts VALUES (2, 'seedance-1', 'SEEDANCE_API')");
                st.execute("INSERT INTO accounts VALUES (3, 'studio-1', 'GOOGLE_AI_STUDIO')");
                assertThatThrownBy(() -> st.execute("INSERT INTO accounts VALUES (4, 'x', 'NOPE')")).hasMessageContaining("CHECK");
                assertThatThrownBy(() -> st.execute("INSERT INTO accounts VALUES (5, 'a@x', 'FAKE')")).hasMessageContaining("UNIQUE");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM accounts")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='idx_acc_email'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }
}
