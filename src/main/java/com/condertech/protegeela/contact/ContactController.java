package com.condertech.protegeela.contact;

import com.condertech.protegeela.shared.SqlRows;
import com.condertech.protegeela.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {
    private final JdbcTemplate jdbc;
    private final SqlRows rows;
    public ContactController(JdbcTemplate jdbc, SqlRows rows) { this.jdbc = jdbc; this.rows = rows; }

    public record ContactInput(@NotBlank @Size(max=150) String name,
            @NotBlank @Size(max=30) String phone, @Email @Size(max=254) String email,
            @NotBlank @Size(max=40) String relationship, Boolean can_view_exact_location) {}
    public record Permission(@NotNull Boolean can_view_exact_location) {}

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal UUID user) {
        return Map.of("items", rows.list("SELECT * FROM app_contacts WHERE owner_user_id=? ORDER BY is_primary DESC, created_at", user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal UUID user, @Valid @RequestBody ContactInput input) {
        lock(user);
        int count = jdbc.queryForObject("SELECT count(*) FROM app_contacts WHERE owner_user_id=?", Integer.class, user);
        if (count >= 20) throw new BusinessException("Limite de 20 contatos atingido.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_contacts(id,owner_user_id,name,phone,email,relationship,can_view_exact_location,is_primary) VALUES (?,?,?,?,?,?,?,?)",
                id, user, input.name().strip(), input.phone().strip(), email(input.email()), input.relationship().strip(),
                Boolean.TRUE.equals(input.can_view_exact_location()), count == 0);
        return owned(user, id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @Valid @RequestBody ContactInput input) {
        lock(user);
        owned(user, id);
        jdbc.update("UPDATE app_contacts SET name=?,phone=?,email=?,relationship=? WHERE id=? AND owner_user_id=?",
                input.name().strip(), input.phone().strip(), email(input.email()), input.relationship().strip(), id, user);
        return owned(user, id);
    }

    @PatchMapping("/{id}/location-permission")
    @Transactional
    public Map<String, Object> permission(@AuthenticationPrincipal UUID user, @PathVariable UUID id, @Valid @RequestBody Permission input) {
        lock(user);
        owned(user, id);
        jdbc.update("UPDATE app_contacts SET can_view_exact_location=? WHERE id=? AND owner_user_id=?", input.can_view_exact_location(), id, user);
        return owned(user, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal UUID user, @PathVariable UUID id) {
        lock(user);
        boolean primary = Boolean.TRUE.equals(owned(user, id).get("is_primary"));
        jdbc.update("DELETE FROM app_contacts WHERE id=? AND owner_user_id=?", id, user);
        if (primary) jdbc.update("UPDATE app_contacts SET is_primary=true WHERE id=(SELECT id FROM app_contacts WHERE owner_user_id=? ORDER BY created_at,id LIMIT 1)", user);
    }

    private Map<String, Object> owned(UUID user, UUID id) {
        return rows.one("SELECT * FROM app_contacts WHERE id=? AND owner_user_id=?", id, user);
    }
    private void lock(UUID user) { jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user); }
    private String email(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
