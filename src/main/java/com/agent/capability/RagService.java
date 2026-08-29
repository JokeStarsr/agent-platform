package com.agent.capability;

import com.agent.common.AuditService;
import com.agent.common.Result;
import com.agent.model.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/** L4 AI 能力层：RAG 检索服务 v2 - 在线检索全管道 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final LlmGateway llmGateway;
    private final AuditService auditService;

    // L5 转人工门控配置：置信度门控 + 转人工标记（P1 收口，对应 docs/design/api/20260830-handoff-mechanism.md）
    @Value("${app.rag.handoff.threshold:0.25}")
    private double handoffThreshold = 0.25;
    @Value("${app.rag.handoff.w-retrieval:0.55}")
    private double wRetrieval = 0.55;
    @Value("${app.rag.handoff.w-coverage:0.25}")
    private double wCoverage = 0.25;
    @Value("${app.rag.handoff.w-citation:0.20}")
    private double wCitation = 0.20;
    // 拒答信号（2026-08-30 三轮校准）：仅"完全缺失答案"的真拒答/内容安全拒答。
    // 覆盖：我不知道/无法回答/无法确认/没有找到/并未包含任何（通用）+ 无法提供任何/拒绝生成（内容安全拒答）。
    // 排除：建议转人工（好答案收尾）、无法提供（好答案对子部分的缺口说明，如 KB-023"无法提供该部分信息"）
    private static final List<String> REFUSAL_SIGNALS = List.of(
            "我不知道", "无法回答", "无法确定", "不能回答", "不清楚", "无法确认",
            "没有找到", "没有关于", "没有相关信息", "并未包含任何", "无法判断",
            "无法提供任何", "拒绝回答", "拒绝生成", "拒绝提供");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public RagService(VectorStore vectorStore, LlmGateway llmGateway, AuditService auditService) {
        this.vectorStore = vectorStore;
        this.llmGateway = llmGateway;
        this.auditService = auditService;
    }

    /** 文档入库：Tika 解析 → 按章节切块 → 向量化写入（租户打标） */
    public Result<Long> indexDocument(MultipartFile file, String tenantId) {
        try {
            TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
            List<Document> docs = reader.get();
            List<Document> chunks = new ArrayList<>();
            for (Document doc : docs) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("tenant_id", tenantId);
                meta.put("source", file.getOriginalFilename());
                // 按 "## " 章节标题切块：每个策略小节独立成块，保证检索精确命中对应小节
                for (String section : doc.getText().split("(?=\\n## )")) {
                    String sec = section.trim();
                    if (sec.isEmpty() || !sec.contains("##")) {
                        continue;
                    }
                    chunks.add(new Document(sec, new HashMap<>(meta)));
                }
            }
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

    /** 按租户清空其知识库切片（评测重置用） */
    public Result<Void> delete(String tenantId) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            vectorStore.delete(b.eq("tenant_id", tenantId).build());
            return Result.ok();
        } catch (Exception e) {
            log.error("按租户清空知识库失败: tenantId={}, ex={}", tenantId, e.getMessage(), e);
            return Result.error(500, "清空知识库失败: " + e.getMessage());
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
            String source = metadata != null ? metadata.getOrDefault("source", "知识库切片").toString() : "知识库切片";
            String content = doc.getText();
            double score = doc.getScore() != null ? doc.getScore() : 0.5;
            chunks.add(new ScoredChunk(content, tenant, source, score));
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
        GenerationPrompts prompts = buildGenerationPrompts(selectedChunks, query);
        String answer = llmGatewayGenerate(prompts.systemPrompt(), prompts.userPrompt());

        List<String> sourceChunks = selectedChunks.stream()
                .map(ScoredChunk::getContent)
                .collect(Collectors.toList());

        RagResult result = new RagResult(answer, prompts.citations(), sourceChunks);
        computeHandoff(result, selectedChunks, query, answer);
        return result;
    }

    /** 构建生成 Prompt（含引用上下文），同步/流式共用 */
    private GenerationPrompts buildGenerationPrompts(List<ScoredChunk> selectedChunks, String query) {
        StringBuilder contextBuilder = new StringBuilder();
        List<String> citations = new ArrayList<>();
        for (int i = 0; i < selectedChunks.size(); i++) {
            ScoredChunk chunk = selectedChunks.get(i);
            String citationRef = "【" + (i + 1) + "】";
            contextBuilder.append(citationRef).append(" ").append(chunk.getContent()).append("\n");
            citations.add(citationRef + ": " + (chunk.getSource() != null ? chunk.getSource() : "知识库切片"));
        }
        String systemPrompt = buildSystemPrompt() + "\n" + contextBuilder.toString();
        String userPrompt = "基于以上上下文，回答用户问题。要求：1) 只基于提供的上下文回答，不外推，但上下文中与问题直接相关的信息（政策/流程/费用等）都应如实整理作答，不得因缺少专门的操作指引表述而拒答；2) 仅当上下文中确实不存在任何与问题相关的信息时，才诚实回答“我不知道”并建议转人工；3) 必须对每个关键结论用【1】、【2】…标注引用，编号按上下文切片出现顺序从【1】开始连续递增，禁止使用文档章节号（如【2.7】）；4) 置信度低时须明确说明。\n\n用户问题：" + query;
        return new GenerationPrompts(systemPrompt, userPrompt, citations);
    }

    /** 生成 Prompt 三要素（system / user / 引用列表） */
    public record GenerationPrompts(String systemPrompt, String userPrompt, List<String> citations) {}

    /** RAG SSE 流式检索（P1 收口 #2，docs/design/api/20260830-rag-stream.md）
     *  检索同步 → retrieval 事件 → 逐 token answer 事件（记 firstTokenMs）→ done 事件（置信度/转人工）。
     *  每行一个紧凑 JSON，前端按 type 字段分发。
     */
    public Flux<String> streamSearch(String query, int topK, String tenantId) {
        return Flux.defer(() -> {
            long startNs = System.nanoTime();
            try {
                QueryRewrite rewrite = rewriteQuery(query);
                List<ScoredChunk> hybrid = hybridSearch(rewrite.rewritten, tenantId, rewrite.variants, topK);
                List<ScoredChunk> selected = rerankChunks(hybrid, query, tenantId).stream()
                        .limit(topK).collect(Collectors.toList());
                GenerationPrompts prompts = buildGenerationPrompts(selected, query);
                List<String> sourceChunks = selected.stream()
                        .map(ScoredChunk::getContent).collect(Collectors.toList());

                // 1) retrieval 事件：检索完成、生成开始前，先告知前端命中了哪些资料
                List<String> sources = selected.stream()
                        .map(ScoredChunk::getSource).distinct().collect(Collectors.toList());
                Map<String, Object> retrievalData = new LinkedHashMap<>();
                retrievalData.put("chunkCount", selected.size());
                retrievalData.put("topK", topK);
                retrievalData.put("sources", sources);
                Flux<String> retrievalEvent = Flux.just(event("retrieval", retrievalData));

                // 2) 逐 token answer 事件 + 记首 Token + 累积全文；完成后发 done，异常发 error
                StringBuilder answerBuf = new StringBuilder();
                long[] firstTokenMs = {0L};
                Flux<String> generation = llmGateway.stream(prompts.systemPrompt(), prompts.userPrompt())
                        .map(token -> {
                            if (firstTokenMs[0] == 0L) {
                                firstTokenMs[0] = (System.nanoTime() - startNs) / 1_000_000;
                            }
                            answerBuf.append(token);
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("text", token);
                            return event("answer", d);
                        })
                        .concatWith(Flux.defer(() -> {
                            long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
                            String fullAnswer = answerBuf.toString();
                            RagResult r = new RagResult(fullAnswer, prompts.citations(), sourceChunks);
                            computeHandoff(r, selected, query, fullAnswer);
                            auditService.log(tenantId, query, topK, selected.size(), sources,
                                    latencyMs, firstTokenMs[0], r.isNeedsHandoff(), r.getHandoffReason(),
                                    r.getConfidenceScore(), fullAnswer.length());
                            return Flux.just(doneEvent(r, latencyMs, firstTokenMs[0]));
                        }))
                        .onErrorResume(ex -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("message", "生成失败: " + ex.getMessage());
                            return Flux.just(event("error", d));
                        });

                return Flux.concat(retrievalEvent, generation);
            } catch (Exception e) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("message", "检索失败: " + e.getMessage());
                return Flux.just(event("error", d));
            }
        });
    }

    /** done 事件：完整答案 + 引用 + 置信度/转人工（与同步接口同构，前端可复用渲染逻辑） */
    private String doneEvent(RagResult r, long latencyMs, long firstTokenMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", r.getAnswer());
        payload.put("citations", r.getCitations());
        payload.put("sourceChunks", r.getSourceChunks());
        payload.put("confidenceScore", r.getConfidenceScore());
        payload.put("needsHandoff", r.isNeedsHandoff());
        payload.put("handoffReason", r.getHandoffReason());
        payload.put("latencyMs", latencyMs);
        payload.put("firstTokenMs", firstTokenMs);
        return event("done", payload);
    }

    /** 序列化 SSE 事件为紧凑 JSON（type + data） */
    private String event(String type, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        if (data != null) {
            payload.putAll(data);
        }
        try {
            return JSON_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"事件序列化失败\"}";
        }
    }

    /** 置信度门控 + 转人工标记（对应 docs/design/api/20260830-handoff-mechanism.md §2）
     *  纯启发式合成，零额外 LLM 调用：检索相似度*0.55 + 关键词覆盖*0.25 + 引用完整率*0.20。
     */
    private void computeHandoff(RagResult result, List<ScoredChunk> selectedChunks, String query, String answer) {
        double retrievalScore = selectedChunks.isEmpty()
                ? 0.0 : Math.max(0.0, Math.min(1.0,
                selectedChunks.stream().mapToDouble(ScoredChunk::getScore).max().orElse(0.0)));
        double coverageScore = selectedChunks.isEmpty() ? 0.0 : coverageScore(query, selectedChunks.get(0).getContent());
        double citationScore = (answer != null && answer.matches(".*【[\\d.]+】.*")) ? 1.0 : 0.0;

        double confidence = wRetrieval * retrievalScore + wCoverage * coverageScore + wCitation * citationScore;
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        boolean noRetrieval = selectedChunks.isEmpty();
        boolean refused = hasRefusalSignal(answer);
        boolean needsHandoff = noRetrieval || refused || confidence < handoffThreshold;
        String reason = noRetrieval ? "NO_RETRIEVAL"
                : (refused ? "REFUSAL"
                : (confidence < handoffThreshold ? "LOW_CONFIDENCE" : "NONE"));

        result.setConfidenceScore(confidence);
        result.setNeedsHandoff(needsHandoff);
        result.setHandoffReason(reason);
    }

    /** 查询关键词在切片中的 bigram 覆盖率（中文以字为单位成对） */
    private double coverageScore(String query, String chunkText) {
        Set<String> q = charBigrams(query);
        if (q.isEmpty()) return 0.0;
        Set<String> c = charBigrams(chunkText);
        if (c.isEmpty()) return 0.0;
        int overlap = 0;
        for (String bg : q) {
            if (c.contains(bg)) overlap++;
        }
        return (double) overlap / q.size();
    }

    private Set<String> charBigrams(String text) {
        String norm = text.toLowerCase().replaceAll("\\s+", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i < norm.length() - 1; i++) {
            set.add(norm.substring(i, i + 2));
        }
        return set;
    }

    /** 答案是否带拒答/建议转人工信号 */
    private boolean hasRefusalSignal(String answer) {
        if (answer == null || answer.isEmpty()) return true;
        String lower = answer.toLowerCase();
        for (String s : REFUSAL_SIGNALS) {
            if (lower.contains(s.toLowerCase())) return true;
        }
        return false;
    }

    /** 系统Prompt */
    private String buildSystemPrompt() {
        return "你是企业级智能客服助手。你的职责是基于企业知识库回答用户问题。\n" +
                "核心规则：\n" +
                "1. 只基于提供的知识库内容回答，不凭个人经验或外部信息。\n" +
                "2. 答案必须逐句引用来源，引用编号用【1】、【2】…整数（对应上下文切片出现顺序），禁止使用文档章节号（如【2.7】）。\n" +
                "3. 如果知识库中没有答案，必须诚实回答“我不知道”，并主动提供转人工选项。\n" +
                "4. 对于涉及政策、法规、敏感信息的问题，必须进行置信度检查，低置信度时转人工。\n" +
                "5. 对输出内容进行安全过滤，拒绝生成违法、歧视、虚假信息。\n";
    }

    /** 经 L6 LLM 网关生成答案（OpenAI 兼容协议 → DeepSeek） */
    private String llmGatewayGenerate(String system, String user) {
        return llmGateway.generate(system, user);
    }

    /** 辅助类：检索结果切片 */
    public static class ScoredChunk {
        private final String content;
        private final String tenantId;
        private final String source;
        private final double score;

        public ScoredChunk(String content, String tenantId, String source, double score) {
            this.content = content;
            this.tenantId = tenantId;
            this.source = source;
            this.score = score;
        }

        public String getContent() {
            return content;
        }

        public String getTenantId() {
            return tenantId;
        }

        public String getSource() {
            return source;
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
        private double confidenceScore = 0.0; // L5 转人工门控：合成置信度 0-1
        private boolean needsHandoff = false; // 是否建议转人工
        private String handoffReason = "NONE"; // NONE / NO_RETRIEVAL / REFUSAL / LOW_CONFIDENCE

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

        public void setLatencyMs(int latencyMs) {
            this.latencyMs = latencyMs;
        }

        public double getConfidenceScore() {
            return confidenceScore;
        }

        public void setConfidenceScore(double confidenceScore) {
            this.confidenceScore = confidenceScore;
        }

        public boolean isNeedsHandoff() {
            return needsHandoff;
        }

        public void setNeedsHandoff(boolean needsHandoff) {
            this.needsHandoff = needsHandoff;
        }

        public String getHandoffReason() {
            return handoffReason;
        }

        public void setHandoffReason(String handoffReason) {
            this.handoffReason = handoffReason;
        }

        public static RagResult ok(String answer) {
            return new RagResult(answer);
        }

        public static RagResult error(String msg) {
            return new RagResult(500, msg, null, null, null);
        }
    }
}