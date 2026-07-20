package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.dto.response.OperationsAttentionResponse;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperationsAttentionService {
    private final ReservationRepository reservationRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    @Value("${hotel.operations.draft-warning-minutes:30}")
    private long draftWarningMinutes;
    @Value("${hotel.operations.arrival-window-hours:2}")
    private long arrivalWindowHours;
    @Value("${hotel.operations.refund-alert-hours:48}")
    private long refundAlertHours;

    @Transactional(readOnly = true)
    public OperationsAttentionResponse getQueue() {
        LocalDateTime now = LocalDateTime.now();
        List<OperationsAttentionResponse.Item> items = new ArrayList<>();

        for (Reservation reservation : reservationRepository.findAllWithDetails()) {
            if (reservation.getStatus() == ReservationStatus.CANCELLATION_PENDING) {
                items.add(item("CANCELLATION_REQUEST", "DANGER", reservation,
                        "Yêu cầu hủy chờ duyệt", "Xác nhận hủy hoặc từ chối yêu cầu của khách",
                        reservation.getUpdatedAt() != null ? reservation.getUpdatedAt() : reservation.getCreatedAt(),
                        reservation.getRefundableAmount() != null
                                ? reservation.getRefundableAmount().longValue() : null));
            }
            if (reservation.getStatus() == ReservationStatus.DRAFT) {
                LocalDateTime confirmationDueAt = reservation.getCreatedAt() != null
                        ? reservation.getCreatedAt().plusMinutes(draftWarningMinutes) : null;
                boolean overdue = confirmationDueAt != null && confirmationDueAt.isBefore(now);
                items.add(item(overdue ? "DRAFT_OVERDUE" : "DRAFT_PENDING",
                        overdue ? "WARNING" : "INFO", reservation,
                        overdue ? "Đơn cọc lâu chưa xác nhận" : "Đơn chờ xác nhận",
                        overdue ? "Kiểm tra tiền cọc, xác nhận đơn hoặc liên hệ khách"
                                : "Kiểm tra tiền cọc và xác nhận đơn trong thời gian quy định",
                        confirmationDueAt, null));
            }
            if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                if (reservation.getCheckIn().isAfter(now)
                        && !reservation.getCheckIn().isAfter(now.plusHours(arrivalWindowHours))) {
                    items.add(item("ARRIVING_SOON", "INFO", reservation,
                            "Khách sắp đến", "Chuẩn bị phòng và thông tin check-in",
                            reservation.getCheckIn(), null));
                } else if (reservation.getCheckIn().isBefore(now)
                        && reservation.getCheckIn().toLocalDate().equals(now.toLocalDate())) {
                    items.add(item("CHECK_IN_LATE", "WARNING", reservation,
                            "Khách check-in trễ", "Liên hệ khách; chưa tự động chuyển no-show",
                            reservation.getCheckIn(), null));
                } else if (reservation.getCheckIn().toLocalDate().isBefore(now.toLocalDate())) {
                    items.add(item("NO_SHOW_CANDIDATE", "DANGER", reservation,
                            "Cần xử lý no-show", "Nhân viên xác minh trước khi đánh dấu không đến",
                            reservation.getCheckIn(), null));
                }
            }

            if (reservation.getStatus() == ReservationStatus.CHECKED_IN
                    && reservation.getCheckOut() != null
                    && reservation.getCheckOut().isBefore(now)) {
                boolean seriouslyOverdue = reservation.getCheckOut().isBefore(now.minusHours(2));
                items.add(item("CHECK_OUT_OVERDUE", seriouslyOverdue ? "DANGER" : "WARNING", reservation,
                        "Khách quá giờ trả phòng", "Mở đối soát, liên hệ khách và xử lý phụ phí nếu có",
                        reservation.getCheckOut(), null));
            }
        }

        for (PaymentTransaction transaction : paymentTransactionRepository.findByStatusOrderByUpdatedAtAsc(
                PaymentStatus.REFUND_PENDING)) {
            Reservation reservation = transaction.getReservation();
            LocalDateTime refundDueAt = transaction.getUpdatedAt() != null
                    ? transaction.getUpdatedAt().plusHours(refundAlertHours) : null;
            boolean overdue = refundDueAt != null && refundDueAt.isBefore(now);
            items.add(item(overdue ? "REFUND_OVERDUE" : "REFUND_PENDING", overdue ? "DANGER" : "WARNING", reservation,
                    overdue ? "Hoàn tiền đã quá hạn" : "Hoàn tiền chờ xử lý",
                    "Đối chiếu giao dịch " + transaction.getTxnRef(),
                    refundDueAt, transaction.getRefundAmount()));
        }

        Map<String, Integer> severityPriority = Map.of("DANGER", 0, "WARNING", 1, "INFO", 2);
        items.sort(Comparator
                .comparingInt((OperationsAttentionResponse.Item value) ->
                        severityPriority.getOrDefault(value.getSeverity(), 3))
                .thenComparing(OperationsAttentionResponse.Item::getDueAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return OperationsAttentionResponse.builder().total(items.size()).items(items).build();
    }

    private OperationsAttentionResponse.Item item(String type, String severity, Reservation reservation,
                                                    String title, String detail, LocalDateTime dueAt, Long amount) {
        String customerName = reservation.getCustomerProfile() != null
                ? reservation.getCustomerProfile().getFullName() : "Khách hàng";
        return OperationsAttentionResponse.Item.builder()
                .type(type).severity(severity).reservationId(reservation.getId())
                .reservationCode(reservation.getReservationCode()).customerName(customerName)
                .title(title).detail(detail).dueAt(dueAt).amount(amount).build();
    }
}
