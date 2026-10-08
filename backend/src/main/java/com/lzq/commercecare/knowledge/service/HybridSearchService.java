package com.lzq.commercecare.knowledge.service;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import com.lzq.commercecare.knowledge.service.ReciprocalRankFusionService.FusedDocument;

/**
 * 两路使用相同型号约束；候选预算与原基线保持一致，再执行RRF。
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
        return search(question, topK, null);
    }

    public List<FusedDocument> search(
            String question, int topK, String productModel
    ) {
        int candidateTopK = Math.min(50, Math.max(10, topK * 3));
        List<Document> vector = vectorSearchService.search(
                question, candidateTopK, productModel);
        List<Document> keyword = keywordSearchService.search(
                question, candidateTopK, productModel);
        return fusionService.fuse(vector, keyword, topK);
    }
}