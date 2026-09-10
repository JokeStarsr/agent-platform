package com.agent.data.dataagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * L7 数据层：数据血缘记录仓库（W16 Step 3）
 * <p>记录每次 Data Agent 查询的完整血缘信息：涉及的表、列、SQL、分析结果、验证结果等。</p>
 */
@Repository
public class DataLineageRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public DataLineageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 数据血缘记录实体。
     */
    public record DataLineage(
            Long id,
            String traceId,
            String tenantId,
            String question,
            String generatedSql,
            List<String> tablesUsed,
            List<Map<String, String>> columnsUsed,
            int resultRows,
            String chartType,
            String analysisText,
            Map<String, Object> verification,
            long durationMs,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private static final RowMapper<DataLineage> ROW_MAPPER = (rs, rowNum) -> {
        try {
            return new DataLineage(
                    rs.getLong("id"),
                    rs.getString("trace_id"),
                    rs.getString("tenant_id"),
                    rs.getString("question"),
                    rs.getString("generated_sql"),
                    parseJsonList(rs.getString("tables_used")),
                    parseJsonColumns(rs.getString("columns_used")),
                    rs.getInt("result_rows"),
                    rs.getString("chart_type"),
                    rs.getString("analysis_text"),
                    parseJsonMap(rs.getString("verification")),
                    rs.getLong("duration_ms"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        } catch (Exception e) {
            throw new SQLException("Failed to map DataLineage row", e);
        }
    };

    /**
     * 插入数据血缘记录。
     */
    public Long insert(DataLineage lineage) {
        String sql = """
                INSERT INTO t_data_lineage (
                    trace_id, tenant_id, question, generated_sql,
                    tables_used, columns_used, result_rows, chart_type,
                    analysis_text, verification, duration_ms
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
                RETURNING id
                """;

        try {
            return jdbc.queryForObject(sql, Long.class,
                    lineage.traceId(),
                    lineage.tenantId(),
                    lineage.question(),
                    lineage.generatedSql(),
                    toJson(lineage.tablesUsed()),
                    toJson(lineage.columnsUsed()),
                    lineage.resultRows(),
                    lineage.chartType(),
                    lineage.analysisText(),
                    toJson(lineage.verification()),
                    lineage.durationMs()
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize lineage data", e);
        }
    }

    /**
     * 按 trace_id 查询血缘记录。
     */
    public DataLineage findByTraceId(String traceId) {
        String sql = "SELECT * FROM t_data_lineage WHERE trace_id = ? ORDER BY created_at DESC LIMIT 1";
        List<DataLineage> results = jdbc.query(sql, ROW_MAPPER, traceId);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 按租户查询血缘历史（分页）。
     */
    public List<DataLineage> findByTenantId(String tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM t_data_lineage
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """;
        return jdbc.query(sql, ROW_MAPPER, tenantId, limit, offset);
    }

    /**
     * 查询涉及指定表的血缘记录（反向溯源）。
     */
    public List<DataLineage> findByTableName(String tableName, int limit) {
        String sql = """
                SELECT * FROM t_data_lineage
                WHERE tables_used @> ?::jsonb
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try {
            return jdbc.query(sql, ROW_MAPPER, JSON.writeValueAsString(List.of(tableName)), limit);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize table name", e);
        }
    }

    /**
     * 统计租户的查询次数。
     */
    public long countByTenantId(String tenantId) {
        String sql = "SELECT COUNT(*) FROM t_data_lineage WHERE tenant_id = ?";
        Long count = jdbc.queryForObject(sql, Long.class, tenantId);
        return count != null ? count : 0;
    }

    /**
     * 删除指定租户的旧记录（保留最近 N 条）。
     */
    public int deleteOldRecords(String tenantId, int keepRecent) {
        String sql = """
                DELETE FROM t_data_lineage
                WHERE tenant_id = ?
                AND id NOT IN (
                    SELECT id FROM t_data_lineage
                    WHERE tenant_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                )
                """;
        return jdbc.update(sql, tenantId, tenantId, keepRecent);
    }

    // ========== JSON 辅助方法 ==========

    private static String toJson(Object obj) throws JsonProcessingException {
        return obj != null ? JSON.writeValueAsString(obj) : "null";
    }

    private static List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Map<String, String>> parseJsonColumns(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, JSON.getTypeFactory()
                    .constructCollectionType(List.class,
                            JSON.getTypeFactory().constructMapType(Map.class, String.class, String.class)));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, JSON.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }
}
