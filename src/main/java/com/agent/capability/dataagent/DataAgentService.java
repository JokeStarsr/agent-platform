package com.agent.capability.dataagent;

import com.agent.common.BizException;
import com.agent.data.dataagent.SchemaMetadataRepository;
import com.agent.data.dataagent.SchemaMetadataRepository.SchemaMetadata;
import com.agent.model.llm.LlmGateway;
import com.agent.tool.sandbox.CodeSandboxService;
import com.agent.tool.sandbox.SqlSanitizer;
import com.agent.tool.sandbox.SqlSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L4 能力层：Data Agent NL2SQL 服务（docs/design/architecture/20260910-data-agent.md §4）
 * <p>核心流程：加载 Schema 语义层 → 构建 Prompt → 调 LLM 生成 SQL → SqlSanitizer 白名单校验
 * → SqlSandboxService 只读执行 → 返回结果 + SQL 展示。</p>
 */
@Service
public class DataAgentService {

    private static final Logger log = LoggerFactory.getLogger(DataAgentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SchemaMetadataRepository schemaRepo;
    private final LlmGateway llm;
    private final SqlSandboxService sqlSandbox;
    private final CodeSandboxService codeSandbox;

    // 内存缓存：tableName → List<SchemaMetadata>
    private Map<String, List<SchemaMetadata>> schemaCache = new HashMap<>();

    public DataAgentService(SchemaMetadataRepository schemaRepo, LlmGateway llm,
                            SqlSandboxService sqlSandbox, CodeSandboxService codeSandbox) {
        this.schemaRepo = schemaRepo;
        this.llm = llm;
        this.sqlSandbox = sqlSandbox;
        this.codeSandbox = codeSandbox;
    }

    @PostConstruct
    void loadSchema() {
        refreshSchemaCache();
        log.info("Data Agent Schema 语义层加载完成：{} 表", schemaCache.size());
    }

    /**
     * 刷新 Schema 缓存（管理 API 触发）。
     */
    public void refreshSchemaCache() {
        schemaCache = schemaRepo.groupByTable();
    }

    /**
     * NL2SQL 查询：自然语言 → SQL → 执行 → 返回结果。
     *
     * @param question 用户自然语言问题
     * @param maxRows  最大返回行数（默认 100）
     * @return 查询结果（含 SQL 展示）
     */
    public QueryResult query(String question, Integer maxRows) {
        if (question == null || question.isBlank()) {
            throw new BizException(400, "问题不能为空");
        }

        // 1. 构建 Prompt（Schema 语义层 + few-shot 示例）
        String systemPrompt = buildSystemPrompt();
        String userPrompt = question;

        // 2. 调 LLM 生成 SQL
        long start = System.currentTimeMillis();
        String generatedSql = llm.generate(systemPrompt, userPrompt);
        long llmDuration = System.currentTimeMillis() - start;

        // 3. 清理 SQL（去除 markdown 代码块标记）
        generatedSql = cleanSql(generatedSql);

        // 4. SqlSanitizer 白名单校验（AST 解析 + 强制 LIMIT）
        SqlSanitizer.CheckResult checkResult;
        try {
            checkResult = SqlSanitizer.check(generatedSql, maxRows != null ? maxRows : SqlSanitizer.DEFAULT_MAX_ROWS);
        } catch (BizException e) {
            throw new BizException(e.getCode(), "SQL 校验失败：" + e.getMessage() + "\n生成的 SQL：\n" + generatedSql);
        }

        // 5. SqlSandboxService 只读执行
        SqlSandboxService.SqlResult sqlResult;
        try {
            sqlResult = sqlSandbox.execute(checkResult.sql(), checkResult.maxRows());
        } catch (Exception e) {
            throw new BizException(500, "SQL 执行失败：" + e.getMessage() + "\n生成的 SQL：\n" + checkResult.sql());
        }

        // 6. 组装结果（不含 verification）
        long totalDuration = llmDuration + sqlResult.durationMs();
        return new QueryResult(
                question,
                checkResult.sql(),
                sqlResult.columns(),
                sqlResult.rows(),
                sqlResult.rowCount(),
                sqlResult.truncated(),
                totalDuration,
                llmDuration,
                sqlResult.durationMs(),
                null  // verification 由 queryWithVerification 填充
        );
    }

    /**
     * 构建系统提示词（Schema 语义层 + few-shot 示例）。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据分析专家，将自然语言查询转为 PostgreSQL SQL。\n\n");

        // Schema 语义层
        sb.append("## 表结构（Schema 语义层）\n");
        for (Map.Entry<String, List<SchemaMetadata>> entry : schemaCache.entrySet()) {
            String tableName = entry.getKey();
            List<SchemaMetadata> columns = entry.getValue();
            if (columns.isEmpty()) continue;

            String tableComment = columns.get(0).tableComment();
            sb.append("### ").append(tableName).append("（").append(tableComment).append("）\n");

            for (SchemaMetadata col : columns) {
                sb.append("- ").append(col.columnName())
                        .append(" (").append(col.columnType()).append("): ")
                        .append(col.columnComment());
                if (col.foreignKey() != null && !col.foreignKey().isBlank()) {
                    sb.append("（外键 → ").append(col.foreignKey()).append("）");
                }
                sb.append("\n");
            }

            // 常用口径
            if (columns.get(0).commonMetrics() != null && !columns.get(0).commonMetrics().isBlank()) {
                sb.append("\n常用口径：\n");
                try {
                    Map<String, String> metrics = JSON.readValue(columns.get(0).commonMetrics(),
                            JSON.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
                    for (Map.Entry<String, String> m : metrics.entrySet()) {
                        sb.append("- ").append(m.getKey()).append(" = ").append(m.getValue()).append("\n");
                    }
                } catch (Exception ignored) {
                }
            }
            sb.append("\n");
        }

        // Few-shot 示例
        sb.append("## Few-shot 示例\n");
        sb.append("Q: \"上月销售额\"\n");
        sb.append("A: SELECT SUM(amount) AS total_sales FROM t_order WHERE status IN ('paid', 'shipped', 'completed') ");
        sb.append("AND created_at >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') ");
        sb.append("AND created_at < DATE_TRUNC('month', CURRENT_DATE)\n\n");

        sb.append("Q: \"销售额最高的 3 个产品\"\n");
        sb.append("A: SELECT p.name, SUM(oi.subtotal) AS total_sales FROM t_order_item oi ");
        sb.append("JOIN t_product p ON oi.product_id = p.product_id ");
        sb.append("JOIN t_order o ON oi.order_id = o.order_id ");
        sb.append("WHERE o.status IN ('paid', 'shipped', 'completed') ");
        sb.append("GROUP BY p.name ORDER BY total_sales DESC LIMIT 3\n\n");

        sb.append("Q: \"各地区的客户数\"\n");
        sb.append("A: SELECT region, COUNT(*) AS customer_count FROM t_customer GROUP BY region ORDER BY customer_count DESC\n\n");

        // 输出规则
        sb.append("## 输出规则\n");
        sb.append("1. 只输出 SQL 语句，不要解释\n");
        sb.append("2. 使用中文列别名（如 total_sales AS 总销售额）\n");
        sb.append("3. 必须包含 LIMIT（无 LIMIT 时默认 LIMIT 100）\n");
        sb.append("4. 时间过滤用 CURRENT_DATE 而非硬编码日期\n");

        return sb.toString();
    }

    /**
     * 清理 SQL（去除 markdown 代码块标记）。
     */
    private String cleanSql(String sql) {
        if (sql == null) return "";
        sql = sql.trim();
        // 去除 ```sql ... ``` 或 ``` ... ```
        if (sql.startsWith("```")) {
            int start = sql.indexOf('\n');
            int end = sql.lastIndexOf("```");
            if (start > 0 && end > start) {
                sql = sql.substring(start + 1, end).trim();
            }
        }
        return sql;
    }

    /**
     * 查询结果 record。
     */
    public record QueryResult(
            String question,
            String sql,
            List<String> columns,
            List<List<Object>> rows,
            int rowCount,
            boolean truncated,
            long totalDurationMs,
            long llmDurationMs,
            long sqlDurationMs,
            VerificationResult verification
    ) {
    }

    /**
     * 数值复算校验结果 record。
     */
    public record VerificationResult(
            boolean required,
            boolean passed,
            String details,
            String verificationCode,
            long durationMs
    ) {
    }

    /**
     * NL2SQL 查询（含数值复算校验）：自然语言 → SQL → 执行 → 复算校验 → 返回结果。
     *
     * @param question          用户自然语言问题
     * @param maxRows           最大返回行数（默认 100）
     * @param requireVerification 是否要求数值复算校验（默认 true）
     * @return 查询结果（含 SQL 展示 + 复算报告）
     */
    public QueryResult queryWithVerification(String question, Integer maxRows, Boolean requireVerification) {
        // 1. 执行 NL2SQL 查询（不含 verification）
        QueryResult baseResult = query(question, maxRows);

        // 2. 数值复算校验（如要求）
        VerificationResult verification = null;
        if (requireVerification == null || requireVerification) {
            verification = verifyNumericalConclusions(
                    baseResult.question(),
                    baseResult.sql(),
                    baseResult.columns(),
                    baseResult.rows()
            );
        }

        // 3. 组装完整结果
        return new QueryResult(
                baseResult.question(),
                baseResult.sql(),
                baseResult.columns(),
                baseResult.rows(),
                baseResult.rowCount(),
                baseResult.truncated(),
                baseResult.totalDurationMs() + (verification != null ? verification.durationMs() : 0),
                baseResult.llmDurationMs(),
                baseResult.sqlDurationMs(),
                verification
        );
    }

    /**
     * 数值复算校验：提取结论 → 生成代码 → 沙箱执行 → 比对。
     */
    private VerificationResult verifyNumericalConclusions(
            String question, String sql, List<String> columns, List<List<Object>> rows) {

        long start = System.currentTimeMillis();

        // 1. 提取数值结论（LLM）
        String conclusionsJson;
        try {
            conclusionsJson = extractNumericalConclusions(question, columns, rows);
        } catch (Exception e) {
            return new VerificationResult(false, false,
                    "提取数值结论失败：" + e.getMessage(), null,
                    System.currentTimeMillis() - start);
        }

        // 无数值结论 → 跳过复算
        if (conclusionsJson == null || conclusionsJson.isBlank() || "[]".equals(conclusionsJson.trim())) {
            return new VerificationResult(false, true,
                    "无数值结论，跳过复算", null,
                    System.currentTimeMillis() - start);
        }

        // 2. 生成复算代码（LLM）
        String verificationCode;
        try {
            verificationCode = generateVerificationCode(sql, columns, rows, conclusionsJson);
        } catch (Exception e) {
            return new VerificationResult(true, false,
                    "生成复算代码失败：" + e.getMessage(), null,
                    System.currentTimeMillis() - start);
        }

        // 3. 沙箱执行（Python）
        CodeSandboxService.CodeResult codeResult;
        try {
            codeResult = codeSandbox.execute("python3", verificationCode, null);
        } catch (Exception e) {
            return new VerificationResult(true, false,
                    "沙箱执行异常：" + e.getMessage(), verificationCode,
                    System.currentTimeMillis() - start);
        }

        // 4. 比对结果
        long duration = System.currentTimeMillis() - start;
        if (codeResult.exitCode() == 0 && codeResult.stdout().contains("复算通过")) {
            return new VerificationResult(true, true,
                    "数值复算通过", verificationCode, duration);
        } else {
            String details = codeResult.exitCode() != 0
                    ? "复算代码执行失败（exitCode=" + codeResult.exitCode() + "）：" + codeResult.stderr()
                    : "数值复算不一致：" + codeResult.stdout();
            return new VerificationResult(true, false, details, verificationCode, duration);
        }
    }

    /**
     * 提取数值结论（LLM）：从查询结果中提取数值型结论。
     */
    private String extractNumericalConclusions(String question, List<String> columns, List<List<Object>> rows) {
        String systemPrompt = """
                你是数据分析专家，从查询结果中提取数值结论。
                输出 JSON 数组，每个元素包含字段名和数值。
                如果结果无数值型数据（如纯文本列表），输出空数组 []。
                只输出 JSON，不要解释。
                """;

        String userPrompt = String.format("""
                用户问题：%s
                查询列：%s
                查询结果（前 5 行）：%s

                数值结论（JSON 数组）：
                """,
                question,
                String.join(", ", columns),
                formatRows(columns, rows, 5));

        return llm.generate(systemPrompt, userPrompt).trim();
    }

    /**
     * 生成复算代码（LLM）：基于查询结果生成 Python 复算代码。
     */
    private String generateVerificationCode(String sql, List<String> columns,
                                            List<List<Object>> rows, String conclusionsJson) {
        String systemPrompt = """
                你是 Python 数据分析专家，生成复算代码验证数值结论。
                输入：原 SQL + 查询结果（JSON）+ 数值结论（JSON）
                输出：Python 代码（基于结果重新计算，assert 比对）

                代码规则：
                1. 使用 json.loads 解析结果和结论
                2. 重新计算数值（如 SUM/AVG/COUNT）
                3. 用 assert abs(复算值 - 结论值) < 0.01 比对
                4. 最后 print("复算通过")
                5. 只输出代码，不要解释
                """;

        String userPrompt = String.format("""
                原 SQL：%s
                查询结果（JSON）：%s
                数值结论（JSON）：%s

                Python 复算代码：
                """,
                sql,
                formatRowsAsJson(columns, rows),
                conclusionsJson);

        String code = llm.generate(systemPrompt, userPrompt).trim();
        return cleanCode(code);
    }

    /**
     * 格式化行数据（用于 Prompt）。
     */
    private String formatRows(List<String> columns, List<List<Object>> rows, int maxRows) {
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(rows.size(), maxRows);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append("{");
            List<Object> row = rows.get(i);
            for (int j = 0; j < columns.size() && j < row.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append("\"").append(columns.get(j)).append("\": ");
                Object val = row.get(j);
                if (val instanceof String) {
                    sb.append("\"").append(val).append("\"");
                } else {
                    sb.append(val);
                }
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 格式化行数据为 JSON（用于 Prompt）。
     */
    private String formatRowsAsJson(List<String> columns, List<List<Object>> rows) {
        try {
            List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (List<Object> row : rows) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 0; i < columns.size() && i < row.size(); i++) {
                    map.put(columns.get(i), row.get(i));
                }
                list.add(map);
            }
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 清理代码（去除 markdown 代码块标记）。
     */
    private String cleanCode(String code) {
        if (code == null) return "";
        code = code.trim();
        // 去除 ```python ... ``` 或 ``` ... ```
        if (code.startsWith("```")) {
            int start = code.indexOf('\n');
            int end = code.lastIndexOf("```");
            if (start > 0 && end > start) {
                code = code.substring(start + 1, end).trim();
            }
        }
        return code;
    }
}
