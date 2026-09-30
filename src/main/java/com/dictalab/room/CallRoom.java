package com.dictalab.room;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đối tượng phòng gọi thoại 1-1, giữ toàn bộ trạng thái cần thiết.
 * Giữ trong bộ nhớ — backend khởi động lại sẽ mất toàn bộ phòng.
 * Mọi truy cập từ RoomRegistry được bảo vệ bằng lock ngoài.
 */
public final class CallRoom {

    private final String roomId;
    private final String inviteCode;
    private final long hostUserId;
    private final String hostDisplayName;

    private Long guestUserId;
    private String guestDisplayName;
    private Long pendingUserId;
    private String pendingDisplayName;
    private Long lessonId;
    private int lessonPosition;
    private int lessonSentenceCount;
    private boolean hostReady;
    private boolean guestReady;
    private List<String> hostDrafts = List.of();
    private List<String> guestDrafts = List.of();
    private long hostDraftSequence;
    private long guestDraftSequence;
    private boolean hostCallReady;
    private boolean guestCallReady;
    private Instant callStartedAt;

    private RoomStatus status;
    private final Instant createdAt;
    private Instant lastActivityAt;

    /** Thời điểm host mất kết nối WebSocket (null nếu đang kết nối) */
    private Instant hostDisconnectedAt;
    /** Thời điểm guest mất kết nối WebSocket (null nếu đang kết nối) */
    private Instant guestDisconnectedAt;

    /** Số giây giữ chỗ sau khi mất kết nối trước khi giải phóng slot */
    public static final long RECONNECT_WINDOW_SECONDS = 60;
    /** Thời gian sống của invite link (24 giờ) */
    public static final long INVITE_TTL_SECONDS = 24 * 60 * 60;

