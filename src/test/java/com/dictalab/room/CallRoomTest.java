package com.dictalab.room;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CallRoomTest {

    @Test
    void sharedAudioIsHostOnlyIdempotentAndResetsPerSentence() {
        CallRoom room = new CallRoom(1L, "Host");
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        room.setPending(2L, "Guest", now);
        room.approveGuest(1L, now);
        room.selectLesson(1L, 10L, 2);
        assertThat(room.playShared(1L, 1, 0, 1, now)).isFalse();
        assertThat(room.audioReady(3L, 1, true)).isFalse();
        assertThat(room.audioReady(1L, 2, true)).isFalse();
        room.audioReady(1L, 1, true);
        room.audioReady(2L, 1, true);
        assertThat(room.playShared(2L, 1, 0, 1, now)).isFalse();
        assertThat(room.playShared(1L, 1, 0, Double.NaN, now)).isFalse();
        assertThat(room.playShared(1L, 1, 0, 0.75, now)).isTrue();
        assertThat(room.playbackState().replayCount()).isEqualTo(1);
        assertThat(room.playbackState().startsAt()).isEqualTo(now.plusMillis(750));
        assertThat(room.playShared(1L, 1, 0, 1, now)).isFalse();
        room.markReady(1L, 1);
        room.markReady(2L, 1);
        assertThat(room.advanceLesson(1L)).isFalse();
        assertThat(room.finishSharedPlayback(2L, 1, 0)).isFalse();
        room.finishSharedPlayback(1L, 1, 1);
        assertThat(room.playbackState().playing()).isTrue();
        room.finishSharedPlayback(2L, 1, 1);
        assertThat(room.playShared(1L, 1, 0, 1, now)).isFalse();
        assertThat(room.advanceLesson(1L)).isTrue();
        assertThat(room.playbackState().replayCount()).isZero();
        assertThat(room.playbackState().sequence()).isEqualTo(1);
        assertThat(room.playbackState().hostAudioReady()).isFalse();
        assertThat(room.playbackState().guestAudioReady()).isFalse();
        assertThat(room.playShared(1L, 1, 1, 1, now)).isFalse();
    }

    @Test
    void disconnectReleasesPlaybackButRequiresAudioReadinessAgain() {
        CallRoom room = new CallRoom(1L, "Host");
        Instant now = Instant.now();
        room.setPending(2L, "Guest", now);
        room.approveGuest(1L, now);
        room.selectLesson(1L, 10L, 1);
        room.audioReady(1L, 1, true);
        room.audioReady(2L, 1, true);
        room.playShared(1L, 1, 0, 1, now);
        long version = room.playbackState().version();
        room.markGuestDisconnected(now);
        room.finishSharedPlayback(1L, 1, 1);
        assertThat(room.playbackState().version()).isGreaterThan(version);
        assertThat(room.playbackState().playing()).isFalse();
        assertThat(room.playShared(1L, 1, 1, 1, now)).isFalse();
        room.audioReady(2L, 1, true);
        assertThat(room.playShared(1L, 1, 1, 1, now)).isTrue();
        room.expirePlayback(now.plusSeconds(302));
        assertThat(room.playbackState().playing()).isFalse();
        assertThat(room.playbackState().hostAudioReady()).isFalse();
        assertThat(room.playbackState().guestAudioReady()).isFalse();
    }

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
