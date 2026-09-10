package com.agent.capability.dataagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * L4 能力层：图表生成服务（W16 Step 1）
 * <p>根据查询结果的列类型和数据特征，自动选择图表类型并生成 ECharts 配置 JSON。</p>
 * <p>图表类型选择规则：
 * <ul>
 *   <li>时间序列（date/timestamp 列 + 数值列）→ 折线图</li>
 *   <li>分类 + 数值（1 个分类列 + 1-2 个数值列）→ 柱状图</li>
 *   <li>比例/占比（分类列 + 百分比/占比列）→ 饼图</li>
 *   <li>单值聚合（COUNT/SUM/AVG 等）→ KPI 卡片</li>
 *   <li>多列复杂数据 → 表格</li>
 * </ul>
 */
@Service
public class ChartGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ChartGeneratorService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // 时间相关列名模式
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?i)(date|time|month|week|year|day|hour|minute|second|created|updated|timestamp)");

    // 数值相关列名模式
    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
            "(?i)(count|sum|avg|total|amount|price|quantity|num|number|score|rate|ratio|percent)");

    // 分类相关列名模式
    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "(?i)(name|category|type|status|level|region|department|group|class)");

    /**
     * 根据查询结果生成 ECharts 配置。
     *
     * @param question 用户问题（用于标题）
     * @param columns  列名列表
     * @param rows     数据行
     * @return ECharts option JSON
     */
    public ChartResult generateChart(String question, List<String> columns, List<List<Object>> rows) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return new ChartResult("empty", "无数据", emptyOption());
        }

        ChartType type = detectChartType(columns, rows);
        Map<String, Object> option = switch (type) {
            case LINE -> generateLineChart(question, columns, rows);
            case BAR -> generateBarChart(question, columns, rows);
            case PIE -> generatePieChart(question, columns, rows);
            case KPI -> generateKPIChart(question, columns, rows);
            case TABLE -> generateTableOption(question, columns, rows);
        };

        return new ChartResult(type.name().toLowerCase(), type.getDescription(), option);
    }

    /**
     * 检测适合的图表类型。
     */
    private ChartType detectChartType(List<String> columns, List<List<Object>> rows) {
        int colCount = columns.size();
        int rowCount = rows.size();

        // 单行单列 → KPI
        if (rowCount == 1 && colCount <= 2) {
            return ChartType.KPI;
        }

        // 分析列类型
        int timeColIdx = findTimeColumn(columns);
        int numericColIdx = findNumericColumn(columns, 0);
        int categoryColIdx = findCategoryColumn(columns);

        // 时间序列 → 折线图
        if (timeColIdx >= 0 && numericColIdx >= 0 && timeColIdx != numericColIdx) {
            return ChartType.LINE;
        }

        // 分类 + 数值 → 柱状图或饼图
        if (categoryColIdx >= 0 && numericColIdx >= 0 && categoryColIdx != numericColIdx) {
            // 检查是否是比例/占比
            String numericColName = columns.get(numericColIdx).toLowerCase();
            if (numericColName.contains("percent") || numericColName.contains("ratio") ||
                    numericColName.contains("rate") || numericColName.contains("占比")) {
                return ChartType.PIE;
            }
            // 数据行数少（≤10）→ 饼图，否则 → 柱状图
            return rowCount <= 10 ? ChartType.PIE : ChartType.BAR;
        }

        // 多列复杂数据 → 表格
        return ChartType.TABLE;
    }

    /**
     * 查找时间列索引。
     */
    private int findTimeColumn(List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (TIME_PATTERN.matcher(columns.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找数值列索引（从 startIdx 开始）。
     */
    private int findNumericColumn(List<String> columns, int startIdx) {
        for (int i = startIdx; i < columns.size(); i++) {
            if (NUMERIC_PATTERN.matcher(columns.get(i)).find()) {
                return i;
            }
        }
        // 如果没找到，返回第一个非时间非分类列
        for (int i = startIdx; i < columns.size(); i++) {
            String col = columns.get(i).toLowerCase();
            if (!TIME_PATTERN.matcher(col).find() && !CATEGORY_PATTERN.matcher(col).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找分类列索引。
     */
    private int findCategoryColumn(List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (CATEGORY_PATTERN.matcher(columns.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 生成折线图配置。
     */
    private Map<String, Object> generateLineChart(String question, List<String> columns, List<List<Object>> rows) {
        int timeIdx = findTimeColumn(columns);
        int valueIdx = findNumericColumn(columns, 0);
        if (valueIdx == timeIdx) {
            valueIdx = findNumericColumn(columns, timeIdx + 1);
        }

        List<String> xData = new ArrayList<>();
        List<Number> yData = new ArrayList<>();

        for (List<Object> row : rows) {
            if (timeIdx >= 0 && timeIdx < row.size()) {
                xData.add(String.valueOf(row.get(timeIdx)));
            }
            if (valueIdx >= 0 && valueIdx < row.size()) {
                yData.add(toNumber(row.get(valueIdx)));
            }
        }

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", question, "left", "center"));
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "category", "data", xData));
        option.put("yAxis", Map.of("type", "value"));
        option.put("series", List.of(Map.of(
                "name", columns.get(valueIdx),
                "type", "line",
                "data", yData,
                "smooth", true
        )));

        return option;
    }

    /**
     * 生成柱状图配置。
     */
    private Map<String, Object> generateBarChart(String question, List<String> columns, List<List<Object>> rows) {
        int categoryIdx = findCategoryColumn(columns);
        int valueIdx = findNumericColumn(columns, 0);
        if (valueIdx == categoryIdx) {
            valueIdx = findNumericColumn(columns, categoryIdx + 1);
        }

        List<String> xData = new ArrayList<>();
        List<Number> yData = new ArrayList<>();

        for (List<Object> row : rows) {
            if (categoryIdx >= 0 && categoryIdx < row.size()) {
                xData.add(String.valueOf(row.get(categoryIdx)));
            }
            if (valueIdx >= 0 && valueIdx < row.size()) {
                yData.add(toNumber(row.get(valueIdx)));
            }
        }

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", question, "left", "center"));
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "category", "data", xData));
        option.put("yAxis", Map.of("type", "value"));
        option.put("series", List.of(Map.of(
                "name", columns.get(valueIdx),
                "type", "bar",
                "data", yData
        )));

        return option;
    }

    /**
     * 生成饼图配置。
     */
    private Map<String, Object> generatePieChart(String question, List<String> columns, List<List<Object>> rows) {
        int categoryIdx = findCategoryColumn(columns);
        int valueIdx = findNumericColumn(columns, 0);
        if (valueIdx == categoryIdx) {
            valueIdx = findNumericColumn(columns, categoryIdx + 1);
        }

        List<Map<String, Object>> pieData = new ArrayList<>();
        for (List<Object> row : rows) {
            String name = categoryIdx >= 0 && categoryIdx < row.size()
                    ? String.valueOf(row.get(categoryIdx)) : "项" + (pieData.size() + 1);
            Number value = valueIdx >= 0 && valueIdx < row.size()
                    ? toNumber(row.get(valueIdx)) : 0;
            pieData.add(Map.of("name", name, "value", value));
        }

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", question, "left", "center"));
        option.put("tooltip", Map.of("trigger", "item"));
        option.put("legend", Map.of("orient", "vertical", "left", "left"));
        option.put("series", List.of(Map.of(
                "name", question,
                "type", "pie",
                "radius", "50%",
                "data", pieData
        )));

        return option;
    }

    /**
     * 生成 KPI 卡片配置。
     */
    private Map<String, Object> generateKPIChart(String question, List<String> columns, List<List<Object>> rows) {
        String label = columns.isEmpty() ? "数值" : columns.get(0);
        Object value = rows.isEmpty() || rows.get(0).isEmpty() ? 0 : rows.get(0).get(0);

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", question, "left", "center"));
        option.put("graphic", List.of(
                Map.of("type", "text", "left", "center", "top", "40%",
                        "style", Map.of("text", label, "fontSize", 16, "fill", "#666")),
                Map.of("type", "text", "left", "center", "top", "55%",
                        "style", Map.of("text", String.valueOf(value), "fontSize", 36, "fontWeight", "bold", "fill", "#333"))
        ));

        return option;
    }

    /**
     * 生成表格配置（ECharts 不直接支持表格，返回原始数据供前端渲染）。
     */
    private Map<String, Object> generateTableOption(String question, List<String> columns, List<List<Object>> rows) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", question, "left", "center"));
        option.put("type", "table");
        option.put("columns", columns);
        option.put("data", rows);
        return option;
    }

    /**
     * 空数据配置。
     */
    private Map<String, Object> emptyOption() {
        return Map.of("title", Map.of("text", "无数据", "left", "center"));
    }

    /**
     * 转换为数字。
     */
    private Number toNumber(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return (Number) obj;
        try {
            String s = obj.toString().trim();
            if (s.contains(".")) {
                return Double.parseDouble(s);
            } else {
                return Long.parseLong(s);
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 图表类型枚举。
     */
    private enum ChartType {
        LINE("折线图（时间序列数据）"),
        BAR("柱状图（分类对比数据）"),
        PIE("饼图（比例/占比数据）"),
        KPI("KPI 卡片（单值聚合）"),
        TABLE("表格（多列复杂数据）");

        private final String description;

        ChartType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 图表生成结果。
     */
    public record ChartResult(String chartType, String description, Map<String, Object> echartsOption) {
    }
}
