package com.condertech.protegeela.realtime;

import com.condertech.protegeela.auth.AuthService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlertsSocket extends TextWebSocketHandler {
    private static class Client {
        final WebSocketSession session;
        final long connectedAt = System.currentTimeMillis();
        volatile long lastMessage = connectedAt;
        volatile String token;
        Client(WebSocketSession session) { this.session = new ConcurrentWebSocketSessionDecorator(session, 2000, 8192); }
    }
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final AuthService auth;
    private final ObjectMapper json;

    public AlertsSocket(AuthService auth, ObjectMapper json) { this.auth = auth; this.json = json; }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (clients.size() >= 500) { session.close(CloseStatus.SERVICE_OVERLOAD); return; }
        session.setTextMessageSizeLimit(1024);
        session.setBinaryMessageSizeLimit(0);
        clients.put(session.getId(), new Client(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client client = clients.get(session.getId());
        if (client == null) return;
        try {
            Map<?, ?> body = json.readValue(message.getPayload(), Map.class);
            if (client.token == null) {
                if (System.currentTimeMillis() - client.connectedAt > 5000
                        || !"authenticate".equals(body.get("type"))
                        || !(body.get("token") instanceof String token) || auth.resolve(token).isEmpty()) {
                    close(client, CloseStatus.POLICY_VIOLATION); return;
                }
                client.token = token;
                client.lastMessage = System.currentTimeMillis();
                send(client, "ready");
            } else if ("ping".equals(body.get("type")) && valid(client)) {
                if (System.currentTimeMillis() - client.lastMessage < 1000) {
                    close(client, CloseStatus.POLICY_VIOLATION); return;
                }
                client.lastMessage = System.currentTimeMillis();
                send(client, "pong");
            } else {
                close(client, CloseStatus.POLICY_VIOLATION);
            }
        } catch (RuntimeException ex) { close(client, CloseStatus.POLICY_VIOLATION); }
    }

    public void broadcast() {
        for (Client client : clients.values()) {
            if (client.token != null && valid(client)) send(client, "alerts_changed");
        }
    }

    private boolean valid(Client client) {
        try {
            if (client.token != null && auth.resolve(client.token).isPresent()) return true;
        } catch (RuntimeException ignored) { /* Fail closed when authentication cannot be checked. */ }
        close(client, CloseStatus.POLICY_VIOLATION);
        return false;
    }

    @Scheduled(fixedDelay = 1000)
    public void expireIdleConnections() {
        long now = System.currentTimeMillis();
        for (Client client : clients.values()) {
            if ((client.token == null && now - client.connectedAt > 5000) || now - client.lastMessage > 65000)
                close(client, CloseStatus.POLICY_VIOLATION);
        }
    }

    private void send(Client client, String type) {
        try { client.session.sendMessage(new TextMessage("{\"type\":\"" + type + "\"}")); }
        catch (IOException | RuntimeException ex) { close(client, CloseStatus.SERVER_ERROR); }
    }

    private void close(Client client, CloseStatus status) {
        clients.remove(client.session.getId());
        client.token = null;
        try { if (client.session.isOpen()) client.session.close(status); } catch (IOException ignored) {}
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client client = clients.remove(session.getId());
        if (client != null) client.token = null;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Client client = clients.get(session.getId());
        if (client != null) close(client, CloseStatus.SERVER_ERROR);
    }
}
