package com.condertech.protegeela.config;

import com.condertech.protegeela.realtime.AlertsSocket;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final AlertsSocket handler;
    private final String[] origins;
    public WebSocketConfig(AlertsSocket handler, @Value("${app.cors.allowed-origins}") String origins) {
        this.handler = handler;
        this.origins = Arrays.stream(origins.split(",")).map(String::strip).toArray(String[]::new);
    }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/alerts").setAllowedOriginPatterns(origins);
    }
}
