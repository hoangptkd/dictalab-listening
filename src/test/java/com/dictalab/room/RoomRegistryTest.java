package com.dictalab.room;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RoomRegistryTest {
    @Test
    void concurrentCreationReservesExactlyOneRoomForAUser() throws Exception {
        for (int trial = 0; trial < 30; trial++) {
            var registry = new RoomRegistry();
            List<Callable<Boolean>> requests = java.util.Collections.nCopies(16, () -> registry.createRoom(1L, "Host").isPresent());
            var results = race(requests);
            assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
            assertThat(registry.findRoomForUser(1L)).isPresent();
        }
    }

    @Test
    void concurrentCreationCannotExceedCapacity() throws Exception {
        var registry = new RoomRegistry();
        for (long id = 1; id <= 199; id++) assertThat(registry.createRoom(id, "Host")).isPresent();
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (long id = 200; id < 216; id++) {
            long userId = id;
            requests.add(() -> registry.createRoom(userId, "Host").isPresent());
        }
        assertThat(race(requests)).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    @Test
    void pendingMembershipCannotRaceWithCreationOrAnotherJoin() throws Exception {
        for (int trial = 0; trial < 30; trial++) {
            var registry = new RoomRegistry();
            CallRoom first = registry.createRoom(1, "First").orElseThrow();
            CallRoom second = registry.createRoom(2, "Second").orElseThrow();
            List<Callable<Boolean>> requests = List.of(
                    () -> registry.joinRoom(first.getRoomId(), 3, "Guest") == RoomRegistry.JoinResult.OK,
                    () -> registry.joinRoom(second.getRoomId(), 3, "Guest") == RoomRegistry.JoinResult.OK,
                    () -> registry.createRoom(3, "Guest").isPresent());
            assertThat(race(requests)).filteredOn(Boolean::booleanValue).hasSize(1);
            if (first.isPending(3)) assertThat(registry.approveGuest(first.getRoomId(), 1)).isEqualTo(RoomRegistry.ApproveResult.OK);
            if (second.isPending(3)) assertThat(registry.approveGuest(second.getRoomId(), 2)).isEqualTo(RoomRegistry.ApproveResult.OK);
            assertThat(registry.findRoomForUser(3)).isPresent();
        }
    }

    @Test
    void leavingRejectionAndCleanupReleaseOnlyTheOldReservation() {
        var registry = new RoomRegistry();
        CallRoom room = registry.createRoom(1, "Host").orElseThrow();
        assertThat(registry.joinRoom(room.getRoomId(), 2, "Guest")).isEqualTo(RoomRegistry.JoinResult.OK);
        assertThat(registry.rejectGuest(room.getRoomId(), 1)).isEqualTo(RoomRegistry.RejectResult.OK);
        assertThat(registry.createRoom(2, "Guest")).isPresent();
        registry.leaveRoom(room.getRoomId(), 1);
        CallRoom replacement = registry.createRoom(1, "Host").orElseThrow();
        registry.cleanupExpiredRooms();
        assertThat(registry.findRoomForUser(1)).hasValue(replacement);

        var other = new RoomRegistry();
        CallRoom expired = other.createRoom(10, "Host").orElseThrow();
        other.joinRoom(expired.getRoomId(), 20, "Guest");
        other.approveGuest(expired.getRoomId(), 10);
        other.selectLesson(expired.getRoomId(), 10, 1, 3);
        expired.markGuestDisconnected(Instant.now().minusSeconds(120));
        other.cleanupExpiredRooms();
        assertThat(expired.isClosed()).isTrue();
        assertThat(other.createRoom(10, "Host")).isPresent();
        assertThat(other.createRoom(20, "Guest")).isPresent();
    }

    private <T> List<T> race(List<Callable<T>> requests) throws Exception {
        var executor = Executors.newFixedThreadPool(requests.size());
        var ready = new CountDownLatch(requests.size());
        var start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (var request : requests) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                return request.call();
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(5, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
