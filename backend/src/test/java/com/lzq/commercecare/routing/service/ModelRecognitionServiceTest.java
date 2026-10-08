package com.lzq.commercecare.routing.service;

import java.util.List;
import com.lzq.commercecare.routing.dto.ModelRecognitionRequest;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse.Status;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 纯规则测试不调用数据库或LLM；未知提及与子型号边界是重点。 */
class ModelRecognitionServiceTest {
    private final ModelRecognitionService service =
            new ModelRecognitionService(new ProductModelCatalog());

    @Test
    void recognizesAllCanonicalModelsAndLowercase() {
        for (String model : List.of(
                "SoundBee-A1", "SoundBee-A2", "SoundBee-A2-Plus",
                "SoundBee-A3", "SoundBee-A3-Pro",
                "SoundBee-B1", "SoundBee-B1-Plus", "SoundBee-B2",
                "SoundBee-B2-Max", "SoundBee-C1", "SoundBee-C1-Pro"
        )) {
            for (String question : List.of(model + "怎么处理？",
                    model.toLowerCase(java.util.Locale.ROOT) + "怎么处理？")) {
                ModelRecognitionResponse result = service.recognize(question);
                assertEquals(Status.RESOLVED, result.status());
                assertEquals(model, result.productModel());
                assertEquals(List.of(model), result.detectedModels());
                assertTrue(result.unknownMentions().isEmpty());
            }
        }
    }

    @Test
    void recognizesChineseAdjacentTextWithoutMatchingParentModel() {
        ModelRecognitionResponse result =
                service.recognize("请问SoundBee-A2-Plus怎么配对？");
        assertEquals(Status.RESOLVED, result.status());
        assertEquals(List.of("SoundBee-A2-Plus"), result.detectedModels());
    }

    @Test
    void deduplicatesCanonicalModelsButKeepsOriginalMentions() {
        ModelRecognitionResponse result =
                service.recognize("SoundBee-A2坏了，soundbee-a2能换吗？");
        assertEquals(Status.RESOLVED, result.status());
        assertEquals(List.of("SoundBee-A2"), result.detectedModels());
        assertEquals(List.of("SoundBee-A2", "soundbee-a2"), result.mentions());
    }

    @Test
    void missingCompleteNameNeverDefaultsToA1() {
        for (String question : List.of("耳机坏了能退吗？", "A2怎么配对？",
                "SoundBee A2怎么配对？", "订单12345什么时候到？",
                "OtherBrand-A2怎么配对？")) {
            ModelRecognitionResponse result = service.recognize(question);
            assertEquals(Status.MISSING, result.status());
            assertNull(result.productModel());
            assertTrue(result.detectedModels().isEmpty());
        }
    }

    @Test
    void capturesUnknownSuffixInsteadOfResolvingKnownPrefix() {
        for (String mention : List.of("SoundBee-Z9", "SoundBee-A2Plus",
                "SoundBee-A2-Unknown", "SoundBee-A2_Pro",
                "SoundBee-A2 Plus")) {
            ModelRecognitionResponse result = service.recognize(mention + "能退吗？");
            assertEquals(Status.UNKNOWN, result.status());
            assertNull(result.productModel());
            assertTrue(result.detectedModels().isEmpty());
            assertEquals(List.of(mention), result.unknownMentions());
        }
    }

    @Test
    void preservesInternalDotButIgnoresSentencePeriod() {
        ModelRecognitionResponse unknown = service.recognize("SoundBee-A2.5能退吗？");
        assertEquals(Status.UNKNOWN, unknown.status());
        assertEquals(List.of("SoundBee-A2.5"), unknown.unknownMentions());
        assertEquals("SoundBee-A2",
                service.recognize("SoundBee-A2. 怎么配对？").productModel());
    }

    @Test
    void multipleModelsKeepFirstMentionOrder() {
        ModelRecognitionResponse result =
                service.recognize("SoundBee-A3和SoundBee-A2有什么区别？");
        assertEquals(Status.MULTIPLE, result.status());
        assertNull(result.productModel());
        assertEquals(List.of("SoundBee-A3", "SoundBee-A2"), result.detectedModels());
    }

    @Test
    void correctionSentenceDoesNotSilentlyChooseOneModel() {
        ModelRecognitionResponse result =
                service.recognize("不是SoundBee-A2，是SoundBee-A3。");
        assertEquals(Status.MULTIPLE, result.status());
        assertNull(result.productModel());
    }

    @Test
    void unknownHasPriorityAndKnownModelsAreNotDiscarded() {
        ModelRecognitionResponse result =
                service.recognize("SoundBee-A2、SoundBee-A3、SoundBee-Z9比较一下");
        assertEquals(Status.UNKNOWN, result.status());
        assertNull(result.productModel());
        assertEquals(List.of("SoundBee-A2", "SoundBee-A3"), result.detectedModels());
        assertEquals(List.of("SoundBee-Z9"), result.unknownMentions());
    }

    @Test
    void doesNotMatchInsideAnotherEnglishIdentifier() {
        for (String question : List.of("fooSoundBee-A2坏了",
                "X-SoundBee-A2坏了", "_SoundBee-A2坏了")) {
            assertEquals(Status.MISSING, service.recognize(question).status());
        }
    }

    @Test
    void unknownDedupPreservesFirstOriginalSpelling() {
        ModelRecognitionResponse result =
                service.recognize("SoundBee-Z9和soundbee-z9是什么？");
        assertEquals(List.of("SoundBee-Z9"), result.unknownMentions());
        assertEquals(2, result.mentions().size());
    }

    @Test
    void returnedListsCannotBeModified() {
        ModelRecognitionResponse result = service.recognize("SoundBee-A2坏了");
        assertThrows(UnsupportedOperationException.class,
                () -> result.detectedModels().add("SoundBee-A3"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.mentions().clear());
    }

    @Test
    void rejectsInvalidDirectServiceInput() {
        assertThrows(IllegalArgumentException.class, () -> service.recognize(null));
        assertThrows(IllegalArgumentException.class, () -> service.recognize("   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.recognize("a".repeat(1001)));
    }

    @Test
    void requestValidationRejectsBlankAndOversizedQuestions() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new ModelRecognitionRequest("  ")).isEmpty());
            assertFalse(validator.validate(
                    new ModelRecognitionRequest("a".repeat(1001))).isEmpty());
            assertTrue(validator.validate(
                    new ModelRecognitionRequest("SoundBee-A2能退吗？")).isEmpty());
        }
    }
}
