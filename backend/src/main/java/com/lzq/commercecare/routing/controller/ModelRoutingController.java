package com.lzq.commercecare.routing.controller;

import com.lzq.commercecare.routing.dto.ModelRecognitionRequest;
import com.lzq.commercecare.routing.dto.ModelRecognitionResponse;
import com.lzq.commercecare.routing.service.ModelRecognitionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 型号识别预览接口；返回识别状态，不调用模型或检索服务。 */
@RestController
@RequestMapping("/api/v1/routing")
public class ModelRoutingController {
    private final ModelRecognitionService service;

    public ModelRoutingController(ModelRecognitionService service) {
        this.service = service;
    }

    @PostMapping("/model")
    public ModelRecognitionResponse recognize(
            @Valid @RequestBody ModelRecognitionRequest request
    ) {
        return service.recognize(request.question());
    }
}
