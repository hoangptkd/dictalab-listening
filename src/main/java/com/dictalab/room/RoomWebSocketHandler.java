package com.dictalab.room;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.dictalab.security.DictaLabPrincipal;

/**
 * WebSocket handler cho phòng gọi thoại.
 *
 * Luồng chính:
 *  1. Handshake: xác thực JWT cookie, kiểm tra roomId và quyền thành viên.
 *  2. Khi cả hai thành viên kết nối WS: gửi signal "peer-joined" cho mỗi bên.
 *  3. Relay message (offer/answer/ice-candidate/ready/mic-state) chỉ giữa hai thành viên cùng phòng.
 *  4. Disconnect: ghi nhận, giữ chỗ 60s; peer nhận "peer-disconnected".
 *
 * Quy ước caller: HOST là offerer, GUEST là answerer (tránh xung đột double-offer).
 *
 * Bảo mật:
 *  - Chỉ nhận message từ thành viên đã được duyệt (ACTIVE).
 *  - Giới hạn kích thước message 32 KB.
 *  - Giới hạn tần suất 20 message/giây/kết nối (đơn giản bằng token bucket).
 *  - Không log nội dung SDP/ICE.
 *  - Message chỉ được relay đến peer trong cùng phòng.
 */
@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);
    private static final int MAX_MESSAGE_SIZE_BYTES = 32 * 1024; // 32 KB
    private static final int MAX_MSG_PER_SECOND = 20;

    /** Các message type được phép relay giữa hai peer */
    private static final Set<String> RELAY_TYPES = Set.of(
            "offer", "answer", "ice-candidate", "ready", "mic-state", "renegotiate", "draft", "call-ready",
            "audio-ready", "lesson-play", "lesson-played"
    );

    private final RoomRegistry registry;
    private final ObjectMapper objectMapper;
    private final com.dictalab.repository.AppUserRepository users;
    private final com.dictalab.security.JwtTokenService tokens;

    /** roomId → (userId → WebSocketSession) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, WebSocketSession>> roomSessions
            = new ConcurrentHashMap<>();

    /** sessionId → rate limiter (simple token bucket) */
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RoomWebSocketHandler(RoomRegistry registry, ObjectMapper objectMapper,
                                com.dictalab.repository.AppUserRepository users,
                                com.dictalab.security.JwtTokenService tokens) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.users = users;
        this.tokens = tokens;
    }

    public RoomWebSocketHandler(RoomRegistry registry, ObjectMapper objectMapper,
                                com.dictalab.repository.AppUserRepository users) {
        this(registry, objectMapper, users, null);
    }

    public RoomWebSocketHandler(RoomRegistry registry, ObjectMapper objectMapper) {
        this(registry,objectMapper,null);
    }

    private boolean accountValid(WebSocketSession session) {
        var principal=extractPrincipal(session);
        return principal!=null && (tokens==null || tokens.active(principal)) && (users==null || users.findById(principal.id())
                .filter(current -> current.isEnabled() && current.getAuthVersion()==principal.authVersion()
                        && current.getEmail().equals(principal.email()) && current.getRole().equals(principal.role())).isPresent());
    }

    /** Idle sockets must also stop accepting signals after expiry, logout or account revocation. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 20000)
    public void checkAuthentication() {
        for (var peers : roomSessions.values()) for (var session : peers.values()) {
            if (!session.isOpen()) continue;
            try {
                if (!accountValid(session)) closeWithError(session, "Phiên đăng nhập không còn hợp lệ.");
            } catch (RuntimeException unavailable) {
                closeWithError(session, "Không thể kiểm tra phiên đăng nhập. Vui lòng kết nối lại.");
            }
        }
    }

    public void roomChanged(String roomId) {
        var sessions=roomSessions.get(roomId);
        if(sessions==null)return;
        for(var session:sessions.values()) if(session.isOpen()) {
            sendJson(session,buildSignal("room-changed",Map.of()));
        }
    }

    // ----------------------------------------------------------------------- lifecycle

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        DictaLabPrincipal principal = extractPrincipal(session);
        if (principal == null || !accountValid(session)) {
            closeWithError(session, "Phiên đăng nhập không hợp lệ.");
            return;
        }

        String roomId = extractRoomId(session);
        if (roomId == null) {
            closeWithError(session, "Không xác định được phòng.");
            return;
        }

        // Kiểm tra membership
        CallRoom room = registry.findById(roomId).orElse(null);
        if (room == null || room.isClosed()) {
            closeWithError(session, "Phòng không tồn tại hoặc đã đóng.");
            return;
        }
        if (!room.isMember(principal.id())) {
            closeWithError(session, "Bạn không phải thành viên phòng này.");
            return;
        }

        // Lưu session
        session.getAttributes().put("userId", principal.id());
        session.getAttributes().put("roomId", roomId);
        session.getAttributes().put("displayName", principal.displayName());
        session.setTextMessageSizeLimit(MAX_MESSAGE_SIZE_BYTES);

        WebSocketSession previous = roomSessions
                .computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
                .put(principal.id(), session);
        rateLimiters.put(session.getId(), new RateLimiter(MAX_MSG_PER_SECOND));
        if (previous != null && previous.isOpen()) {
            try {
                previous.close(new CloseStatus(4001, "Phòng đã được mở ở tab khác."));
            } catch (IOException e) {
                log.debug("Không đóng được WebSocket cũ của user {}: {}", principal.id(), e.getMessage());
            }
        }

        // Đánh dấu reconnect nếu cần
        registry.markReconnected(roomId, principal.id());

        log.debug("WS connected: user {} in room {}", principal.id(), roomId);

        // Thông báo cho bên kia nếu đang online
        WebSocketSession peerSession = findPeer(roomId, principal.id());
        if (peerSession != null && peerSession.isOpen()) {
            // Thông báo peer đã online lại (hoặc lần đầu vào)
            boolean isReconnect = false; // simplified — có thể track disconnect time nếu cần
            sendJson(peerSession, buildSignal("peer-joined", Map.of(
                    "userId", principal.id(),
                    "displayName", principal.displayName()
            )));
            // Gửi cho user mới biết peer đang online
            String peerDisplayName = (String) peerSession.getAttributes().get("displayName");
            Long peerId = (Long) peerSession.getAttributes().get("userId");
            sendJson(session, buildSignal("peer-joined", Map.of(
                    "userId", peerId,
                    "displayName", peerDisplayName
            )));
        } else {
            // Gửi self info để frontend biết đang chờ peer
            sendJson(session, buildSignal("waiting-for-peer", Map.of(
                    "isHost", room.isHost(principal.id())
            )));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomId = (String) session.getAttributes().get("roomId");
        if (userId == null || roomId == null) return;
        if (roomSessions.getOrDefault(roomId, new ConcurrentHashMap<>()).get(userId) != session) return;

        // Rate limiting
        RateLimiter limiter = rateLimiters.get(session.getId());
        if (limiter != null && !limiter.tryConsume()) {
            sendJson(session, buildSignal("error", Map.of("message", "Gửi quá nhanh, vui lòng chờ.")));
            return;
        }

        // Kiểm tra kích thước
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE_BYTES) {
            sendJson(session, buildSignal("error", Map.of("message", "Tin nhắn quá lớn.")));
            return;
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendJson(session, buildSignal("error", Map.of("message", "Định dạng không hợp lệ.")));
            return;
        }

        String type = json.path("type").asText("");

        CallRoom room = registry.findById(roomId).orElse(null);
        if (room == null || room.isClosed() || !room.isMember(userId)) {
            closeWithError(session, "Phòng đã đóng hoặc bạn không còn là thành viên.");
            return;
        }
        // A live WebSocket outlasts HTTP authentication; revalidate on heartbeat, at most every 20s.
        long now=System.nanoTime();
        Long checked=(Long)session.getAttributes().get("accountCheckedAt");
        if (checked==null || now-checked>java.time.Duration.ofSeconds(20).toNanos()) {
            try {
                if(!accountValid(session)) { closeWithError(session,"Phiên đăng nhập không còn hợp lệ."); return; }
            } catch (RuntimeException unavailable) {
                closeWithError(session, "Không thể kiểm tra phiên đăng nhập. Vui lòng kết nối lại."); return;
            }
            session.getAttributes().put("accountCheckedAt",now);
        }

        // Ping/pong — không relay
        if ("ping".equals(type)) {
            sendJson(session, buildSignal("pong", Map.of()));
            return;
        }

        // Chỉ relay các type hợp lệ
        if (!RELAY_TYPES.contains(type)) {
            sendJson(session, buildSignal("error", Map.of("message", "Loại tin nhắn không được hỗ trợ: " + type)));
            return;
        }

        // Kiểm tra membership vẫn hợp lệ
        if (room.getStatus() != RoomStatus.ACTIVE) {
            sendJson(session, buildSignal("error", Map.of("message", "Phòng chưa kích hoạt.")));
            return;
        }

        if (Set.of("audio-ready", "lesson-play", "lesson-played").contains(type)) {
            WebSocketSession peer = findPeer(roomId, userId);
            if ("lesson-play".equals(type) && (peer == null || !peer.isOpen())) {
                sendJson(session, buildSignal("error", Map.of("message", "Bạn học chưa kết nối. Hãy chờ kết nối lại.")));
                return;
            }
            var playback = registry.updatePlayback(roomId, userId, type, json.path("position").asInt(-1),
                    json.path("sequence").asLong(-1), json.path("speed").asDouble(0),
                    json.path("ready").asBoolean(false));
            if (playback == null) {
                sendJson(session, buildSignal("error", Map.of("message", "Chưa thể phát chung. Cả hai cần bật nghe và chờ lượt trước kết thúc.")));
                return;
            }
            String signal = buildSignal("lesson-audio", Map.of("playback", playback, "serverNow", Instant.now().toString()));
            sendJson(session, signal);
            if (peer != null && peer.isOpen()) sendJson(peer, signal);
            return;
        }

        if ("draft".equals(type)) {
            JsonNode words = json.path("words");
            if (!words.isArray() || words.size() > 100) return;
            List<String> values = new ArrayList<>(words.size());
            for (JsonNode word : words) {
                if (!word.isTextual() || word.asText().length() > 80) return;
                values.add(word.asText());
            }
            int position = json.path("position").asInt(-1);
            long sequence = json.path("sequence").asLong(-1);
            if (!registry.updateDraft(roomId, userId, position, sequence, values)) return;
            WebSocketSession peer = findPeer(roomId, userId);
            if (peer != null && peer.isOpen()) {
                ObjectNode update = objectMapper.createObjectNode();
                update.put("type", "draft");
                update.put("position", position);
                update.put("sequence", sequence);
                update.put("fromUserId", userId);
                update.set("words", objectMapper.valueToTree(values));
                sendJson(peer, update.toString());
            }
            return;
        }

        if ("call-ready".equals(type)) {
            Instant startedAt = registry.markCallReady(roomId, userId);
            if (startedAt != null) {
                String signal = buildSignal("call-started", Map.of(
                        "startedAt", startedAt.toString(),
                        "serverNow", Instant.now().toString()));
                sendJson(session, signal);
                WebSocketSession peer = findPeer(roomId, userId);
                if (peer != null && peer.isOpen()) sendJson(peer, signal);
            }
            return;
        }

        // Không log nội dung SDP/ICE — chỉ log type
        log.debug("WS relay: type={} from user {} in room {}", type, userId, roomId);

        // Relay đến peer
        WebSocketSession peerSession = findPeer(roomId, userId);
        if (peerSession != null && peerSession.isOpen()) {
            // Thêm senderId vào message để peer biết nguồn
            ObjectNode enriched = (ObjectNode) objectMapper.readTree(message.getPayload());
            enriched.put("fromUserId", userId);
            sendJson(peerSession, enriched.toString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomId = (String) session.getAttributes().get("roomId");
        if (userId == null || roomId == null) return;

        // Xóa khỏi map session
        ConcurrentHashMap<Long, WebSocketSession> roomMap = roomSessions.get(roomId);
        if (roomMap != null) {
            if (!roomMap.remove(userId, session)) {
                rateLimiters.remove(session.getId());
                return;
            }
            if (roomMap.isEmpty()) roomSessions.remove(roomId);
        }
        rateLimiters.remove(session.getId());

        // Ghi nhận disconnect trong registry (để cleanup có thể đóng phòng nếu cần)
        registry.markDisconnected(roomId, userId);

        log.debug("WS disconnected: user {} from room {}, status={}", userId, roomId, status);

        // Thông báo peer
        WebSocketSession peerSession = findPeer(roomId, userId);
        if (peerSession != null && peerSession.isOpen()) {
            sendJson(peerSession, buildSignal("peer-disconnected", Map.of(
                    "userId", userId,
                    "reconnectWindowSeconds", CallRoom.RECONNECT_WINDOW_SECONDS
            )));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("WS transport error for session {}: {}", session.getId(), exception.getMessage());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    // ----------------------------------------------------------------------- helpers

    private DictaLabPrincipal extractPrincipal(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal instanceof Authentication auth) {
            Object details = auth.getPrincipal();
            if (details instanceof DictaLabPrincipal p) return p;
        }
        return null;
    }

    private String extractRoomId(WebSocketSession session) {
        // URI path: /ws/room/{roomId}
        String path = session.getUri() != null ? session.getUri().getPath() : null;
        if (path == null) return null;
        String[] parts = path.split("/");
        // parts: ["", "ws", "room", "{roomId}"]
        return parts.length >= 4 ? parts[3] : null;
    }

    private WebSocketSession findPeer(String roomId, long myUserId) {
        ConcurrentHashMap<Long, WebSocketSession> roomMap = roomSessions.get(roomId);
        if (roomMap == null) return null;
        return roomMap.entrySet().stream()
                .filter(e -> e.getKey() != myUserId)
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private void sendJson(WebSocketSession session, String json) {
        if (!session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.debug("Không gửi được message cho session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private String buildSignal(String type, Map<String, Object> data) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", type);
            data.forEach((k, v) -> {
                if (v instanceof String s) node.put(k, s);
                else if (v instanceof Long l) node.put(k, l);
                else if (v instanceof Boolean b) node.put(k, b);
                else if (v instanceof Integer i) node.put(k, i);
                else node.putPOJO(k, v);
            });
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    private void closeWithError(WebSocketSession session, String reason) {
        try {
            sendJson(session, buildSignal("error", Map.of("message", reason)));
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            log.debug("Không đóng được session: {}", e.getMessage());
        }
    }

    // ----------------------------------------------------------------------- rate limiter

    /**
     * Token bucket đơn giản: cho phép maxPerSecond token/giây.
     * Không cần chính xác tuyệt đối — chỉ cần ngăn flood.
     */
    private static final class RateLimiter {
        private final int maxPerSecond;
        private long windowStart = System.currentTimeMillis();
        private int count = 0;

        RateLimiter(int maxPerSecond) {
            this.maxPerSecond = maxPerSecond;
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 1000) {
                windowStart = now;
                count = 0;
            }
            if (count >= maxPerSecond) return false;
            count++;
            return true;
        }
    }
}
