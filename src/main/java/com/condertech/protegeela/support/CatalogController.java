package com.condertech.protegeela.support;

import com.condertech.protegeela.shared.SqlRows;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final JdbcTemplate jdbc;
    private final SqlRows rows;
    public CatalogController(JdbcTemplate jdbc, SqlRows rows) { this.jdbc = jdbc; this.rows = rows; }

    public record SupportInput(@NotBlank @Size(max=200) String name,
            @NotBlank @Size(max=40) String category, @Size(max=5000) String description,
            @NotBlank @Size(max=300) String address, @NotBlank @Size(max=100) String city,
            @NotBlank @Pattern(regexp="[A-Z]{2}") String state,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max=30) String phone, @Size(max=300) String opening_hours,
            @Size(max=500) @Pattern(regexp="https?://[^\\s]+") String website,
            @Size(max=500) String accessibility_info, @NotNull Boolean is_verified) {}
    public record ContentInput(@NotBlank @Size(max=200) String title,
            @NotBlank @Size(max=1000) String summary, @NotBlank @Size(max=50000) String content,
            @NotBlank @Size(max=40) String category, @NotNull Boolean is_published,
            @Size(max=200) String source_name,
            @Size(max=500) @Pattern(regexp="https?://[^\\s]+") String source_url) {}
    public record ServiceInput(@NotBlank @Size(max=200) String name,
            @NotBlank @Size(max=30) String phone, @NotBlank @Size(max=1000) String description,
            @NotBlank @Size(max=100) String region, @NotNull Boolean is_active) {}

    @GetMapping("/support-points")
    public Map<String, Object> support() {
        return Map.of("items", rows.list("SELECT * FROM app_support_points ORDER BY is_verified DESC,name,id LIMIT 1000"));
    }

    @GetMapping("/safety-content")
    public Map<String, Object> content() {
        return Map.of("items", rows.list("SELECT * FROM app_safety_contents WHERE is_published ORDER BY title,id LIMIT 200"));
    }

    @GetMapping("/emergency-services/active")
    public Map<String, Object> service() {
        return Collections.singletonMap("service", rows.list("SELECT * FROM app_emergency_services WHERE is_active ORDER BY region,name,id LIMIT 1")
                .stream().findFirst().orElse(null));
    }

    @GetMapping("/admin/metrics")
    public Map<String, Object> metrics() {
        return rows.one("""
            SELECT count(*) AS total_alerts,
            count(*) FILTER (WHERE status IN ('active','acknowledged')) AS active_alerts,
            count(*) FILTER (WHERE status='closed') AS closed_alerts,
            (SELECT count(*) FROM app_support_points WHERE is_verified) AS verified_support_points
            FROM app_alerts
            """);
    }

    @PostMapping("/admin/support-points")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createSupport(@Valid @RequestBody SupportInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_support_points(id,name,category,description,address,city,state,latitude,longitude,
                phone,opening_hours,website,accessibility_info,is_verified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, input.name().strip(), input.category(), input.description(), input.address(), input.city(), input.state(),
                input.latitude(), input.longitude(), input.phone(), input.opening_hours(), input.website(), input.accessibility_info(), input.is_verified());
        return rows.one("SELECT * FROM app_support_points WHERE id=?", id);
    }

    @PutMapping("/admin/support-points/{id}")
    @Transactional
    public Map<String, Object> updateSupport(@PathVariable UUID id, @Valid @RequestBody SupportInput input) {
        rows.one("SELECT id FROM app_support_points WHERE id=? FOR UPDATE", id);
        jdbc.update("UPDATE app_support_points SET name=?,category=?,description=?,address=?,city=?,state=?,latitude=?,longitude=?,phone=?,opening_hours=?,website=?,accessibility_info=?,is_verified=? WHERE id=?",
                input.name().strip(), input.category(), input.description(), input.address(), input.city(), input.state(),
                input.latitude(), input.longitude(), input.phone(), input.opening_hours(), input.website(), input.accessibility_info(), input.is_verified(), id);
        return rows.one("SELECT * FROM app_support_points WHERE id=?", id);
    }

    @DeleteMapping("/admin/support-points/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteSupport(@PathVariable UUID id) {
        rows.one("SELECT id FROM app_support_points WHERE id=? FOR UPDATE", id);
        jdbc.update("DELETE FROM app_support_points WHERE id=?", id);
    }

    @PostMapping("/admin/safety-content")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createContent(@Valid @RequestBody ContentInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_safety_contents(id,title,summary,content,category,is_published,source_name,source_url) VALUES (?,?,?,?,?,?,?,?)",
                id, input.title(), input.summary(), input.content(), input.category(), input.is_published(),
                input.source_name(), input.source_url());
        return rows.one("SELECT * FROM app_safety_contents WHERE id=?", id);
    }

    @PutMapping("/admin/safety-content/{id}")
    @Transactional
    public Map<String, Object> updateContent(@PathVariable UUID id, @Valid @RequestBody ContentInput input) {
        rows.one("SELECT id FROM app_safety_contents WHERE id=? FOR UPDATE", id);
        jdbc.update("UPDATE app_safety_contents SET title=?,summary=?,content=?,category=?,is_published=?,source_name=?,source_url=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                input.title(), input.summary(), input.content(), input.category(), input.is_published(),
                input.source_name(), input.source_url(), id);
        return rows.one("SELECT * FROM app_safety_contents WHERE id=?", id);
    }

    @PostMapping("/admin/emergency-services")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createService(@Valid @RequestBody ServiceInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_emergency_services(id,name,phone,description,region,is_active) VALUES (?,?,?,?,?,?)",
                id, input.name(), input.phone(), input.description(), input.region(), input.is_active());
        return rows.one("SELECT * FROM app_emergency_services WHERE id=?", id);
    }

    @PutMapping("/admin/emergency-services/{id}")
    @Transactional
    public Map<String, Object> updateService(@PathVariable UUID id, @Valid @RequestBody ServiceInput input) {
        rows.one("SELECT id FROM app_emergency_services WHERE id=? FOR UPDATE", id);
        jdbc.update("UPDATE app_emergency_services SET name=?,phone=?,description=?,region=?,is_active=? WHERE id=?",
                input.name(), input.phone(), input.description(), input.region(), input.is_active(), id);
        return rows.one("SELECT * FROM app_emergency_services WHERE id=?", id);
    }
}
