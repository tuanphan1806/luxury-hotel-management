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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationsDashboardService {
    private final ReservationRepository reservationRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;

    @Transactional(readOnly = true)
    public OperationsDashboardResponse getSummary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime startOfTomorrow = startOfDay.plusDays(1);

        long totalRooms = roomRepository.count();
        long availableRooms = roomRepository.countByStatus(RoomStatus.AVAILABLE);
        long occupiedRooms = roomRepository.countByStatus(RoomStatus.CHECKED_IN);
        long maintenanceRooms = roomRepository.countByStatus(RoomStatus.MAINTENANCE);
        long sellableRooms = Math.max(0, totalRooms - maintenanceRooms);
        int occupancyRate = sellableRooms == 0
                ? 0
                : (int) Math.round(occupiedRooms * 100.0 / sellableRooms);

        return OperationsDashboardResponse.builder()
                .generatedAt(now)
                .arrivalsToday(reservationRepository.countByCheckInWindowAndStatuses(
                        startOfDay, startOfTomorrow,
                        List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT)))
                .departuresToday(reservationRepository.countByCheckOutWindowAndStatuses(
                        startOfDay, startOfTomorrow,
                        List.of(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT)))
                .activeStays(reservationRepository.countByStatus(ReservationStatus.CHECKED_IN))
                .bookingsCreatedToday(reservationRepository.countCreatedInWindowExcludingStatus(
                        startOfDay, startOfTomorrow, ReservationStatus.PAYMENT_PENDING))
                .pendingConfirmations(reservationRepository.countByStatus(ReservationStatus.DRAFT))
                .cancellationRequests(reservationRepository.countByStatus(ReservationStatus.CANCELLATION_PENDING))
                .totalRooms(totalRooms)
                .availableRooms(availableRooms)
                .occupiedRooms(occupiedRooms)
                .maintenanceRooms(maintenanceRooms)
                .dirtyRooms(roomRepository.countByCleaningStatus(CleaningStatus.DIRTY))
                .occupancyRate(occupancyRate)
                .customerAccounts(userRepository.countByType(UserType.CUSTOMER))
                .customerProfiles(customerProfileRepository.count())
                .build();
    }
}
