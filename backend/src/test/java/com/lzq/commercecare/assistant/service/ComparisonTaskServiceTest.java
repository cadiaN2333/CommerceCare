package com.lzq.commercecare.assistant.service;

import com.lzq.commercecare.routing.service.ModelRecognitionService;
import com.lzq.commercecare.routing.service.ProductModelCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 任务规则与型号识别一起验证，未知与纠正不能误入比较。 */
class ComparisonTaskServiceTest {
    private final ComparisonTaskService tasks = new ComparisonTaskService();
    private final ModelRecognitionService recognition = new ModelRecognitionService(new ProductModelCatalog());
    private ComparisonTaskService.Task decide(String question) {
        return tasks.decide(question, recognition.recognize(question));
    }
    @Test void singleModel() {
        assertEquals(ComparisonTaskService.Task.SINGLE_MODEL, decide("SoundBee-A2保修多久？"));
    }
    @Test void explicitComparison() {
        assertEquals(ComparisonTaskService.Task.COMPARISON, decide("SoundBee-A2和SoundBee-A2-Plus保修有何区别？"));
    }
    @Test void missingDimension() {
        assertEquals(ComparisonTaskService.Task.CLARIFY, decide("SoundBee-A2和SoundBee-A2-Plus有什么区别？"));
    }
    @Test void overTwoModels() {
        assertEquals(ComparisonTaskService.Task.CLARIFY, decide("SoundBee-A2、SoundBee-A3和SoundBee-B1保修比较"));
    }
    @Test void unknownHasPriority() {
        assertEquals(ComparisonTaskService.Task.CLARIFY, decide("SoundBee-A2和SoundBee-Z9保修比较"));
    }
    @Test void correctionIsNotComparison() {
        assertEquals(ComparisonTaskService.Task.CLARIFY, decide("不是SoundBee-A2而是SoundBee-A3，要比较保修"));
    }
    @Test void explicitUnknownFactsGoToEvidenceCheck() {
        assertEquals(ComparisonTaskService.Task.COMPARISON, decide("SoundBee-A2和SoundBee-A2-Plus维修收费哪个高？"));
    }
    @Test void questionIsNotAProductCorrection() {
        assertEquals(ComparisonTaskService.Task.COMPARISON,
                decide("SoundBee-A2和SoundBee-A2-Plus保修差别是不是只有天数？"));
        assertEquals(ComparisonTaskService.Task.CLARIFY,
                decide("不是SoundBee-A2，是SoundBee-A3，要比较保修"));
    }
}
