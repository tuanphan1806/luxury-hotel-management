package com.hotel.backend.service;
 
import com.hotel.backend.dto.request.AssignRoomRequest;
import com.hotel.backend.dto.request.CancelReservationRequest;
import com.hotel.backend.dto.request.CreateReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInCheckedInRequest;
import com.hotel.backend.dto.request.CheckoutRefundRequest;
import com.hotel.backend.dto.request.ReservationRefundRequest;
import com.hotel.backend.dto.request.RejectReservationRequest;
import com.hotel.backend.dto.request.UpdateReservationRequest;
import com.hotel.backend.dto.response.AvailabilityResponse;
import com.hotel.backend.dto.response.FinalPaymentResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.dto.response.WalkInReservationResponse;
import com.hotel.backend.entity.User;
 

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
 
public interface ReservationService {
 
    // Khách nhập thông tin → tạo phiên PAYMENT_PENDING, chưa chiếm tồn phòng.
    ReservationResponse createReservation(User currentUser, CreateReservationRequest request);

    ReservationResponse createReservation(
            User currentUser,
            CreateReservationRequest request,
            String deterministicGuestToken);

    ReservationResponse createWalkInReservation(CreateWalkInReservationRequest request);

    WalkInReservationResponse createWalkInCheckedIn(
            CreateWalkInCheckedInRequest request,
            User currentUser,
            String ipAddress);

    // Chỉ tạo RoomHold sau khi khách yêu cầu mở QR thanh toán.
    void activatePaymentHolds(Long reservationId, LocalDateTime expiresAt);

    // Được gọi từ payment provider khi thanh toán thành công
    void convertHoldsAfterPayment(Long reservationId);

    // Atomic failure path for money received but not accepted by the booking.
    void cancelForPaymentFailure(Long reservationId, String reasonCode, String message);

    /** Recover a deposit cancelled by timeout when the bank timestamp was in time. */
    boolean recoverOnTimeDepositPayment(Long reservationId, String paymentId, Instant providerOccurredAt);
    // Lấy chi tiết đặt phòng
    ReservationResponse getReservation(Long reservationId, User currentUser, String guestToken);

    ReservationResponse lookupGuestReservation(String guestToken);
 
    // Lấy danh sách đặt phòng của khách
    List<ReservationResponse> getMyReservations(User currentUser);
 
    // Khách/Staff hủy đặt phòng
    ReservationResponse cancelReservation(
            Long reservationId,
            CancelReservationRequest request,
            User currentUser,
            String guestToken);

    ReservationResponse approveCancellation(Long reservationId, CancelReservationRequest request);
    ReservationResponse rejectCancellation(Long reservationId);
    ReservationResponse cancelByStaff(Long reservationId, CancelReservationRequest request);
 
    // Staff xác nhận đặt phòng (sau thanh toán hoặc duyệt thủ công)
    ReservationResponse confirmReservation(Long reservationId);
    ReservationResponse rejectConfirmation(Long reservationId, RejectReservationRequest request);
 
    // Kiểm tra phòng trống theo ngày
    List<AvailabilityResponse> checkAvailability(LocalDateTime checkIn, LocalDateTime checkOut);
 
    ReservationResponse checkIn(Long reservationId, List<AssignRoomRequest> requests);
    ReservationResponse checkOut(Long reservationId);
    ReservationResponse updateCheckoutAdditionalFee(Long reservationId, CheckoutRefundRequest request);
    ReservationResponse requestCheckoutRefund(Long reservationId, ReservationRefundRequest request);
    ReservationResponse markNoShow(Long reservationId);

    List<ReservationResponse> getAllReservations();
    ReservationResponse updateReservation(Long reservationId, UpdateReservationRequest request, User currentUser);

    FinalPaymentResponse calculateFinalPayment(Long reservationId, User currentUser);
    CheckoutReconciliationResponse getCheckoutReconciliation(Long reservationId, User currentUser);

    /** Internal settlement projection; no state mutation and no public controller mapping. */
    long getProjectedCheckoutTotal(Long reservationId);
    ReservationInvoiceResponse getInvoice(Long reservationId, User currentUser);
}
