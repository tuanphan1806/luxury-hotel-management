package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.RefundRecipientRequest;
import com.hotel.backend.dto.response.RefundRecipientResponse;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.RefundRecipient;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import com.hotel.backend.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RefundRecipientService {

    private static final EnumSet<RefundStatus> EDITABLE_REFUND_STATUSES = EnumSet.of(
            RefundStatus.AWAITING_CUSTOMER_INFO,
            RefundStatus.READY_FOR_MANUAL_TRANSFER,
            RefundStatus.REQUESTED);
    private static final EnumSet<RefundRecipientStatus> CURRENT_RECIPIENT_STATUSES = EnumSet.of(
            RefundRecipientStatus.SUBMITTED,
            RefundRecipientStatus.VERIFIED);
    private static final EnumSet<RefundRecipientStatus> EDITABLE_RECIPIENT_STATUSES = EnumSet.of(
            RefundRecipientStatus.SUBMITTED);

    private final ReservationRepository reservationRepository;
    private final PaymentRefundRepository refundRepository;
    private final RefundRecipientRepository recipientRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundDataCipher cipher;
    private final SePayConfig sePayConfig;

    @Transactional
    public RefundRecipientResponse submit(
            Long reservationId,
            RefundRecipientRequest request,
            User currentUser,
            String guestToken) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccess(currentUser, reservation, guestToken);
        if (!refundRepository.findByReservationIdAndChannelAndStatusIn(
                reservationId,
                RefundChannel.MANUAL_BANK_TRANSFER,
                EnumSet.of(RefundStatus.SUCCEEDED)).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không thể đổi tài khoản sau khi đã có khoản hoàn chuyển khoản thành công");
        }
        List<PaymentRefund> manualRefunds = editableManualRefunds(reservationId);
        if (manualRefunds.isEmpty() && !canPreSubmitRecipient(reservation)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation chưa ở giai đoạn có thể tiếp nhận thông tin hoàn tiền");
        }

        String accountNumber = normalizeAccountNumber(request.getAccountNumber());
        String accountHolderName = normalizeText(request.getAccountHolderName());
        String bankCode = VietQrBankCatalog.canonicalCode(normalizeText(request.getBankCode()));
        String bankName = normalizeText(request.getBankName());

        List<RefundRecipient> previous = recipientRepository
                .findByReservationIdAndStatusInForUpdate(reservationId, EDITABLE_RECIPIENT_STATUSES);
        RefundRecipient unchanged = previous.stream()
                .filter(item -> sameRecipient(item, bankCode, bankName, accountNumber, accountHolderName))
                .findFirst()
                .orElse(null);
        if (unchanged != null) {
            attachRecipient(manualRefunds, unchanged);
            return masked(unchanged, false);
        }
        previous.forEach(item -> item.setStatus(RefundRecipientStatus.SUPERSEDED));
        recipientRepository.saveAll(previous);

        RefundRecipient recipient = recipientRepository.save(RefundRecipient.builder()
                .reservation(reservation)
                .paymentRefund(manualRefunds.size() == 1 ? manualRefunds.get(0) : null)
                .method(RefundRecipientMethod.BANK_ACCOUNT)
                .bankCode(bankCode)
                .bankName(bankName)
                .accountNumberCiphertext(cipher.encrypt(accountNumber))
                .accountNumberLast4(last4(accountNumber))
                .accountHolderCiphertext(cipher.encrypt(accountHolderName))
                .encryptionKeyVersion(RefundDataCipher.KEY_VERSION)
                .status(RefundRecipientStatus.SUBMITTED)
                .providedBy(operator(currentUser, reservation))
                .providedAt(LocalDateTime.now())
                .build());

        attachRecipient(manualRefunds, recipient);
        return masked(recipient, false);
    }

    @Transactional
    public RefundRecipientResponse submitForRefund(
            String refundId,
            RefundRecipientRequest request,
            User currentUser,
            String guestToken) {
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu hoàn tiền"));
        Reservation reservation = reservationOfNullable(refund);
        if (reservation != null) {
            ensureCanAccess(currentUser, reservation, guestToken);
        } else if (!isStaffOrAdmin(currentUser)) {
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER,
                    "Refund unmatched chỉ Staff/Admin được cập nhật người nhận");
        }
        if (refund.getChannel() != RefundChannel.MANUAL_BANK_TRANSFER
                || !EDITABLE_REFUND_STATUSES.contains(refund.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Yêu cầu hoàn không ở trạng thái nhận thông tin ngân hàng");
        }

        String accountNumber = normalizeAccountNumber(request.getAccountNumber());
        String accountHolderName = normalizeText(request.getAccountHolderName());
        String bankCode = VietQrBankCatalog.canonicalCode(normalizeText(request.getBankCode()));
        String bankName = normalizeText(request.getBankName());

        RefundRecipient current = refund.getRecipient();
        if (current != null
                && sameRecipient(current, bankCode, bankName, accountNumber, accountHolderName)) {
            if (current.getPaymentRefund() == null) {
                current.setPaymentRefund(refund);
                recipientRepository.save(current);
            }
            return masked(current, false);
        }
        if (current != null && current.getPaymentRefund() != null
                && java.util.Objects.equals(current.getPaymentRefund().getId(), refund.getId())) {
            current.setStatus(RefundRecipientStatus.SUPERSEDED);
            recipientRepository.save(current);
        }

        RefundRecipient recipient = recipientRepository.save(RefundRecipient.builder()
                .reservation(reservation)
                .paymentRefund(refund)
                .method(RefundRecipientMethod.BANK_ACCOUNT)
                .bankCode(bankCode)
                .bankName(bankName)
                .accountNumberCiphertext(cipher.encrypt(accountNumber))
                .accountNumberLast4(last4(accountNumber))
                .accountHolderCiphertext(cipher.encrypt(accountHolderName))
                .encryptionKeyVersion(RefundDataCipher.KEY_VERSION)
                .status(RefundRecipientStatus.SUBMITTED)
                .providedBy(operator(currentUser, reservation))
                .providedAt(LocalDateTime.now())
                .build());
        refund.setRecipient(recipient);
        refund.setStatus(RefundStatus.REQUESTED);
        if (refund.getManualFallbackAvailableAtUtc() == null) {
            refund.setManualFallbackAvailableAtUtc(newManualFallbackAvailableAt());
        }
        refund.setMessage("Đã nhận thông tin ngân hàng; chờ SePay xác nhận tự động giao dịch tiền ra");
        refundRepository.save(refund);
        return masked(recipient, false);
    }

    @Transactional(readOnly = true)
    public RefundRecipientResponse getMaskedForRefund(
            String refundId,
            User currentUser,
            String guestToken) {
        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu hoàn tiền"));
        Reservation reservation = reservationOfNullable(refund);
        if (reservation != null) {
            ensureCanAccess(currentUser, reservation, guestToken);
        } else if (!isStaffOrAdmin(currentUser)) {
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER,
                    "Refund unmatched chỉ Staff/Admin được xem người nhận");
        }
        RefundRecipient recipient = refund.getRecipient();
        if (recipient == null) {
            return RefundRecipientResponse.builder()
                    .refundId(refundId)
                    .reservationId(reservation != null ? reservation.getId() : null)
                    .method(RefundRecipientMethod.BANK_ACCOUNT)
                    .required(true)
                    .build();
        }
        return masked(recipient, false);
    }

    @Transactional(readOnly = true)
    public RefundRecipientResponse getMasked(
            Long reservationId,
            User currentUser,
            String guestToken) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccess(currentUser, reservation, guestToken);
        List<PaymentRefund> manualRefunds = refundRepository.findByReservationId(reservationId).stream()
                .filter(refund -> refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER)
                .toList();
        if (manualRefunds.isEmpty() && !canPreSubmitRecipient(reservation)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation chưa ở giai đoạn có thể tiếp nhận thông tin hoàn tiền");
        }
        return recipientRepository
                .findFirstByReservationIdAndStatusInOrderByCreatedAtDesc(
                        reservationId, CURRENT_RECIPIENT_STATUSES)
                .map(recipient -> masked(recipient, false))
                .orElseGet(() -> RefundRecipientResponse.builder()
                        .reservationId(reservationId)
                        .method(RefundRecipientMethod.BANK_ACCOUNT)
                        .required(true)
                        .build());
    }

    private void attachRecipient(List<PaymentRefund> refunds, RefundRecipient recipient) {
        for (PaymentRefund refund : refunds) {
            refund.setRecipient(recipient);
            refund.setStatus(RefundStatus.REQUESTED);
            if (refund.getManualFallbackAvailableAtUtc() == null) {
                refund.setManualFallbackAvailableAtUtc(newManualFallbackAvailableAt());
            }
            refund.setMessage("Đã nhận thông tin ngân hàng; chờ SePay xác nhận tự động giao dịch tiền ra");
        }
        if (refunds.size() == 1 && recipient.getPaymentRefund() == null) {
            recipient.setPaymentRefund(refunds.get(0));
            recipientRepository.save(recipient);
        }
        refundRepository.saveAll(refunds);
    }

    private Instant newManualFallbackAvailableAt() {
        return Instant.now().plusSeconds(
                Math.max(1, sePayConfig.getRefundWebhookTimeoutMinutes()) * 60L);
    }

    private boolean sameRecipient(
            RefundRecipient recipient,
            String bankCode,
            String bankName,
            String accountNumber,
            String accountHolderName) {
        return bankCode.equals(recipient.getBankCode())
                && bankName.equalsIgnoreCase(recipient.getBankName())
                && accountNumber.equals(cipher.decrypt(recipient.getAccountNumberCiphertext()))
                && accountHolderName.equalsIgnoreCase(
                        cipher.decrypt(recipient.getAccountHolderCiphertext()));
    }

    private List<PaymentRefund> editableManualRefunds(Long reservationId) {
        return refundRepository.findByReservationIdAndChannelAndStatusIn(
                reservationId,
                RefundChannel.MANUAL_BANK_TRANSFER,
                EDITABLE_REFUND_STATUSES);
    }

    private boolean canPreSubmitRecipient(Reservation reservation) {
        if (!List.of(ReservationStatus.CANCELLATION_PENDING, ReservationStatus.CHECKED_IN)
                .contains(reservation.getStatus())) {
            return false;
        }
        return transactionRepository.findByReservationId(reservation.getId()).stream()
                .anyMatch(payment -> List.of(PaymentStatus.SUCCESS,
                                PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED)
                        .contains(payment.getStatus()));
    }

    private RefundRecipientResponse masked(RefundRecipient recipient, boolean required) {
        return RefundRecipientResponse.builder()
                .recipientId(recipient.getId())
                .refundId(recipient.getPaymentRefund() != null
                        ? recipient.getPaymentRefund().getId() : null)
                .reservationId(recipient.getReservation() != null
                        ? recipient.getReservation().getId() : null)
                .method(recipient.getMethod())
                .status(recipient.getStatus())
                .bankCode(recipient.getBankCode())
                .bankName(recipient.getBankName())
                .accountNumberMasked("****" + recipient.getAccountNumberLast4())
                .accountHolderNameMasked(RefundDataCipher.maskPersonName(
                        cipher.decrypt(recipient.getAccountHolderCiphertext())))
                .providedAt(recipient.getProvidedAt())
                .required(required)
                .build();
    }

    private Reservation reservationOf(PaymentRefund refund) {
        Reservation reservation = reservationOfNullable(refund);
        if (reservation == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Yêu cầu hoàn chưa liên kết reservation");
        }
        return reservation;
    }

    private Reservation reservationOfNullable(PaymentRefund refund) {
        if (refund.getReservation() != null) return refund.getReservation();
        if (refund.getPaymentTransaction() != null
                && refund.getPaymentTransaction().getReservation() != null) {
            return refund.getPaymentTransaction().getReservation();
        }
        return null;
    }

    private void ensureCanAccess(User currentUser, Reservation reservation, String guestToken) {
        if (currentUser == null) {
            if (hasText(guestToken) && guestToken.equals(reservation.getGuestToken())) return;
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER);
        }
        if (List.of(UserType.ADMIN, UserType.STAFF).contains(currentUser.getType())) return;
        if (reservation.getCustomerProfile() == null
                || reservation.getCustomerProfile().getLinkedUser() == null
                || !reservation.getCustomerProfile().getLinkedUser().getId().equals(currentUser.getId())) {
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER);
        }
    }

    private String operator(User currentUser, Reservation reservation) {
        return currentUser != null && hasText(currentUser.getUsername())
                ? currentUser.getUsername()
                : reservation != null
                ? "guest:" + reservation.getReservationCode()
                : "hotel_system";
    }

    private boolean isStaffOrAdmin(User user) {
        return user != null && List.of(UserType.ADMIN, UserType.STAFF).contains(user.getType());
    }

    private String normalizeAccountNumber(String value) {
        String normalized = normalizeText(value).replace(" ", "").replace("-", "");
        if (!normalized.matches("^[0-9]{6,24}$")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số tài khoản không hợp lệ");
        }
        return normalized;
    }

    private String normalizeText(String value) {
        if (!hasText(value)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thông tin ngân hàng không được để trống");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String last4(String accountNumber) {
        return accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
