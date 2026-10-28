package com.dictalab.web;

import com.dictalab.service.ProgressOutbox;
import com.dictalab.service.ProgressPublisher;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/progress")
@Validated
public class ProgressOperationsController {
    private final ProgressOutbox outbox;
    private final ObjectProvider<ProgressPublisher> publisher;
    public ProgressOperationsController(ProgressOutbox outbox, ObjectProvider<ProgressPublisher> publisher) {
        this.outbox = outbox; this.publisher = publisher;
    }

    @PostMapping("/backfill")
    public ProgressOutbox.Backfill backfill(@RequestParam(defaultValue="") String after,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        requirePublisher();
        return outbox.backfill(after, limit);
    }

    @PostMapping("/events/{id}/retry")
    public void retry(@PathVariable String id) { requirePublisher().retry(id); }

    private ProgressPublisher requirePublisher() {
        var worker = publisher.getIfAvailable();
        if (worker == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Progress publishing is disabled");
        return worker;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalidRequest() {
        return java.util.Map.of("error", "Invalid progress operation");
    }
}
