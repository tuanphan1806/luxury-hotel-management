package com.hotel.backend.service;

import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationRoomResponse;
import com.hotel.backend.dto.response.ReservationRoomTypeResponse;
import com.hotel.backend.dto.response.RoomHoldResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Builds reservation read models. It deliberately contains no authorization,
 * persistence, state transition, or transaction logic.
 */
@Component
@RequiredArgsConstructor
public class ReservationResponseAssembler {

    private final PaymentRefundService paymentRefundService;
    private final ReservationAddOnService reservationAddOnService;
    private final ReservationPricingReadService pricingReadService;
    private final ReservationRoomTypeRepository
            reservationRoomTypeRepository;

    public ReservationResponse withRoomTypeDetails(Reservation reservation) {
        ReservationResponse response = reservationAddOnService.enrich(
                ReservationResponse.fromWithDetails(
                        reservation, roomTypeDetails(reservation)));
        return pricingReadService.enrich(reservation, response);
    }

    public ReservationResponse withRoomTypeDetailsAndRefundSummary(Reservation reservation) {
        return applyRefundSummary(withRoomTypeDetails(reservation));
    }

    public ReservationResponse applyRefundSummary(ReservationResponse response) {
        return paymentRefundService.applyReservationRefundSummary(response);
    }

    public List<ReservationRoomResponse> assignedRooms(Reservation reservation) {
        return reservation.getRoomTypes().stream()
                .flatMap(roomType -> roomType.getRooms().stream())
                .map(ReservationRoomResponse::from)
                .sorted(Comparator.comparing(
                        ReservationRoomResponse::getRoomName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    private List<ReservationRoomTypeResponse> roomTypeDetails(Reservation reservation) {
        return reservationRoomTypeRepository
                .findDetailsByReservationId(reservation.getId())
                .stream()
                .map(reservationRoomType -> {
                    ReservationRoomTypeResponse response =
                            ReservationRoomTypeResponse.from(reservationRoomType);
                    if (reservationRoomType.getRoomHold() != null) {
                        response.setRoomHold(RoomHoldResponse.from(reservationRoomType.getRoomHold()));
                    }
                    return response;
                })
                .toList();
    }
}
