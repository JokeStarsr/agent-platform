package com.agent.capability.dataagent;

import com.agent.data.dataagent.DataLineageRepository.DataLineage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * L4 能力层：分析报告导出服务（W16 Step 4）
 * <p>将 Data Agent 分析结果导出为 Markdown 或 HTML 报告，包含：
 * <ul>
 *   <li>问题与 SQL</li>
 *   <li>数据表格</li>
 *   <li>图表配置（ECharts）</li>
 *   <li>分析结论</li>
 *   <li>数据血缘</li>
 *   <li>验证结果</li>
 * </ul>
 */
@Service
public class ReportExporter {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * 导出为 Markdown 报告。
     */
    public String exportMarkdown(String question,
                                 DataAgentService.QueryResult queryResult,
                                 ChartGeneratorService.ChartResult chartResult,
                                 ConclusionVerifier.VerificationResult verification,
                                 DataLineage lineage) {
        StringBuilder md = new StringBuilder();

        // 标题
        md.append("# Data Agent 分析报告\n\n");
        md.append("**生成时间**: ").append(DATE_FORMATTER.format(Instant.now())).append("\n\n");

        // 问题
        md.append("## 问题\n\n");
        md.append(question).append("\n\n");

        // SQL
        md.append("## 生成的 SQL\n\n");
        md.append("```sql\n");
        md.append(queryResult.sql()).append("\n");
        md.append("```\n\n");

        // 数据表格
        md.append("## 查询结果\n\n");
        md.append("**行数**: ").append(queryResult.rowCount());
        if (queryResult.truncated()) {
            md.append(" (已截断)");
        }
        md.append("\n\n");

        if (!queryResult.rows().isEmpty()) {
            md.append("| ").append(String.join(" | ", queryResult.columns())).append(" |\n");
            md.append("| ").append("-".repeat(queryResult.columns().size() * 3)).append(" |\n");
            for (List<Object> row : queryResult.rows()) {
                md.append("| ");
                for (Object cell : row) {
                    md.append(cell != null ? cell.toString() : "").append(" | ");
                }
                md.append("\n");
            }
            md.append("\n");
        }

        // 图表
        if (chartResult != null && !"empty".equals(chartResult.chartType())) {
            md.append("## 图表\n\n");
            md.append("**类型**: ").append(chartResult.description()).append("\n\n");
            md.append("**ECharts 配置**:\n\n");
            md.append("```json\n");
            md.append(formatJson(chartResult.echartsOption())).append("\n");
            md.append("```\n\n");
        }

        // 分析结论（如果有额外生成）
        // 注意：QueryResult 当前不包含 analysisText 字段
        // 此部分留作扩展，可由外部 LLM 分析生成

        // 验证结果
        if (verification != null) {
            md.append("## 数值验证\n\n");
            md.append("**状态**: ").append(verification.allPassed() ? "✅ 全部通过" : "❌ 存在不一致").append("\n\n");
            md.append("**摘要**: ").append(verification.summary()).append("\n\n");

            if (!verification.details().isEmpty()) {
                md.append("### 验证详情\n\n");
                md.append("| 结论 | 声称值 | 复算值 | 状态 |\n");
                md.append("| --- | --- | --- | --- |\n");
                for (var detail : verification.details()) {
                    md.append("| ").append(detail.description())
                            .append(" | ").append(detail.claimValue())
                            .append(" | ").append(detail.recalcValue())
                            .append(" | ").append(detail.passed() ? "✅" : "❌")
                            .append(" |\n");
                }
                md.append("\n");
            }
        }

        // 数据血缘
        if (lineage != null) {
            md.append("## 数据血缘\n\n");
            md.append("**追踪 ID**: `").append(lineage.traceId()).append("`\n\n");
            md.append("**涉及表**: ").append(String.join(", ", lineage.tablesUsed())).append("\n\n");
            md.append("**涉及列**: ").append(lineage.columnsUsed().size()).append(" 列\n\n");
            md.append("**耗时**: ").append(lineage.durationMs()).append(" ms\n\n");
        }

        // 页脚
        md.append("---\n\n");
        md.append("*本报告由 Agent Platform Data Agent 自动生成*\n");

        return md.toString();
    }

