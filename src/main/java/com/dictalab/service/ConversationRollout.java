package com.dictalab.service;

import com.dictalab.config.ConversationRolloutProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** Session-stable canary and one unqueued shadow call, with no domain writes. */
@Service
final class ConversationRollout {
    private final ConversationRolloutProperties config;
    private final ConversationPythonClient python;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "conversation-shadow");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private long windowStarted = System.nanoTime();
    private int submitted;

    ConversationRollout(ConversationRolloutProperties config, ConversationPythonClient python, MeterRegistry metrics) {
        this.config = config;
        this.python = python;
        this.metrics = metrics;
        metrics.gauge("dictalab.conversation.shadow.active", worker, ThreadPoolExecutor::getActiveCount);
        metrics.gauge("dictalab.conversation.shadow.queue", worker, w -> w.getQueue().size());
    }

    boolean usePython(long user, String session) {
        return python.enabled() || config.canaryEnabled()
                && (config.canaryUsers().contains(user) || config.canarySessions().contains(session));
    }

    boolean mayUsePython() { return python.enabled() || config.canaryEnabled(); }

    synchronized boolean shadow(String level, String input) {
        if (config.shadowSampleRate() == 0 || ThreadLocalRandom.current().nextDouble() >= config.shadowSampleRate())
            return false;
        long now = System.nanoTime();
        if (now - windowStarted >= Duration.ofHours(1).toNanos()) {
            windowStarted = now;
            submitted = 0;
        }
        if (submitted >= config.shadowMaxRequestsPerHour()) return false;
        try {
            worker.execute(() -> {
                String outcome = "success";
                try {
                    // The shadow response is intentionally discarded; only bounded metadata is recorded by the client.
                    python.completeShadowTask(level, input, Duration.ofSeconds(config.shadowTimeoutSeconds()));
                } catch (RuntimeException failure) {
                    outcome = "failure";
                } finally {
                    metrics.counter("dictalab.conversation.shadow.requests", "outcome", outcome).increment();
                }
            });
            submitted++;
            return true;
        } catch (RejectedExecutionException busy) {
            metrics.counter("dictalab.conversation.shadow.requests", "outcome", "skipped_busy").increment();
            return false;
        }
    }

    @PreDestroy void close() { worker.shutdownNow(); }
}
