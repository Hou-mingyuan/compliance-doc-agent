package com.portfolio.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class SchemaMigrationTest {

    @Test
    void currentSchemaUpgradesOriginalMvpTablesWithoutLosingRows() throws Exception {
        String url = "jdbc:h2:mem:legacy-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE compliance_document (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      title VARCHAR(255) NOT NULL,
                      doc_type VARCHAR(32) DEFAULT 'GENERAL',
                      content TEXT,
                      status VARCHAR(24) DEFAULT 'PENDING',
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE compliance_check (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      document_id BIGINT NOT NULL,
                      rule_code VARCHAR(64) NOT NULL,
                      severity VARCHAR(16) NOT NULL,
                      message TEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE compliance_document_chunk (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      document_id BIGINT NOT NULL,
                      chunk_index INT NOT NULL,
                      content TEXT NOT NULL,
                      char_start INT,
                      char_end INT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("INSERT INTO compliance_document(title, content) VALUES ('legacy', 'kept')");

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));

            try (ResultSet row = statement.executeQuery("""
                    SELECT title, content, tenant_id, owner_id, file_format, page_count, version_no
                    FROM compliance_document WHERE id = 1
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("title")).isEqualTo("legacy");
                assertThat(row.getString("content")).isEqualTo("kept");
                assertThat(row.getString("tenant_id")).isEqualTo("demo-tenant");
                assertThat(row.getString("owner_id")).isEqualTo("demo-user");
                assertThat(row.getString("file_format")).isEqualTo("txt");
                assertThat(row.getInt("page_count")).isEqualTo(1);
                assertThat(row.getInt("version_no")).isEqualTo(1);
            }
            assertThat(columnExists(connection, "compliance_document_chunk", "section_title")).isTrue();
            assertThat(columnExists(connection, "audit_report", "source_digest")).isTrue();
            assertThat(columnExists(connection, "audit_event", "event_hash")).isTrue();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }
}
