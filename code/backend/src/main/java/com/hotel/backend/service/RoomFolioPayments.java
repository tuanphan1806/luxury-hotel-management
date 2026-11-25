package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomFolioPayments {
    private final RoomFolioCollectionRepository collections;
    private final PaymentTransactionRepository transactions;
    private final PaymentRefundRepository refundRows;
    private final PaymentRefundService refunds;

    public record Progress(Long id, RoomFolioCollection.Pick pick, long cash, long qr, String pendingTransactionId) {}

    public Progress current(Long reservationId) {
        return collections.findByReservationIdAndClosedAtIsNull(reservationId).map(collection -> {
            var rows = transactions.findByRoomFolioCollectionIdOrderById(collection.getId());
            var reversals = refundRows.findByPaymentTransaction_RoomFolioCollectionId(collection.getId());
            var pending = rows.stream().filter(t -> t.getStatus() == PaymentStatus.PENDING).findFirst();
            return new Progress(collection.getId(), collection.getSelection(),
                    net(rows, reversals, PaymentProvider.CASH), net(rows, reversals, PaymentProvider.SEPAY),
                    pending.map(PaymentTransaction::getId).orElse(null));
        }).orElse(null);
    }

    private long net(List<PaymentTransaction> payments, List<PaymentRefund> reversals, PaymentProvider provider) {
        return refunds.getNetPaidAmount(payments.stream().filter(p -> p.getProvider() == provider).toList(),
                reversals.stream().filter(f -> f.getPaymentTransaction().getProvider() == provider).toList());
    }

    public Long start(Long reservationId, RoomFolioCollection.Pick pick) {
        var collection = new RoomFolioCollection();
        collection.setReservationId(reservationId);
        collection.setSelection(pick);
        return collections.save(collection).getId();
    }

    public void checkSelection(Progress progress, RoomFolioCollection.Pick pick) {
        if (progress != null && !progress.pick().equals(pick)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Đơn đang có phiếu thu cho các căn khác hoặc phân bổ khác. Hãy mở lại phiếu đang xử lý.");
        }
    }

    public void close(Long collectionId) {
        collections.getReferenceById(collectionId).setClosedAt(LocalDateTime.now());
    }
}
