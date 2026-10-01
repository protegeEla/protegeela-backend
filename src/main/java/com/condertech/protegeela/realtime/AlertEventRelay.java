package com.condertech.protegeela.realtime;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AlertEventRelay {
    private static final Logger log = LoggerFactory.getLogger(AlertEventRelay.class);
    private static final String CHANNEL = "protegeela:alerts:changed";
    private final String instance = UUID.randomUUID().toString();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AlertsSocket sockets;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listener;
    private long retryAfter;

    public AlertEventRelay(AlertsSocket sockets, StringRedisTemplate redis) {
        this.sockets = sockets;
        this.redis = redis;
        listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redis.getConnectionFactory());
        listener.setMaxSubscriptionRegistrationWaitingTime(1500);
        listener.setRecoveryInterval(10000);
        listener.setErrorHandler(ex -> log.warn("Falha no canal Redis de alertas; atualizacao HTTP permanece ativa."));
        listener.addMessageListener((message, pattern) -> {
            String sender = new String(message.getBody(), StandardCharsets.UTF_8);
            if (!instance.equals(sender) && sender.matches("[a-f0-9-]{36}")) pending.set(true);
        }, new ChannelTopic(CHANNEL));
        listener.afterPropertiesSet();
    }

    private final AtomicBoolean publish = new AtomicBoolean();

    @TransactionalEventListener
    public void afterCommit(PublicAlertsChanged event) { pending.set(true); publish.set(true); }

    @Scheduled(fixedDelay = 500)
    public void deliver() {
        if (pending.getAndSet(false)) sockets.broadcast();
        if (!publish.getAndSet(false) || System.currentTimeMillis() < retryAfter) return;
        try { redis.convertAndSend(CHANNEL, instance); }
        catch (RuntimeException ex) { unavailable(); }
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 1000)
    public void subscribe() {
        if (listener.isRunning() || System.currentTimeMillis() < retryAfter) return;
        try { listener.start(); }
        catch (RuntimeException ex) { unavailable(); }
    }

    private void unavailable() {
        retryAfter = System.currentTimeMillis() + 30000;
        log.warn("Redis indisponivel para eventos; WebSocket local e consultas HTTP continuam ativos.");
    }

    @PreDestroy
    public void stop() throws Exception { listener.destroy(); }
}
