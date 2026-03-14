package org.apache.flink.examples;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.TableEnvironment;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Flink SQL Runner — reads a .sql file and executes each statement.
 *
 * DDL statements (CREATE TABLE, SET, ...) are executed immediately.
 * DML statements (INSERT INTO) are collected into a StatementSet and
 * submitted together so they run as a single Flink job.
 */
public class SqlRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: SqlRunner <path-to-sql-file>");
        }

        String sqlFile = args[0];
        String sqlScript = new String(Files.readAllBytes(Paths.get(sqlFile)));

        TableEnvironment env = TableEnvironment.create(
            EnvironmentSettings.newInstance().inStreamingMode().build()
        );

        List<String> statements = splitStatements(sqlScript);

        StatementSet statementSet = env.createStatementSet();
        boolean hasDml = false;

        for (String stmt : statements) {
            String upper = stmt.toUpperCase().trim();
            if (upper.startsWith("INSERT")) {
                statementSet.addInsertSql(stmt);
                hasDml = true;
            } else {
                env.executeSql(stmt);
            }
        }

        if (hasDml) {
            statementSet.execute();
        }
    }

    /**
     * Splits a SQL script into individual statements by semicolons,
     * trimming whitespace and skipping blank entries.
     */
    static List<String> splitStatements(String script) {
        List<String> result = new ArrayList<>();
        for (String part : script.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
