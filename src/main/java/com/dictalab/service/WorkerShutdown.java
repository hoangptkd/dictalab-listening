package com.dictalab.service;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

final class WorkerShutdown {
    private WorkerShutdown() {}

    static void drain(ExecutorService workers, Duration grace) {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
