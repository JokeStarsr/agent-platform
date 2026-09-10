package com.agent.capability.dataagent;

import com.agent.model.llm.LlmGateway;
import com.agent.tool.sandbox.CodeSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L4 能力层：结论复算引擎（W16 Step 2）
 * <p>从分析结果中提取数值结论，生成独立 Python 复算代码，在沙箱中执行验证。
 * 不一致即拦截，确保数据准确性（Data Agent 编数字是最高频事故）。</p>
 */
@Service
public class ConclusionVerifier {

    private static final Logger log = LoggerFactory.getLogger(ConclusionVerifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // 数值提取模式（支持中文数字单位）
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?(?:\\s*(?:万|亿|%|百分)))");

    private final LlmGateway llm;
    private final CodeSandboxService sandbox;

    public ConclusionVerifier(LlmGateway llm, CodeSandboxService sandbox) {
        this.llm = llm;
        this.sandbox = sandbox;
    }

    /**
     * 验证分析结论的数值准确性。
     *
     * @param question    用户问题
     * @param analysis    LLM 生成的分析文本
     * @param queryResult 查询结果（columns + rows）
     * @return 验证结果
     */
    public VerificationResult verify(String question, String analysis, DataAgentService.QueryResult queryResult) {
        if (analysis == null || analysis.isBlank()) {
            return new VerificationResult(true, "无分析文本", List.of(), 0);
        }

        // 1. 提取数值结论
        List<NumericConclusion> conclusions = extractConclusions(question, analysis, queryResult);
        if (conclusions.isEmpty()) {
            return new VerificationResult(true, "无数值结论需验证", List.of(), 0);
        }

        // 2. 生成 Python 复算代码
        String verificationCode = generateVerificationCode(queryResult, conclusions);

        // 3. 沙箱执行
        long startTime = System.currentTimeMillis();
        CodeSandboxService.CodeResult codeResult = sandbox.execute(verificationCode, "python", null);
        long duration = System.currentTimeMillis() - startTime;

        // 4. 解析结果
        if (codeResult.exitCode() != 0) {
            // 执行失败时，将所有结论标记为未验证
            List<VerificationDetail> failedDetails = conclusions.stream()
                    .map(c -> new VerificationDetail(c.description(), c.claimValue(), "N/A", false))
                    .toList();
            return new VerificationResult(false,
                    "复算代码执行失败: " + codeResult.stderr(),
                    failedDetails, duration);
        }

        // 5. 比对结果
        List<VerificationDetail> details = parseVerificationOutput(codeResult.stdout(), conclusions);
        boolean allPassed = details.stream().allMatch(VerificationDetail::passed);

        String summary = allPassed
                ? String.format("全部 %d 个数值结论验证通过", conclusions.size())
                : String.format("%d/%d 个数值结论验证失败",
                details.stream().filter(d -> !d.passed()).count(), conclusions.size());

        return new VerificationResult(allPassed, summary, details, duration);
    }

    /**
     * 从分析文本中提取数值结论。
     */
    private List<NumericConclusion> extractConclusions(String question, String analysis,
                                                       DataAgentService.QueryResult queryResult) {
        List<NumericConclusion> conclusions = new ArrayList<>();

        // 使用 LLM 提取结构化结论
        String prompt = buildExtractionPrompt(question, analysis, queryResult);
        String response = llm.generate(prompt, "");

        // 解析 LLM 返回的 JSON
        try {
            List<Map<String, Object>> extracted = JSON.readValue(response,
                    JSON.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> item : extracted) {
                String description = (String) item.getOrDefault("description", "");
                String claimValue = String.valueOf(item.getOrDefault("claim_value", ""));
                String calculationMethod = (String) item.getOrDefault("calculation_method", "");
                String columnRef = (String) item.getOrDefault("column_ref", "");

                if (!claimValue.isBlank()) {
                    conclusions.add(new NumericConclusion(description, claimValue, calculationMethod, columnRef));
                }
            }
        } catch (Exception e) {
            log.warn("解析结论提取结果失败: {}", e.getMessage());
            // 回退到正则提取
            conclusions.addAll(extractWithRegex(analysis));
        }

        return conclusions;
    }

