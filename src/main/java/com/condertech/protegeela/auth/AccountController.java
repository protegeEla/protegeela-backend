package com.condertech.protegeela.auth;

import com.condertech.protegeela.user.entity.User;
import com.condertech.protegeela.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final UserService users;
    private final AuthService auth;
    private final AuthRateLimiter limits;

    public AccountController(UserService users, AuthService auth, AuthRateLimiter limits) {
        this.users = users;
        this.auth = auth;
        this.limits = limits;
    }

    public record Register(@NotBlank @Size(max = 150) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(min = 8, max = 72) String password) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record Login(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record ProfileInput(@NotBlank @Size(max = 150) String full_name,
            @NotBlank @Size(max = 30) String phone,
            @NotNull @Pattern(regexp = "standard|discreet") String privacy_mode) {}
    public record PrivacyInput(@NotNull @Pattern(regexp = "standard|discreet") String privacy_mode) {}
    public record Profile(UUID id, String full_name, String phone, String role, String privacy_mode) {
        static Profile from(User user) {
            return new Profile(user.getId(), user.getName(), user.getPhone(),
                    user.getRole().name().toLowerCase(Locale.ROOT), user.getPrivacyMode());
        }
    }
    public record Session(String token, Profile profile) {
        @Override public String toString() { return "Session[redacted]"; }
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Session register(@Valid @RequestBody Register input, HttpServletRequest request) {
        limits.register(request.getRemoteAddr());
        User user = users.create(input.name(), input.email(), input.password());
        user.updateProfile(input.name(), input.phone(), "standard");
        return new Session(auth.issueToken(user), Profile.from(user));
    }

    @PostMapping("/auth/login")
    public Session login(@Valid @RequestBody Login input, HttpServletRequest request) {
        limits.login(request.getRemoteAddr(), input.email());
        User user = auth.authenticate(input.email(), input.password());
        return new Session(auth.issueToken(user), Profile.from(user));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        auth.revoke(authorization.substring(7));
    }

    @GetMapping("/profile")
    public Profile profile(@AuthenticationPrincipal UUID id) { return Profile.from(users.findById(id)); }

    @PutMapping("/profile")
    @Transactional
    public Profile update(@AuthenticationPrincipal UUID id, @Valid @RequestBody ProfileInput input) {
        User user = users.findById(id);
        user.updateProfile(input.full_name(), input.phone(), input.privacy_mode());
        return Profile.from(user);
    }

    @PatchMapping("/profile/privacy")
    @Transactional
    public Profile privacy(@AuthenticationPrincipal UUID id, @Valid @RequestBody PrivacyInput input) {
        User user = users.findById(id);
        user.updateProfile(user.getName(), user.getPhone(), input.privacy_mode());
        return Profile.from(user);
    }
}
