package com.hotel.backend.service;

import com.hotel.backend.constant.AssignStatus;
import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.request.RoomMaintenanceRequest;
import com.hotel.backend.entity.ReservationRoom;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationRoomRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.service.Impl.RoomServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock RoomRepository roomRepository;
    @Mock RoomTypeRepository roomTypeRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock ReservationRoomRepository reservationRoomRepository;
    @Mock ReservationAuditService reservationAuditService;
    @InjectMocks RoomServiceImpl roomService;

    @Test
    void manualBookedStatusIsRejected() {
        Room room = room(1L, "101", RoomStatus.AVAILABLE, CleaningStatus.CLEAN);
        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));

        assertThrows(AppException.class, () -> roomService.updateStatus(1L, RoomStatus.BOOKED));
        verify(roomRepository, never()).save(any());
    }

    @Test
    void checkedInRoomCannotStartMaintenance() {
        Room room = room(1L, "101", RoomStatus.CHECKED_IN, CleaningStatus.CLEAN);
        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));

        RoomMaintenanceRequest request = new RoomMaintenanceRequest();
        request.setReason("Sửa điều hòa");
        request.setExpectedCompletedDate(LocalDate.now().plusDays(1));

        assertThrows(AppException.class, () -> roomService.startMaintenance(1L, request));
        verify(roomRepository, never()).save(any());
    }

    @Test
    void maintenanceStoresReasonDateAndHistory() {
        Room room = room(1L, "101", RoomStatus.AVAILABLE, CleaningStatus.CLEAN);
        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomMaintenanceRequest request = new RoomMaintenanceRequest();
        request.setReason("Sửa điều hòa");
        request.setExpectedCompletedDate(LocalDate.now().plusDays(1));

        roomService.startMaintenance(1L, request);

        assertEquals(RoomStatus.MAINTENANCE, room.getStatus());
        assertEquals(CleaningStatus.DIRTY, room.getCleaningStatus());
        assertEquals("Sửa điều hòa", room.getMaintenanceReason());
        assertEquals(1, room.getMaintenanceHistory().size());
        assertEquals("Khởi tạo bảo trì", room.getMaintenanceHistory().get(0).getAction());
    }

    @Test
    void transferMovesReservationRoomAndUpdatesPhysicalRooms() {
        Room source = room(1L, "101", RoomStatus.CHECKED_IN, CleaningStatus.CLEAN);
        Room target = room(2L, "102", RoomStatus.AVAILABLE, CleaningStatus.CLEAN);
        ReservationRoom activeStay = ReservationRoom.builder()
                .room(source)
                .status(AssignStatus.CHECKED_IN)
                .build();
        activeStay.setId(10L);

        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(source));
        when(roomRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
        when(reservationRoomRepository.findFirstByRoomIdAndStatus(1L, AssignStatus.CHECKED_IN))
                .thenReturn(Optional.of(activeStay));
        when(reservationRoomRepository.existsByRoomIdAndStatus(2L, AssignStatus.CHECKED_IN)).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        roomService.transferCheckedInRoom(1L, 2L);

        assertSame(target, activeStay.getRoom());
        assertEquals(RoomStatus.AVAILABLE, source.getStatus());
        assertEquals(CleaningStatus.DIRTY, source.getCleaningStatus());
        assertEquals(RoomStatus.CHECKED_IN, target.getStatus());
        verify(reservationRoomRepository).save(activeStay);
    }

    private Room room(Long id, String name, RoomStatus status, CleaningStatus cleaningStatus) {
        RoomType roomType = RoomType.builder().typeName("Standard").build();
        roomType.setId(100L);
        Room room = Room.builder()
                .roomName(name)
                .roomType(roomType)
                .floor(1)
                .status(status)
                .cleaningStatus(cleaningStatus)
                .maintenanceHistory(new ArrayList<>())
                .build();
        room.setId(id);
        return room;
    }
}
