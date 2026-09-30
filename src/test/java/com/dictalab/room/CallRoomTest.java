package com.dictalab.room;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CallRoomTest {

    @Test
    void callClockStartsOnlyAfterBothMembersConfirmAudio() {
        CallRoom room = new CallRoom(1L, "Host");
        Instant joinedAt = Instant.parse("2026-09-30T10:00:00Z");
        assertThat(room.setPending(2L, "Guest", joinedAt)).isTrue();
        assertThat(room.approveGuest(1L, joinedAt)).isTrue();

        assertThat(room.markCallReady(1L, joinedAt.plusSeconds(1))).isNull();
        Instant startedAt = room.markCallReady(2L, joinedAt.plusSeconds(2));

        assertThat(startedAt).isEqualTo(joinedAt.plusSeconds(2));
        assertThat(room.markCallReady(1L, joinedAt.plusSeconds(20))).isEqualTo(startedAt);
    }
}
