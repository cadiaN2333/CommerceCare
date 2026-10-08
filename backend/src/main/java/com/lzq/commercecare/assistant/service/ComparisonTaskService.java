package com.lzq.commercecare.assistant.service;

import java.util.List;
import java.util.regex.Pattern;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;
import org.springframework.stereotype.Service;

/** 首版任务规则：仅在两个已知型号、明确比较与维度时放行。 */
@Service
public class ComparisonTaskService {
    public enum Task { SINGLE_MODEL, COMPARISON, CLARIFY }

    private static final List<String> COMPARISON_WORDS =
            List.of("区别", "差别", "比较", "对比", "哪个", "哪款");
    private static final List<String> DIMENSIONS = List.of(
            "退货", "换货", "退换", "保修", "维修", "发票", "配送", "物流", "配对", "充电",
            "价格", "收费", "费用", "编码", "协议", "音质", "性能", "性价比");
    private static final List<String> CORRECTIONS = List.of("说错", "改成");
    // 只识别明确的型号纠正句，不能将“是不是”里的“不是”误当纠正。
    private static final Pattern MODEL_CORRECTION = Pattern.compile(
            "不是\\s*SoundBee-[A-Za-z0-9._-]+\\s*[,，]?\\s*(?:而是|是)\\s*SoundBee-[A-Za-z0-9._-]+",
            Pattern.CASE_INSENSITIVE);

    public Task decide(String question, ModelRecognitionResponse recognition) {
        if (recognition.status() == ModelRecognitionResponse.Status.RESOLVED) {
            return Task.SINGLE_MODEL;
        }
        if (recognition.status() != ModelRecognitionResponse.Status.MULTIPLE
                || recognition.detectedModels().size() != 2
                || !recognition.unknownMentions().isEmpty()
                || containsAny(question, CORRECTIONS)
                || MODEL_CORRECTION.matcher(question).find()) {
            return Task.CLARIFY;
        }
        return containsAny(question, COMPARISON_WORDS) && containsAny(question, DIMENSIONS)
                ? Task.COMPARISON : Task.CLARIFY;
    }

    private boolean containsAny(String question, List<String> words) {
        return words.stream().anyMatch(question::contains);
    }
}
