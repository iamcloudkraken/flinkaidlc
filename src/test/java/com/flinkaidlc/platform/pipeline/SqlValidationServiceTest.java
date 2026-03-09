package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.exception.SqlValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class SqlValidationServiceTest {

    private SqlValidationService sqlValidationService;

    @BeforeEach
    void setUp() {
        sqlValidationService = new SqlValidationService();
    }

    @Test
    void validate_validSelectQuery_doesNotThrow() {
        String sql = "SELECT a.id, b.value FROM source_table a JOIN sink_table b ON a.id = b.id";
        assertThatCode(() -> sqlValidationService.validate(sql, List.of("source_table", "sink_table")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_blankSql_throwsSqlValidationException() {
        assertThatThrownBy(() -> sqlValidationService.validate("  ", List.of("t1")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void validate_nullSql_throwsSqlValidationException() {
        assertThatThrownBy(() -> sqlValidationService.validate(null, List.of("t1")))
                .isInstanceOf(SqlValidationException.class);
    }

    @Test
    void validate_createTableDdl_throwsSqlValidationException() {
        String ddl = "CREATE TABLE my_table (id INT, name VARCHAR(100))";
        assertThatThrownBy(() -> sqlValidationService.validate(ddl, List.of("my_table")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("DDL statements are not permitted");
    }

    @Test
    void validate_dropTableDdl_throwsSqlValidationException() {
        String ddl = "DROP TABLE my_table";
        assertThatThrownBy(() -> sqlValidationService.validate(ddl, List.of("my_table")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("DDL statements are not permitted");
    }

    @Test
    void validate_alterTableDdl_throwsSqlValidationException() {
        String ddl = "ALTER TABLE my_table ADD COLUMN extra INT";
        assertThatThrownBy(() -> sqlValidationService.validate(ddl, List.of("my_table")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("DDL statements are not permitted");
    }

    @Test
    void validate_truncateDdl_throwsSqlValidationException() {
        String ddl = "TRUNCATE TABLE my_table";
        assertThatThrownBy(() -> sqlValidationService.validate(ddl, List.of("my_table")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("DDL statements are not permitted");
    }

    @Test
    void validate_multiStatementWithSemicolon_throwsSqlValidationException() {
        String multiSql = "SELECT id FROM source_table; DROP TABLE source_table";
        assertThatThrownBy(() -> sqlValidationService.validate(multiSql, List.of("source_table")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("Multi-statement");
    }

    @Test
    void validate_unknownTableReference_throwsSqlValidationException() {
        String sql = "SELECT id FROM unknown_table";
        assertThatThrownBy(() -> sqlValidationService.validate(sql, List.of("source_table", "sink_table")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("Unknown table references");
    }

    @Test
    void validate_caseInsensitiveTableNames_doesNotThrow() {
        String sql = "SELECT id FROM SOURCE_TABLE";
        assertThatCode(() -> sqlValidationService.validate(sql, List.of("source_table")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_invalidSqlSyntax_throwsSqlValidationException() {
        String badSql = "SELECT FROM WHERE";
        assertThatThrownBy(() -> sqlValidationService.validate(badSql, List.of("t1")))
                .isInstanceOf(SqlValidationException.class)
                .hasMessageContaining("SQL parse error");
    }

    @Test
    void validate_trailingSemicolonOnly_doesNotThrow() {
        // A single statement with a trailing semicolon should be allowed
        String sql = "SELECT id FROM source_table;";
        assertThatCode(() -> sqlValidationService.validate(sql, List.of("source_table")))
                .doesNotThrowAnyException();
    }
}
