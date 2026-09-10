package com.agent.capability.dataagent;

import com.agent.data.dataagent.DataLineageRepository;
import com.agent.data.dataagent.DataLineageRepository.DataLineage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L4 能力层：数据血缘服务（W16 Step 3）
 * <p>记录每次 Data Agent 查询的完整血缘信息：
 * <ul>
 *   <li>涉及的表（从 SQL 解析）</li>
 *   <li>涉及的列（从 SQL + Schema 元数据提取）</li>
 *   <li>生成的 SQL</li>
 *   <li>分析文本</li>
 *   <li>验证结果</li>
 * </ul>
 * 支持反向溯源：查询哪些分析使用了某张表。
 */
@Service
public class DataLineageService {

    private static final Logger log = LoggerFactory.getLogger(DataLineageService.class);

    // SQL 表名提取模式（FROM/JOIN 后的表名）
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_]*)");

    private final DataLineageRepository repository;

    public DataLineageService(DataLineageRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录数据血缘。
     *
     * @param traceId       请求追踪 ID
     * @param tenantId      租户 ID
     * @param question      用户问题
     * @param queryResult   查询结果
     * @param chartResult   图表结果（可为 null）
     * @param verification  验证结果（可为 null）
     * @param durationMs    总耗时
     * @return 血缘记录 ID
     */
    public Long recordLineage(String traceId, String tenantId, String question,
                              DataAgentService.QueryResult queryResult,
                              ChartGeneratorService.ChartResult chartResult,
                              ConclusionVerifier.VerificationResult verification,
                              long durationMs) {
        // 提取表名
        List<String> tablesUsed = extractTablesFromSql(queryResult.sql());

        // 提取列名（简化版：从 Schema 元数据中匹配）
        List<Map<String, String>> columnsUsed = extractColumnsFromResult(queryResult);

        // 构建验证结果 Map
        Map<String, Object> verificationMap = buildVerificationMap(verification);

        // 创建血缘记录
        DataLineage lineage = new DataLineage(
                null,  // id 由数据库生成
                traceId,
                tenantId,
                question,
                queryResult.sql(),
                tablesUsed,
                columnsUsed,
                queryResult.rowCount(),
                chartResult != null ? chartResult.chartType() : null,
                null,  // analysisText 暂时不记录（W16 Step 4 报告导出时使用）
                verificationMap,
                durationMs,
                Instant.now(),
                Instant.now()
        );

        Long id = repository.insert(lineage);
        log.info("记录数据血缘: id={}, traceId={}, tables={}, columns={}",
                id, traceId, tablesUsed, columnsUsed.size());
        return id;
    }

    /**
     * 查询血缘记录（按 trace_id）。
     */
    public DataLineage getByTraceId(String traceId) {
        return repository.findByTraceId(traceId);
    }

    /**
     * 查询租户的血缘历史（分页）。
     */
    public List<DataLineage> getHistory(String tenantId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return repository.findByTenantId(tenantId, pageSize, offset);
    }

    /**
     * 反向溯源：查询涉及指定表的分析记录。
     */
    public List<DataLineage> getByTableName(String tableName, int limit) {
        return repository.findByTableName(tableName, limit);
    }

    /**
     * 统计租户的查询次数。
     */
    public long getQueryCount(String tenantId) {
        return repository.countByTenantId(tenantId);
    }

    /**
     * 清理旧记录（保留最近 N 条）。
     */
    public int cleanupOldRecords(String tenantId, int keepRecent) {
        int deleted = repository.deleteOldRecords(tenantId, keepRecent);
        log.info("清理血缘记录: tenantId={}, keepRecent={}, deleted={}", tenantId, keepRecent, deleted);
        return deleted;
    }

    // ========== 辅助方法 ==========

    /**
     * 从 SQL 中提取表名。
     */
    private List<String> extractTablesFromSql(String sql) {
        if (sql == null || sql.isBlank()) return List.of();

        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group(1).toLowerCase();
            // 过滤 SQL 关键字
            if (!isSqlKeyword(tableName)) {
                tables.add(tableName);
            }
        }
        return new ArrayList<>(tables);
    }

    /**
     * 判断是否为 SQL 关键字。
     */
    private boolean isSqlKeyword(String word) {
        Set<String> keywords = Set.of(
                "select", "from", "where", "and", "or", "not", "in", "like",
                "join", "left", "right", "inner", "outer", "on", "as",
                "group", "by", "order", "having", "limit", "offset",
                "union", "intersect", "except", "all", "distinct",
                "case", "when", "then", "else", "end", "null", "true", "false",
                "is", "between", "exists", "any", "some"
        );
        return keywords.contains(word.toLowerCase());
    }

    /**
     * 从查询结果中提取列信息。
     */
    private List<Map<String, String>> extractColumnsFromResult(DataAgentService.QueryResult queryResult) {
        List<Map<String, String>> columns = new ArrayList<>();
        if (queryResult.columns() != null) {
            for (String colName : queryResult.columns()) {
                columns.add(Map.of("column", colName));
            }
        }
        return columns;
    }

    /**
     * 构建验证结果 Map。
     */
    private Map<String, Object> buildVerificationMap(ConclusionVerifier.VerificationResult verification) {
        if (verification == null) {
            return Map.of("performed", false);
        }

        Map<String, Object> map = new HashMap<>();
        map.put("performed", true);
        map.put("all_passed", verification.allPassed());
        map.put("summary", verification.summary());
        map.put("duration_ms", verification.durationMs());

        if (verification.details() != null) {
            List<Map<String, Object>> detailsList = new ArrayList<>();
            for (var detail : verification.details()) {
                Map<String, Object> detailMap = new HashMap<>();
                detailMap.put("description", detail.description());
                detailMap.put("claim_value", detail.claimValue());
                detailMap.put("recalc_value", detail.recalcValue());
                detailMap.put("passed", detail.passed());
                detailsList.add(detailMap);
            }
            map.put("details", detailsList);
        }

        return map;
    }
}
