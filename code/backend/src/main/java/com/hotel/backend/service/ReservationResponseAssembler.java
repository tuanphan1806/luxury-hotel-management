package com.hotel.backend.service;

import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationRoomResponse;
import com.hotel.backend.dto.response.ReservationRoomTypeResponse;
import com.hotel.backend.dto.response.RoomHoldResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.math.BigDecimal;

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
    private final ReservationReadBatchLoader batchLoader;

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

    /**
     * Builds a reservation list with a fixed number of secondary queries,
     * independent of the number of reservations in the response.
     */
    public List<ReservationResponse> withBatchDetailsAndRefundSummary(
            List<Reservation> reservations) {
        if (reservations == null || reservations.isEmpty()) {
            return List.of();
        }
        ReservationReadBatchLoader.BatchData batch =
                batchLoader.load(reservations);
        return reservations.stream()
                .map(reservation -> fromBatch(reservation, batch))
                .toList();
    }

    private ReservationResponse fromBatch(
            Reservation reservation,
            ReservationReadBatchLoader.BatchData batch) {
        Long reservationId = reservation.getId();
        List<ReservationRoomType> roomTypes =
                batch.roomTypesFor(reservationId);
        ReservationResponse response = ReservationResponse.fromWithDetails(
                reservation, roomTypeDetails(roomTypes));
        reservationAddOnService.enrich(
                response, batch.servicesFor(reservationId));
        pricingReadService.enrich(
                reservation,
                response,
                batch.snapshotsFor(reservationId),
                roomTypes);
        response.setRooms(assignedRooms(roomTypes));
        response.setPaidAmount(BigDecimal.valueOf(
                paymentRefundService.getNetPaidAmount(
                        batch.paymentsFor(reservationId),
                        batch.refundsFor(reservationId))));
        return paymentRefundService.applyReservationRefundSummary(
                response,
                batch.refundsFor(reservationId),
                batch.paymentsFor(reservationId),
                batch.recipientsFor(reservationId));
    }

    public List<ReservationRoomResponse> assignedRooms(Reservation reservation) {
        return assignedRooms(reservation.getRoomTypes());
    }

    private List<ReservationRoomResponse> assignedRooms(
            Collection<ReservationRoomType> roomTypes) {
        return roomTypes.stream()
                .flatMap(roomType -> roomType.getRooms().stream())
                .map(ReservationRoomResponse::from)
                .sorted(Comparator.comparing(
                        ReservationRoomResponse::getRoomName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    private List<ReservationRoomTypeResponse> roomTypeDetails(Reservation reservation) {
        return roomTypeDetails(reservationRoomTypeRepository
                .findDetailsByReservationId(reservation.getId()));
    }

    private List<ReservationRoomTypeResponse> roomTypeDetails(
            List<ReservationRoomType> roomTypes) {
        return roomTypes.stream()
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
