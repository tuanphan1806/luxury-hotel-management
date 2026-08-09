package com.hotel.backend.service;

import com.hotel.backend.entity.Facility;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.service.Impl.FacilityServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FacilityServiceImplTest {

    @Mock FacilityRepository facilityRepository;
    @Mock MediaAssetService mediaAssetService;
    @Mock ReservationAuditService reservationAuditService;
    @InjectMocks FacilityServiceImpl service;

    @Test
    void deleteRejectsFacilityStillAssignedToRoomTypes() {
        Facility facility = facility(1L);
        RoomType roomType = RoomType.builder().typeName("Standard").build();
        roomType.setId(10L);
        facility.setRoomTypes(new HashSet<>(Set.of(roomType)));
        when(facilityRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(facility));

        assertThrows(AppException.class, () -> service.delete(1L));

        verify(facilityRepository, never()).delete(any());
        verify(mediaAssetService, never()).releaseReferences(any(), any(), any());
    }

    @Test
    void deletePermanentlyRemovesOnlyUnassignedFacility() {
        Facility facility = facility(1L);
        when(facilityRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(facility));

        service.delete(1L);

        verify(facilityRepository).delete(facility);
        verify(facilityRepository).flush();
        verify(mediaAssetService).releaseReferences(eq(List.of()), any(), eq(1L));
    }

    private Facility facility(Long id) {
        Facility facility = Facility.builder()
                .facilityName("Hồ bơi")
                .imageUrls(List.of())
                .roomTypes(new HashSet<>())
                .build();
        facility.setId(id);
        return facility;
    }
}
