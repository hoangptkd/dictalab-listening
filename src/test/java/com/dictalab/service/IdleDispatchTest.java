package com.dictalab.service;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.*;

class IdleDispatchTest {
    static class MutableClock extends Clock {
        long millis = 100000;
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
        public long millis() { return millis; }
    }

    @Test void emptyPollsBackOffToFiveSecondsAndWakeupCannotBeLostDuringPoll() {
        var clock = new MutableClock(); var gate = new IdleDispatch(clock);
        for (long delay : new long[]{1000, 2000, 4000, 5000, 5000}) {
            long ticket = gate.begin(); assertThat(ticket).isGreaterThanOrEqualTo(0);
            gate.complete(ticket, false);
            clock.millis += delay - 1; assertThat(gate.begin()).isEqualTo(-1);
            clock.millis++; assertThat(gate.begin()).isGreaterThanOrEqualTo(0);
        }
        long old = gate.begin(); gate.wake(); gate.complete(old, false);
        assertThat(gate.begin()).isGreaterThanOrEqualTo(0);
        gate.complete(gate.begin(), true); assertThat(gate.begin()).isGreaterThanOrEqualTo(0);
        assertThat(gate.sweepDue()).isTrue(); assertThat(gate.sweepDue()).isFalse();
        clock.millis += 30000; assertThat(gate.sweepDue()).isTrue();
    }
}
