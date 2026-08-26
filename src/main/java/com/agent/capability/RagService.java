package com.agent.capability;

import com.agent.common.Result;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * L4 AI 能力层：RAG 检索服务 v1
 * <p>
 * 对应 ADS 第 4.4.1 节 "RAG 检索服务"（双管道：离线索引 + 在线检索）。
 * API 对齐本地 springAITest 已验证的 Spring AI 1.0.0 用法。
 * <p>
 * 通过 {@code app.rag.enabled=true} 开启（需先配置 ZHIPUAI_API_KEY，未配置时不创建 bean，避免启动失败）。
 * 租户隔离：metadata 写入 tenant_id，检索时按 tenant_id 过滤。
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class RagService {

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
            return Result.error(500, "文档入库失败: " + e.getMessage());
        }
    }

    /** RAG 检索：向量相似度搜索，按租户过滤，返回带引用的切片 */
    public Result<List<Document>> search(String query, int topK, String tenantId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq("tenant_id", tenantId).build())
                .build();
        return Result.ok(vectorStore.similaritySearch(request));
    }

    /** 按租户清空其知识库切片 */
    public Result<Void> delete(String tenantId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        vectorStore.delete(b.eq("tenant_id", tenantId).build());
        return Result.ok();
    }
}