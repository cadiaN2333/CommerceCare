package com.lzq.commercecare.knowledge.controller;

import java.util.List;

import com.lzq.commercecare.knowledge.dto.HybridSearchResponse;
import com.lzq.commercecare.knowledge.dto.KnowledgeIndexRequest;
import com.lzq.commercecare.knowledge.dto.KnowledgeIndexResponse;
import com.lzq.commercecare.knowledge.dto.KnowledgeSearchRequest;
import com.lzq.commercecare.knowledge.dto.KnowledgeSearchResponse;
import com.lzq.commercecare.knowledge.service.HybridSearchService;
import com.lzq.commercecare.knowledge.service.KnowledgeIngestionService;
import com.lzq.commercecare.knowledge.service.KnowledgeSearchService;
import com.lzq.commercecare.knowledge.service.LuceneKeywordSearchService;
import com.lzq.commercecare.knowledge.service.ReciprocalRankFusionService.FusedDocument;
import jakarta.validation.Valid;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供知识入库和三条独立检索路径，供相同语料与问题集做算法对照。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;
    private final LuceneKeywordSearchService keywordSearchService;
    private final HybridSearchService hybridSearchService;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            KnowledgeSearchService searchService,
            LuceneKeywordSearchService keywordSearchService,
            HybridSearchService hybridSearchService
    ) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.keywordSearchService = keywordSearchService;
        this.hybridSearchService = hybridSearchService;
    }

    @PostMapping("/index")
    public KnowledgeIndexResponse index(
            @Valid @RequestBody KnowledgeIndexRequest request
    ) {
        int indexedChunks = ingestionService.index(
                request.sourceId(),
                request.title(),
                request.content(),
                request.productModel(),
                request.topic()
        );

        return new KnowledgeIndexResponse(
                request.sourceId(),
                indexedChunks
        );
    }

    @PostMapping("/search")
    public KnowledgeSearchResponse search(
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        return toSearchResponse(
                request.question(),
                searchService.search(request.question(), request.topK(), request.productModel())
        );
    }

    @PostMapping("/keyword-search")
    public KnowledgeSearchResponse keywordSearch(
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        return toSearchResponse(
                request.question(),
                keywordSearchService.search(
                        request.question(),
                        request.topK(),
                        request.productModel()
                )
        );
    }

    @PostMapping("/hybrid-search")
    public HybridSearchResponse hybridSearch(
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        List<HybridSearchResponse.Result> results =
                hybridSearchService.search(
                                request.question(),
                                request.topK(),
                                request.productModel()
                        )
                        .stream()
                        .map(this::toHybridResult)
                        .toList();

        return new HybridSearchResponse(request.question(), results);
    }

    private KnowledgeSearchResponse toSearchResponse(
            String question,
            List<Document> documents
    ) {
        return new KnowledgeSearchResponse(
                question,
                documents.stream()
                        .map(this::toSearchResult)
                        .toList()
        );
    }

    private KnowledgeSearchResponse.Result toSearchResult(
            Document document
    ) {
        return new KnowledgeSearchResponse.Result(
                metadataText(document, "sourceId"),
                metadataText(document, "title"),
                metadataText(document, "category"),
                metadataText(document, "productModel"),
                metadataText(document, "topic"),
                metadataInteger(document, "chunkIndex"),
                document.getText(),
                document.getScore()
        );
    }

    // 保留融合分数与两路原始分数的不同含义，避免把 RRF 分数当作置信度。
    private HybridSearchResponse.Result toHybridResult(
            FusedDocument result
    ) {
        Document document = result.document();

        return new HybridSearchResponse.Result(
                document.getId(),
                metadataText(document, "sourceId"),
                metadataText(document, "title"),
                metadataText(document, "category"),
                metadataText(document, "productModel"),
                metadataText(document, "topic"),
                metadataInteger(document, "chunkIndex"),
                document.getText(),
                result.fusionScore(),
                "RRF",
                result.vectorRank(),
                result.keywordRank(),
                result.vectorScore(),
                result.keywordScore()
        );
    }

    private String metadataText(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private Integer metadataInteger(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value instanceof Number number
                ? number.intValue()
                : null;
    }
}
