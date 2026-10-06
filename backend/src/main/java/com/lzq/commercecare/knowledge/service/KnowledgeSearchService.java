package com.lzq.commercecare.knowledge.service;


import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import java.util.List;

@Service
public class KnowledgeSearchService {

    private final VectorStore vectorStore;

    public KnowledgeSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> search(String question, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .build();

        return vectorStore.similaritySearch(request);
    }
}
