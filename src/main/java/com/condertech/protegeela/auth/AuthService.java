package com.condertech.protegeela.auth;

import com.condertech.protegeela.user.entity.User;
import com.condertech.protegeela.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public User authenticate(String email, String password) {
        User user = users.findByEmail(email.strip().toLowerCase(Locale.ROOT)).orElse(null);
        boolean matches = encoder.matches(password, user == null ? dummyHash : user.getPassword());
        if (!matches || user == null || !user.isEnabled()) {
            throw new BadCredentialsException("E-mail ou senha incorretos.");
        }
        return user;
    }

    @Transactional
    public String issueToken(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at <= ?", Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO auth_sessions(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                hash(token), user.getId(), Timestamp.from(Instant.now().plus(12, ChronoUnit.HOURS)));
        return token;
    }

    public Optional<User> resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return jdbc.query("SELECT user_id FROM auth_sessions WHERE token_hash = ? AND expires_at > ?",
                (rs, row) -> rs.getObject("user_id", UUID.class), hash(token), Timestamp.from(Instant.now()))
                .stream().findFirst().flatMap(users::findById).filter(User::isEnabled);
    }

    public void revoke(String token) {
        jdbc.update("DELETE FROM auth_sessions WHERE token_hash = ?", hash(token));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
