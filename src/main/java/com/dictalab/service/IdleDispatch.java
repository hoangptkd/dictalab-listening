package com.dictalab.service;

import java.time.Clock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local wakeups reduce latency; bounded polling remains the durable recovery path across JVMs. */
final class IdleDispatch {
    private final Clock clock;
    private long nextPoll, nextSweep, revision;
    private long idleDelay = 1000;

    IdleDispatch() { this(Clock.systemUTC()); }
    IdleDispatch(Clock clock) { this.clock = clock; }

    synchronized long begin() { return clock.millis() < nextPoll ? -1 : revision; }
    synchronized void complete(long ticket, boolean foundWork) {
        if (ticket != revision) return; // An enqueue during polling must not lose its wakeup.
        nextPoll = clock.millis() + (foundWork ? 0 : idleDelay);
        idleDelay = foundWork ? 1000 : Math.min(5000, idleDelay * 2);
    }
    synchronized boolean sweepDue() {
        if (clock.millis() < nextSweep) return false;
        nextSweep = clock.millis() + 30000;
        return true;
    }
    synchronized void wake() { revision++; nextPoll = 0; idleDelay = 1000; }
    void wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { wake(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { wake(); }
        });
    }
}
