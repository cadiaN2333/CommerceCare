package com.lzq.commercecare.routing.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 应用自己的规范型号目录，不读取评测金标准或答案参数。
 * 后续增加型号需同步资料适用范围；这里不保证资料已入库。
 */
@Component
public class ProductModelCatalog {
    private final Map<String, String> canonicalModels;

    public ProductModelCatalog() {
        Map<String, String> models = new LinkedHashMap<>();
        for (String model : List.of(
                "SoundBee-A1", "SoundBee-A2", "SoundBee-A2-Plus",
                "SoundBee-A3", "SoundBee-A3-Pro",
                "SoundBee-B1", "SoundBee-B1-Plus", "SoundBee-B2",
                "SoundBee-B2-Max", "SoundBee-C1", "SoundBee-C1-Pro"
        )) {
            models.put(model.toLowerCase(Locale.ROOT), model);
        }
        canonicalModels = Map.copyOf(models);
    }

    /** 仅归一大小写；不删除连字符、不猜测别名。未知名称返回null。 */
    public String canonicalize(String mention) {
        return canonicalModels.get(mention.toLowerCase(Locale.ROOT));
    }
}
