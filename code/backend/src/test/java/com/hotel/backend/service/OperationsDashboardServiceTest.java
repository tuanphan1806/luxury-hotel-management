package com.hotel.backend.service;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.response.OperationsDashboardResponse;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationsDashboardServiceTest {

    @Mock ReservationRepository reservationRepository;
    @Mock RoomRepository roomRepository;
    @Mock UserRepository userRepository;
    @Mock CustomerProfileRepository customerProfileRepository;
    @InjectMocks OperationsDashboardService dashboardService;

    /**
     * Công suất chỉ tính phòng CHECKED_IN trên số phòng có thể bán
     * (tổng phòng trừ phòng bảo trì), không tính BOOKED như phòng đang ở.
     */
    @Test
    void summaryUsesActualOccupancyAndSeparatesAccountsFromProfiles() {
        when(roomRepository.count()).thenReturn(10L);
        when(roomRepository.countByStatus(RoomStatus.AVAILABLE)).thenReturn(3L);
        when(roomRepository.countByStatus(RoomStatus.CHECKED_IN)).thenReturn(4L);
        when(roomRepository.countByStatus(RoomStatus.MAINTENANCE)).thenReturn(2L);
        when(roomRepository.countByCleaningStatus(CleaningStatus.DIRTY)).thenReturn(1L);
        when(reservationRepository.countByCheckInWindowAndStatuses(any(), any(), anyList())).thenReturn(5L);
        when(reservationRepository.countByCheckOutWindowAndStatuses(any(), any(), anyList())).thenReturn(2L);
        when(reservationRepository.countByStatus(ReservationStatus.CHECKED_IN)).thenReturn(4L);
        when(reservationRepository.countByStatus(ReservationStatus.DRAFT)).thenReturn(3L);
        when(reservationRepository.countByStatus(ReservationStatus.CANCELLATION_PENDING)).thenReturn(1L);
        when(reservationRepository.countCreatedInWindowExcludingStatus(any(), any(), any())).thenReturn(6L);
        when(userRepository.countByType(UserType.CUSTOMER)).thenReturn(7L);
        when(customerProfileRepository.count()).thenReturn(12L);

        OperationsDashboardResponse result = dashboardService.getSummary();

        assertEquals(50, result.getOccupancyRate());
        assertEquals(4, result.getOccupiedRooms());
        assertEquals(7, result.getCustomerAccounts());
        assertEquals(12, result.getCustomerProfiles());
        assertEquals(5, result.getArrivalsToday());
        assertEquals(2, result.getDeparturesToday());
    }
}
