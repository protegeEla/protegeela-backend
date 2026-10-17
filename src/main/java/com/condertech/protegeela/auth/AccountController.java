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
    private final EmailVerificationService emailVerification;

    public AccountController(UserService users, AuthService auth, AuthRateLimiter limits,
            EmailVerificationService emailVerification) {
        this.users = users;
        this.auth = auth;
        this.limits = limits;
        this.emailVerification = emailVerification;
    }

    public record Register(@NotBlank @Size(max = 150) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(min = 8, max = 72) String password) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record Login(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password, boolean remember_me) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record VerifyEmail(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) {
        @Override public String toString() { return "VerifyEmail[redacted]"; }
    }
    public record ResendEmailCode(@NotBlank @Email @Size(max = 254) String email) {
        @Override public String toString() { return "ResendEmailCode[redacted]"; }
    }
    public record Registration(String email, String status) {}
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
    public Object register(@Valid @RequestBody Register input, HttpServletRequest request) {
        limits.register(request.getRemoteAddr());
        User user = users.create(input.name(), input.email(), input.password());
        user.updateProfile(input.name(), input.phone(), "standard");
        if (emailVerification.isEnabled()) {
            emailVerification.sendForNewAccount(user);
            return new Registration(user.getEmail(), "verification_required");
        }
        user.verifyEmail();
        return new Session(auth.issueToken(user), Profile.from(user));
    }

    @PostMapping("/auth/verify-email")
    public Session verifyEmail(@Valid @RequestBody VerifyEmail input, HttpServletRequest request) {
        limits.verifyEmail(request.getRemoteAddr(), input.email());
        User user = emailVerification.verify(input.email(), input.code());
        return new Session(auth.issueToken(user), Profile.from(user));
    }

    @PostMapping("/auth/resend-verification")
    public Map<String, String> resendVerification(@Valid @RequestBody ResendEmailCode input,
            HttpServletRequest request) {
        limits.resendVerification(request.getRemoteAddr(), input.email());
        emailVerification.resend(input.email());
        return Map.of("status", "accepted",
                "message", "Se a conta estiver aguardando confirmação, um novo código será enviado.");
    }

    @PostMapping("/auth/login")
    public Session login(@Valid @RequestBody Login input, HttpServletRequest request) {
        limits.login(request.getRemoteAddr(), input.email());
        User user = auth.authenticate(input.email(), input.password());
        return new Session(auth.issueToken(user, input.remember_me()), Profile.from(user));
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
