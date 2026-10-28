package com.dictalab.progress;

import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/users")
@Validated
public class ProgressController {
    private final ProgressStore store;
    public ProgressController(ProgressStore store) { this.store = store; }

    @GetMapping("/{userId}/dashboard")
    public ProgressStore.Projection dashboard(@PathVariable @Positive long userId) {
        return store.dashboard(userId);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalidRequest() {
        return java.util.Map.of("error", "Invalid progress user");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    public java.util.Map<String,String> databaseUnavailable() {
        return java.util.Map.of("error", "Progress projection is temporarily unavailable");
    }
}
