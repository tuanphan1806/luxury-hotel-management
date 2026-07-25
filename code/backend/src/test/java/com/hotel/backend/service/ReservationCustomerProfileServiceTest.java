package com.hotel.backend.service;

import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.dto.request.CustomerProfileRequest;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.CustomerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCustomerProfileServiceTest {

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    @InjectMocks
    private ReservationCustomerProfileService service;

    @Test
    void findOrCreateOnlineCustomerProfileReturnsExistingLinkedProfile() {
        User user = User.builder().fullName("Nguyen Van A").email("a@example.com").build();
        user.setId(11L);
        CustomerProfile existing = CustomerProfile.builder()
                .fullName("Nguyen Van A")
                .linkedUser(user)
                .source(CustomerProfileSource.ONLINE)
                .build();
        when(customerProfileRepository.findByLinkedUserId(11L)).thenReturn(Optional.of(existing));

        CustomerProfile result = service.findOrCreateOnlineCustomerProfile(user);

        assertSame(existing, result);
        verify(customerProfileRepository, never()).save(any());
    }

    @Test
    void resolveGuestOnlineCustomerProfileRequiresEmailBeforeSaving() {
        CustomerProfileRequest request = CustomerProfileRequest.builder()
                .fullName("Nguyen Van A")
                .build();

        assertThrows(AppException.class,
                () -> service.resolveGuestOnlineCustomerProfile(request));
        verify(customerProfileRepository, never()).save(any());
    }

    @Test
    void resolveWalkInCustomerProfileReusesMatchingIdentityAndUpdatesProvidedFields() {
        CustomerProfile existing = CustomerProfile.builder()
                .fullName("Old Name")
                .phone("0900000000")
                .email("old@example.com")
                .idCardNumber("012345678")
                .source(CustomerProfileSource.WALK_IN)
                .build();
        CustomerProfileRequest request = CustomerProfileRequest.builder()
                .fullName("New Name")
                .phone("0900000000")
                .email("old@example.com")
                .idCardNumber("012345678")
                .build();
        when(customerProfileRepository.findFirstByIdCardNumber("012345678"))
                .thenReturn(Optional.of(existing));
        when(customerProfileRepository.save(existing)).thenReturn(existing);

        CustomerProfile result = service.resolveWalkInCustomerProfile(null, request);

        assertSame(existing, result);
        assertEquals("New Name", result.getFullName());
        verify(customerProfileRepository).save(existing);
    }

    @Test
    void resolveWalkInCustomerProfileDoesNotOverwriteConflictingIdentity() {
        CustomerProfile existing = CustomerProfile.builder()
                .fullName("Existing")
                .phone("0900000000")
                .idCardNumber("012345678")
                .source(CustomerProfileSource.WALK_IN)
                .build();
        CustomerProfileRequest request = CustomerProfileRequest.builder()
                .fullName("Different Guest")
                .phone("0911111111")
                .idCardNumber("012345678")
                .build();
        when(customerProfileRepository.findFirstByIdCardNumber("012345678"))
                .thenReturn(Optional.of(existing));
        when(customerProfileRepository.save(any(CustomerProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfile result = service.resolveWalkInCustomerProfile(null, request);

        assertNotSame(existing, result);
        assertEquals("Different Guest", result.getFullName());
        assertEquals("0911111111", result.getPhone());
        assertEquals("Existing", existing.getFullName());
    }
}
