package com.hotel.backend.integration;

import com.hotel.backend.config.DataSeeder;
import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.LocalDateTime;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationIntegrationTest {

    private static final String VERIFY_EMAIL = "verify-flow-test@example.com";
    private static final String SEED_EMAIL = "verify-seeder-test@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerProfileRepository customerProfileRepository;

    @Autowired
    private DataSeeder dataSeeder;

    @AfterEach
    void cleanUp() {
        deleteTestUser(VERIFY_EMAIL);
        deleteTestUser(SEED_EMAIL);
    }

    @Test
    void validConfirmationLinkActivatesUserAndConsumesCode() throws Exception {
        String verificationCode = UUID.randomUUID().toString();
        User user = userRepository.save(User.builder()
                .fullName("Verification Test")
                .username("verify_flow_test")
                .email(VERIFY_EMAIL)
                .phone("0911111101")
                .password("encoded-password")
                .type(UserType.CUSTOMER)
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .verificationCode(sha256(verificationCode))
                .verificationExpiresAt(LocalDateTime.now().plusHours(1))
                .build());

        customerProfileRepository.save(CustomerProfile.builder()
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .source(CustomerProfileSource.ONLINE)
                .linkedUser(user)
                .build());

        mockMvc.perform(get("/auth/confirm-email").param("secretCode", "  " + verificationCode + "  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3000/login?verification=success"));

        User verifiedUser = userRepository.findByEmail(VERIFY_EMAIL).orElseThrow();
        assertThat(verifiedUser.isEmailVerified()).isTrue();
        assertThat(verifiedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(verifiedUser.getVerificationCode()).isNull();
        assertThat(verifiedUser.getVerificationExpiresAt()).isNull();
    }

    @Test
    void expiredConfirmationLinkDoesNotActivateUser() throws Exception {
        String verificationCode = UUID.randomUUID().toString();
        userRepository.save(User.builder()
                .fullName("Expired Verification Test")
                .username("verify_expired_test")
                .email(VERIFY_EMAIL)
                .phone("0911111103")
                .password("encoded-password")
                .type(UserType.CUSTOMER)
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .verificationCode(sha256(verificationCode))
                .verificationExpiresAt(LocalDateTime.now().minusMinutes(1))
                .build());

        mockMvc.perform(get("/auth/confirm-email").param("secretCode", verificationCode))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3000/login?verification=failed"));

        User unchanged = userRepository.findByEmail(VERIFY_EMAIL).orElseThrow();
        assertThat(unchanged.isEmailVerified()).isFalse();
        assertThat(unchanged.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void invalidConfirmationLinkRedirectsWithFailure() throws Exception {
        mockMvc.perform(get("/auth/confirm-email").param("secretCode", "invalid-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3000/login?verification=failed"));
    }

    @Test
    void dataSeederDoesNotDowngradeVerifiedUser() {
        User verifiedUser = userRepository.save(User.builder()
                .fullName("Seeder Verification Test")
                .username("verify_seeder_test")
                .email(SEED_EMAIL)
                .phone("0911111102")
                .password("encoded-password")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());

        ReflectionTestUtils.invokeMethod(
                dataSeeder,
                "seedUnverifiedUser",
                verifiedUser.getFullName(),
                verifiedUser.getUsername(),
                verifiedUser.getEmail(),
                "123456",
                verifiedUser.getPhone(),
                "Test address",
                "SHOULD_NOT_REPLACE",
                null);

        User reloaded = userRepository.findByEmail(SEED_EMAIL).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.getVerificationCode()).isNull();
    }

    private void deleteTestUser(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            customerProfileRepository.findByLinkedUserId(user.getId())
                    .ifPresent(customerProfileRepository::delete);
            userRepository.delete(user);
        });
    }
}