    public CallRoom(long hostUserId, String hostDisplayName) {
        this.roomId = UUID.randomUUID().toString();
        this.inviteCode = generateInviteCode();
        this.hostUserId = hostUserId;
        this.hostDisplayName = hostDisplayName;
        this.status = RoomStatus.WAITING_GUEST;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    /** Sinh mã mời ngẫu nhiên an toàn (16 byte → 22 ký tự base64url không đệm). */
    private static String generateInviteCode() {
        byte[] bytes = new byte[16];
        try {
            java.security.SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            new java.security.SecureRandom().nextBytes(bytes);
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------------ queries

    public String getRoomId() { return roomId; }
    public String getInviteCode() { return inviteCode; }
    public long getHostUserId() { return hostUserId; }
    public String getHostDisplayName() { return hostDisplayName; }
    public Long getGuestUserId() { return guestUserId; }
    public String getGuestDisplayName() { return guestDisplayName; }
    public Long getPendingUserId() { return pendingUserId; }
    public String getPendingDisplayName() { return pendingDisplayName; }
    public RoomStatus getStatus() { return status; }
    public Long getLessonId() { return lessonId; }
    public int getLessonPosition() { return lessonPosition; }
    public int getLessonSentenceCount() { return lessonSentenceCount; }
    public boolean isHostReady() { return hostReady; }
    public boolean isGuestReady() { return guestReady; }
    public List<String> peerDrafts(long userId) { return isHost(userId) ? guestDrafts : hostDrafts; }
    public long peerDraftSequence(long userId) { return isHost(userId) ? guestDraftSequence : hostDraftSequence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getHostDisconnectedAt() { return hostDisconnectedAt; }
    public Instant getGuestDisconnectedAt() { return guestDisconnectedAt; }
    public Instant getCallStartedAt() { return callStartedAt; }

    public boolean isHost(long userId) { return hostUserId == userId; }
    public boolean isGuest(long userId) { return guestUserId != null && guestUserId == userId; }
    public boolean isPending(long userId) { return pendingUserId != null && pendingUserId == userId; }
    public boolean isMember(long userId) { return isHost(userId) || isGuest(userId); }
    public boolean isClosed() { return status == RoomStatus.CLOSED; }
    public boolean isFull() { return guestUserId != null; }

    public boolean isInviteExpired(Instant now) {
        return now.getEpochSecond() - createdAt.getEpochSecond() > INVITE_TTL_SECONDS;
    }

    // ------------------------------------------------------------------ mutations (đều cần được gọi dưới lock)

    public void touch(Instant now) {
        this.lastActivityAt = now;
    }

    public boolean selectLesson(long userId, long selectedLessonId, int sentenceCount) {
        if (!isHost(userId) || status != RoomStatus.ACTIVE || lessonId != null || sentenceCount < 1) return false;
        lessonId = selectedLessonId;
        lessonSentenceCount = sentenceCount;
        lessonPosition = 1;
        return true;
    }

    public void resetLesson() {
        lessonId = null;
        lessonPosition = 0;
        lessonSentenceCount = 0;
        hostReady = false;
        guestReady = false;
        hostDrafts = List.of();
        guestDrafts = List.of();
    }

    public boolean markReady(long userId, int position) {
        if (status != RoomStatus.ACTIVE || lessonId == null || lessonPosition != position) return false;
        if (isHost(userId)) hostReady = true;
        else if (isGuest(userId)) guestReady = true;
        else return false;
        return true;
    }

    public boolean advanceLesson(long userId) {
        if (!isHost(userId) || status != RoomStatus.ACTIVE || lessonId == null || !hostReady || !guestReady
                || lessonPosition > lessonSentenceCount) return false;
        lessonPosition++;
        hostReady = false;
        guestReady = false;
        hostDrafts = List.of();
        guestDrafts = List.of();
        hostDraftSequence = 0;
        guestDraftSequence = 0;
        return true;
    }

    public boolean updateDraft(long userId, int position, long sequence, List<String> words) {
        if (status != RoomStatus.ACTIVE || lessonId == null || lessonPosition != position
                || sequence < 1 || words.size() > 100
                || words.stream().anyMatch(word -> word == null || word.length() > 80)) return false;
        if (isHost(userId) && !hostReady && sequence > hostDraftSequence) {
            hostDrafts = List.copyOf(words);
            hostDraftSequence = sequence;
        } else if (isGuest(userId) && !guestReady && sequence > guestDraftSequence) {
            guestDrafts = List.copyOf(words);
            guestDraftSequence = sequence;
        }
        else return false;
        return true;
    }

    /** Bắt đầu đồng hồ khi cả hai đầu đã xác nhận nhận được âm thanh. */
    public Instant markCallReady(long userId, Instant now) {
        if (status != RoomStatus.ACTIVE) return null;
        if (isHost(userId)) hostCallReady = true;
        else if (isGuest(userId)) guestCallReady = true;
        else return null;
        if (callStartedAt == null && hostCallReady && guestCallReady) callStartedAt = now;
        return callStartedAt;
    }

    /**
     * Guest gửi yêu cầu tham gia. Trả false nếu phòng không ở trạng thái hợp lệ để nhận yêu cầu.
     */
    public boolean setPending(long userId, String displayName, Instant now) {
        if (status != RoomStatus.WAITING_GUEST) return false;
        if (hostUserId == userId) return false;  // host không tự join
        this.pendingUserId = userId;
        this.pendingDisplayName = displayName;
        this.status = RoomStatus.PENDING_APPROVAL;
        this.lastActivityAt = now;
        return true;
    }

    /**
     * Host duyệt guest đang pending. Trả false nếu không có pending hoặc không phải host duyệt.
     */
    public boolean approveGuest(long approvingUserId, Instant now) {
        if (hostUserId != approvingUserId) return false;
        if (status != RoomStatus.PENDING_APPROVAL || pendingUserId == null) return false;
        this.guestUserId = pendingUserId;
        this.guestDisplayName = pendingDisplayName;
        this.pendingUserId = null;
        this.pendingDisplayName = null;
        this.status = RoomStatus.ACTIVE;
        this.lastActivityAt = now;
        return true;
    }

    /**
     * Host từ chối guest đang pending. Trả false nếu không hợp lệ.
     */
    public boolean rejectGuest(long rejectingUserId, Instant now) {
        if (hostUserId != rejectingUserId) return false;
        if (status != RoomStatus.PENDING_APPROVAL || pendingUserId == null) return false;
        this.pendingUserId = null;
        this.pendingDisplayName = null;
        this.status = RoomStatus.WAITING_GUEST;
        this.lastActivityAt = now;
        return true;
    }

    /**
     * Thành viên rời phòng.
     * - Host rời: đóng phòng.
     * - Guest rời: phòng về WAITING_GUEST.
     * Trả false nếu userId không phải thành viên.
     */
    public boolean leave(long userId, Instant now) {
        if (isHost(userId)) {
            this.status = RoomStatus.CLOSED;
            this.lastActivityAt = now;
            return true;
        }
        if (isGuest(userId)) {
            this.guestUserId = null;
            this.guestDisplayName = null;
            this.status = lessonId == null ? RoomStatus.WAITING_GUEST : RoomStatus.CLOSED;
            this.hostCallReady = false;
            this.guestCallReady = false;
            this.callStartedAt = null;
            this.lastActivityAt = now;
            return true;
        }
        if (isPending(userId)) {
            this.pendingUserId = null;
            this.pendingDisplayName = null;
            this.status = RoomStatus.WAITING_GUEST;
            this.lastActivityAt = now;
            return true;
        }
        return false;
    }

    /** Ghi nhận host mất kết nối WS. */
    public void markHostDisconnected(Instant now) {
        if (isClosed()) return;
        this.hostDisconnectedAt = now;
        this.lastActivityAt = now;
    }

    /** Host kết nối lại. */
    public void clearHostDisconnected() {
        this.hostDisconnectedAt = null;
    }

    /** Ghi nhận guest mất kết nối WS. */
    public void markGuestDisconnected(Instant now) {
        if (isClosed()) return;
        this.guestDisconnectedAt = now;
        this.lastActivityAt = now;
    }

    /** Guest kết nối lại. */
    public void clearGuestDisconnected() {
        this.guestDisconnectedAt = null;
    }

    /** Đóng phòng (dùng khi cleanup). */
    public void close(Instant now) {
        this.status = RoomStatus.CLOSED;
        this.lastActivityAt = now;
    }
}
