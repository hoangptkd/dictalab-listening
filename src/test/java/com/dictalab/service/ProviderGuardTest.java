package com.dictalab.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderGuardTest {
    @Test void zeroConcurrencyIsUnlimitedButCircuitRemainsActive(){
        var metrics=new SimpleMeterRegistry();
        var guard=new ProviderGuard("unlimited",0,metrics);
        var permits=new java.util.ArrayList<ProviderGuard.Permit>();
        try{
            for(int i=0;i<150;i++)permits.add(guard.enter());
            assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isEqualTo(150);
            permits.forEach(ProviderGuard.Permit::success);
        }finally{permits.forEach(ProviderGuard.Permit::close);}
        assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isZero();
        for(int i=0;i<5;i++)try(var permit=guard.enter()){permit.failure();}
        assertThatThrownBy(guard::enter).isInstanceOf(ProviderGuard.Unavailable.class);
    }
    @Test void retryAfterHonorsProviderFloorWithoutShorteningOutOfBudgetOrInvalidValues(){
        var now=java.time.Instant.parse("2026-10-03T00:00:00Z");
        assertThat(ProviderGuard.retryAfterSeconds("120",now)).isEqualTo(120);
        assertThat(ProviderGuard.retryAfterSeconds("Sat, 3 Oct 2026 00:02:00 GMT",now)).isEqualTo(120);
        assertThat(ProviderGuard.retryAfterSeconds("Sat, 3 Oct 2026 00:02:00 GMT",now.plusMillis(1))).isEqualTo(120);
        assertThat(ProviderGuard.retryAfterSeconds(null,now)).isEqualTo(30);
        assertThat(ProviderGuard.retryAfterSeconds("3600",now)).isEqualTo(-1);
        assertThat(ProviderGuard.retryAfterSeconds("999999999999999999999999",now)).isEqualTo(-1);
        assertThat(ProviderGuard.retryAfterSeconds("garbage",now)).isEqualTo(-1);
        assertThat(ProviderGuard.retryAfterSeconds("-1",now)).isEqualTo(-1);
    }
    @Test void recoveryAllowsOneProbeAndClosesOnlyAfterSuccess() throws Exception {
        var metrics=new SimpleMeterRegistry();
        var guard=new ProviderGuard("recovery",2,metrics,io.micrometer.observation.ObservationRegistry.NOOP,
                1,java.time.Duration.ofMillis(1));
        try(var permit=guard.enter()){permit.failure();}
        Thread.sleep(10);
        try(var probe=guard.enter()){
            assertThatThrownBy(guard::enter).isInstanceOf(ProviderGuard.Unavailable.class);
            probe.success();
        }
        try(var first=guard.enter();var second=guard.enter()){first.success();second.success();}
        assertThat(metrics.get("dictalab.provider.circuit").gauge().value()).isZero();
        assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isZero();
    }
    @Test void saturationAndFailuresReleaseEveryPermit(){
        var metrics=new SimpleMeterRegistry();var guard=new ProviderGuard("test",1,metrics);
        try(var first=guard.enter()){
            assertThatThrownBy(guard::enter).isInstanceOf(ProviderGuard.Unavailable.class);
            first.success();
        }
        for(int i=0;i<5;i++)try(var permit=guard.enter()){permit.failure();}
        assertThatThrownBy(guard::enter).isInstanceOf(ProviderGuard.Unavailable.class);
        assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isZero();
        assertThat(metrics.get("dictalab.provider.rejected").counter().count()).isEqualTo(2);
    }
}
