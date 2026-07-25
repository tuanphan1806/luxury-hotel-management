package com.hotel.backend.integration;

import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.constant.OAuthProvider;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.OAuthLoginProfile;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.OAuthAccountRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.service.OAuthAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OAuthAccountIntegrationTest {

    @Autowired
    private OAuthAccountService oauthAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private CustomerProfileRepository customerProfileRepository;

    @Test
    void firstGoogleLoginCreatesCompleteCustomerAndLaterLoginReusesIt() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String subject = "google-" + suffix;
        String email = "oauth-" + suffix + "@gmail.com";
        OAuthLoginProfile googleProfile = new OAuthLoginProfile(
                OAuthProvider.GOOGLE,
                subject,
                email,
                true,
                null,
                "Khách Google mới",
                "https://images.example/google-avatar.png");

        long usersBefore = userRepository.count();
        long oauthAccountsBefore = oauthAccountRepository.count();
        long customerProfilesBefore = customerProfileRepository.count();

        var firstLogin = oauthAccountService.resolveOrCreate(googleProfile);
        userRepository.flush();
        oauthAccountRepository.flush();
        customerProfileRepository.flush();

        assertThat(firstLogin.getId()).isNotNull();
        assertThat(firstLogin.getType()).isEqualTo(UserType.CUSTOMER);
        assertThat(firstLogin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(firstLogin.isEmailVerified()).isTrue();
        assertThat(firstLogin.getPassword()).isNull();

        var mapping = oauthAccountRepository
                .findByProviderAndProviderSubject(OAuthProvider.GOOGLE, subject)
                .orElseThrow();
        assertThat(mapping.getUser().getId()).isEqualTo(firstLogin.getId());

        var customerProfile = customerProfileRepository
                .findByLinkedUserId(firstLogin.getId())
                .orElseThrow();
        assertThat(customerProfile.getFullName()).isEqualTo("Khách Google mới");
        assertThat(customerProfile.getEmail()).isEqualTo(email);
        assertThat(customerProfile.getSource()).isEqualTo(CustomerProfileSource.ONLINE);

        long usersAfterFirstLogin = userRepository.count();
        long oauthAccountsAfterFirstLogin = oauthAccountRepository.count();
        long customerProfilesAfterFirstLogin = customerProfileRepository.count();
        assertThat(usersAfterFirstLogin).isEqualTo(usersBefore + 1);
        assertThat(oauthAccountsAfterFirstLogin).isEqualTo(oauthAccountsBefore + 1);
        assertThat(customerProfilesAfterFirstLogin).isEqualTo(customerProfilesBefore + 1);

        var laterLogin = oauthAccountService.resolveOrCreate(googleProfile);
        userRepository.flush();
        oauthAccountRepository.flush();
        customerProfileRepository.flush();

        assertThat(laterLogin.getId()).isEqualTo(firstLogin.getId());
        assertThat(userRepository.count()).isEqualTo(usersAfterFirstLogin);
        assertThat(oauthAccountRepository.count()).isEqualTo(oauthAccountsAfterFirstLogin);
        assertThat(customerProfileRepository.count()).isEqualTo(customerProfilesAfterFirstLogin);
    }
}
