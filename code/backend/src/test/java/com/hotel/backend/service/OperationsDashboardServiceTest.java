package com.hotel.backend.service;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.ContactMessageStatus;
import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.response.OperationsDashboardResponse;
import com.hotel.backend.repository.ContactMessageRepository;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationServiceOrderRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationsDashboardServiceTest {

    @Mock ReservationRepository reservationRepository;
    @Mock RoomRepository roomRepository;
    @Mock UserRepository userRepository;
    @Mock CustomerProfileRepository customerProfileRepository;
    @Mock ReservationServiceOrderRepository reservationServiceOrderRepository;
    @Mock ContactMessageRepository contactMessageRepository;
    @InjectMocks OperationsDashboardService dashboardService;

    @Test
    void summaryIncludesCurrentOperationalQueuesAndRoomReadiness() {
        when(roomRepository.count()).thenReturn(12L);
        when(roomRepository.countByStatusAndCleaningStatus(
                RoomStatus.AVAILABLE, CleaningStatus.CLEAN)).thenReturn(4L);
        when(roomRepository.countByStatusAndCleaningStatus(
                RoomStatus.AVAILABLE, CleaningStatus.DIRTY)).thenReturn(2L);
        when(roomRepository.countByStatusAndCleaningStatusIsNull(RoomStatus.AVAILABLE)).thenReturn(1L);
        when(roomRepository.countByStatusAndCleaningStatus(
                RoomStatus.AVAILABLE, CleaningStatus.IN_PROGRESS)).thenReturn(1L);
        when(roomRepository.countByStatus(RoomStatus.CHECKED_IN)).thenReturn(6L);
        when(roomRepository.countByStatus(RoomStatus.MAINTENANCE)).thenReturn(2L);

        when(reservationRepository.countByCheckInWindowAndStatuses(
                any(LocalDateTime.class), any(LocalDateTime.class), anyList())).thenReturn(5L);
        when(reservationRepository.countByCheckOutWindowAndStatuses(
                any(LocalDateTime.class), any(LocalDateTime.class), anyList())).thenReturn(4L);
        when(reservationRepository.countCreatedInWindowExcludingStatus(
                any(LocalDateTime.class), any(LocalDateTime.class), any(ReservationStatus.class))).thenReturn(7L);
        when(reservationRepository.countByStatus(ReservationStatus.CHECKED_IN)).thenReturn(6L);
        when(reservationRepository.countByStatus(ReservationStatus.DRAFT)).thenReturn(2L);
        when(reservationRepository.countByStatus(ReservationStatus.CANCELLATION_PENDING)).thenReturn(1L);

        when(reservationServiceOrderRepository.countByStatusIn(List.of(
                ReservationServiceStatus.REQUESTED,
                ReservationServiceStatus.CONFIRMED))).thenReturn(3L);
        when(contactMessageRepository.countByStatusIn(List.of(
                ContactMessageStatus.NEW,
                ContactMessageStatus.READ))).thenReturn(4L);
        when(userRepository.countByType(UserType.CUSTOMER)).thenReturn(10L);
        when(customerProfileRepository.count()).thenReturn(16L);

        OperationsDashboardResponse result = dashboardService.getSummary();

        assertNotNull(result.getGeneratedAt());
        assertEquals(5L, result.getArrivalsToday());
        assertEquals(4L, result.getDeparturesToday());
        assertEquals(6L, result.getActiveStays());
        assertEquals(2L, result.getPendingConfirmations());
        assertEquals(1L, result.getCancellationRequests());
        assertEquals(3L, result.getPendingServiceRequests());
        assertEquals(4L, result.getOpenContactMessages());
        assertEquals(60, result.getOccupancyRate());
        assertEquals(4L, result.getAvailableRooms());
        assertEquals(3L, result.getDirtyRooms());
        assertEquals(1L, result.getCleaningRooms());
        assertEquals(2L, result.getMaintenanceRooms());
    }
}
