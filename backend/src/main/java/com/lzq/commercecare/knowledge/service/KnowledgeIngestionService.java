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

    public int index(String sourceId, String title, String content) {
        Document originalDocument = new Document(
                content,
                Map.of(
                        "sourceId", sourceId,
                        "title", title,
                        "category", "after_sales"
                )
        );

        List<Document> chunks = textSplitter.apply(List.of(originalDocument));
        List<Document> documentsToStore = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);

            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("sourceId", sourceId);
            metadata.put("title", title);
            metadata.put("category", "after_sales");
            metadata.put("chunkIndex", i);

            String stableKey = sourceId + ":" + i;
            String documentId = UUID.nameUUIDFromBytes(
                    stableKey.getBytes(StandardCharsets.UTF_8)
            ).toString();

            documentsToStore.add(
                    new Document(documentId, chunk.getText(), metadata)
            );
        }

        if (!documentsToStore.isEmpty()) {
            vectorStore.add(documentsToStore);
            keywordSearchService.upsert(documentsToStore);
        }

        return documentsToStore.size();
    }
}