    /**
     * 构建结论提取 Prompt。
     */
    private String buildExtractionPrompt(String question, String analysis, DataAgentService.QueryResult queryResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("从以下分析文本中提取所有数值结论，返回 JSON 数组。\n\n");
        sb.append("用户问题: ").append(question).append("\n\n");
        sb.append("查询结果列: ").append(String.join(", ", queryResult.columns())).append("\n\n");
        sb.append("分析文本:\n").append(analysis).append("\n\n");
        sb.append("返回格式（JSON 数组，每个元素包含）:\n");
        sb.append("- description: 结论描述（如'华东地区销售额最高'）\n");
        sb.append("- claim_value: 声明的数值（如'1234.56'）\n");
        sb.append("- calculation_method: 计算方法（如'SUM of amount WHERE region=华东'）\n");
        sb.append("- column_ref: 涉及的列名（如'amount'）\n\n");
        sb.append("只返回 JSON 数组，不要其他内容。");

        return sb.toString();
    }

    /**
     * 正则回退提取数值。
     */
    private List<NumericConclusion> extractWithRegex(String analysis) {
        List<NumericConclusion> conclusions = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(analysis);

        while (matcher.find()) {
            String value = matcher.group(1);
            // 获取前后 20 字符作为上下文
            int start = Math.max(0, matcher.start() - 20);
            int end = Math.min(analysis.length(), matcher.end() + 20);
            String context = analysis.substring(start, end).trim();

            conclusions.add(new NumericConclusion(context, value, "regex_extract", ""));
        }

        return conclusions;
    }

    /**
     * 生成 Python 复算代码。
     */
    private String generateVerificationCode(DataAgentService.QueryResult queryResult,
                                            List<NumericConclusion> conclusions) {
        StringBuilder code = new StringBuilder();
        code.append("import json\n\n");

        // 注入查询结果
        code.append("# 查询结果数据\n");
        code.append("data = [\n");
        try {
            for (List<Object> row : queryResult.rows()) {
                code.append("    ").append(JSON.writeValueAsString(row)).append(",\n");
            }
            code.append("]\n");
            code.append("columns = ").append(JSON.writeValueAsString(queryResult.columns())).append("\n\n");
        } catch (Exception e) {
            log.error("序列化查询结果失败: {}", e.getMessage());
            // 回退到简单字符串表示
            code.append("    # 序列化失败，使用空数据\n");
            code.append("]\n");
            code.append("columns = []\n\n");
        }

        // 构建列索引映射
        code.append("# 列索引映射\n");
        code.append("col_idx = {col: i for i, col in enumerate(columns)}\n\n");

        // 为每个结论生成验证代码
        code.append("# 验证每个数值结论\n");
        code.append("results = []\n");

        for (int i = 0; i < conclusions.size(); i++) {
            NumericConclusion c = conclusions.get(i);
            code.append(String.format("\n# 结论 %d: %s\n", i + 1, c.description()));
            code.append(String.format("claim_value_%d = %s\n", i + 1, normalizeNumber(c.claimValue())));

            // 根据计算方法生成复算代码
            String recalcCode = generateRecalculationCode(c, queryResult.columns());
            code.append(recalcCode);
            code.append(String.format("recalc_value_%d = recalc\n", i + 1));

            // 比对
            code.append(String.format("diff_%d = abs(float(claim_value_%d) - float(recalc_value_%d))\n",
                    i + 1, i + 1, i + 1));
            code.append(String.format("tolerance_%d = max(0.01, abs(float(claim_value_%d)) * 0.01)  # 1%% 容差\n",
                    i + 1, i + 1));
            code.append(String.format("passed_%d = diff_%d <= tolerance_%d\n", i + 1, i + 1, i + 1));
            code.append(String.format("results.append({'idx': %d, 'desc': '%s', 'claim': claim_value_%d, " +
                            "'recalc': recalc_value_%d, 'passed': passed_%d})\n",
                    i + 1, escapeQuotes(c.description()), i + 1, i + 1, i + 1));
        }

        // 输出结果
        code.append("\n# 输出验证结果\n");
        code.append("for r in results:\n");
        code.append("    status = 'PASS' if r['passed'] else 'FAIL'\n");
        code.append("    print(f\"{status}|{r['desc']}|{r['claim']}|{r['recalc']}\")\n");

        return code.toString();
    }

