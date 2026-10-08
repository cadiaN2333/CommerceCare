package com.lzq.commercecare.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 知识入库请求；型号和主题可缺省，以兼容原有演示资料。
 * 型号使用规范写法并区分大小写，别名识别由后续独立模块处理。
 */
public record KnowledgeIndexRequest(
        @NotBlank @Size(max = 120) String sourceId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 100_000) String content,
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        String productModel,
        @Pattern(regexp = "return|quality|pairing|charging|logistics|invoice|general")
        String topic
) {
}