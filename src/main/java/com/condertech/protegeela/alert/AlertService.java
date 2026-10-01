package com.condertech.protegeela.alert;

import com.condertech.protegeela.shared.SqlRows;
import com.condertech.protegeela.realtime.PublicAlertsChanged;
import org.springframework.context.ApplicationEventPublisher;
import com.condertech.protegeela.shared.exception.BusinessException;
import com.condertech.protegeela.shared.exception.ResourceConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    private final SqlRows rows;
    private final ApplicationEventPublisher events;
    public AlertService(JdbcTemplate jdbc, SqlRows rows, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.rows = rows; this.events = events;
    }

    public record Location(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("0") @DecimalMax("100000") Double accuracy,
            @DecimalMin("-12000") @DecimalMax("100000") Double altitude,
            @NotBlank @Size(max=40) String source, @NotNull Instant captured_at) {}
    public record Create(@NotNull UUID client_request_id,
            @NotBlank @Pattern(regexp="immediate_danger|domestic_violence|harassment|medical|other") String alert_type,
            @NotNull Boolean is_silent, @NotBlank @Size(max=40) String location_status,
            @Valid Location location, Boolean public_visibility) {}

    @Transactional
    public Map<String, Object> create(UUID user, Create input) {
        // Serializes simultaneous creations for one account, including retries.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user);
        var previous = rows.list("SELECT * FROM app_alerts WHERE user_id=? AND client_request_id=?", user, input.client_request_id());
        if (!previous.isEmpty()) return previous.get(0);
        if (active(user) != null) throw new ResourceConflictException("Ja existe um alerta ativo.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_alerts(id,user_id,client_request_id,alert_type,is_silent,location_status,public_visibility) VALUES (?,?,?,?,?,?,?)",
                id, user, input.client_request_id(), input.alert_type(), input.is_silent(),
                input.location() == null ? "location_unavailable" : "captured",
                Boolean.TRUE.equals(input.public_visibility()) && !input.is_silent());
        if (input.location() != null) saveLocation(user, id, input.location());
        if (input.location() != null && Boolean.TRUE.equals(input.public_visibility()) && !input.is_silent())
            events.publishEvent(new PublicAlertsChanged());
        return owned(user, id, false);
    }

    public Map<String, Object> active(UUID user) {
        return rows.list("SELECT * FROM app_alerts WHERE user_id=? AND status IN ('active','acknowledged')", user)
                .stream().findFirst().orElse(null);
    }

    public Map<String, Object> owned(UUID user, UUID id, boolean lock) {
        return rows.one("SELECT * FROM app_alerts WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""), id, user);
    }

    public Map<String, Object> latestLocation(UUID user, UUID id) {
        owned(user, id, false);
        return rows.list("SELECT * FROM app_alert_locations WHERE alert_id=? AND user_id=?", id, user)
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public void updateLocation(UUID user, UUID id, Location location) {
        var alert = owned(user, id, true);
        if ("closed".equals(alert.get("status"))) throw new ResourceConflictException("Alerta encerrado.");
        saveLocation(user, id, location);
        if (Boolean.TRUE.equals(alert.get("public_visibility")) && !Boolean.TRUE.equals(alert.get("is_silent")))
            events.publishEvent(new PublicAlertsChanged());
    }

    @Transactional
    public Map<String, Object> close(UUID user, UUID id, String reason) {
        var alert = owned(user, id, true);
        if (!"closed".equals(alert.get("status"))) {
            jdbc.update("UPDATE app_alerts SET status='closed',ended_at=CURRENT_TIMESTAMP,end_reason=?,public_visibility=false,public_latitude=NULL,public_longitude=NULL WHERE id=? AND user_id=?", reason, id, user);
            jdbc.update("DELETE FROM app_alert_locations WHERE alert_id=? AND user_id=?", id, user);
            if (Boolean.TRUE.equals(alert.get("public_visibility")) && !Boolean.TRUE.equals(alert.get("is_silent")))
                events.publishEvent(new PublicAlertsChanged());
        }
        return owned(user, id, false);
    }

    public List<Map<String, Object>> inBounds(double south, double west, double north, double east) {
        if (!Double.isFinite(south) || !Double.isFinite(north) || !Double.isFinite(west) || !Double.isFinite(east)
                || south < -90 || north > 90 || south > north || west < -180 || west > 180 || east < -180 || east > 180) {
            throw new BusinessException("Limites do mapa invalidos.");
        }
        String longitude = west <= east ? "public_longitude BETWEEN ? AND ?" : "(public_longitude >= ? OR public_longitude <= ?)";
        // Never return user identifiers, exact coordinates or recipient details.
        return rows.list("SELECT id,alert_type,status,public_latitude AS latitude,public_longitude AS longitude,1600 AS radius_meters,started_at FROM app_alerts WHERE status IN ('active','acknowledged') AND public_visibility AND NOT is_silent AND public_latitude BETWEEN ? AND ? AND "
                + longitude + " ORDER BY started_at DESC,id LIMIT 100", south, north, west, east);
    }

    private void saveLocation(UUID user, UUID id, Location location) {
        if (!Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())
                || !Double.isFinite(location.accuracy()) || (location.altitude() != null && !Double.isFinite(location.altitude()))
                || location.captured_at().isAfter(Instant.now().plusSeconds(300))
                || location.captured_at().isBefore(Instant.now().minusSeconds(86400))) {
            throw new BusinessException("Localizacao invalida ou desatualizada.");
        }
        int changed = jdbc.update("""
            INSERT INTO app_alert_locations(id,alert_id,user_id,latitude,longitude,accuracy,altitude,source,captured_at)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (alert_id) DO UPDATE SET
            latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,accuracy=EXCLUDED.accuracy,
            altitude=EXCLUDED.altitude,source=EXCLUDED.source,captured_at=EXCLUDED.captured_at
            WHERE EXCLUDED.captured_at >= app_alert_locations.captured_at
            """, UUID.randomUUID(), id, user, location.latitude(), location.longitude(), location.accuracy(),
                location.altitude(), location.source(), Timestamp.from(location.captured_at()));
        if (changed > 0) {
            // Fixed 0.02-degree grid; at most ~1.6 km from the disclosed center.
            double latitude = Math.round(location.latitude() * 50.0) / 50.0;
            double longitude = Math.round(location.longitude() * 50.0) / 50.0;
            jdbc.update("UPDATE app_alerts SET location_status='captured',public_latitude=?,public_longitude=? WHERE id=? AND user_id=?", latitude, longitude, id, user);
        }
    }
}
