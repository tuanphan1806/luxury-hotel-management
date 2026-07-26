package com.hotel.backend.service;

import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerProfileLinkServiceTest {

    @Test
    void reusesAnExistingLinkedProfile() {
        CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
        CustomerProfile existing = CustomerProfile.builder().fullName("Existing").build();
        when(repository.findByLinkedUserId(5L)).thenReturn(Optional.of(existing));
        CustomerProfileLinkService service = new CustomerProfileLinkService(repository);

        User user = User.builder().fullName("User").build();
        user.setId(5L);

        assertSame(existing, service.ensureForUser(user));
    }

    @Test
    void synchronizesTheExistingLinkedProfileFields() {
        CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
        CustomerProfile profile = CustomerProfile.builder().fullName("Old").build();
        when(repository.findByLinkedUserId(6L)).thenReturn(Optional.of(profile));
        when(repository.save(any(CustomerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CustomerProfileLinkService service = new CustomerProfileLinkService(repository);

        User user = User.builder()
                .fullName("New name")
                .phone("0387736436")
                .email("new@example.com")
                .address("Hà Nội")
                .build();
        user.setId(6L);

        service.sync(user);

        assertEquals("New name", profile.getFullName());
        assertEquals("new@example.com", profile.getEmail());
        verify(repository).save(profile);
    }
}
