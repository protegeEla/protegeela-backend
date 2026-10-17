package com.condertech.protegeela.checkin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/safety-checkins")
public class SafetyCheckInController {
    private final SafetyCheckInService checkIns;

    public SafetyCheckInController(SafetyCheckInService checkIns) {
        this.checkIns = checkIns;
    }

    public record Start(@Min(1) @Max(720) int duration_minutes,
            @Min(0) @Max(30) int grace_minutes, Boolean share_location,
            @Valid Location location) {}

    public record Location(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("0") @DecimalMax("100000") Double accuracy,
            Double altitude,
            @NotBlank @Size(max=40) String source,
            @NotNull Instant captured_at) {
        SafetyCheckInService.Location toService() {
            return new SafetyCheckInService.Location(
                    latitude, longitude, accuracy, altitude, source, captured_at);
        }
    }

    public record UpdateLocation(@NotNull @Valid Location location) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(@AuthenticationPrincipal UUID user,
            @Valid @RequestBody Start input) {
        return Map.of("check_in", checkIns.start(user, input.duration_minutes(), input.grace_minutes(),
                Boolean.TRUE.equals(input.share_location()),
                input.location() == null ? null : input.location().toService()));
    }

    @GetMapping("/current")
    public Map<String, Object> current(@AuthenticationPrincipal UUID user) {
        return Collections.singletonMap("check_in", checkIns.current(user));
    }

    @PostMapping("/complete")
    public Map<String, Object> complete(@AuthenticationPrincipal UUID user) {
        return Map.of("check_in", checkIns.complete(user));
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal UUID user) {
        return Map.of("check_in", checkIns.cancel(user));
    }

    @PostMapping("/current/share")
    public Map<String, Object> createShareLink(@AuthenticationPrincipal UUID user) {
        return checkIns.createShareLink(user);
    }

    @PutMapping("/current/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLocation(@AuthenticationPrincipal UUID user,
            @Valid @RequestBody UpdateLocation input) {
        checkIns.updateLocation(user, input.location().toService());
    }

    @GetMapping("/shared/{token}")
    public ResponseEntity<Map<String, Object>> shared(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(checkIns.shared(token));
    }
}
