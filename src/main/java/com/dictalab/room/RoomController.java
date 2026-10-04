package com.dictalab.room;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.dto.ApiDtos;
import com.dictalab.service.PracticeService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * REST controller cho tính năng "Luyện cùng bạn".
 * Tất cả endpoint yêu cầu xác thực; userId lấy từ principal đã xác minh JWT.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomRegistry registry;
    private final PracticeService practiceService;
    private final RoomWebSocketHandler sockets;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${dictalab.room.turn-url:}")
    private String turnUrl;

    @Value("${dictalab.room.turn-secret:}")
    private String turnSecret;

    @Value("${dictalab.room.turn-ttl-seconds:3600}")
    private long turnTtlSeconds;

    @Value("${dictalab.room.allowed-origins:http://localhost:18081}")
    private String allowedOriginsConfig;

    public RoomController(RoomRegistry registry, PracticeService practiceService, RoomWebSocketHandler sockets) {
        this.registry = registry;
        this.practiceService = practiceService;
        this.sockets=sockets;
    }

    // ------------------------------------------------------------------ endpoints

    /**
     * POST /api/v1/rooms — Tạo phòng mới.
     * Host không thể tạo phòng mới nếu đang trong phòng khác.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomDtos.CreateRoomResponse createRoom(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            jakarta.servlet.http.HttpServletRequest request) {

        var opt = registry.createRoom(principal.id(), principal.displayName());
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bạn đang trong một phòng khác hoặc hệ thống đạt giới hạn phòng. Vui lòng rời phòng hiện tại trước.");
        }
        CallRoom room = opt.get();
        String baseUrl = buildBaseUrl(request);
        String inviteUrl = baseUrl + "/rooms/join/" + room.getInviteCode();
        return new RoomDtos.CreateRoomResponse(room.getRoomId(), room.getInviteCode(), inviteUrl);
    }

    @GetMapping("/mine")
    public ResponseEntity<RoomDtos.RoomStateResponse> myRoom(
            @AuthenticationPrincipal DictaLabPrincipal principal) {
        return registry.findRoomForUser(principal.id())
                .filter(room -> !room.isClosed())
                .map(room -> ResponseEntity.ok(buildRoomState(room, principal.id())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{roomId}/lesson")
    public RoomDtos.RoomStateResponse selectLesson(@PathVariable String roomId,
                                                    @AuthenticationPrincipal DictaLabPrincipal principal,
                                                    @Valid @RequestBody RoomDtos.SelectLessonRequest request) {
        CallRoom room = memberRoom(roomId, principal.id());
        if (!room.isHost(principal.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ chủ phòng được chọn bài.");
        }
        int count = practiceService.sharedLessonSize(request.lessonId());
        if (!registry.selectLesson(roomId, principal.id(), request.lessonId(), count)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phòng chưa sẵn sàng hoặc đã chọn bài.");
        }
        try {
            practiceService.startShared(principal.id(), roomId, request.lessonId());
            sockets.roomChanged(roomId);
            return buildRoomState(room, principal.id());
        } catch (RuntimeException error) {
            registry.resetLesson(roomId, request.lessonId());
            throw error;
        }
    }

    @PostMapping("/{roomId}/practice")
    public ApiDtos.PracticeStartResponse startShared(@PathVariable String roomId,
                                                     @AuthenticationPrincipal DictaLabPrincipal principal) {
        CallRoom room = memberRoom(roomId, principal.id());
        if (room.getLessonId() == null || room.getStatus() != com.dictalab.room.RoomStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phòng chưa chọn bài học chung.");
        }
        return practiceService.startShared(principal.id(), roomId, room.getLessonId());
    }

    @PostMapping("/{roomId}/ready")
    public RoomDtos.RoomStateResponse ready(@PathVariable String roomId,
                                             @AuthenticationPrincipal DictaLabPrincipal principal) {
        CallRoom room = memberRoom(roomId, principal.id());
        if (room.getLessonId() == null || !practiceService.isSharedSentenceReady(
                principal.id(), roomId, room.getLessonPosition())
                || !registry.markReady(roomId, principal.id(), room.getLessonPosition())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bạn chưa hoàn thành câu hiện tại.");
        }
        sockets.roomChanged(roomId);
        return buildRoomState(room, principal.id());
    }

    @PostMapping("/{roomId}/advance")
    public RoomDtos.RoomStateResponse advance(@PathVariable String roomId,
                                               @AuthenticationPrincipal DictaLabPrincipal principal) {
        CallRoom room = memberRoom(roomId, principal.id());
        if (!registry.advanceLesson(roomId, principal.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ chủ phòng được chuyển câu khi cả hai sẵn sàng.");
        }
        sockets.roomChanged(roomId);
        return buildRoomState(room, principal.id());
    }

    /**
     * GET /api/v1/rooms/join/{inviteCode} — Xem thông tin phòng từ invite link.
     * Không join, chỉ trả thông tin để guest quyết định có bấm "Xin tham gia" không.
     */
    @GetMapping("/join/{inviteCode}")
    public RoomDtos.RoomInfoResponse getRoomByInvite(
            @PathVariable String inviteCode) {

        var opt = registry.findByInviteCode(inviteCode);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lời mời không tồn tại hoặc đã hết hạn.");
        }
        CallRoom room = opt.get();
        if (room.isClosed()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Phòng đã đóng.");
        }
        return new RoomDtos.RoomInfoResponse(
                room.getRoomId(),
                room.getStatus().name(),
                room.getHostDisplayName(),
                room.isFull(),
                room.isInviteExpired(Instant.now())
        );
    }

    /**
     * POST /api/v1/rooms/{roomId}/join — Guest gửi yêu cầu tham gia.
     */
    @PostMapping("/{roomId}/join")
    public RoomDtos.JoinResponse joinRoom(
            @PathVariable String roomId,
            @AuthenticationPrincipal DictaLabPrincipal principal) {

        RoomRegistry.JoinResult result = registry.joinRoom(roomId, principal.id(), principal.displayName());
        if(result==RoomRegistry.JoinResult.OK)sockets.roomChanged(roomId);
        return switch (result) {
            case OK -> new RoomDtos.JoinResponse(roomId, "PENDING_APPROVAL",
                    "Yêu cầu của bạn đã được gửi. Vui lòng chờ chủ phòng chấp nhận.");
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại.");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.GONE, "Lời mời đã hết hạn.");
            case FULL -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Phòng đã đủ 2 người.");
            case IS_HOST -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bạn là chủ phòng này.");
            case ALREADY_IN_ROOM -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bạn đang trong một phòng khác. Vui lòng rời phòng đó trước.");
            case CONFLICT -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Phòng đang có yêu cầu khác đang xử lý.");
        };
    }

    /**
     * POST /api/v1/rooms/{roomId}/approve — Host duyệt guest đang pending.
     */
    @PostMapping("/{roomId}/approve")
    public RoomDtos.RoomStateResponse approveGuest(
            @PathVariable String roomId,
            @AuthenticationPrincipal DictaLabPrincipal principal) {

        RoomRegistry.ApproveResult result = registry.approveGuest(roomId, principal.id());
        return switch (result) {
            case OK -> {
                CallRoom room = registry.findById(roomId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại."));
                sockets.roomChanged(roomId);
                yield buildRoomState(room, principal.id());
            }
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại.");
            case FORBIDDEN -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ chủ phòng mới được duyệt.");
            case NO_PENDING -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có yêu cầu đang chờ duyệt.");
        };
    }

    /**
     * POST /api/v1/rooms/{roomId}/reject — Host từ chối guest đang pending.
     */
    @PostMapping("/{roomId}/reject")
    public RoomDtos.ApproveRejectResponse rejectGuest(
            @PathVariable String roomId,
            @AuthenticationPrincipal DictaLabPrincipal principal) {

        RoomRegistry.RejectResult result = registry.rejectGuest(roomId, principal.id());
        if(result==RoomRegistry.RejectResult.OK)sockets.roomChanged(roomId);
        return switch (result) {
            case OK -> new RoomDtos.ApproveRejectResponse(roomId, "WAITING_GUEST", "Đã từ chối yêu cầu tham gia.");
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại.");
            case FORBIDDEN -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ chủ phòng mới được từ chối.");
            case NO_PENDING -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có yêu cầu đang chờ xử lý.");
        };
    }

    /**
     * DELETE /api/v1/rooms/{roomId} — Rời phòng.
     */
    @DeleteMapping("/{roomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveRoom(
            @PathVariable String roomId,
            @AuthenticationPrincipal DictaLabPrincipal principal) {

        RoomRegistry.LeaveResult result = registry.leaveRoom(roomId, principal.id());
        switch (result) {
            case OK, HOST_LEFT -> sockets.roomChanged(roomId);
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại.");
            case NOT_MEMBER -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên phòng này.");
        }
    }

    /**
     * GET /api/v1/rooms/{roomId} — Lấy trạng thái phòng hiện tại (polling hoặc sau reconnect).
     */
    @GetMapping("/{roomId}")
    public RoomDtos.RoomStateResponse getRoomState(
            @PathVariable String roomId,
            @AuthenticationPrincipal DictaLabPrincipal principal) {

        CallRoom room = registry.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại."));
        if (!room.isMember(principal.id()) && !room.isPending(principal.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên phòng này.");
        }
        return buildRoomState(room, principal.id());
    }

    /**
     * GET /api/v1/rooms/{roomId}/ice-config — Cấp STUN/TURN credentials ngắn hạn.
     * Chỉ thành viên đã được duyệt mới được gọi endpoint này.
     */
    @GetMapping("/{roomId}/ice-config")
    public RoomDtos.IceConfigResponse getIceConfig(
            @PathVariable String roomId,
            @AuthenticationPrincipal DictaLabPrincipal principal) {

        CallRoom room = registry.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại."));
        if (!room.isMember(principal.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ thành viên phòng mới lấy được cấu hình kết nối.");
        }

        List<RoomDtos.IceConfigResponse.IceServer> servers = new ArrayList<>();

        // STUN server (Google public — chỉ hỗ trợ P2P, không relay)
        servers.add(new RoomDtos.IceConfigResponse.IceServer(
                List.of("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"),
                null, null));

        // TURN server (nếu đã cấu hình)
        if (turnUrl != null && !turnUrl.isBlank() && turnSecret != null && !turnSecret.isBlank()) {
            try {
                long timestamp = Instant.now().getEpochSecond() + turnTtlSeconds;
                String username = timestamp + ":" + principal.id();
                String credential = computeTurnCredential(username, turnSecret);
                servers.add(new RoomDtos.IceConfigResponse.IceServer(
                        java.util.Arrays.stream(turnUrl.split(",")).map(String::trim)
                                .filter(value -> !value.isEmpty()).toList(), username, credential));
            } catch (Exception e) {
                log.warn("Không tạo được TURN credential cho user {}: {}", principal.id(), e.getMessage());
                // Không ném lỗi — fallback về STUN only
            }
        }

        return new RoomDtos.IceConfigResponse(servers);
    }

    // ------------------------------------------------------------------ helpers

    private RoomDtos.RoomStateResponse buildRoomState(CallRoom room, long userId) {
        var host = new RoomDtos.RoomStateResponse.MemberInfo(room.getHostUserId(), room.getHostDisplayName());
        RoomDtos.RoomStateResponse.MemberInfo guest = room.getGuestUserId() != null
                ? new RoomDtos.RoomStateResponse.MemberInfo(room.getGuestUserId(), room.getGuestDisplayName())
                : null;
        RoomDtos.RoomStateResponse.MemberInfo pending = room.getPendingUserId() != null
                ? new RoomDtos.RoomStateResponse.MemberInfo(room.getPendingUserId(), room.getPendingDisplayName())
                : null;
        return new RoomDtos.RoomStateResponse(room.getRoomId(), room.getStatus().name(), host, guest, pending,
                room.isHost(userId) ? room.getInviteCode() : null,
                room.getLessonId(), room.getLessonPosition(), room.getLessonSentenceCount(),
                room.isHostReady(), room.isGuestReady(), room.peerDrafts(userId), room.peerDraftSequence(userId),
                registry.playbackState(room.getRoomId()),
                room.getCallStartedAt(), Instant.now());
    }

    private CallRoom memberRoom(String roomId, long userId) {
        CallRoom room = registry.findById(roomId)
                .filter(value -> !value.isClosed())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Phòng không tồn tại."));
        if (!room.isMember(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên phòng này.");
        }
        return room;
    }

    private String buildBaseUrl(jakarta.servlet.http.HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null) host = request.getHeader("Host");
        if (host == null) host = request.getServerName() + ":" + request.getServerPort();
        return scheme + "://" + host;
    }

    /**
     * TURN REST time-limited credentials using the server's shared secret.
     * username = "<timestamp>:<userId>", credential = HMAC-SHA1(username, secret).
     */
    private String computeTurnCredential(String username, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] raw = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }
}
