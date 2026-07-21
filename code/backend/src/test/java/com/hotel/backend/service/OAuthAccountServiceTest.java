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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthAccountServiceTest {

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OAuthAccountService oauthAccountService;

    @Test
    void returnsActiveUserWhenProviderMappingAlreadyExists() {
        User user = activeCustomer("guest@gmail.com");
        OAuthAccount mapping = OAuthAccount.builder()
                .provider(OAuthProvider.GOOGLE)
                .providerSubject("google-subject")
                .user(user)
                .build();
        OAuthLoginProfile profile = googleProfile(
                "google-subject", "guest@gmail.com", true, null);

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.GOOGLE, "google-subject"))
                .thenReturn(Optional.of(mapping));

        User resolved = oauthAccountService.resolveOrCreate(profile);

        assertThat(resolved).isSameAs(user);
        verifyNoInteractions(userRepository);
        verify(oauthAccountRepository, never()).saveAndFlush(any(OAuthAccount.class));
    }

    @Test
    void createsActivePasswordlessCustomerAndGoogleMappingForNewVerifiedGmail() {
        OAuthLoginProfile profile = new OAuthLoginProfile(
                OAuthProvider.GOOGLE,
                "new-google-subject",
                "  New.Guest@Gmail.com ",
                true,
                null,
                "  New Guest  ",
                " https://images.example/avatar.png ");

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.GOOGLE, "new-google-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("new.guest@gmail.com"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(oauthAccountRepository.saveAndFlush(any(OAuthAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User resolved = oauthAccountService.resolveOrCreate(profile);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User createdUser = userCaptor.getValue();
        assertThat(resolved).isSameAs(createdUser);
        assertThat(createdUser.getEmail()).isEqualTo("new.guest@gmail.com");
        assertThat(createdUser.getFullName()).isEqualTo("New Guest");
        assertThat(createdUser.getUsername()).startsWith("oauth_google_");
        assertThat(createdUser.getType()).isEqualTo(UserType.CUSTOMER);
        assertThat(createdUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(createdUser.isEmailVerified()).isTrue();
        assertThat(createdUser.getPassword()).isNull();
        assertThat(createdUser.getPhone()).isNull();
        assertThat(createdUser.getImageUrl()).isEqualTo("https://images.example/avatar.png");

        ArgumentCaptor<OAuthAccount> mappingCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).saveAndFlush(mappingCaptor.capture());
        OAuthAccount createdMapping = mappingCaptor.getValue();
        assertThat(createdMapping.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(createdMapping.getProviderSubject()).isEqualTo("new-google-subject");
        assertThat(createdMapping.getUser()).isSameAs(createdUser);
    }

    @Test
    void autoLinksExistingUserForVerifiedGmailAddress() {
        User existingUser = User.builder()
                .email("guest@gmail.com")
                .fullName("Existing Guest")
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .type(UserType.CUSTOMER)
                .build();
        OAuthLoginProfile profile = googleProfile(
                "gmail-subject", "GUEST@GMAIL.COM", true, null);

        stubExistingUserLink(profile, existingUser);

        User resolved = oauthAccountService.resolveOrCreate(profile);

        assertThat(resolved).isSameAs(existingUser);
        assertThat(existingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(existingUser.isEmailVerified()).isTrue();
        verifyLinkedMapping(existingUser, "gmail-subject");
    }

    @Test
    void autoLinksExistingUserForVerifiedEmailMatchingGoogleHostedDomain() {
        User existingUser = User.builder()
                .email("member@hotel.example")
                .fullName("Hotel Member")
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .type(UserType.CUSTOMER)
                .build();
        OAuthLoginProfile profile = googleProfile(
                "workspace-subject", "member@hotel.example", true, " HOTEL.EXAMPLE ");

        stubExistingUserLink(profile, existingUser);

        User resolved = oauthAccountService.resolveOrCreate(profile);

        assertThat(resolved).isSameAs(existingUser);
        assertThat(existingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(existingUser.isEmailVerified()).isTrue();
        verifyLinkedMapping(existingUser, "workspace-subject");
    }

    @Test
    void rejectsGoogleProfileWithoutEmailUsingMissingEmailCode() {
        OAuthLoginProfile profile = googleProfile(
                "missing-email-subject", "  ", true, null);

        assertOAuthError(OAuthLoginError.MISSING_EMAIL,
                () -> oauthAccountService.resolveOrCreate(profile));

        verifyNoInteractions(oauthAccountRepository, userRepository);
    }

    @Test
    void rejectsGoogleProfileWithUnverifiedEmailUsingUnverifiedEmailCode() {
        OAuthLoginProfile profile = googleProfile(
                "unverified-subject", "guest@gmail.com", false, null);

        assertOAuthError(OAuthLoginError.UNVERIFIED_EMAIL,
                () -> oauthAccountService.resolveOrCreate(profile));

        verifyNoInteractions(oauthAccountRepository, userRepository);
    }

    @Test
    void rejectsFacebookEmailCollisionWithoutSavingMapping() {
        User existingUser = activeCustomer("guest@example.com");
        OAuthLoginProfile profile = new OAuthLoginProfile(
                OAuthProvider.FACEBOOK,
                "facebook-subject",
                "guest@example.com",
                true,
                null,
                "Facebook Guest",
                null);

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.FACEBOOK, "facebook-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("guest@example.com"))
                .thenReturn(Optional.of(existingUser));

        assertOAuthError(OAuthLoginError.ACCOUNT_CONFLICT,
                () -> oauthAccountService.resolveOrCreate(profile));

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(oauthAccountRepository, never()).saveAndFlush(any(OAuthAccount.class));
    }

    @Test
    void rejectsNewFacebookProfileWhenFacebookOmitsEmail() {
        OAuthLoginProfile profile = new OAuthLoginProfile(
                OAuthProvider.FACEBOOK,
                "facebook-without-email",
                null,
                false,
                null,
                "Facebook Guest",
                "https://images.example/facebook-avatar.png");

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.FACEBOOK, "facebook-without-email"))
                .thenReturn(Optional.empty());

        assertOAuthError(OAuthLoginError.MISSING_EMAIL,
                () -> oauthAccountService.resolveOrCreate(profile));

        verifyNoInteractions(userRepository);
        verify(oauthAccountRepository, never()).saveAndFlush(any(OAuthAccount.class));
    }

    @Test
    void createsPendingFacebookAccountAfterUserSuppliesEmail() {
        OAuthLoginProfile profile = new OAuthLoginProfile(
                OAuthProvider.FACEBOOK,
                "facebook-profile-completion",
                null,
                false,
                null,
                "Facebook Guest",
                "https://images.example/facebook-avatar.png");

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.FACEBOOK, "facebook-profile-completion"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("guest@example.com")).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(oauthAccountRepository.saveAndFlush(any(OAuthAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User resolved = oauthAccountService.createPendingFacebookAccount(
                profile, " Guest@Example.com ");

        assertThat(resolved.getEmail()).isEqualTo("guest@example.com");
        assertThat(resolved.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(resolved.isEmailVerified()).isFalse();
        assertThat(resolved.getPassword()).isNull();
        assertThat(resolved.getType()).isEqualTo(UserType.CUSTOMER);
        ArgumentCaptor<OAuthAccount> mappingCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).saveAndFlush(mappingCaptor.capture());
        assertThat(mappingCaptor.getValue().getProviderSubject())
                .isEqualTo("facebook-profile-completion");
        assertThat(mappingCaptor.getValue().getUser()).isSameAs(resolved);
    }

    @Test
    void reusesExistingFacebookMappingEvenWhenFacebookStillOmitsEmail() {
        User user = User.builder()
                .email("facebook-existing@example.com")
                .fullName("Facebook Guest")
                .status(UserStatus.ACTIVE)
                .emailVerified(false)
                .type(UserType.CUSTOMER)
                .build();
        OAuthAccount mapping = OAuthAccount.builder()
                .provider(OAuthProvider.FACEBOOK)
                .providerSubject("facebook-existing-subject")
                .user(user)
                .build();
        OAuthLoginProfile profile = new OAuthLoginProfile(
                OAuthProvider.FACEBOOK,
                "facebook-existing-subject",
                null,
                false,
                null,
                "Facebook Guest",
                null);

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.FACEBOOK, "facebook-existing-subject"))
                .thenReturn(Optional.of(mapping));

        User resolved = oauthAccountService.resolveOrCreate(profile);

        assertThat(resolved).isSameAs(user);
        verifyNoInteractions(userRepository);
        verify(oauthAccountRepository, never()).saveAndFlush(any(OAuthAccount.class));
    }

    @ParameterizedTest
    @EnumSource(value = UserType.class, names = {"STAFF", "ADMIN"})
    void neverAutoLinksGoogleEmailToPrivilegedAccount(UserType privilegedType) {
        User privilegedUser = User.builder()
                .email("privileged@gmail.com")
                .fullName("Privileged User")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .type(privilegedType)
                .build();
        OAuthLoginProfile profile = googleProfile(
                "privileged-google-subject", "privileged@gmail.com", true, null);

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.GOOGLE, "privileged-google-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("privileged@gmail.com"))
                .thenReturn(Optional.of(privilegedUser));

        assertOAuthError(OAuthLoginError.ACCOUNT_CONFLICT,
                () -> oauthAccountService.resolveOrCreate(profile));

        verify(userRepository, never()).save(any(User.class));
        verify(oauthAccountRepository, never()).saveAndFlush(any(OAuthAccount.class));
    }

    @Test
    void rejectsExistingProviderMappingWhenUserIsInactive() {
        User inactiveUser = User.builder()
                .email("disabled@gmail.com")
                .status(UserStatus.INACTIVE)
                .emailVerified(true)
                .type(UserType.CUSTOMER)
                .build();
        OAuthAccount mapping = OAuthAccount.builder()
                .provider(OAuthProvider.GOOGLE)
                .providerSubject("disabled-subject")
                .user(inactiveUser)
                .build();
        OAuthLoginProfile profile = googleProfile(
                "disabled-subject", "disabled@gmail.com", true, null);

        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.GOOGLE, "disabled-subject"))
                .thenReturn(Optional.of(mapping));

        assertOAuthError(OAuthLoginError.ACCOUNT_DISABLED,
                () -> oauthAccountService.resolveOrCreate(profile));

        verifyNoInteractions(userRepository);
        verify(oauthAccountRepository, never()).saveAndFlush(any(OAuthAccount.class));
    }

    @ParameterizedTest
    @EnumSource(value = UserType.class, names = {"STAFF", "ADMIN"})
    void rejectsExistingOAuthMappingAfterCustomerIsPromoted(UserType privilegedType) {
        User privilegedUser = User.builder()
                .email("promoted@gmail.com")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .type(privilegedType)
                .build();
        OAuthAccount mapping = OAuthAccount.builder()
                .provider(OAuthProvider.GOOGLE)
                .providerSubject("promoted-subject")
                .user(privilegedUser)
                .build();
        when(oauthAccountRepository.findByProviderAndProviderSubject(
                OAuthProvider.GOOGLE, "promoted-subject"))
                .thenReturn(Optional.of(mapping));

        assertOAuthError(OAuthLoginError.ACCOUNT_CONFLICT,
                () -> oauthAccountService.resolveOrCreate(googleProfile(
                        "promoted-subject", "promoted@gmail.com", true, null)));

        verifyNoInteractions(userRepository);
    }

    private void stubExistingUserLink(OAuthLoginProfile profile, User existingUser) {
        when(oauthAccountRepository.findByProviderAndProviderSubject(
                profile.provider(), profile.providerSubject()))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(existingUser.getEmail()))
                .thenReturn(Optional.of(existingUser));
        when(oauthAccountRepository.findByUserAndProvider(existingUser, profile.provider()))
                .thenReturn(Optional.empty());
        when(userRepository.save(existingUser)).thenReturn(existingUser);
        when(oauthAccountRepository.saveAndFlush(any(OAuthAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void verifyLinkedMapping(User user, String providerSubject) {
        verify(userRepository).save(user);
        ArgumentCaptor<OAuthAccount> mappingCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).saveAndFlush(mappingCaptor.capture());
        OAuthAccount mapping = mappingCaptor.getValue();
        assertThat(mapping.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(mapping.getProviderSubject()).isEqualTo(providerSubject);
        assertThat(mapping.getUser()).isSameAs(user);
    }

    private OAuthLoginProfile googleProfile(
            String subject,
            String email,
            boolean emailVerified,
            String hostedDomain) {
        return new OAuthLoginProfile(
                OAuthProvider.GOOGLE,
                subject,
                email,
                emailVerified,
                hostedDomain,
                "Google Guest",
                null);
    }

    private User activeCustomer(String email) {
        return User.builder()
                .email(email)
                .fullName("Active Guest")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .type(UserType.CUSTOMER)
                .build();
    }

    private void assertOAuthError(OAuthLoginError expectedError, Executable executable) {
        OAuthLoginException exception = assertThrows(OAuthLoginException.class, executable);
        assertThat(exception.getError()).isEqualTo(expectedError);
        assertThat(exception.getMessage()).isEqualTo(expectedError.getCode());
    }
}
