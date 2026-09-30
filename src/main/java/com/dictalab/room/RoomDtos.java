package com.dictalab.room;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.Positive;

/** DTOs dùng cho Room API — record Java để serialize thành JSON. */
public final class RoomDtos {

    private RoomDtos() {}

    /** Trả về sau khi tạo phòng thành công. */
    public record CreateRoomResponse(
            String roomId,
            String inviteCode,
            String inviteUrl
    ) {}

    /** Thông tin phòng khi guest xem từ invite link. */
    public record RoomInfoResponse(
            String roomId,
            String status,
            String hostDisplayName,
            boolean full,
            boolean expired
    ) {}

    /** Trả về sau khi guest gửi yêu cầu tham gia. */
    public record JoinResponse(
            String roomId,
            String status,
            String message
    ) {}

    public record SelectLessonRequest(@Positive long lessonId) {}

    /** Trả về sau khi host approve/reject. */
    public record ApproveRejectResponse(
            String roomId,
            String status,
            String message
    ) {}

    /**
     * STUN/TURN ICE server configuration — cấp cho thành viên đã được duyệt.
     * credentials là null nếu chưa cấu hình TURN.
     */
    public record IceConfigResponse(
            List<IceServer> iceServers
    ) {
        public record IceServer(
                List<String> urls,
                String username,
                String credential
        ) {}
    }

    /** Trạng thái phòng đầy đủ (dùng sau khi approve để sync). */
    public record RoomStateResponse(
            String roomId,
            String status,
            MemberInfo host,
            MemberInfo guest,       // null nếu chưa có guest
            MemberInfo pending,     // null nếu không có pending
            String inviteCode,      // chỉ chủ phòng nhận được
            Long lessonId,
            int lessonPosition,
            int lessonSentenceCount,
            boolean hostReady,
            boolean guestReady,
            List<String> peerDrafts,
            long peerDraftSequence,
            Instant callStartedAt,
            Instant serverNow
    ) {
        public record MemberInfo(long userId, String displayName) {}
    }
}
