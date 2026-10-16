package com.dictalab.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory registry quản lý toàn bộ CallRoom.
 * Thread-safe: mỗi phòng có lock riêng; lock trên roomsByUser được giữ ngắn.
 * ponytail: một lock index toàn registry; chuyển sang index nguyên tử theo user nếu có contention.
 *
 * Giới hạn:
 *  - Tối đa MAX_ROOMS phòng mở đồng thời.
 *  - Một user không thể có mặt (host hoặc guest) trong hai phòng cùng lúc.
 *  - Phòng bỏ trống (host disconnect > RECONNECT_WINDOW_SECONDS) được dọn mỗi 60s.
 *  - Invite hết hạn (> INVITE_TTL_SECONDS) cũng được dọn.
 *
 * Lưu ý: backend khởi động lại sẽ mất toàn bộ phòng — thiết kế có chủ ý.
 */
@Component
public class RoomRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);
    private final int maximumRooms;

    /** roomId → room */
    private final ConcurrentHashMap<String, CallRoom> rooms = new ConcurrentHashMap<>();
    /** roomId → lock cho phòng đó */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** userId → roomId (gồm cả yêu cầu đang chờ duyệt để giữ chỗ cho user) */
    private final ConcurrentHashMap<Long, String> roomsByUser = new ConcurrentHashMap<>();
    /** inviteCode → roomId */
    private final ConcurrentHashMap<String, String> roomsByInvite = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RoomRegistry(@org.springframework.beans.factory.annotation.Value("${dictalab.room.maximum-rooms:200}") int maximumRooms,
                        io.micrometer.core.instrument.MeterRegistry metrics) {
        if(maximumRooms<1 || maximumRooms>100_000)throw new IllegalArgumentException("Invalid room capacity");
        this.maximumRooms=maximumRooms;
        metrics.gauge("dictalab.rooms.open",rooms,Map::size);
        metrics.gauge("dictalab.rooms.members",roomsByUser,Map::size);
    }
    public RoomRegistry(){this(200,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());}

    // ----------------------------------------------------------------------- public API

    /**
     * Tạo phòng mới. Trả Optional.empty() nếu đã đạt giới hạn hoặc host đang trong phòng khác.
     */
    public Optional<CallRoom> createRoom(long hostUserId, String hostDisplayName) {
        // Lock order: room lock, then membership index. Creation only takes the index lock.
        synchronized (roomsByUser) {
            if (rooms.size() >= maximumRooms) return Optional.empty();
            String priorRoomId = roomsByUser.get(hostUserId);
            if (priorRoomId != null) {
                CallRoom prior = rooms.get(priorRoomId);
                if (prior != null && !prior.isClosed()) return Optional.empty();
                roomsByUser.remove(hostUserId, priorRoomId);
            }

            CallRoom room = new CallRoom(hostUserId, hostDisplayName);
            locks.put(room.getRoomId(), new ReentrantLock());
            rooms.put(room.getRoomId(), room);
            roomsByInvite.put(room.getInviteCode(), room.getRoomId());
            roomsByUser.put(hostUserId, room.getRoomId());
            log.debug("Room created: {} by user {}", room.getRoomId(), hostUserId);
            return Optional.of(room);
        }
    }

    /** Tìm phòng theo roomId. */
    public Optional<CallRoom> findById(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    /** Tìm phòng theo inviteCode. */
    public Optional<CallRoom> findByInviteCode(String inviteCode) {
        String roomId = roomsByInvite.get(inviteCode);
        if (roomId == null) return Optional.empty();
        return findById(roomId);
    }

    /** Tìm phòng mà user đang là thành viên hoặc đang chờ duyệt. */
    public Optional<CallRoom> findRoomForUser(long userId) {
        synchronized (roomsByUser) {
            String roomId = roomsByUser.get(userId);
            if (roomId != null) return findById(roomId).filter(room -> !room.isClosed());
            return Optional.empty();
        }
    }

    /**
     * Guest xin tham gia phòng.
     * Trả kết quả enum để caller biết lý do thất bại.
     */
    public JoinResult joinRoom(String roomId, long guestUserId, String guestDisplayName) {
        return withLock(roomId, () -> {
            synchronized (roomsByUser) {
                CallRoom room = rooms.get(roomId);
                if (room == null || room.isClosed()) return JoinResult.NOT_FOUND;
                if (room.isHost(guestUserId)) return JoinResult.IS_HOST;  // kiểm tra host trước
                if (roomsByUser.containsKey(guestUserId)) return JoinResult.ALREADY_IN_ROOM;
                if (room.isInviteExpired(Instant.now())) return JoinResult.EXPIRED;
                if (room.isFull()) return JoinResult.FULL;
                if (!room.setPending(guestUserId, guestDisplayName, Instant.now())) return JoinResult.CONFLICT;
                roomsByUser.put(guestUserId, roomId);
                return JoinResult.OK;
            }
        });
    }

    /**
     * Host duyệt guest đang pending.
     */
    public ApproveResult approveGuest(String roomId, long hostUserId) {
        return withLock(roomId, () -> {
            synchronized (roomsByUser) {
                CallRoom room = rooms.get(roomId);
                if (room == null || room.isClosed()) return ApproveResult.NOT_FOUND;
                if (!room.isHost(hostUserId)) return ApproveResult.FORBIDDEN;
                if (!room.approveGuest(hostUserId, Instant.now())) return ApproveResult.NO_PENDING;
                return ApproveResult.OK;
            }
        });
    }

    /**
     * Host từ chối guest đang pending.
     */
    public RejectResult rejectGuest(String roomId, long hostUserId) {
        return withLock(roomId, () -> {
            synchronized (roomsByUser) {
                CallRoom room = rooms.get(roomId);
                if (room == null || room.isClosed()) return RejectResult.NOT_FOUND;
                if (!room.isHost(hostUserId)) return RejectResult.FORBIDDEN;
                Long pendingUserId = room.getPendingUserId();
                if (!room.rejectGuest(hostUserId, Instant.now())) return RejectResult.NO_PENDING;
                roomsByUser.remove(pendingUserId, roomId);
                return RejectResult.OK;
            }
        });
    }

    /**
     * Thành viên rời phòng.
     * Nếu host rời: phòng đóng, guest bị xóa khỏi index.
     * Nếu guest rời: phòng về WAITING_GUEST.
     */
    public LeaveResult leaveRoom(String roomId, long userId) {
        return withLock(roomId, () -> {
            synchronized (roomsByUser) {
                CallRoom room = rooms.get(roomId);
                if (room == null) return LeaveResult.NOT_FOUND;
                if (!room.isMember(userId) && !room.isPending(userId)) return LeaveResult.NOT_MEMBER;
                boolean wasHost = room.isHost(userId);
                if (!room.leave(userId, Instant.now())) return LeaveResult.NOT_MEMBER;

                // Dọn index user
                roomsByUser.remove(userId, roomId);
                if (wasHost || room.isClosed()) {
                    // Host rời → phòng đóng → dọn tất cả user còn lại khỏi index
                    roomsByUser.entrySet().removeIf(e -> roomId.equals(e.getValue()));
                    // Không xóa khỏi rooms ngay — để cleanup() xử lý; test có thể kiểm tra isClosed()
                }
                return wasHost ? LeaveResult.HOST_LEFT : LeaveResult.OK;
            }
        });
    }

    public boolean selectLesson(String roomId, long userId, long lessonId, int sentenceCount) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            return room != null && room.selectLesson(userId, lessonId, sentenceCount);
        });
    }

    public boolean markReady(String roomId, long userId, int position) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            return room != null && room.markReady(userId, position);
        });
    }

    public boolean advanceLesson(String roomId, long userId) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            return room != null && room.advanceLesson(userId);
        });
    }

    public void resetLesson(String roomId, long lessonId) {
        withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            if (room != null && room.getLessonId() != null && room.getLessonId().equals(lessonId)) {
                room.resetLesson();
            }
            return null;
        });
    }

    public boolean updateDraft(String roomId, long userId, int position, long sequence, java.util.List<String> words) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            return room != null && room.updateDraft(userId, position, sequence, words);
        });
    }

    public Instant markCallReady(String roomId, long userId) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            return room == null ? null : room.markCallReady(userId, Instant.now());
        });
    }

    public RoomDtos.PlaybackState updatePlayback(String roomId, long userId, String type, int position,
                                                 long sequence, double speed, boolean ready) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            if (room == null || room.isClosed() || !room.isMember(userId)) return null;
            boolean accepted = switch (type) {
                case "audio-ready" -> room.audioReady(userId, position, ready);
                case "lesson-play" -> room.playShared(userId, position, sequence, speed, Instant.now());
                case "lesson-played" -> room.finishSharedPlayback(userId, position, sequence);
                default -> false;
            };
            return accepted ? room.playbackState() : null;
        });
    }

    public RoomDtos.PlaybackState playbackState(String roomId) {
        return withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            if (room != null) room.expirePlayback(Instant.now());
            return room == null ? null : room.playbackState();
        });
    }

    /** Ghi nhận thành viên mất kết nối WebSocket. */
    public void markDisconnected(String roomId, long userId) {
        withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            if (room == null) return null;
            Instant now = Instant.now();
            if (room.isHost(userId)) room.markHostDisconnected(now);
            else if (room.isGuest(userId)) room.markGuestDisconnected(now);
            return null;
        });
    }

    /** Ghi nhận thành viên kết nối lại sau khi mất kết nối WS. */
    public void markReconnected(String roomId, long userId) {
        withLock(roomId, () -> {
            CallRoom room = rooms.get(roomId);
            if (room == null) return null;
            if (room.isHost(userId)) room.clearHostDisconnected();
            else if (room.isGuest(userId)) room.clearGuestDisconnected();
            return null;
        });
    }

    // ----------------------------------------------------------------------- cleanup

    /**
     * Chạy mỗi 60 giây: dọn phòng hết hạn.
     * - Host mất kết nối > RECONNECT_WINDOW_SECONDS → đóng phòng.
     * - Phòng WAITING_GUEST invite đã hết hạn → đóng phòng.
     * - Phòng CLOSED → xóa khỏi registry.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredRooms() {
        Instant now = Instant.now();
        int cleaned = 0;
        for (Map.Entry<String, CallRoom> entry : rooms.entrySet()) {
            String roomId = entry.getKey();
            CallRoom room = entry.getValue();
            boolean removed = withLock(roomId, () -> {
                synchronized (roomsByUser) {
                    if (room.isClosed()) {
                        roomsByUser.entrySet().removeIf(e -> roomId.equals(e.getValue()));
                        removeRoom(room);
                        return true;
                    }
                    // Host mất kết nối quá lâu
                    if (room.getHostDisconnectedAt() != null) {
                        long elapsed = now.getEpochSecond() - room.getHostDisconnectedAt().getEpochSecond();
                        if (elapsed > CallRoom.RECONNECT_WINDOW_SECONDS) {
                            log.debug("Room {} closed: host {} disconnected too long", roomId, room.getHostUserId());
                            room.close(now);
                            roomsByUser.entrySet().removeIf(e -> roomId.equals(e.getValue()));
                        }
                    }
                    // Guest mất kết nối quá lâu → giải phóng slot; bài học chung đang chạy sẽ đóng an toàn.
                    if (!room.isClosed() && room.getGuestDisconnectedAt() != null && room.getGuestUserId() != null) {
                        long elapsed = now.getEpochSecond() - room.getGuestDisconnectedAt().getEpochSecond();
                        if (elapsed > CallRoom.RECONNECT_WINDOW_SECONDS) {
                            log.debug("Room {}: guest {} slot released after disconnect timeout", roomId, room.getGuestUserId());
                            long guestId = room.getGuestUserId();
                            room.leave(guestId, now);
                            roomsByUser.remove(guestId, roomId);
                            if (room.isClosed()) roomsByUser.entrySet().removeIf(e -> roomId.equals(e.getValue()));
                        }
                    }
                    // Invite hết hạn và chưa ai join
                    if (!room.isClosed() && room.getStatus() == RoomStatus.WAITING_GUEST && room.isInviteExpired(now)) {
                        log.debug("Room {} closed: invite expired", roomId);
                        room.close(now);
                        roomsByUser.remove(room.getHostUserId(), roomId);
                    }
                    return false;
                }
            });
            if (removed) cleaned++;
        }
        if (cleaned > 0) log.debug("Cleanup: removed {} closed rooms", cleaned);
    }

    // ----------------------------------------------------------------------- helpers

    private void removeRoom(CallRoom room) {
        rooms.remove(room.getRoomId());
        locks.remove(room.getRoomId());
        roomsByInvite.remove(room.getInviteCode());
    }

    private <T> T withLock(String roomId, java.util.concurrent.Callable<T> action) {
        ReentrantLock lock = locks.get(roomId);
        if (lock != null) lock.lock();
        try {
            return action.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (lock != null) lock.unlock();
        }
    }

    // ----------------------------------------------------------------------- result enums

    public enum JoinResult { OK, NOT_FOUND, EXPIRED, FULL, IS_HOST, ALREADY_IN_ROOM, CONFLICT }
    public enum ApproveResult { OK, NOT_FOUND, FORBIDDEN, NO_PENDING }
    public enum RejectResult { OK, NOT_FOUND, FORBIDDEN, NO_PENDING }
    public enum LeaveResult { OK, HOST_LEFT, NOT_FOUND, NOT_MEMBER }
}
