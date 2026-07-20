package com.hotel.backend.service;

import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.constant.OAuthProvider;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.OAuthLoginProfile;
import com.hotel.backend.entity.OAuthAccount;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.OAuthLoginException;
import com.hotel.backend.repository.OAuthAccountRepository;
import com.hotel.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "OAUTH-ACCOUNT-SERVICE")
public class OAuthAccountService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;

    @Transactional
    public User resolveOrCreate(OAuthLoginProfile profile) {
        validateIdentity(profile);

        var existingMapping = oauthAccountRepository.findByProviderAndProviderSubject(
                profile.provider(), profile.providerSubject());
        if (existingMapping.isPresent()) {
            return requireActive(existingMapping.get().getUser());
        }

        String normalizedEmail = normalizeEmail(profile.email());
        if (!isUsableEmail(normalizedEmail)) {
            throw new OAuthLoginException(OAuthLoginError.MISSING_EMAIL);
        }

        var existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isPresent()) {
            return linkExistingUser(profile, existingUser.get());
        }

        return createProviderUser(profile, normalizedEmail);
    }

    private void validateIdentity(OAuthLoginProfile profile) {
        if (profile == null || profile.provider() == null
                || profile.providerSubject() == null || profile.providerSubject().isBlank()) {
            throw new OAuthLoginException(OAuthLoginError.PROVIDER_ERROR);
        }
        if (profile.provider() == OAuthProvider.GOOGLE) {
            if (!isUsableEmail(normalizeEmail(profile.email()))) {
                throw new OAuthLoginException(OAuthLoginError.MISSING_EMAIL);
            }
            if (!profile.emailVerified()) {
                throw new OAuthLoginException(OAuthLoginError.UNVERIFIED_EMAIL);
            }
        }
    }

    private User linkExistingUser(OAuthLoginProfile profile, User user) {
        if (profile.provider() != OAuthProvider.GOOGLE || !isAuthoritativeGoogleEmail(profile)) {
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT);
        }
        // Không tự động gắn social identity vào tài khoản đặc quyền chỉ dựa trên
        // email. STAFF/ADMIN phải dùng đăng nhập local hoặc một flow liên kết có
        // xác thực lại riêng.
        if (user.getType() != UserType.CUSTOMER) {
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT);
        }
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_DISABLED);
        }

        oauthAccountRepository.findByUserAndProvider(user, profile.provider())
                .ifPresent(existing -> {
                    if (!existing.getProviderSubject().equals(profile.providerSubject())) {
                        throw new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT);
                    }
                });

        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        fillMissingProfileFields(user, profile);

        try {
            User linkedUser = userRepository.save(user);
            oauthAccountRepository.saveAndFlush(OAuthAccount.builder()
                    .provider(profile.provider())
                    .providerSubject(profile.providerSubject())
                    .user(linkedUser)
                    .build());
            return linkedUser;
        } catch (DataIntegrityViolationException exception) {
            log.warn("OAuth account link conflict for provider={}", profile.provider());
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT);
        }
    }

    private User createProviderUser(OAuthLoginProfile profile, String normalizedEmail) {
        User user = User.builder()
                .username(generateUniqueUsername(profile.provider()))
                .email(normalizedEmail)
                .fullName(normalizeFullName(profile.fullName(), normalizedEmail))
                .password(null)
                .phone(null)
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .imageUrl(truncate(trimToNull(profile.imageUrl()), 500))
                .build();

        try {
            User savedUser = userRepository.saveAndFlush(user);
            oauthAccountRepository.saveAndFlush(OAuthAccount.builder()
                    .provider(profile.provider())
                    .providerSubject(profile.providerSubject())
                    .user(savedUser)
                    .build());
            return savedUser;
        } catch (DataIntegrityViolationException exception) {
            log.warn("Concurrent OAuth account creation conflict for provider={}", profile.provider());
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT);
        }
    }

    private User requireActive(User user) {
        if (user != null && user.getType() != UserType.CUSTOMER) {
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT);
        }
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !user.isEnabled()) {
            throw new OAuthLoginException(OAuthLoginError.ACCOUNT_DISABLED);
        }
        return user;
    }

    private boolean isAuthoritativeGoogleEmail(OAuthLoginProfile profile) {
        String email = normalizeEmail(profile.email());
        if (!profile.emailVerified() || email == null) {
            return false;
        }
        if (email.endsWith("@gmail.com")) {
            return true;
        }
        String hostedDomain = trimToNull(profile.hostedDomain());
        if (hostedDomain == null) {
            return false;
        }
        String normalizedDomain = hostedDomain.toLowerCase(Locale.ROOT);
        return email.endsWith("@" + normalizedDomain);
    }

    private void fillMissingProfileFields(User user, OAuthLoginProfile profile) {
        if (user.getFullName() == null || user.getFullName().isBlank()) {
            user.setFullName(normalizeFullName(profile.fullName(), user.getEmail()));
        }
        if ((user.getImageUrl() == null || user.getImageUrl().isBlank())
                && profile.imageUrl() != null && !profile.imageUrl().isBlank()) {
            user.setImageUrl(truncate(profile.imageUrl().trim(), 500));
        }
    }

    private String generateUniqueUsername(OAuthProvider provider) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "oauth_" + provider.name().toLowerCase(Locale.ROOT)
                    + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (!userRepository.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new OAuthLoginException(OAuthLoginError.PROVIDER_ERROR);
    }

    private String normalizeFullName(String fullName, String email) {
        String normalized = trimToNull(fullName);
        if (normalized == null) {
            int at = email.indexOf('@');
            normalized = at > 0 ? email.substring(0, at) : "Khách hàng";
        }
        return truncate(normalized, 255);
    }

    private String normalizeEmail(String email) {
        String normalized = trimToNull(email);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean isUsableEmail(String email) {
        return email != null && email.length() <= 255 && EMAIL_PATTERN.matcher(email).matches();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}
