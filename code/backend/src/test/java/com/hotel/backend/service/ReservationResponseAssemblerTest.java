package com.hotel.backend.service;

import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationRoomResponse;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoom;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomHold;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationResponseAssemblerTest {

    @Mock
    private PaymentRefundService paymentRefundService;

    @Mock
    private ReservationAddOnService reservationAddOnService;

    @Mock
    private ReservationPricingReadService pricingReadService;

    @Mock
    private ReservationRoomTypeRepository
            reservationRoomTypeRepository;

    private ReservationResponseAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ReservationResponseAssembler(
                paymentRefundService,
                reservationAddOnService,
                pricingReadService,
                reservationRoomTypeRepository);
    }

    @Test
    void preservesRoomHoldInDetailedReservationResponse() {
        when(reservationAddOnService.enrich(any(ReservationResponse.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(pricingReadService.enrich(
                any(Reservation.class),
                any(ReservationResponse.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Reservation reservation = baseReservation();
        RoomType roomType = RoomType.builder()
                .typeName("Deluxe")
                .typeNameEn("Deluxe")
                .build();
        roomType.setId(4L);
        ReservationRoomType item = ReservationRoomType.builder()
                .reservation(reservation)
                .roomType(roomType)
                .quantity(2)
                .roomPrice(BigDecimal.valueOf(900_000))
                .subtotal(BigDecimal.valueOf(1_800_000))
                .build();
        item.setId(8L);
        item.setRoomHold(RoomHold.builder().reservationRoomType(item).build());
        reservation.setRoomTypes(new LinkedHashSet<>(List.of(item)));
        when(reservationRoomTypeRepository
                .findDetailsByReservationId(reservation.getId()))
                .thenReturn(List.of(item));
        when(paymentRefundService.applyReservationRefundSummary(any(ReservationResponse.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReservationResponse response = assembler.withRoomTypeDetailsAndRefundSummary(reservation);

        assertThat(response.getRoomTypes()).hasSize(1);
        assertThat(response.getRoomTypes().get(0).getRoomTypeId()).isEqualTo(4L);
        assertThat(response.getRoomTypes().get(0).getRoomHold()).isNotNull();
    }

    @Test
    void sortsAssignedRoomsByRoomNameWithNullLast() {
        Reservation reservation = baseReservation();
        ReservationRoomType item = ReservationRoomType.builder().reservation(reservation).build();
        item.setId(9L);
        item.setRooms(new LinkedHashSet<>(List.of(
                assignedRoom(item, 1L, "B-202"),
                assignedRoom(item, 2L, null),
                assignedRoom(item, 3L, "A-101"))));
        reservation.setRoomTypes(new LinkedHashSet<>(List.of(item)));

        List<ReservationRoomResponse> rooms = assembler.assignedRooms(reservation);

        assertThat(rooms).extracting(ReservationRoomResponse::getRoomName)
                .containsExactly("A-101", "B-202", null);
    }

    private Reservation baseReservation() {
        CustomerProfile customer = CustomerProfile.builder()
                .fullName("Guest")
                .phone("0900000000")
                .email("guest@example.com")
                .build();
        customer.setId(3L);
        Reservation reservation = Reservation.builder()
                .customerProfile(customer)
                .totalAmount(BigDecimal.ZERO)
                .roomTypes(new LinkedHashSet<>())
                .build();
        reservation.setId(7L);
        return reservation;
    }

    private ReservationRoom assignedRoom(
            ReservationRoomType item,
            Long id,
            String roomName) {
        Room room = roomName == null ? null : Room.builder().roomName(roomName).build();
        if (room != null) room.setId(id + 100);
        ReservationRoom reservationRoom = ReservationRoom.builder()
                .reservationRoomType(item)
                .room(room)
                .build();
        reservationRoom.setId(id);
        return reservationRoom;
    }
}
