package com.condertech.protegeela.auth;

import com.condertech.protegeela.shared.exception.BusinessException;
import com.condertech.protegeela.shared.exception.EmailDeliveryException;
import com.condertech.protegeela.user.entity.User;
import com.condertech.protegeela.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmailVerificationService {
    private static final long CODE_LIFETIME_MINUTES = 10;
    private static final String INVALID_CODE_MESSAGE = "Código inválido ou expirado.";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<JavaMailSender> mailSenders;
    @Value("${app.email-verification.enabled:false}")
    private boolean enabled;
    private final SecureRandom random = new SecureRandom();

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from:}")
    private String fromAddress;

    public EmailVerificationService(UserRepository users,
            PasswordEncoder passwordEncoder,
            ObjectProvider<JavaMailSender> mailSenders) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mailSenders = mailSenders;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void sendForNewAccount(User user) {
        sendCode(user);
    }

    @Transactional
    public User verify(String email, String code) {
        ensureEnabled();
        User user = users.findByEmail(normalize(email))
                .orElseThrow(() -> new BusinessException(INVALID_CODE_MESSAGE));
        String hash = user.getEmailVerificationCodeHash();
        Instant expiresAt = user.getEmailVerificationExpiresAt();
        if (user.isEmailVerified() || hash == null || expiresAt == null
                || !expiresAt.isAfter(Instant.now())
                || !passwordEncoder.matches(code, hash)) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }
        user.verifyEmail();
        return user;
    }

    @Transactional
    public void resend(String email) {
        ensureEnabled();
        users.findByEmail(normalize(email))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::sendCode);
    }

    private void sendCode(User user) {
        JavaMailSender sender = mailSenders.getIfAvailable();
        if (!StringUtils.hasText(mailHost) || !StringUtils.hasText(fromAddress) || sender == null) {
            throw new EmailDeliveryException();
        }

        String code = "%06d".formatted(random.nextInt(1_000_000));
        user.startEmailVerification(passwordEncoder.encode(code),
                Instant.now().plus(CODE_LIFETIME_MINUTES, ChronoUnit.MINUTES));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Código de confirmação do ProtegeEla");
        message.setText("Olá, " + user.getName() + ".\n\n"
                + "Seu código para confirmar a criação da conta é: " + code + "\n\n"
                + "Ele expira em " + CODE_LIFETIME_MINUTES + " minutos.\n"
                + "Se você não solicitou a criação desta conta, ignore esta mensagem.");
        try {
            sender.send(message);
        } catch (MailException exception) {
            throw new EmailDeliveryException();
        }
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new BusinessException("A confirmação por e-mail está desativada no momento.");
        }
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
