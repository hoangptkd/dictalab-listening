package com.dictalab.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WorkerShutdownTest {
    @Test void drainStopsAdmissionButLetsRunningAndQueuedTasksCommit() throws Exception {
        var workers=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var committed=new java.util.concurrent.atomic.AtomicInteger();
        workers.submit(()->{entered.countDown(); try {release.await(); committed.incrementAndGet();} catch(InterruptedException e){Thread.currentThread().interrupt();}});
        workers.submit(committed::incrementAndGet);
        assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        var caller=Executors.newSingleThreadExecutor();
        try {
            var stopping=caller.submit(()->WorkerShutdown.drain(workers,Duration.ofSeconds(3)));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(workers::isShutdown);
            assertThatThrownBy(()->workers.submit(()->{})).isInstanceOf(RejectedExecutionException.class);
            assertThat(stopping.isDone()).isFalse(); release.countDown(); stopping.get(2,TimeUnit.SECONDS);
            assertThat(committed.get()).isEqualTo(2); assertThat(workers.isTerminated()).isTrue();
        } finally {release.countDown();workers.shutdownNow();caller.shutdownNow();}
    }

    @Test void deadlineInterruptsWorkerWithoutClaimingSuccessfulCompletion() throws Exception {
        var workers=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var interrupted=new CountDownLatch(1);
        workers.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}});
        try {
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); WorkerShutdown.drain(workers,Duration.ofMillis(20));
            assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();
        } finally {workers.shutdownNow();}
    }

    @Test void interruptedShutdownPreservesCallerInterrupt() {
        var workers=Executors.newSingleThreadExecutor(); workers.submit(()->{});
        Thread.currentThread().interrupt();
        try {WorkerShutdown.drain(workers,Duration.ofSeconds(1));assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(workers.isShutdown()).isTrue();}
        finally {Thread.interrupted();workers.shutdownNow();}
    }
}
