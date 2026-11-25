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
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoomFolioPayments {
    private final RoomFolioCollectionRepository collections;
    private final PaymentTransactionRepository transactions;
    private final PaymentRefundRepository refundRows;
    private final PaymentRefundService refunds;

    public record Progress(Long id, RoomFolioCollection.Pick pick, long cash, long qr, String pendingTransactionId, Long pendingAmount) {}

    record Funding(long netPaid, long netSeparate, List<PaymentTransaction> payments, Map<String, Long> available) {}

    Funding funding(Long reservationId, long issuedAmount) {
        var rows = transactions.findByReservationId(reservationId).stream()
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PaymentTransaction::getId)).toList();
        var refundsByPayment = refundRows.findByReservationId(reservationId).stream()
                .filter(r -> r.getPaymentTransaction() != null)
                .collect(Collectors.groupingBy(r -> r.getPaymentTransaction().getId()));
        Map<String, Long> available = new LinkedHashMap<>();
        for (var row : rows) available.put(row.getId(), refunds.getNetPaidAmount(List.of(row),
                refundsByPayment.getOrDefault(row.getId(), List.of())));
        long netPaid = available.values().stream().mapToLong(Long::longValue).sum();
        long tracked = 0;
        if (issuedAmount > 0) {
            for (var issued : collections.findByReservationIdAndClosedAtIsNotNullOrderById(reservationId)) {
                for (var allocation : issued.getSelection().appliedPayments().entrySet()) {
                    tracked += allocation.getValue();
                    available.computeIfPresent(allocation.getKey(), (id, amount) -> Math.max(0, amount - allocation.getValue()));
                }
            }
            // Receipts issued by the original V42 release have no allocation detail. Reserve their
            // untracked credit against the oldest available receipts before assigning new funds.
            long untracked = Math.max(0, issuedAmount - tracked);
            for (var source : available.entrySet()) {
                long used = Math.min(source.getValue(), untracked);
                source.setValue(source.getValue() - used);
                untracked -= used;
                if (untracked == 0) break;
            }
        }
        long unapplied = available.values().stream().mapToLong(Long::longValue).sum();
        return new Funding(netPaid, netPaid - unapplied, rows, available);
    }

    void issue(Long reservationId, Long collectionId, long total, long previouslyIssued) {
        var collection = collections.getReferenceById(collectionId);
        var funding = funding(reservationId, previouslyIssued);
        Map<String, Long> applied = new LinkedHashMap<>();
        // Apply this cashier's tender first, then the available booking credit in receipt order.
        var sources = funding.payments().stream().sorted(Comparator.comparing(row ->
                !collectionId.equals(row.getRoomFolioCollectionId()))).toList();
        long remaining = total;
        for (var row : sources) {
            long amount = Math.min(remaining, funding.available().get(row.getId()));
            if (amount > 0) applied.put(row.getId(), amount);
            remaining -= amount;
            if (remaining == 0) break;
        }
        if (remaining != 0) throw new AppException(ErrorCode.INVALID_REQUEST, "Số tiền khả dụng không đủ để lập phiếu.");
        var pick = collection.getSelection();
        collection.setSelection(new RoomFolioCollection.Pick(pick.slotIds(), pick.selection(), applied));
        collection.setClosedAt(LocalDateTime.now());
    }

    public Progress current(Long reservationId) {
        return collections.findByReservationIdAndClosedAtIsNull(reservationId).map(collection -> {
            var rows = transactions.findByRoomFolioCollectionIdOrderById(collection.getId());
            var reversals = refundRows.findByPaymentTransaction_RoomFolioCollectionId(collection.getId());
            var pending = rows.stream().filter(t -> t.getStatus() == PaymentStatus.PENDING).findFirst();
            return new Progress(collection.getId(), collection.getSelection(),
                    net(rows, reversals, PaymentProvider.CASH), net(rows, reversals, PaymentProvider.SEPAY),
                    pending.map(PaymentTransaction::getId).orElse(null),
                    pending.map(t -> t.getExpectedAmount() != null ? t.getExpectedAmount() : t.getAmount()).orElse(null));
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
