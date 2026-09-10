package com.agent.sdk.examples;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Agent Platform Java SDK 示例
 * W17 开放平台接入示例代码
 */
public class AgentPlatformClient {

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 初始化客户端
     *
     * @param apiKey  API Key（sk- 开头）
     * @param baseUrl 平台基础 URL
     */
    public AgentPlatformClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * NL2SQL 数据查询
     *
     * @param question   自然语言问题
     * @param maxRows    最大返回行数
     * @param enableChart 是否生成图表
     * @return 查询结果（含 SQL、表格数据、图表配置）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> query(String question, int maxRows, boolean enableChart) throws Exception {
        String url = baseUrl + "/api/data-agent/query";
        String body = objectMapper.writeValueAsString(Map.of(
                "question", question,
                "maxRows", maxRows,
                "enableChart", enableChart
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    /**
     * NL2SQL + 结论复算校验
     *
     * @param question 自然语言问题
     * @param maxRows  最大返回行数
     * @return 查询结果（含验证报告）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryWithVerification(String question, int maxRows) throws Exception {
        String url = baseUrl + "/api/data-agent/query/verify";
        String body = objectMapper.writeValueAsString(Map.of(
                "question", question,
                "maxRows", maxRows,
                "requireVerification", true
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    /**
     * RAG 知识检索
     *
     * @param query 检索查询
     * @param topK  返回结果数
     * @return 检索结果（含文档片段、相关性分数）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String query, int topK) throws Exception {
        String url = baseUrl + "/api/rag/search";
        String body = objectMapper.writeValueAsString(Map.of(
                "query", query,
                "topK", topK
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    /**
     * 查询用量统计
     *
     * @param period 统计周期（today/yesterday/week/month）
     * @return 用量统计（Token 数、费用、环比）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUsageStats(String period) throws Exception {
        String url = baseUrl + "/api/cost-dashboard/usage/tenant?period=" + period;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    /**
     * 查询 API Key 列表
     *
     * @return API Key 列表（仅前缀，不含明文）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listApiKeys() throws Exception {
        String url = baseUrl + "/api/open/keys";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    // ========== 使用示例 ==========

    public static void main(String[] args) {
        // 初始化客户端（替换为你的 API Key）
        AgentPlatformClient client = new AgentPlatformClient(
                "sk-abc123def456ghi789jkl012mno345pqr",
                "http://localhost:8082"
        );

        try {
            // 示例 1: NL2SQL 查询
            System.out.println("=== 示例 1: NL2SQL 查询 ===");
            Map<String, Object> result1 = client.query("上月销售额最高的 3 个产品", 100, true);
            Map<String, Object> data1 = (Map<String, Object>) result1.get("data");
            System.out.println("问题: " + data1.get("question"));
            System.out.println("SQL: " + data1.get("sql"));
            System.out.println("行数: " + data1.get("rowCount"));
            System.out.println("数据: " + data1.get("rows"));
            System.out.println();

            // 示例 2: NL2SQL + 复算校验
            System.out.println("=== 示例 2: NL2SQL + 复算校验 ===");
            Map<String, Object> result2 = client.queryWithVerification("上月销售额和增长率", 100);
            Map<String, Object> data2 = (Map<String, Object>) result2.get("data");
            Map<String, Object> verification = (Map<String, Object>) data2.get("verification");
            System.out.println("验证通过: " + verification.get("allPassed"));
            System.out.println("验证摘要: " + verification.get("summary"));
            for (Map<String, Object> detail : (java.util.List<Map<String, Object>>) verification.get("details")) {
                String status = (Boolean) detail.get("passed") ? "✅" : "❌";
                System.out.println("  " + status + " " + detail.get("description") +
                        ": 声称 " + detail.get("claimValue") +
                        ", 复算 " + detail.get("recalcValue"));
            }
            System.out.println();

            // 示例 3: RAG 知识检索
            System.out.println("=== 示例 3: RAG 知识检索 ===");
            Map<String, Object> result3 = client.search("差旅报销标准", 5);
            java.util.List<Map<String, Object>> docs = (java.util.List<Map<String, Object>>) result3.get("data");
            System.out.println("检索结果数: " + docs.size());
            for (int i = 0; i < Math.min(3, docs.size()); i++) {
                Map<String, Object> doc = docs.get(i);
                String content = (String) doc.get("content");
                System.out.println("  " + (i + 1) + ". " + content.substring(0, Math.min(100, content.length())) +
                        "... (分数: " + String.format("%.4f", (Double) doc.get("score")) + ")");
            }
            System.out.println();

            // 示例 4: 查询用量统计
            System.out.println("=== 示例 4: 查询用量统计 ===");
            Map<String, Object> result4 = client.getUsageStats("today");
            Map<String, Object> data4 = (Map<String, Object>) result4.get("data");
            System.out.println("今日 Token: " + data4.get("totalTokens"));
            System.out.println("今日费用: ¥" + String.format("%.2f", data4.get("totalCost")));
            System.out.println("Token 环比: " + String.format("%.1f%%", data4.get("tokenGrowthRate")));
            System.out.println();

            // 示例 5: 查询 API Key 列表
            System.out.println("=== 示例 5: 查询 API Key 列表 ===");
            Map<String, Object> result5 = client.listApiKeys();
            for (Map<String, Object> key : (java.util.List<Map<String, Object>>) result5.get("data")) {
                System.out.println("  - " + key.get("apiKeyPrefix") + "... (" +
                        key.get("name") + ", " + key.get("status") + ")");
            }
            System.out.println();

        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
