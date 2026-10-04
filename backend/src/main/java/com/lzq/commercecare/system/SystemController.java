package com.lzq.commercecare.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final String applicationName;

    public SystemController(
            @Value("${spring.application.name}") String applicationName
    ) {
        this.applicationName = applicationName;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        return new SystemInfoResponse(
                applicationName,
                Runtime.version().toString()
        );
    }
}
