package com.agent.capability;

import com.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/** L4 AI 能力层：RAG 检索服务 v2 - 在线检索全管道 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 文档入库：Tika 解析 → 语义切块 → 向量化写入（租户打标） */
    public Result<Long> indexDocument(MultipartFile file, String tenantId) {
        try {
            TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
            List<Document> docs = reader.get();
            docs.forEach(d -> {
                d.getMetadata().put("tenant_id", tenantId);
                d.getMetadata().put("source", file.getOriginalFilename());
            });
            List<Document> chunks = splitter.apply(docs);
            if (chunks.isEmpty()) {
                return Result.error(400, "文档解析后无可切块的文本");
            }
            vectorStore.add(chunks);
            return Result.ok((long) chunks.size());
        } catch (Exception e) {
            log.error("文档入库失败: ex={}", e.getMessage(), e);
            return Result.error(500, "文档入库失败: " + e.getMessage());
        }
    }

    /** RAG 在线检索全管道 v2
     * 双管道协同：查询改写 → 混合检索 → 重排 → Top-K 注入 → 生成 + 引用溯源。
     * 对应ADS 5.1关键链路设计。
     */
    public Result<RagResult> search(String query, int topK, String tenantId) {
        long startNs = System.nanoTime();

        try {
            /** 阶段1：查询改写 - 指代消解/多路改写 */
            // QueryRewrite 是 static inner class，直接用字段访问
            QueryRewrite rewrite = rewriteQuery(query);
            // 字段是 public final，直接访问
            String rewrittenQuery = rewrite.rewritten;
            List<String> queryVariants = rewrite.variants;

            /** 阶段2：混合检索 - 向量+关键词 */
            List<ScoredChunk> hybridResults = hybridSearch(rewrittenQuery, tenantId, queryVariants, topK);

            /** 阶段3：重排 - 按分数降序排序 */
            List<ScoredChunk> reranked = rerankChunks(hybridResults, query, tenantId);

            /** 阶段4：Top-K注入 */
            List<ScoredChunk> selected = reranked.stream()
                    .limit(topK)
                    .collect(Collectors.toList());

            /** 阶段5：生成 + 引用溯源 */
            RagResult result = generateWithCitations(selected, query, tenantId);

            long durMs = (System.nanoTime() - startNs) / 1_000_000;
            result.setLatencyMs((int) durMs);
            return Result.ok(result);

        } catch (Exception e) {
            log.error("RAG在线检索失败: query={}, tenantId={}, ex={}", query, tenantId, e.getMessage(), e);
            return Result.error(500, "RAG检索服务异常: " + e.getMessage());
        }
    }

    /** 查询改写 - 返回 QueryRewrite 对象，字段直接公开 */
    private QueryRewrite rewriteQuery(String query) {
        // TODO: 实际部署中接入 LLM 或规则引擎
        // 目前按 "指代消解 + 多路改写" 简单实现
        String rewritten = query; // 实际应由模型根据上下文重写
        List<String> variants = new ArrayList<>();
        variants.add(query); // 原查询
        variants.add(query + " 怎么办"); // 变体：带疑问词
        variants.add("解决" + query); // 变体：带动词
        return new QueryRewrite(rewritten, variants);
    }

    /** 查询记录类 - 使用公有字段，避免 getter/setter 复杂性 */
    public static class QueryRewrite {
        public final String rewritten;
        public final List<String> variants;

        public QueryRewrite(String rewritten, List<String> variants) {
            this.rewritten = rewritten;
            this.variants = variants;
        }
    }

    /** 混合检索：向量+关键词 */
    private List<ScoredChunk> hybridSearch(String query, String tenantId, List<String> queryVariants, int topK) {
        // 1) 向量检索
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest vecReq = SearchRequest.builder()
                .query(query)
                .topK(topK * 2)
                .filterExpression(b.eq("tenant_id", tenantId).build())
                .build();
        List<Document> docs = vectorStore.similaritySearch(vecReq);
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }

        // 2) 转换为 ScoredChunk 列表
        // 说明：Spring AI 1.0.0 Document API 为 getText()（不是 getContent()），getScore() 可能为 null
        // TODO: 混合检索 = 向量 + BM25/关键词，此处先只实现向量检索，BM25 留待接入 Elasticsearch/Lucene
        List<ScoredChunk> chunks = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata();
            String tenant = metadata != null ? metadata.getOrDefault("tenant_id", "unknown").toString() : "unknown";
            String content = doc.getText();
            double score = doc.getScore() != null ? doc.getScore() : 0.5;
            chunks.add(new ScoredChunk(content, tenant, score));
        }
        return chunks;
    }

    /** 重排 - 按分数降序排序 */
    private List<ScoredChunk> rerankChunks(List<ScoredChunk> chunks, String query, String tenantId) {
        return chunks.stream()
                .sorted(Comparator.comparingDouble((ScoredChunk c) -> c.score).reversed())
                .collect(Collectors.toList());
    }

    /** 生成 + 引用溯源 */
    private RagResult generateWithCitations(List<ScoredChunk> selectedChunks, String query, String tenantId) {
        // 1) 构建带引用的系统Prompt
        StringBuilder contextBuilder = new StringBuilder();
        List<String> citations = new ArrayList<>();
        for (int i = 0; i < selectedChunks.size(); i++) {
            ScoredChunk chunk = selectedChunks.get(i);
            String citationRef = "【" + (i + 1) + "】";
            contextBuilder.append(citationRef).append(" ").append(chunk.getContent()).append("\n");
            citations.add(citationRef + ": 知识库切片");
        }

        String systemPrompt = buildSystemPrompt() + "\n" + contextBuilder.toString();

        // 2) 调用 LLM 生成答案（实际项目中注入LlmGateway）
        String userPrompt = "基于以上上下文，回答用户问题。如果答案不在上下文中说明，必须诚实回答“我不知道”。要求：1) 只基于提供的上下文回答，不外推；2) 必须对每个关键结论引用对应编号（如【1】、【2】）；3) 置信度低时须明确说明。\n\n用户问题：" + query;

        String answer = llmGatewayGenerate(systemPrompt, userPrompt);

        // 3) 组装结果
        List<String> sourceChunks = selectedChunks.stream()
                .map(ScoredChunk::getContent)
                .collect(Collectors.toList());

        return new RagResult(answer, citations, sourceChunks);
    }

    /** 系统Prompt */
    private String buildSystemPrompt() {
        return "你是企业级智能客服助手。你的职责是基于企业知识库回答用户问题。\n" +
                "核心规则：\n" +
                "1. 只基于提供的知识库内容回答，不凭个人经验或外部信息。\n" +
                "2. 答案必须逐句引用来源，格式：【编号】，对应前面上下文中出现的切片。\n" +
                "3. 如果知识库中没有答案，必须诚实回答“我不知道”，并主动提供转人工选项。\n" +
                "4. 对于涉及政策、法规、敏感信息的问题，必须进行置信度检查，低置信度时转人工。\n" +
                "5. 对输出内容进行安全过滤，拒绝生成违法、歧视、虚假信息。\n";
    }

    /** LLM生成占位符 */
    private String llmGatewayGenerate(String system, String user) {
        // 实际项目中应注入LlmGateway并调用
        // 此处返回占位符，实际部署时替换为真实调用
        return "【占位符】LLM生成答案（实际部署时通过LlmGateway调用DeepSeek）";
    }

    /** 辅助类：检索结果切片 */
    public static class ScoredChunk {
        private final String content;
        private final String tenantId;
        private final double score;

        public ScoredChunk(String content, String tenantId, double score) {
            this.content = content;
            this.tenantId = tenantId;
            this.score = score;
        }

        public String getContent() {
            return content;
        }

        public String getTenantId() {
            return tenantId;
        }

        public double getScore() {
            return score;
        }
    }

    /** RAG 检索结果封装 */
    public static class RagResult {
        private int code;
        private String message;
        private String answer;          // LLM 生成的答案
        private List<String> citations; // 引用列表 【1】 【2】 ...
        private List<String> sourceChunks; // 使用的切片内容
        private int latencyMs;          // 响应延迟 ms
        private double faithfulness;    // 忠实度评分（0-1）
        private double recallAtK;       // 召回率评分（0-1）

        public RagResult() {
        }

        /** 主构造：answer, citations, sourceChunks */
        public RagResult(int code, String message, String answer, List<String> citations,
                         List<String> sourceChunks) {
            this.code = code;
            this.message = message;
            this.answer = answer;
            this.citations = citations;
            this.sourceChunks = sourceChunks;
            this.latencyMs = 0;
            this.faithfulness = 0.0;
            this.recallAtK = 0.0;
        }

        /** 简便构造：answer + citations + sourceChunks（用于 generateWithCitations 组装） */
        public RagResult(String answer, List<String> citations, List<String> sourceChunks) {
            this(0, "ok", answer, citations, sourceChunks);
        }

        /** 简便构造：仅 answer */
        public RagResult(String answer) {
            this(0, "ok", answer, null, null);
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public String getAnswer() {
            return answer;
        }

        public List<String> getCitations() {
            return citations;
        }

        public List<String> getSourceChunks() {
            return sourceChunks;
        }

        public int getLatencyMs() {
            return latencyMs;
        }

        public double getFaithfulness() {
            return faithfulness;
        }

        public double getRecallAtK() {
            return recallAtK;
        }

        public void setLatencyMs(int latencyMs) {
            this.latencyMs = latencyMs;
        }

        public static RagResult ok(String answer) {
            return new RagResult(answer);
        }

        public static RagResult error(String msg) {
            return new RagResult(500, msg, null, null, null);
        }
    }
}