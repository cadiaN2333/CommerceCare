package com.lzq.commercecare.routing.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse.Status;
import org.springframework.stereotype.Service;

/**
 * 先捕获完整提及，再校验目录；不能逐个目录名称做前缀匹配。
 * 首版不解析否定、会话指代或比较意图。
 */
@Service
public class ModelRecognitionService {
    // 捕获未知后缀；内部小数点也保留，句末英文句号不当作型号。
    // 带空格的Plus/Pro/Max整体保留为未知写法，避免误认成基础型号。
    private static final Pattern MENTION = Pattern.compile(
            "(?<![A-Za-z0-9_-])SoundBee-[A-Za-z0-9]"
                    + "(?:[A-Za-z0-9_-]|\\.(?=[A-Za-z0-9._-]))*"
                    + "(?:[ \\t]+(?:Plus|Pro|Max)(?![A-Za-z0-9_-]))?"
                    + "(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE
    );

    private final ProductModelCatalog catalog;

    public ModelRecognitionService(ProductModelCatalog catalog) {
        this.catalog = catalog;
    }

    public ModelRecognitionResponse recognize(String question) {
        if (question == null || question.isBlank() || question.length() > 1000) {
            throw new IllegalArgumentException("问题不能为空，且不能超过1000字符。");
        }

        // LinkedHashSet保留首次出现顺序，同时对规范型号去重。
        Set<String> known = new LinkedHashSet<>();
        Map<String, String> unknown = new LinkedHashMap<>();
        List<String> mentions = new ArrayList<>();
        Matcher matcher = MENTION.matcher(question);

        while (matcher.find()) {
            String raw = matcher.group();
            mentions.add(raw);
            String canonical = catalog.canonicalize(raw);
            if (canonical != null) {
                known.add(canonical);
            } else {
                // 未知名称按大小写归一去重，但响应保留首个原文。
                unknown.putIfAbsent(raw.toLowerCase(Locale.ROOT), raw);
            }
        }

        List<String> models = List.copyOf(known);
        List<String> unknowns = List.copyOf(unknown.values());

        // 未知提及优先，不能丢掉它后只用剩余已知型号继续检索。
        if (!unknowns.isEmpty()) {
            return new ModelRecognitionResponse(
                    Status.UNKNOWN, null, models, unknowns, mentions,
                    "发现不在当前目录中的型号名称，请核对完整型号。");
        }
        if (models.size() > 1) {
            return new ModelRecognitionResponse(
                    Status.MULTIPLE, null, models, unknowns, mentions,
                    "识别到多个型号，已全部保留；单型号咨询需明确目标型号。");
        }
        if (models.isEmpty()) {
            return new ModelRecognitionResponse(
                    Status.MISSING, null, models, unknowns, mentions,
                    "未识别到完整型号；咨询型号专属问题时请补充完整型号。");
        }
        return new ModelRecognitionResponse(
                Status.RESOLVED, models.getFirst(), models, unknowns, mentions,
                "已识别型号，可用于后续型号过滤检索。");
    }
}
