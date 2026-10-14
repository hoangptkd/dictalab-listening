package com.dictalab.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded provider concurrency and one recovery probe, without an unbounded waiting queue. */
public final class ProviderGuard {
    private final Semaphore permits;
    private final AtomicInteger active=new AtomicInteger();
    private final MeterRegistry metrics;
    private final String provider;
    private final io.micrometer.observation.ObservationRegistry observations;
    private final int failureThreshold;
    private final Duration recoveryDelay;
    private int failures;
    private long openUntil;
    private boolean probe;

    public ProviderGuard(String provider,int concurrency,MeterRegistry metrics){
        this(provider,concurrency,metrics,io.micrometer.observation.ObservationRegistry.NOOP,5,Duration.ofSeconds(30));
    }
    public ProviderGuard(String provider,int concurrency,MeterRegistry metrics,
            io.micrometer.observation.ObservationRegistry observations,int failureThreshold,Duration recoveryDelay){
        if(concurrency<1 || concurrency>128) throw new IllegalArgumentException("Invalid provider concurrency");
        if(failureThreshold<1 || recoveryDelay.isNegative() || recoveryDelay.isZero())throw new IllegalArgumentException("Invalid circuit settings");
        this.provider=provider; this.metrics=metrics; this.permits=new Semaphore(concurrency);
        this.observations=observations;this.failureThreshold=failureThreshold;this.recoveryDelay=recoveryDelay;
        metrics.gauge("dictalab.provider.inflight",java.util.List.of(io.micrometer.core.instrument.Tag.of("provider",provider)),active);
        metrics.gauge("dictalab.provider.circuit",java.util.List.of(io.micrometer.core.instrument.Tag.of("provider",provider)),
                this, guard -> guard.circuitState());
    }
    private synchronized int circuitState(){return openUntil!=0?1:0;}

    public synchronized Permit enter(){
        long now=System.nanoTime();
        if(openUntil>now || probe || !permits.tryAcquire()){
            metrics.counter("dictalab.provider.rejected","provider",provider).increment();
            throw new Unavailable("Dịch vụ đang bận. Vui lòng thử lại sau.",5,true);
        }
        boolean recovery=openUntil!=0;
        if(recovery) probe=true;
        active.incrementAndGet();
        return new Permit(recovery);
    }

    public final class Permit implements AutoCloseable {
        private final boolean recovery;
        private final long started=System.nanoTime();
        private boolean successful, providerFailure, closed;
        private final io.micrometer.observation.Observation observation;
        private final io.micrometer.observation.Observation.Scope scope;
        private Permit(boolean recovery){
            this.recovery=recovery;
            observation=io.micrometer.observation.Observation.createNotStarted("dictalab.provider.call",observations)
                    .lowCardinalityKeyValue("provider",provider).start();
            scope=observation.openScope();
        }
        public void success(){successful=true;}
        public void failure(){providerFailure=true;}
        @Override public void close(){
            synchronized(ProviderGuard.this){
                if(closed)return;
                closed=true;
                if(successful){failures=0;if(recovery)openUntil=0;}
                else if(providerFailure){
                    if(++failures>=failureThreshold || recovery)openUntil=System.nanoTime()+recoveryDelay.toNanos();
                }
                else if(recovery)openUntil=System.nanoTime()+recoveryDelay.toNanos();
                if(recovery)probe=false;
                active.decrementAndGet();permits.release();
            }
            metrics.timer("dictalab.provider.duration","provider",provider,"outcome",successful?"success":"error")
                    .record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
            if(!successful)observation.error(new IllegalStateException("Provider call failed"));
            scope.close();observation.stop();
        }
    }

    public static final class Unavailable extends IllegalStateException {
        private final int retryAfter;
        private final boolean retrySafe;
        public Unavailable(String message,int retryAfter){this(message,retryAfter,false);}
        // True only when this process rejected admission before any provider request was sent.
        Unavailable(String message,int retryAfter,boolean retrySafe){super(message);this.retryAfter=retryAfter;this.retrySafe=retrySafe;}
        public int retryAfter(){return retryAfter;}
        public boolean retrySafe(){return retrySafe;}
    }

    // A Retry-After longer than our automatic retry budget is not shortened: require manual retry.
    static int retryAfterSeconds(String value,java.time.Instant now){
        if(value==null || value.isBlank()) return 30;
        try{
            long seconds=value.matches("[0-9]+") ? Long.parseLong(value)
                    : Math.max(0,(long)Math.ceil(Duration.between(now,java.time.ZonedDateTime.parse(value,
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()/1000.0));
            return seconds>300 ? -1 : (int)Math.max(1,seconds);
        }catch(java.time.DateTimeException | NumberFormatException invalid){return -1;}
    }
}
