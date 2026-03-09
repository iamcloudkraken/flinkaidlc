package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.exception.SqlValidationException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SqlValidationService {

    /**
     * Validates that the provided SQL is safe to submit as a Flink pipeline query.
     *
     * @param sql               the SQL string to validate
     * @param declaredTableNames table names declared in the pipeline sources and sinks
     * @throws SqlValidationException if the SQL fails any validation rule
     */
    public void validate(String sql, List<String> declaredTableNames) {
        if (sql == null || sql.isBlank()) {
            throw new SqlValidationException("SQL query must not be blank");
        }

        // Check for multi-statement injection via semicolons
        // Split on semicolons (ignoring trailing whitespace/semicolons) and count non-empty parts
        String trimmed = sql.trim();
        // Remove trailing semicolons to get clean statement count
        String withoutTrailing = trimmed.replaceAll(";\\s*$", "");
        if (withoutTrailing.contains(";")) {
            throw new SqlValidationException("Multi-statement SQL is not permitted");
        }

        // Parse the SQL using JSQLParser
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SqlValidationException("SQL parse error: " + e.getMessage(), e);
        }

        // Reject DDL statements
        if (statement instanceof CreateTable
                || statement instanceof Drop
                || statement instanceof Alter
                || statement instanceof Truncate
                || statement instanceof Execute) {
            throw new SqlValidationException("DDL statements are not permitted");
        }

        // Extract table references and validate against declared tables
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        List<String> referencedTables;
        try {
            referencedTables = tablesNamesFinder.getTableList(statement);
        } catch (Exception e) {
            throw new SqlValidationException("Failed to extract table references: " + e.getMessage(), e);
        }

        if (referencedTables != null && !referencedTables.isEmpty()) {
            Set<String> declaredLower = declaredTableNames.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            List<String> unknownTables = referencedTables.stream()
                    .filter(t -> !declaredLower.contains(t.toLowerCase()))
                    .collect(Collectors.toList());

            if (!unknownTables.isEmpty()) {
                throw new SqlValidationException("Unknown table references: " + unknownTables);
            }
        }
    }
}
