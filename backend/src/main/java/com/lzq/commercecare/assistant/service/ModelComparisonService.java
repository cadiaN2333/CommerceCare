package com.lzq.commercecare.assistant.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.lzq.commercecare.assistant.dto.ChatResponse;
import com.lzq.commercecare.knowledge.service.KnowledgeSearchService;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 两次有界型号过滤召回，合并一次生成，并要求双方引用覆盖。 */
@Service
public class ModelComparisonService {
    private final KnowledgeSearchService search;
    private final RagChatService rag;

    public ModelComparisonService(KnowledgeSearchService search, RagChatService rag) {
        this.search = search;
        this.rag = rag;
    }

    public RagChatService.EvidenceAnswer compare(String question, int topK, List<String> models) {
        models = List.copyOf(models);
        if (models.size() != 2 || models.get(0).equals(models.get(1))
                || models.stream().anyMatch(String::isBlank) || topK < 1) {
            throw new IllegalArgumentException("比较需要两个不同规范型号及正数topK。");
        }
        int perModelK = Math.min(topK, 3);
        Map<String, Document> merged = new LinkedHashMap<>();
        for (String model : models) {
            List<Document> candidates = search.search(question, perModelK, model);
            if (candidates.size() > perModelK) {
                throw invalidEvidence("检索返回数量超过比较预算。");
            }
            if (candidates.isEmpty()) {
                return new RagChatService.EvidenceAnswer(new ChatResponse(
                        ChatResponse.Status.INSUFFICIENT_EVIDENCE,
                        "双方资料不完整，当前无法确认比较结论。", List.of()), Map.of());
            }
            for (Document document : candidates) {
                if (!model.equals(document.getMetadata().get("productModel"))
                        || document.getId() == null || document.getId().isBlank()) {
                    throw invalidEvidence("比较候选缺少标识或适用型号不一致。");
                }
                Document previous = merged.putIfAbsent(document.getId(), document);
                // 相同ID只有内容与元数据一致才可去重，不能隐藏索引污染。
                if (previous != null && (!Objects.equals(previous.getText(), document.getText())
                        || !previous.getMetadata().equals(document.getMetadata()))) {
                    throw invalidEvidence("比较候选存在相同ID的冲突资料。");
                }
            }
        }
        RagChatService.EvidenceAnswer answer = rag.answerFromDocuments(
                question, List.copyOf(merged.values()));
        if (answer.response().status() == ChatResponse.Status.ANSWERED) {
            for (String model : models) {
                if (answer.sourceGroups().getOrDefault(model, List.of()).isEmpty()) {
                    throw invalidEvidence("比较回答未引用双方资料。");
                }
            }
            for (String model : answer.sourceGroups().keySet()) {
                if (!models.contains(model)) {
                    throw invalidEvidence("比较引用出现范围外型号。");
                }
            }
        }
        return answer;
    }

    private ResponseStatusException invalidEvidence(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
