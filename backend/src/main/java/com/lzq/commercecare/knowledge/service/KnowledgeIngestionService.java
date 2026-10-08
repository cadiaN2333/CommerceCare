package com.lzq.commercecare.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * 切分资料并把同一片段及其适用型号写入向量库与关键词索引。
 * 两索引尚无跨系统事务；旧尾片段清理与失败修复需后续补齐。
 */
@Service
public class KnowledgeIngestionService {
    private final VectorStore vectorStore;
    private final LuceneKeywordSearchService keywordSearchService;
    private final TokenTextSplitter textSplitter = TokenTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(80)
            .withMinChunkLengthToEmbed(10)
            .withKeepSeparator(true)
            .build();

    public KnowledgeIngestionService(
            VectorStore vectorStore,
            LuceneKeywordSearchService keywordSearchService
    ) {
        this.vectorStore = vectorStore;
        this.keywordSearchService = keywordSearchService;
    }

    // 保留原有服务调用；缺失型号的资料不会命中明确型号的过滤。
    public int index(String sourceId, String title, String content) {
        return index(sourceId, title, content, null, null);
    }

    public int index(
            String sourceId, String title, String content,
            String productModel, String topic
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceId", sourceId);
        metadata.put("title", title);
        metadata.put("category", categoryFor(topic));
        if (productModel != null) {
            metadata.put("productModel", productModel);
        }
        if (topic != null) {
            metadata.put("topic", topic);
        }

        Document original = new Document(content, metadata);
        List<Document> chunks = textSplitter.apply(List.of(original));
        List<Document> stored = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> chunkMetadata = new HashMap<>(chunk.getMetadata());
            chunkMetadata.put("chunkIndex", i);
            String id = UUID.nameUUIDFromBytes(
                    (sourceId + ":" + i).getBytes(StandardCharsets.UTF_8)
            ).toString();
            stored.add(new Document(id, chunk.getText(), chunkMetadata));
        }

        if (!stored.isEmpty()) {
            vectorStore.add(stored);
            keywordSearchService.upsert(stored);
        }
        return stored.size();
    }

    // 主题是资料细类，分类是业务大类；没有主题时明确标记未分类。
    private String categoryFor(String topic) {
        if (topic == null) {
            return "unclassified";
        }
        return switch (topic) {
            case "return", "quality" -> "after_sales";
            case "pairing", "charging" -> "product_support";
            case "logistics" -> "delivery";
            case "invoice" -> "billing";
            default -> "general";
        };
    }
}