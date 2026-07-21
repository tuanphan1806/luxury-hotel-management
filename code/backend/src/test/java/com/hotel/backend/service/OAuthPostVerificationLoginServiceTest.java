package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.OAuthAccountRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthPostVerificationLoginServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuthAccountRepository oauthAccountRepository;
    @Mock
    private OAuthLoginTicketService loginTicketService;

    private OAuthPostVerificationLoginService service;

    @BeforeEach
    void setUp() {
        service = new OAuthPostVerificationLoginService(
                userRepository,
                oauthAccountRepository,
                loginTicketService);
    }

    @Test
    void issuesTicketOnlyForVerifiedPasswordlessCustomerWithOAuthAccount() {
        User user = eligibleOAuthCustomer();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(oauthAccountRepository.existsByUser(user)).thenReturn(true);
        when(loginTicketService.issue(user)).thenReturn("one-time-ticket");

        assertThat(service.issueLoginTicketIfEligible(42L))
                .contains("one-time-ticket");
        verify(loginTicketService).issue(user);
    }

    @Test
    void doesNotAutoLoginNormalPasswordAccount() {
        User user = eligibleOAuthCustomer();
        user.setPassword("encoded-password");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThat(service.issueLoginTicketIfEligible(42L)).isEmpty();
        verify(oauthAccountRepository, never()).existsByUser(user);
        verify(loginTicketService, never()).issue(user);
    }

    @Test
    void doesNotAutoLoginPrivilegedOrInactiveAccount() {
        User admin = eligibleOAuthCustomer();
        admin.setType(UserType.ADMIN);
        when(userRepository.findById(42L)).thenReturn(Optional.of(admin));

        assertThat(service.issueLoginTicketIfEligible(42L)).isEmpty();

        User inactive = eligibleOAuthCustomer();
        inactive.setStatus(UserStatus.INACTIVE);
        when(userRepository.findById(43L)).thenReturn(Optional.of(inactive));

        assertThat(service.issueLoginTicketIfEligible(43L)).isEmpty();
        verify(loginTicketService, never()).issue(admin);
        verify(loginTicketService, never()).issue(inactive);
    }

    private User eligibleOAuthCustomer() {
        return User.builder()
                .fullName("OAuth Customer")
                .username("oauth_customer")
                .email("oauth@example.com")
                .password(null)
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }
}
