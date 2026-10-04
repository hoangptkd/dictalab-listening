package com.dictalab.config;

import com.dictalab.room.RoomWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * Cấu hình WebSocket — đăng ký RoomWebSocketHandler tại /ws/room/{roomId}.
 * Principal từ JWT cookie của HTTP handshake được chuyển sang kết nối WebSocket.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomWebSocketHandler roomWebSocketHandler;

    @Value("${dictalab.room.allowed-origins:http://localhost:18081}")
    private String allowedOriginsConfig;

    public WebSocketConfig(RoomWebSocketHandler roomWebSocketHandler) {
        this.roomWebSocketHandler = roomWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addHandler(roomWebSocketHandler, "/ws/room/{roomId}")
                .setAllowedOriginPatterns(origins);
    }
}
