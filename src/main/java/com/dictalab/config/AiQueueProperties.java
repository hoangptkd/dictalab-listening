package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dictalab.ai.queue")
public record AiQueueProperties(boolean enabled, String exchange, String audioQueue, String coachQueue,
                                int audioWorkers, int coachWorkers) {
    public AiQueueProperties {
        if (exchange == null || exchange.isBlank()) exchange = "dictalab.ai.jobs";
        if (audioQueue == null || audioQueue.isBlank()) audioQueue = "dictalab.conversation.audio";
        if (coachQueue == null || coachQueue.isBlank()) coachQueue = "dictalab.coach.reports";
        if (audioWorkers == 0) audioWorkers = 2;
        if (coachWorkers == 0) coachWorkers = 1;
        if (audioWorkers < 1 || coachWorkers < 1)
            throw new IllegalArgumentException("Invalid AI queue worker limits");
        if (audioQueue.equals(coachQueue)) throw new IllegalArgumentException("AI queues must be distinct");
    }
}
