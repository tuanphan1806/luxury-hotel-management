package com.hotel.backend.service;

import com.hotel.backend.constant.TokenType;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.SignInRequest;
import com.hotel.backend.entity.InvalidatedToken;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.UserToken;
import com.hotel.backend.exception.InvalidDataException;
import com.hotel.backend.repository.InvalidatedTokenRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import com.hotel.backend.service.Impl.AuthenticationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtService jwtService;
    @Mock InvalidatedTokenRepository invalidatedTokenRepository;
    @Mock UserTokenRepository userTokenRepository;

    private AuthenticationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthenticationServiceImpl(
                userRepository,
                authenticationManager,
                jwtService,
                invalidatedTokenRepository,
                userTokenRepository);
    }

    @Test
    void customerTokenIssueDoesNotCreateSingleSessionRecord() {
        User customer = activeUser(1L, UserType.CUSTOMER);
        stubGeneratedTokens(customer, "new-access", "new-refresh");

        var response = service.issueTokens(customer);

        assertEquals("Bearer new-access", response.getAccessToken());
        assertEquals("Bearer new-refresh", response.getRefreshToken());
        verifyNoInteractions(userTokenRepository);
        verifyNoInteractions(invalidatedTokenRepository);
    }

    @Test
    void secondStaffLoginInvalidatesPreviousSessionAndStoresReplacementJtis() {
        User staff = activeUser(2L, UserType.STAFF);
        Date oldAccessExpiry = new Date(System.currentTimeMillis() + 60_000);
        Date oldRefreshExpiry = new Date(System.currentTimeMillis() + 120_000);
        UserToken previous = UserToken.builder()
                .userId(staff.getId())
                .accessToken("old-access-jti")
                .accessTokenExpiresAt(oldAccessExpiry)
                .refreshToken("old-refresh-jti")
                .refreshTokenExpiresAt(oldRefreshExpiry)
                .build();
        when(userTokenRepository.findById(staff.getId())).thenReturn(Optional.of(previous));
        stubGeneratedTokens(staff, "new-access", "new-refresh");
        Date newAccessExpiry = new Date(System.currentTimeMillis() + 180_000);
        Date newRefreshExpiry = new Date(System.currentTimeMillis() + 240_000);
        when(jwtService.extractJti("new-access", TokenType.ACCESS_TOKEN)).thenReturn("new-access-jti");
        when(jwtService.extractJti("new-refresh", TokenType.REFRESH_TOKEN)).thenReturn("new-refresh-jti");
        when(jwtService.extractExpiration("new-access", TokenType.ACCESS_TOKEN)).thenReturn(newAccessExpiry);
        when(jwtService.extractExpiration("new-refresh", TokenType.REFRESH_TOKEN)).thenReturn(newRefreshExpiry);

        service.issueTokens(staff);

        ArgumentCaptor<InvalidatedToken> invalidated = ArgumentCaptor.forClass(InvalidatedToken.class);
        verify(invalidatedTokenRepository, org.mockito.Mockito.times(2)).save(invalidated.capture());
        assertEquals(
                List.of("old-access-jti", "old-refresh-jti"),
                invalidated.getAllValues().stream().map(InvalidatedToken::getToken).toList());
        assertTrue(invalidated.getAllValues().stream()
                .allMatch(token -> "SESSION_REPLACED".equals(token.getReason())));
        verify(userTokenRepository).delete(previous);

        ArgumentCaptor<UserToken> replacement = ArgumentCaptor.forClass(UserToken.class);
        verify(userTokenRepository).save(replacement.capture());
        assertEquals("new-access-jti", replacement.getValue().getAccessToken());
        assertEquals("new-refresh-jti", replacement.getValue().getRefreshToken());
    }

    @Test
    void loginAcceptsEmailAndReturnsTokensForAuthenticatedPrincipal() {
        SignInRequest request = org.mockito.Mockito.mock(SignInRequest.class);
        when(request.getUsername()).thenReturn(" Customer@Example.com ");
        when(request.getPassword()).thenReturn("123456");
        var authentication = new UsernamePasswordAuthenticationToken(
                "Customer@Example.com",
                "123456",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        User customer = activeUser(3L, UserType.CUSTOMER);
        when(userRepository.findByUsernameIgnoreCase("Customer@Example.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("Customer@Example.com"))
                .thenReturn(Optional.of(customer));
        stubGeneratedTokens(customer, "access", "refresh");

        var response = service.getAccessToken(request);

        assertEquals("Bearer access", response.getAccessToken());
        verify(userRepository).findByEmailIgnoreCase("Customer@Example.com");
    }

    @Test
    void loginMasksAuthenticationFailureAndNeverQueriesAccount() {
        SignInRequest request = org.mockito.Mockito.mock(SignInRequest.class);
        when(request.getUsername()).thenReturn("unknown");
        when(request.getPassword()).thenReturn("wrong");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("provider detail"));

        BadCredentialsException error = assertThrows(
                BadCredentialsException.class,
                () -> service.getAccessToken(request));

        assertEquals("Invalid credentials", error.getMessage());
        verifyNoInteractions(userRepository);
    }

    @Test
    void refreshRejectsMalformedBearerContractBeforeReadingToken() {
        assertThrows(InvalidDataException.class, () -> service.getRefreshToken("refresh-only"));
        verifyNoInteractions(jwtService, userRepository, userTokenRepository);
    }

    @Test
    void refreshRejectsAnAlreadyInvalidatedRefreshJti() {
        when(jwtService.extractUsername("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn("customer");
        when(jwtService.extractJti("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn("old-refresh-jti");
        when(invalidatedTokenRepository.existsByToken("old-refresh-jti")).thenReturn(true);

        assertThrows(
                InvalidDataException.class,
                () -> service.getRefreshToken("Bearer old-refresh"));

        verifyNoInteractions(userRepository, userTokenRepository);
    }

    @Test
    void customerRefreshRotatesRefreshTokenWithoutCreatingServerSession() {
        User customer = activeUser(4L, UserType.CUSTOMER);
        when(jwtService.extractUsername("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(customer.getUsername());
        when(jwtService.extractJti("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn("old-refresh-jti");
        when(jwtService.extractSecurityVersion("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(customer.getSecurityVersion());
        when(jwtService.extractExpiration("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(new Date(System.currentTimeMillis() + 120_000));
        when(invalidatedTokenRepository.existsByToken("old-refresh-jti")).thenReturn(false);
        when(userRepository.findByUsernameIgnoreCase(customer.getUsername()))
                .thenReturn(Optional.of(customer));
        when(invalidatedTokenRepository.insertInvalidatedToken(
                eq("old-refresh-jti"), any(Date.class), eq("REFRESH_ROTATED")))
                .thenReturn(1);
        stubGeneratedTokens(customer, "rotated-access", "rotated-refresh");

        var response = service.getRefreshToken("Bearer old-refresh");

        assertEquals("Bearer rotated-access", response.getAccessToken());
        assertEquals("Bearer rotated-refresh", response.getRefreshToken());
        verify(userTokenRepository, never()).save(any());
        verify(invalidatedTokenRepository).insertInvalidatedToken(
                eq("old-refresh-jti"), any(Date.class), eq("REFRESH_ROTATED"));
    }

    @Test
    void staffRefreshRequiresCurrentSessionAndRotatesBothServerJtis() {
        User staff = activeUser(5L, UserType.ADMIN);
        when(jwtService.extractUsername("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(staff.getUsername());
        when(jwtService.extractJti("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn("old-refresh-jti");
        when(jwtService.extractSecurityVersion("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(staff.getSecurityVersion());
        when(jwtService.extractExpiration("old-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(new Date(System.currentTimeMillis() + 120_000));
        when(invalidatedTokenRepository.existsByToken("old-refresh-jti")).thenReturn(false);
        when(userRepository.findByUsernameIgnoreCase(staff.getUsername()))
                .thenReturn(Optional.of(staff));
        UserToken current = UserToken.builder()
                .userId(staff.getId())
                .accessToken("old-access-jti")
                .refreshToken("old-refresh-jti")
                .build();
        when(userTokenRepository.findById(staff.getId())).thenReturn(Optional.of(current));
        when(invalidatedTokenRepository.existsByToken("old-access-jti")).thenReturn(false);
        when(invalidatedTokenRepository.insertInvalidatedToken(
                eq("old-refresh-jti"), any(Date.class), eq("REFRESH_ROTATED")))
                .thenReturn(1);
        stubGeneratedTokens(staff, "new-access", "new-refresh");
        when(jwtService.extractJti("new-access", TokenType.ACCESS_TOKEN))
                .thenReturn("new-access-jti");
        when(jwtService.extractJti("new-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn("new-refresh-jti");
        when(jwtService.extractExpiration("new-access", TokenType.ACCESS_TOKEN))
                .thenReturn(new Date(System.currentTimeMillis() + 180_000));
        when(jwtService.extractExpiration("new-refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(new Date(System.currentTimeMillis() + 240_000));

        service.getRefreshToken("Bearer old-refresh");

        ArgumentCaptor<InvalidatedToken> rotatedAccess =
                ArgumentCaptor.forClass(InvalidatedToken.class);
        verify(invalidatedTokenRepository).save(rotatedAccess.capture());
        assertEquals("old-access-jti", rotatedAccess.getValue().getToken());
        assertEquals("REFRESH_ROTATED", rotatedAccess.getValue().getReason());
        verify(invalidatedTokenRepository).insertInvalidatedToken(
                eq("old-refresh-jti"), any(Date.class), eq("REFRESH_ROTATED"));
        ArgumentCaptor<UserToken> rotated = ArgumentCaptor.forClass(UserToken.class);
        verify(userTokenRepository).save(rotated.capture());
        assertEquals("new-access-jti", rotated.getValue().getAccessToken());
        assertEquals("new-refresh-jti", rotated.getValue().getRefreshToken());
    }

    @Test
    void logoutInvalidatesBothJtisAndRemovesSingleSessionRecord() {
        User staff = activeUser(6L, UserType.STAFF);
        when(jwtService.extractJti("access", TokenType.ACCESS_TOKEN)).thenReturn("access-jti");
        when(jwtService.extractJti("refresh", TokenType.REFRESH_TOKEN)).thenReturn("refresh-jti");
        when(jwtService.extractExpiration("access", TokenType.ACCESS_TOKEN))
                .thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(jwtService.extractExpiration("refresh", TokenType.REFRESH_TOKEN))
                .thenReturn(new Date(System.currentTimeMillis() + 120_000));
        when(jwtService.extractUsername("access", TokenType.ACCESS_TOKEN))
                .thenReturn(staff.getUsername());
        when(invalidatedTokenRepository.existsByToken("access-jti")).thenReturn(false);
        when(invalidatedTokenRepository.insertInvalidatedToken(any(), any(), any()))
                .thenReturn(1);
        when(userRepository.findByUsernameIgnoreCase(staff.getUsername()))
                .thenReturn(Optional.of(staff));

        service.logout("Bearer access", "Bearer refresh");

        verify(invalidatedTokenRepository).insertInvalidatedToken(
                eq("access-jti"), any(Date.class), eq("LOGOUT"));
        verify(invalidatedTokenRepository).insertInvalidatedToken(
                eq("refresh-jti"), any(Date.class), eq("LOGOUT"));
        verify(userTokenRepository).deleteById(staff.getId());
    }

    private User activeUser(Long id, UserType type) {
        User user = User.builder()
                .fullName("QA User")
                .username("qa-" + id)
                .email("qa-" + id + "@example.com")
                .type(type)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .securityVersion(7L)
                .build();
        user.setId(id);
        return user;
    }

    private void stubGeneratedTokens(User user, String accessToken, String refreshToken) {
        List<String> authorities = List.of("ROLE_" + user.getType().name());
        when(jwtService.generateAccessToken(
                user.getUsername(), authorities, user.getSecurityVersion()))
                .thenReturn("Bearer " + accessToken);
        when(jwtService.generateRefreshToken(
                user.getUsername(), authorities, user.getSecurityVersion()))
                .thenReturn("Bearer " + refreshToken);
    }
}
