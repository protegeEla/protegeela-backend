package com.condertech.protegeela.alert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertService alerts;
    public AlertController(AlertService alerts) { this.alerts = alerts; }
    public record Close(@NotBlank @Pattern(regexp="safe|help_received|false_alarm|cancelled") String reason) {}
    public record UpdateLocation(@NotNull @Valid AlertService.Location location) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal UUID user, @Valid @RequestBody AlertService.Create input) {
        return Map.of("alert", alerts.create(user, input));
    }

    @GetMapping("/active")
    public Map<String, Object> active(@AuthenticationPrincipal UUID user) {
        return Collections.singletonMap("alert", alerts.active(user));
    }

    @GetMapping("/map")
    public Map<String, Object> map(@RequestParam double south, @RequestParam double west,
            @RequestParam double north, @RequestParam double east) {
        return Map.of("items", alerts.inBounds(south, west, north, east));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        return Map.of("alert", alerts.owned(user, id, false));
    }

    @GetMapping("/{id}/location")
    public Map<String, Object> location(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        return Collections.singletonMap("location", alerts.latestLocation(user, id));
    }

    @PutMapping("/{id}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLocation(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @Valid @RequestBody UpdateLocation input) {
        alerts.updateLocation(user, id, input.location());
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @Valid @RequestBody Close input) {
        return Map.of("alert", alerts.close(user, id, input.reason()));
    }

    @GetMapping("/{id}/notifications")
    public Map<String, Object> notifications(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        alerts.owned(user, id, false);
        // No provider configured: never report a fictitious successful delivery.
        return Map.of("status", "not_configured", "sent", false);
    }
}
