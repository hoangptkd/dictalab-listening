package com.dictalab.scenario;

import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@Order(-10)
@RestControllerAdvice(assignableTypes = {ScenarioController.class,ScenarioLearningController.class})
public class ScenarioExceptionHandler {
    public record Error(String code, String messageVi, String requestId, boolean retryable) {}

    @ExceptionHandler(ScenarioFailure.class)
    ResponseEntity<Error> failed(ScenarioFailure failure) {
        return ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore())
                .body(new Error(failure.code, failure.getMessage(), requestId(), false));
    }

    private static String requestId() {
        String id = org.slf4j.MDC.get("requestId");
        return id == null ? UUID.randomUUID().toString() : id;
    }
}
