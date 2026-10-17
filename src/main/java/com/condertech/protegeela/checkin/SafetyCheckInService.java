package com.condertech.protegeela.checkin;

import com.condertech.protegeela.shared.SqlRows;
import com.condertech.protegeela.shared.exception.BusinessException;
import com.condertech.protegeela.shared.exception.ResourceConflictException;
import com.condertech.protegeela.shared.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafetyCheckInService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OWNED_COLUMNS = """
            id,user_id,expected_at,expires_at,grace_minutes,status,alert_id,
            created_at,completed_at,share_location,last_location_at
            """;
    private final JdbcTemplate jdbc;
    private final SqlRows rows;
    private final String frontendBaseUrl;

    public SafetyCheckInService(JdbcTemplate jdbc, SqlRows rows,
            @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.frontendBaseUrl = frontendBaseUrl.replaceFirst("/+$", "");
    }

    @Transactional
    public Map<String, Object> start(UUID user, int durationMinutes, int graceMinutes,
            boolean shareLocation, Location location) {
        lockUser(user);
        if (current(user) != null) {
            throw new ResourceConflictException("Ja existe um acompanhamento ativo.");
        }
        Instant expectedAt = Instant.now().plus(durationMinutes, ChronoUnit.MINUTES);
        Instant expiresAt = expectedAt.plus(graceMinutes, ChronoUnit.MINUTES);
        UUID id = UUID.randomUUID();
        ShareToken share = shareLocation ? newShareToken(expiresAt) : null;
        jdbc.update("""
                INSERT INTO app_safety_checkins
                    (id,user_id,expected_at,expires_at,grace_minutes,share_location,
                     share_token_hash,share_token_expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, user, Timestamp.from(expectedAt), Timestamp.from(expiresAt), graceMinutes,
                shareLocation, share == null ? null : share.hash(),
                share == null ? null : Timestamp.from(share.expiresAt()));
        if (shareLocation && location != null) {
            saveLocation(id, location);
        }
        var result = new HashMap<>(owned(user, id));
        if (share != null) result.put("share_url", shareUrl(share.token()));
        return result;
    }

    public Map<String, Object> current(UUID user) {
        return rows.list("SELECT " + OWNED_COLUMNS + """
                FROM app_safety_checkins
                WHERE user_id=? AND status='active'
                ORDER BY created_at DESC LIMIT 1
                """, user).stream().findFirst().orElse(null);
    }

    @Transactional
    public Map<String, Object> createShareLink(UUID user) {
        lockUser(user);
        var active = rows.list("""
                SELECT id,expires_at FROM app_safety_checkins
                WHERE user_id=? AND status='active' FOR UPDATE
                """, user).stream().findFirst()
                .orElseThrow(() -> new ResourceConflictException("Nenhum acompanhamento ativo."));
        UUID id = UUID.fromString(active.get("id").toString());
        ShareToken share = newShareToken(Instant.parse(active.get("expires_at").toString()));
        jdbc.update("""
                UPDATE app_safety_checkins
                SET share_location=true,share_token_hash=?,share_token_expires_at=?
                WHERE id=? AND user_id=? AND status='active'
                """, share.hash(), Timestamp.from(share.expiresAt()), id, user);
        return Map.of(
                "share_url", shareUrl(share.token()),
                "expires_at", share.expiresAt().toString());
    }

    @Transactional
    public void updateLocation(UUID user, Location location) {
        var active = rows.list("""
                SELECT id FROM app_safety_checkins
                WHERE user_id=? AND status='active' AND share_location=true
                """, user).stream().findFirst()
                .orElseThrow(() -> new ResourceConflictException(
                        "Nenhum trajeto ativo com compartilhamento de localizacao."));
        saveLocation(UUID.fromString(active.get("id").toString()), location);
    }

    public Map<String, Object> shared(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new ResourceNotFoundException("Trajeto nao encontrado.");
        }
        var trip = rows.list("""
                SELECT c.id,c.status,c.expected_at,c.expires_at,c.created_at,
                       c.completed_at,c.last_location_at,c.share_location,
                       c.share_token_expires_at,
                       split_part(trim(u.name),' ',1) AS traveler_name
                FROM app_safety_checkins c
                JOIN users u ON u.id=c.user_id
                WHERE c.share_token_hash=?
                """, hash(token)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Trajeto nao encontrado."));
        Instant tokenExpiresAt = Instant.parse(trip.get("share_token_expires_at").toString());
        if (tokenExpiresAt.isBefore(Instant.now())) {
            throw new ResourceNotFoundException("Trajeto nao encontrado.");
        }
        UUID id = UUID.fromString(trip.remove("id").toString());
        trip.remove("share_token_expires_at");
        List<Map<String, Object>> locations = rows.list("""
                SELECT latitude,longitude,accuracy,captured_at
                FROM (
                    SELECT latitude,longitude,accuracy,captured_at
                    FROM app_safety_checkin_locations
                    WHERE check_in_id=?
                    ORDER BY captured_at DESC LIMIT 1000
                ) recent
                ORDER BY captured_at
                """, id);
        return Map.of("trip", trip, "locations", locations);
    }

    @Transactional
    public Map<String, Object> complete(UUID user) {
        return finish(user, "completed");
    }

    @Transactional
    public Map<String, Object> cancel(UUID user) {
        return finish(user, "cancelled");
    }

    private Map<String, Object> finish(UUID user, String status) {
        lockUser(user);
        var active = rows.list("""
                SELECT * FROM app_safety_checkins
                WHERE user_id=? AND status='active' FOR UPDATE
                """, user).stream().findFirst()
                .orElseThrow(() -> new ResourceConflictException("Nenhum acompanhamento ativo."));
        UUID id = UUID.fromString(active.get("id").toString());
        jdbc.update("""
                UPDATE app_safety_checkins
                SET status=?,completed_at=CURRENT_TIMESTAMP,share_location=false
                WHERE id=? AND user_id=? AND status='active'
                """, status, id, user);
        jdbc.update("DELETE FROM app_safety_checkin_locations WHERE check_in_id=?", id);
        return owned(user, id);
    }

    @Scheduled(fixedDelayString = "${app.check-in.scan-delay-ms:15000}")
    @Transactional
    public void expireOverdueCheckIns() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id,user_id FROM app_safety_checkins
                WHERE status='active' AND expires_at <= CURRENT_TIMESTAMP
                ORDER BY expires_at
                LIMIT 100
                """);
        for (var item : due) {
            UUID checkInId = (UUID) item.get("id");
            UUID user = (UUID) item.get("user_id");
            lockUser(user);
            boolean stillDue = !jdbc.query("""
                    SELECT id FROM app_safety_checkins
                    WHERE id=? AND user_id=? AND status='active'
                      AND expires_at <= CURRENT_TIMESTAMP
                    FOR UPDATE
                    """, (rs, row) -> rs.getObject("id", UUID.class), checkInId, user)
                    .isEmpty();
            if (!stillDue) continue;
            UUID alertId = jdbc.query("""
                    SELECT id FROM app_alerts
                    WHERE user_id=? AND status IN ('active','acknowledged')
                    LIMIT 1
                    """, (rs, row) -> rs.getObject("id", UUID.class), user)
                    .stream().findFirst().orElse(null);
            if (alertId == null) {
                alertId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO app_alerts
                            (id,user_id,client_request_id,alert_type,is_silent,location_status,public_visibility)
                        VALUES (?,?,?,'missed_check_in',false,'location_unavailable',false)
                        """, alertId, user, checkInId);
            }
            jdbc.update("""
                    UPDATE app_safety_checkins
                    SET status='overdue',completed_at=CURRENT_TIMESTAMP,alert_id=?
                    WHERE id=? AND status='active'
                    """, alertId, checkInId);
        }
    }

    private Map<String, Object> owned(UUID user, UUID id) {
        return rows.one("SELECT " + OWNED_COLUMNS
                + " FROM app_safety_checkins WHERE id=? AND user_id=?", id, user);
    }

    private void lockUser(UUID user) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user);
    }

    private void saveLocation(UUID checkInId, Location location) {
        validateLocation(location);
        int inserted = jdbc.update("""
                INSERT INTO app_safety_checkin_locations
                    (id,check_in_id,latitude,longitude,accuracy,altitude,source,captured_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (check_in_id,captured_at) DO NOTHING
                """, UUID.randomUUID(), checkInId, location.latitude(), location.longitude(),
                location.accuracy(), location.altitude(), location.source(),
                Timestamp.from(location.capturedAt()));
        if (inserted > 0) {
            jdbc.update("""
                    UPDATE app_safety_checkins
                    SET last_location_at=?
                    WHERE id=? AND (last_location_at IS NULL OR last_location_at <= ?)
                    """, Timestamp.from(location.capturedAt()), checkInId,
                    Timestamp.from(location.capturedAt()));
        }
    }

    private void validateLocation(Location location) {
        Instant now = Instant.now();
        if (!Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())
                || !Double.isFinite(location.accuracy())
                || (location.altitude() != null && !Double.isFinite(location.altitude()))
                || location.capturedAt().isAfter(now.plusSeconds(300))
                || location.capturedAt().isBefore(now.minusSeconds(3600))) {
            throw new BusinessException("Localizacao invalida ou desatualizada.");
        }
    }

    private ShareToken newShareToken(Instant checkInExpiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new ShareToken(token, hash(token), checkInExpiresAt.plus(24, ChronoUnit.HOURS));
    }

    private String shareUrl(String token) {
        return frontendBaseUrl + "/trajeto/" + token;
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public record Location(double latitude, double longitude, double accuracy,
            Double altitude, String source, Instant capturedAt) {}

    private record ShareToken(String token, String hash, Instant expiresAt) {}
}
