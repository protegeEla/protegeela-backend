package com.condertech.protegeela.user.service;

import com.condertech.protegeela.shared.exception.BusinessException;
import com.condertech.protegeela.shared.exception.ResourceNotFoundException;
import com.condertech.protegeela.shared.exception.ResourceConflictException;
import com.condertech.protegeela.user.entity.User;
import com.condertech.protegeela.user.repository.UserRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User create(@NotBlank @Size(max = 150) String name,
                       @NotBlank @Email @Size(max = 254) String email,
                       @NotBlank @Size(min = 8, max = 72) String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("A senha deve ter no máximo 72 bytes em UTF-8.");
        }
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        if (repository.existsByEmail(normalizedEmail)) {
            throw new ResourceConflictException("E-mail já cadastrado.");
        }
        return repository.saveAndFlush(new User(name.strip(), normalizedEmail,
                passwordEncoder.encode(password)));
    }

    public User findById(@NotNull UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }
}
