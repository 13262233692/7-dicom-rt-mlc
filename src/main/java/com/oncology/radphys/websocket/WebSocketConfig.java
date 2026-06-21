package com.oncology.radphys.websocket;

import com.oncology.radphys.config.RtVerificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final DoseStreamWebSocketHandler doseStreamHandler;
    private final RtVerificationProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(doseStreamHandler, "/ws/dose-stream")
                .setAllowedOrigins("*")
                .withSockJS()
                .setStreamBytesLimit(properties.getWebsocket().getBinaryBufferSize())
                .setHeartbeatTime(properties.getWebsocket().getIdleTimeout() / 2);
    }
}
