package com.hotel.backend.config;

import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionAdminBootstrapTest {

    @Test
    void createsOneActiveAdminWithEncodedPassword() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findByUsername("hotel-admin")).thenReturn(Optional.empty());
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(encoder.encode("StrongPassword9")).thenReturn("encoded");

        ProductionAdminBootstrap bootstrap = new ProductionAdminBootstrap(
                users, encoder, "Hotel Admin", "hotel-admin", "ADMIN@EXAMPLE.COM",
                "0900000000", "StrongPassword9");
        bootstrap.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(UserType.ADMIN);
        assertThat(captor.getValue().isEmailVerified()).isTrue();
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded");
        assertThat(captor.getValue().getEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void neverResetsAnExistingMatchingAdminPassword() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User existing = User.builder()
                .username("hotel-admin")
                .email("admin@example.com")
                .type(UserType.ADMIN)
                .password("existing-hash")
                .build();
        when(users.findByUsername("hotel-admin")).thenReturn(Optional.of(existing));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

        ProductionAdminBootstrap bootstrap = new ProductionAdminBootstrap(
                users, encoder, "Hotel Admin", "hotel-admin", "admin@example.com",
                "", "StrongPassword9");
        bootstrap.run();

        verify(users, never()).save(existing);
        verify(encoder, never()).encode("StrongPassword9");
        assertThat(existing.getPassword()).isEqualTo("existing-hash");
    }

    @Test
    void rejectsWeakPasswordBeforeStartup() {
        assertThatThrownBy(() -> new ProductionAdminBootstrap(
                mock(UserRepository.class), mock(PasswordEncoder.class),
                "Hotel Admin", "hotel-admin", "admin@example.com", "", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12+ characters");
    }

    @Test
    void refusesToPromoteAConflictingAccount() {
        UserRepository users = mock(UserRepository.class);
        User customer = User.builder()
                .username("hotel-admin")
                .email("customer@example.com")
                .type(UserType.CUSTOMER)
                .build();
        when(users.findByUsername("hotel-admin")).thenReturn(Optional.of(customer));
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        ProductionAdminBootstrap bootstrap = new ProductionAdminBootstrap(
                users, mock(PasswordEncoder.class), "Hotel Admin", "hotel-admin",
                "admin@example.com", "", "StrongPassword9");

        assertThatThrownBy(bootstrap::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collides");
        verify(users, never()).save(customer);
    }
}
