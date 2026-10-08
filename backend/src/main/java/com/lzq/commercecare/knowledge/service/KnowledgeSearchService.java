package com.lzq.commercecare.knowledge.service;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * 向量召回；型号条件由向量存储处理，先限制适用范围再返回候选。
 */
@Service
public class KnowledgeSearchService {
    private final VectorStore vectorStore;

    public KnowledgeSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // 保留聊天基线调用，聊天流程本轮尚未增加型号约束。
    public List<Document> search(String question, int topK) {
        return search(question, topK, null);
    }

    public List<Document> search(String question, int topK, String productModel) {
        var builder = SearchRequest.builder().query(question).topK(topK);
        if (productModel != null) {
            // 使用表达式构造器，避免把用户输入拼接成过滤语法。
            builder.filterExpression(new FilterExpressionBuilder()
                    .eq("productModel", productModel).build());
        }
        return vectorStore.similaritySearch(builder.build());
    }
}