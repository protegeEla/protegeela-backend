package com.condertech.protegeela.contact;

import com.condertech.protegeela.shared.SqlRows;
import com.condertech.protegeela.shared.exception.BusinessException;
import com.condertech.protegeela.shared.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class ContactInvitationController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final SqlRows rows;
    private final String frontendBaseUrl;

    public ContactInvitationController(JdbcTemplate jdbc, SqlRows rows,
            @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.frontendBaseUrl = frontendBaseUrl.replaceFirst("/+$", "");
    }

    public record Response(@NotBlank @Pattern(regexp="accepted|declined") String response) {}

    @PostMapping("/api/contacts/{id}/invitation")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user);
        rows.one("SELECT id FROM app_contacts WHERE id=? AND owner_user_id=?", id, user);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hash(token);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO app_contact_invitations(id,contact_id,token_hash,expires_at)
                VALUES (?,?,?,?)
                ON CONFLICT (contact_id) DO UPDATE SET
                    id=EXCLUDED.id,token_hash=EXCLUDED.token_hash,
                    expires_at=EXCLUDED.expires_at,created_at=CURRENT_TIMESTAMP,
                    responded_at=NULL
                """, UUID.randomUUID(), id, tokenHash, Timestamp.from(expiresAt));
        jdbc.update("""
                UPDATE app_contacts
                SET invitation_status='pending',invitation_sent_at=CURRENT_TIMESTAMP,confirmed_at=NULL
                WHERE id=? AND owner_user_id=?
                """, id, user);
        return Map.of(
                "invite_url", frontendBaseUrl + "/convite/" + token,
                "expires_at", expiresAt.toString(),
                "delivery_status", "manual_share_required");
    }

    @GetMapping("/api/contact-invitations/{token}")
    public Map<String, Object> get(@PathVariable String token) {
        var invitation = invitation(token, false);
        invitation.remove("contact_id");
        return Map.of("invitation", invitation);
    }

    @PostMapping("/api/contact-invitations/{token}/respond")
    @Transactional
    public Map<String, Object> respond(@PathVariable String token, @Valid @RequestBody Response input) {
        var invitation = invitation(token, true);
        if (!"pending".equals(invitation.get("status"))) {
            throw new BusinessException("Este convite ja foi respondido.");
        }
        UUID contactId = UUID.fromString(invitation.get("contact_id").toString());
        jdbc.update("""
                UPDATE app_contacts SET invitation_status=?,
                    confirmed_at=CASE WHEN ?='accepted' THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE id=?
                """, input.response(), input.response(), contactId);
        jdbc.update("UPDATE app_contact_invitations SET responded_at=CURRENT_TIMESTAMP WHERE contact_id=?", contactId);
        return Map.of("status", input.response());
    }

    private Map<String, Object> invitation(String token, boolean lock) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new ResourceNotFoundException("Convite nao encontrado.");
        }
        var result = rows.list("""
                SELECT i.contact_id,c.name AS contact_name,c.invitation_status AS status,
                       c.preferred_channel,u.name AS owner_name,i.expires_at
                FROM app_contact_invitations i
                JOIN app_contacts c ON c.id=i.contact_id
                JOIN users u ON u.id=c.owner_user_id
                WHERE i.token_hash=?
                """ + (lock ? " FOR UPDATE" : ""), hash(token)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Convite nao encontrado."));
        Instant expiresAt = Instant.parse(result.get("expires_at").toString());
        if (expiresAt.isBefore(Instant.now())) {
            jdbc.update("UPDATE app_contacts SET invitation_status='expired' WHERE id=? AND invitation_status='pending'",
                    UUID.fromString(result.get("contact_id").toString()));
            throw new BusinessException("Este convite expirou.");
        }
        return result;
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
}
