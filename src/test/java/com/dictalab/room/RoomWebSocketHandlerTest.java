package com.dictalab.room;

import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;

class RoomWebSocketHandlerTest {
    @Test
    void onlyHostCanBroadcastOnePlaybackToBothMembers() throws Exception {
        RoomRegistry registry = new RoomRegistry();
        CallRoom room = registry.createRoom(1, "Host").orElseThrow();
        registry.joinRoom(room.getRoomId(), 2, "Guest");
        registry.approveGuest(room.getRoomId(), 1);
        registry.selectLesson(room.getRoomId(), 1, 10, 2);
        ObjectMapper mapper = JsonMapper.builder().build();
        RoomWebSocketHandler handler = new RoomWebSocketHandler(registry, mapper);
        List<String> hostMessages = new ArrayList<>(), guestMessages = new ArrayList<>();
        WebSocketSession host = socket(room.getRoomId(), 1, hostMessages);
        WebSocketSession guest = socket(room.getRoomId(), 2, guestMessages);
        handler.afterConnectionEstablished(host);
        handler.afterConnectionEstablished(guest);
        handler.handleTextMessage(host, new TextMessage("{\"type\":\"audio-ready\",\"position\":1,\"ready\":true}"));
        handler.handleTextMessage(guest, new TextMessage("{\"type\":\"audio-ready\",\"position\":1,\"ready\":true}"));
        hostMessages.clear();
        guestMessages.clear();
        String command = "{\"type\":\"lesson-play\",\"position\":1,\"sequence\":0,\"speed\":0.75}";
        handler.handleTextMessage(guest, new TextMessage(command));
        assertThat(room.playbackState().replayCount()).isZero();
        assertThat(hostMessages).isEmpty();
        guestMessages.clear();
        handler.handleTextMessage(host, new TextMessage(command));
        assertThat(hostMessages).hasSize(1).containsExactlyElementsOf(guestMessages);
        var playback = mapper.readTree(hostMessages.get(0));
        assertThat(playback.path("type").asText()).isEqualTo("lesson-audio");
        assertThat(playback.path("playback").path("replayCount").asInt()).isEqualTo(1);
        assertThat(playback.path("playback").path("startsAt").isNull()).isFalse();
        handler.handleTextMessage(host, new TextMessage(command));
        assertThat(room.playbackState().replayCount()).isEqualTo(1);
        assertThat(guestMessages).hasSize(1);
        handler.afterConnectionClosed(guest, org.springframework.web.socket.CloseStatus.NORMAL);
        assertThat(room.playbackState().guestAudioReady()).isFalse();
    }

    private WebSocketSession socket(String roomId, long id, List<String> messages) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("socket-" + id);
        given(session.getUri()).willReturn(URI.create("ws://localhost/ws/room/" + roomId));
        var principal = new DictaLabPrincipal(id, "user" + id + "@test.local", "User " + id, "unused", "USER", true);
        given(session.getPrincipal()).willReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        given(session.getAttributes()).willReturn(new HashMap<>());
        given(session.isOpen()).willReturn(true);
        willAnswer(call -> { messages.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                .given(session).sendMessage(any(TextMessage.class));
        return session;
    }
}
