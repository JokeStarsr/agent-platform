package com.agent.data.dataagent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * L7 数据层：Schema 语义层元数据（t_schema_metadata）
 * 对应 docs/design/architecture/20260910-data-agent.md §3。
 * NL2SQL 服务依赖此元数据生成准确的 SQL（表/字段中文注释 + 关系图 + 常用口径）。
 */
@Repository
public class SchemaMetadataRepository {

    private final JdbcTemplate jdbc;

    public SchemaMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SchemaMetadata(long id, String tableName, String tableComment, String columnName,
                                  String columnComment, String columnType, boolean isNullable,
                                  String foreignKey, String commonMetrics) {
    }

    private static final RowMapper<SchemaMetadata> ROW = (rs, i) -> new SchemaMetadata(
            rs.getLong("id"),
            rs.getString("table_name"),
            rs.getString("table_comment"),
            rs.getString("column_name"),
            rs.getString("column_comment"),
            rs.getString("column_type"),
            rs.getBoolean("is_nullable"),
            rs.getString("foreign_key"),
            rs.getString("common_metrics"));

    /**
     * 全量加载所有元数据（启动时缓存到内存）。
     */
    public List<SchemaMetadata> loadAll() {
        return jdbc.query("SELECT * FROM t_schema_metadata ORDER BY table_name, column_name", ROW);
    }

    /**
     * 按表名查询元数据。
     */
    public List<SchemaMetadata> findByTable(String tableName) {
        return jdbc.query("SELECT * FROM t_schema_metadata WHERE table_name = ? ORDER BY column_name",
                ROW, tableName);
    }

    /**
     * 按表名分组返回元数据（Map<tableName, List<SchemaMetadata>>）。
     */
    public Map<String, List<SchemaMetadata>> groupByTable() {
        return loadAll().stream()
                .collect(Collectors.groupingBy(SchemaMetadata::tableName));
    }

    /**
     * 插入或更新元数据（幂等）。
     */
    public long upsert(String tableName, String tableComment, String columnName, String columnComment,
                       String columnType, boolean isNullable, String foreignKey, String commonMetrics) {
        jdbc.update("""
                  INSERT INTO t_schema_metadata (table_name, table_comment, column_name, column_comment,
                                                 column_type, is_nullable, foreign_key, common_metrics)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                  ON CONFLICT (table_name, column_name) DO UPDATE SET
                    table_comment = EXCLUDED.table_comment,
                    column_comment = EXCLUDED.column_comment,
                    column_type = EXCLUDED.column_type,
                    is_nullable = EXCLUDED.is_nullable,
                    foreign_key = EXCLUDED.foreign_key,
                    common_metrics = EXCLUDED.common_metrics,
                    updated_at = now()
                  """, tableName, tableComment, columnName, columnComment,
                columnType, isNullable, foreignKey, commonMetrics);
        Long id = jdbc.queryForObject(
                "SELECT id FROM t_schema_metadata WHERE table_name = ? AND column_name = ?",
                Long.class, tableName, columnName);
        return id == null ? 0 : id;
    }
}
