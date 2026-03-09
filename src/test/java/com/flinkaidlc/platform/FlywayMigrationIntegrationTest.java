package com.flinkaidlc.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationsApplyAndAllTablesExist() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
        }

        assertThat(tables).contains(
                "tenants",
                "pipelines",
                "pipeline_sources",
                "pipeline_sinks",
                "pipeline_deployments"
        );
    }

    @Test
    void tenantsTableHasRequiredColumns() throws Exception {
        Set<String> columns = getColumnsForTable("tenants");
        assertThat(columns).contains(
                "tenant_id", "slug", "name", "contact_email", "fid",
                "status", "max_pipelines", "max_total_parallelism",
                "created_at", "updated_at"
        );
    }

    @Test
    void pipelinesTableHasRequiredColumns() throws Exception {
        Set<String> columns = getColumnsForTable("pipelines");
        assertThat(columns).contains(
                "pipeline_id", "tenant_id", "name", "description",
                "sql_query", "status", "parallelism", "checkpoint_interval_ms",
                "upgrade_mode", "created_at", "updated_at"
        );
    }

    @Test
    void pipelineSourcesTableHasRequiredColumns() throws Exception {
        Set<String> columns = getColumnsForTable("pipeline_sources");
        assertThat(columns).contains(
                "source_id", "pipeline_id", "table_name", "topic",
                "bootstrap_servers", "consumer_group", "startup_mode",
                "schema_registry_url", "avro_subject", "watermark_column",
                "watermark_delay_ms", "extra_properties"
        );
    }

    @Test
    void pipelineSinksTableHasRequiredColumns() throws Exception {
        Set<String> columns = getColumnsForTable("pipeline_sinks");
        assertThat(columns).contains(
                "sink_id", "pipeline_id", "table_name", "topic",
                "bootstrap_servers", "schema_registry_url", "avro_subject",
                "partitioner", "delivery_guarantee"
        );
    }

    @Test
    void pipelineDeploymentsTableHasRequiredColumns() throws Exception {
        Set<String> columns = getColumnsForTable("pipeline_deployments");
        assertThat(columns).contains(
                "pipeline_id", "version", "k8s_resource_name", "configmap_name",
                "flink_job_id", "lifecycle_state", "job_state",
                "last_savepoint_path", "error_message", "last_synced_at"
        );
    }

    private Set<String> getColumnsForTable(String tableName) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT column_name FROM information_schema.columns " +
                     "WHERE table_schema = 'public' AND table_name = '" + tableName + "'")) {
            while (rs.next()) {
                columns.add(rs.getString("column_name"));
            }
        }
        return columns;
    }
}
