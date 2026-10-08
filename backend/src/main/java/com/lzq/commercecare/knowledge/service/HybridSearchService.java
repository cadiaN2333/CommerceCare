package com.lzq.commercecare.knowledge.service;


import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import com.lzq.commercecare.knowledge.service.ReciprocalRankFusionService.FusedDocument;

import java.util.List;

/**
 * 编排向量和 BM25 两路召回；扩大候选池后再融合，最终只返回请求的 topK。
 */
@Service
public class HybridSearchService {

    private final KnowledgeSearchService vectorSearchService;
    private final LuceneKeywordSearchService keywordSearchService;
    private final ReciprocalRankFusionService fusionService;

    public HybridSearchService(
            KnowledgeSearchService vectorSearchService,
            LuceneKeywordSearchService keywordSearchService,
            ReciprocalRankFusionService fusionService
    ) {
        this.vectorSearchService = vectorSearchService;
        this.keywordSearchService = keywordSearchService;
        this.fusionService = fusionService;
    }

    public List<FusedDocument> search(String question, int topK) {
        // 候选池至少取 10、最多取 50，这是开发初值，效果需用评测集比较。
        int candidateTopK = Math.min(50, Math.max(10, topK * 3));

        // 按原始向量排名返回候选；失败时异常向上抛出，避免静默变成单路检索。
        List<Document> vectorResults =
                vectorSearchService.search(question, candidateTopK);

        // 按原始 BM25 排名返回候选，原始分数不与向量分数直接相加。
        List<Document> keywordResults =
                keywordSearchService.search(question, candidateTopK);

        // 融合召回结果
        return fusionService.fuse(
                vectorResults,
                keywordResults,
                topK
        );
    }
}