    /**
     * 导出为 HTML 报告。
     */
    public String exportHtml(String question,
                             DataAgentService.QueryResult queryResult,
                             ChartGeneratorService.ChartResult chartResult,
                             ConclusionVerifier.VerificationResult verification,
                             DataLineage lineage) {
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>Data Agent 分析报告</title>\n");
        html.append("  <script src=\"https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js\"></script>\n");
        html.append("  <style>\n");
        html.append("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
        html.append("max-width: 1200px; margin: 0 auto; padding: 20px; background: #f5f5f5; }\n");
        html.append("    h1 { color: #1a1a1a; border-bottom: 3px solid #2563eb; padding-bottom: 10px; }\n");
        html.append("    h2 { color: #2563eb; margin-top: 30px; }\n");
        html.append("    pre { background: #f8f9fa; padding: 15px; border-radius: 8px; overflow-x: auto; }\n");
        html.append("    code { background: #e9ecef; padding: 2px 6px; border-radius: 4px; }\n");
        html.append("    table { width: 100%; border-collapse: collapse; margin: 15px 0; background: white; }\n");
        html.append("    th, td { padding: 10px; border: 1px solid #dee2e6; text-align: left; }\n");
        html.append("    th { background: #2563eb; color: white; }\n");
        html.append("    tr:nth-child(even) { background: #f8f9fa; }\n");
        html.append("    .chart-container { width: 100%; height: 400px; background: white; ");
        html.append("border-radius: 8px; padding: 15px; margin: 15px 0; }\n");
        html.append("    .verification-pass { color: #16a34a; font-weight: bold; }\n");
        html.append("    .verification-fail { color: #dc2626; font-weight: bold; }\n");
        html.append("    .metadata { background: #e7f3ff; padding: 15px; border-radius: 8px; ");
        html.append("border-left: 4px solid #2563eb; margin: 15px 0; }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");

        // 标题
        html.append("<h1>📊 Data Agent 分析报告</h1>\n");
        html.append("<p><strong>生成时间</strong>: ").append(DATE_FORMATTER.format(Instant.now())).append("</p>\n");

        // 问题
        html.append("<h2>❓ 问题</h2>\n");
        html.append("<p>").append(escapeHtml(question)).append("</p>\n");

        // SQL
        html.append("<h2>💻 生成的 SQL</h2>\n");
        html.append("<pre><code class=\"language-sql\">").append(escapeHtml(queryResult.sql())).append("</code></pre>\n");

        // 数据表格
        html.append("<h2>📋 查询结果</h2>\n");
        html.append("<p><strong>行数</strong>: ").append(queryResult.rowCount());
        if (queryResult.truncated()) {
            html.append(" <span style=\"color: #d97706;\">(已截断)</span>");
        }
        html.append("</p>\n");

        if (!queryResult.rows().isEmpty()) {
            html.append("<table>\n");
            html.append("  <thead>\n    <tr>\n");
            for (String col : queryResult.columns()) {
                html.append("      <th>").append(escapeHtml(col)).append("</th>\n");
            }
            html.append("    </tr>\n  </thead>\n");
            html.append("  <tbody>\n");
            for (List<Object> row : queryResult.rows()) {
                html.append("    <tr>\n");
                for (Object cell : row) {
                    html.append("      <td>").append(cell != null ? escapeHtml(cell.toString()) : "").append("</td>\n");
                }
                html.append("    </tr>\n");
            }
            html.append("  </tbody>\n");
            html.append("</table>\n");
        }

        // 图表
        if (chartResult != null && !"empty".equals(chartResult.chartType())) {
            html.append("<h2>📈 图表</h2>\n");
            html.append("<p><strong>类型</strong>: ").append(escapeHtml(chartResult.description())).append("</p>\n");
            html.append("<div id=\"chart\" class=\"chart-container\"></div>\n");
            html.append("<script>\n");
            html.append("  var chart = echarts.init(document.getElementById('chart'));\n");
            html.append("  var option = ").append(formatJson(chartResult.echartsOption())).append(";\n");
            html.append("  chart.setOption(option);\n");
            html.append("</script>\n");
        }

        // 分析结论（如果有额外生成）
        // 注意：QueryResult 当前不包含 analysisText 字段
        // 此部分留作扩展，可由外部 LLM 分析生成

        // 验证结果
        if (verification != null) {
            html.append("<h2>✅ 数值验证</h2>\n");
            String statusClass = verification.allPassed() ? "verification-pass" : "verification-fail";
            String statusText = verification.allPassed() ? "✅ 全部通过" : "❌ 存在不一致";
            html.append("<p><strong>状态</strong>: <span class=\"").append(statusClass).append("\">")
                    .append(statusText).append("</span></p>\n");
            html.append("<p><strong>摘要</strong>: ").append(escapeHtml(verification.summary())).append("</p>\n");

            if (!verification.details().isEmpty()) {
                html.append("<h3>验证详情</h3>\n");
                html.append("<table>\n");
                html.append("  <thead>\n    <tr>\n");
                html.append("      <th>结论</th>\n      <th>声称值</th>\n      <th>复算值</th>\n      <th>状态</th>\n");
                html.append("    </tr>\n  </thead>\n");
                html.append("  <tbody>\n");
                for (var detail : verification.details()) {
                    html.append("    <tr>\n");
                    html.append("      <td>").append(escapeHtml(detail.description())).append("</td>\n");
                    html.append("      <td>").append(escapeHtml(detail.claimValue())).append("</td>\n");
                    html.append("      <td>").append(escapeHtml(detail.recalcValue())).append("</td>\n");
                    html.append("      <td>").append(detail.passed() ? "✅" : "❌").append("</td>\n");
                    html.append("    </tr>\n");
                }
                html.append("  </tbody>\n");
                html.append("</table>\n");
            }
        }

        // 数据血缘
        if (lineage != null) {
            html.append("<h2>🔗 数据血缘</h2>\n");
            html.append("<div class=\"metadata\">\n");
            html.append("  <p><strong>追踪 ID</strong>: <code>").append(lineage.traceId()).append("</code></p>\n");
            html.append("  <p><strong>涉及表</strong>: ").append(String.join(", ", lineage.tablesUsed())).append("</p>\n");
            html.append("  <p><strong>涉及列</strong>: ").append(lineage.columnsUsed().size()).append(" 列</p>\n");
            html.append("  <p><strong>耗时</strong>: ").append(lineage.durationMs()).append(" ms</p>\n");
            html.append("</div>\n");
        }

        // 页脚
        html.append("<hr>\n");
        html.append("<p style=\"text-align: center; color: #6b7280;\">\n");
        html.append("  <em>本报告由 Agent Platform Data Agent 自动生成</em>\n");
        html.append("</p>\n");

        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    // ========== 辅助方法 ==========

    private String formatJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
