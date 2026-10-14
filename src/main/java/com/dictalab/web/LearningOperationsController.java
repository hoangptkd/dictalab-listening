package com.dictalab.web;

import com.dictalab.service.LearningJobs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Existing /api/admin authorization and CSRF apply; deterministic projection recovery only. */
@RestController
@RequestMapping("/api/admin/learning/jobs")
@ConditionalOnProperty(name="dictalab.learning.queue-enabled",havingValue="true")
public class LearningOperationsController {
    private final JdbcTemplate db;
    private final LearningJobs jobs;
    public LearningOperationsController(JdbcTemplate db,LearningJobs jobs) { this.db=db;this.jobs=jobs; }
    @GetMapping
    public List<Map<String,Object>> failed() {
        return db.queryForList("SELECT id,attempt_id,status,attempts,created_at FROM learning_jobs WHERE status='FAILED' ORDER BY created_at,id LIMIT 50");
    }
    @PostMapping("/{id}/retry")
    public void retry(@PathVariable UUID id) { jobs.retryFailed(id.toString()); }
}
