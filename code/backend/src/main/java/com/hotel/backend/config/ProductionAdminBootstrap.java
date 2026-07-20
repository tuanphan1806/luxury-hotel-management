package com.hotel.backend.config;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Creates the first production administrator once, without enabling demo users.
 * The operator must disable this runner and remove the plaintext bootstrap secret
 * immediately after the account is created and its password has been rotated.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
@Slf4j(topic = "PRODUCTION-ADMIN-BOOTSTRAP")
public class ProductionAdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String fullName;
    private final String username;
    private final String email;
    private final String phone;
    private final String password;

    public ProductionAdminBootstrap(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap-admin.full-name:}") String fullName,
            @Value("${app.bootstrap-admin.username:}") String username,
            @Value("${app.bootstrap-admin.email:}") String email,
            @Value("${app.bootstrap-admin.phone:}") String phone,
            @Value("${app.bootstrap-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.fullName = requireText(fullName, "BOOTSTRAP_ADMIN_FULL_NAME");
        this.username = requireText(username, "BOOTSTRAP_ADMIN_USERNAME");
        this.email = normalizeEmail(email);
        this.phone = normalizeNullable(phone);
        this.password = validatePassword(password);
    }

    @Override
    public void run(String... args) {
        User byUsername = userRepository.findByUsername(username).orElse(null);
        User byEmail = userRepository.findByEmail(email).orElse(null);
        User existing = byUsername != null ? byUsername : byEmail;

        if (existing != null) {
            boolean exactIdentity = username.equals(existing.getUsername())
                    && email.equalsIgnoreCase(existing.getEmail());
            if (!exactIdentity || !UserType.ADMIN.equals(existing.getType())) {
                throw new IllegalStateException(
                        "Bootstrap admin username/email collides with an existing non-matching account");
            }
            log.warn("Bootstrap admin already exists; no password or role was changed. Disable bootstrap now.");
            return;
        }

        User admin = User.builder()
                .fullName(fullName)
                .username(username)
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(password))
                .type(UserType.ADMIN)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
        userRepository.save(admin);
        log.warn("Initial ADMIN account created. Rotate its password, disable bootstrap, and remove the secret.");
    }

    private static String validatePassword(String value) {
        String normalized = requireText(value, "BOOTSTRAP_ADMIN_PASSWORD");
        boolean strongEnough = normalized.length() >= 12
                && normalized.chars().anyMatch(Character::isUpperCase)
                && normalized.chars().anyMatch(Character::isLowerCase)
                && normalized.chars().anyMatch(Character::isDigit);
        if (!strongEnough || "123456".equals(normalized) || "password".equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException(
                    "BOOTSTRAP_ADMIN_PASSWORD must be 12+ characters with upper, lower, and digit");
        }
        return normalized;
    }

    private static String normalizeEmail(String value) {
        String normalized = requireText(value, "BOOTSTRAP_ADMIN_EMAIL").toLowerCase(Locale.ROOT);
        if (!normalized.contains("@") || normalized.startsWith("@") || normalized.endsWith("@")) {
            throw new IllegalArgumentException("BOOTSTRAP_ADMIN_EMAIL is invalid");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when bootstrap is enabled");
        }
        return value.trim();
    }
}
