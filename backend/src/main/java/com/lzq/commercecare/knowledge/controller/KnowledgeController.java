package com.lzq.commercecare.knowledge.controller;

import java.util.List;

import com.lzq.commercecare.knowledge.dto.KnowledgeIndexRequest;
import com.lzq.commercecare.knowledge.dto.KnowledgeIndexResponse;
import com.lzq.commercecare.knowledge.dto.KnowledgeSearchRequest;
import com.lzq.commercecare.knowledge.dto.KnowledgeSearchResponse;
import com.lzq.commercecare.knowledge.service.KnowledgeIngestionService;
import com.lzq.commercecare.knowledge.service.KnowledgeSearchService;
import jakarta.validation.Valid;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            KnowledgeSearchService searchService
    ) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
    }

    @PostMapping("/index")
    public KnowledgeIndexResponse index(
            @Valid @RequestBody KnowledgeIndexRequest request
    ) {
        int indexedChunks = ingestionService.index(
                request.sourceId(),
                request.title(),
                request.content()
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
        List<KnowledgeSearchResponse.Result> results =
                searchService.search(request.question(), request.topK())
                        .stream()
                        .map(this::toSearchResult)
                        .toList();

        return new KnowledgeSearchResponse(request.question(), results);
    }

    private KnowledgeSearchResponse.Result toSearchResult(Document document) {
        var metadata = document.getMetadata();
        Object chunkIndexValue = metadata.get("chunkIndex");

        Integer chunkIndex = chunkIndexValue instanceof Number number
                ? number.intValue()
                : null;

        return new KnowledgeSearchResponse.Result(
                metadata.get("sourceId") == null
                        ? null
                        : metadata.get("sourceId").toString(),
                metadata.get("title") == null
                        ? null
                        : metadata.get("title").toString(),
                metadata.get("category") == null
                        ? null
                        : metadata.get("category").toString(),
                chunkIndex,
                document.getText(),
                document.getScore()
        );
    }
}