    /**
     * 根据结论生成复算代码片段。
     */
    private String generateRecalculationCode(NumericConclusion conclusion, List<String> columns) {
        String method = conclusion.calculationMethod().toLowerCase();

        // SUM 聚合
        if (method.contains("sum") && !conclusion.columnRef().isEmpty()) {
            return String.format("recalc = sum(row[col_idx['%s']] for row in data)\n", conclusion.columnRef());
        }

        // AVG 聚合
        if (method.contains("avg") && !conclusion.columnRef().isEmpty()) {
            return String.format("recalc = sum(row[col_idx['%s']] for row in data) / len(data)\n",
                    conclusion.columnRef());
        }

        // COUNT 聚合
        if (method.contains("count")) {
            return "recalc = len(data)\n";
        }

        // MAX 聚合
        if (method.contains("max") && !conclusion.columnRef().isEmpty()) {
            return String.format("recalc = max(row[col_idx['%s']] for row in data)\n", conclusion.columnRef());
        }

        // MIN 聚合
        if (method.contains("min") && !conclusion.columnRef().isEmpty()) {
            return String.format("recalc = min(row[col_idx['%s']] for row in data)\n", conclusion.columnRef());
        }

        // 默认：直接取第一行第一列
        return "recalc = data[0][0] if data else 0\n";
    }

    /**
     * 规范化数字字符串（处理中文单位）。
     */
    private String normalizeNumber(String value) {
        value = value.trim().replace(",", "");
        if (value.contains("万")) {
            double num = Double.parseDouble(value.replace("万", ""));
            return String.valueOf(num * 10000);
        }
        if (value.contains("亿")) {
            double num = Double.parseDouble(value.replace("亿", ""));
            return String.valueOf(num * 100000000);
        }
        if (value.contains("%") || value.contains("百分")) {
            return value.replace("%", "").replace("百分", "");
        }
        return value;
    }

    /**
     * 转义引号。
     */
    private String escapeQuotes(String s) {
        return s.replace("'", "\\'").replace("\"", "\\\"");
    }

    /**
     * 解析验证输出。
     */
    private List<VerificationDetail> parseVerificationOutput(String stdout, List<NumericConclusion> conclusions) {
        List<VerificationDetail> details = new ArrayList<>();
        String[] lines = stdout.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("|")) continue;

            String[] parts = line.split("\\|");
            if (parts.length < 4) continue;

            boolean passed = "PASS".equals(parts[0]);
            String description = parts[1];
            String claimValue = parts[2];
            String recalcValue = parts[3];

            details.add(new VerificationDetail(description, claimValue, recalcValue, passed));
        }

        // 如果解析失败，回退到结论列表
        if (details.isEmpty()) {
            for (NumericConclusion c : conclusions) {
                details.add(new VerificationDetail(c.description(), c.claimValue(), "N/A", false));
            }
        }

        return details;
    }

    /**
     * 数值结论记录。
     */
    public record NumericConclusion(String description, String claimValue,
                                    String calculationMethod, String columnRef) {
    }

    /**
     * 验证详情记录。
     */
    public record VerificationDetail(String description, String claimValue,
                                     String recalcValue, boolean passed) {
    }

    /**
     * 验证结果记录。
     */
    public record VerificationResult(boolean allPassed, String summary,
                                     List<VerificationDetail> details, long durationMs) {
    }
}
