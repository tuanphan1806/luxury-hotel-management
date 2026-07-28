package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.constant.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.BusinessDayCloseRepository;
import com.hotel.backend.repository.FinancialJournalEntryRepository;
import com.hotel.backend.repository.FinancialJournalLineRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Canonical write boundary for the compact operational journal.
 *
 * <p>Every caller invokes this service inside the same transaction that first
 * completes its payment, refund or invoice source. A crash therefore commits
 * both source and journal or neither. Source-key uniqueness provides retry
 * idempotency without an eventually-consistent second ledger.</p>
 */
@Service
public class FinancialJournalService {
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CURRENCY = "VND";

    private final FinancialJournalEntryRepository entryRepository;
    private final FinancialJournalLineRepository lineRepository;
    private final BusinessDayCloseRepository closeRepository;
    private final BusinessDayLockService businessDayLockService;
    private final ReservationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public FinancialJournalService(
            FinancialJournalEntryRepository entryRepository,
            FinancialJournalLineRepository lineRepository,
            BusinessDayCloseRepository closeRepository,
            BusinessDayLockService businessDayLockService,
            ReservationAuditService auditService,
            ObjectMapper objectMapper) {
        this(entryRepository, lineRepository, closeRepository, businessDayLockService, auditService,
                objectMapper, Clock.systemUTC());
    }

    FinancialJournalService(
            FinancialJournalEntryRepository entryRepository,
            FinancialJournalLineRepository lineRepository,
            BusinessDayCloseRepository closeRepository,
            BusinessDayLockService businessDayLockService,
            ReservationAuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.closeRepository = closeRepository;
        this.businessDayLockService = businessDayLockService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** CASH source without a provider-event reclassification path. */
    @Transactional
    public FinancialJournalEntry postPayment(PaymentTransaction payment) {
        return postPaymentInternal(null, payment);
    }

    /** SePay source; reclassifies a previously observed unmatched bank event when necessary. */
    @Transactional
    public FinancialJournalEntry postSePayPayment(
            PaymentProviderEvent event,
            PaymentTransaction payment) {
        if (event == null) return postPaymentInternal(null, payment);
        boolean cashAlreadyObserved = entryRepository
                .existsBySourceTypeAndSourceIdAndPostingKind(
                        FinancialSourceType.PAYMENT_PROVIDER_EVENT,
                        event.getId(),
                        FinancialPostingKind.PROVIDER_CASH_OBSERVED);
        return cashAlreadyObserved
                ? postPaymentAllocation(event, payment)
                : postPaymentInternal(event, payment);
    }

    /** Records real SePay bank movement even when business matching is unresolved. */
    @Transactional
    public FinancialJournalEntry postUnmatchedProviderMovement(PaymentProviderEvent event) {
        if (event == null || event.getId() == null || event.getAmount() == null
                || event.getAmount() <= 0L) return null;
        String transferType = value(event.getTransferType()).toLowerCase(Locale.ROOT);
        if (!List.of("in", "out").contains(transferType)) return null;

        FinancialSourceType sourceType = FinancialSourceType.PAYMENT_PROVIDER_EVENT;
        FinancialPostingKind postingKind = FinancialPostingKind.PROVIDER_CASH_OBSERVED;
        FinancialJournalEntry existing = existing(sourceType, event.getId(), postingKind);
        if (existing != null) return existing;

        BigDecimal amount = money(event.getAmount(), "provider event amount");
        List<LineDraft> lines = transferType.equals("in")
                ? List.of(
                        debit(FinancialAccountCode.BANK_SEPAY, amount, "SePay tiền vào chưa phân loại"),
                        credit(FinancialAccountCode.UNRECONCILED_FUNDS, amount, "Chờ đối soát nguồn tiền vào"))
                : List.of(
                        debit(FinancialAccountCode.UNRECONCILED_FUNDS, amount, "Chờ đối soát nguồn tiền ra"),
                        credit(FinancialAccountCode.BANK_SEPAY, amount, "SePay tiền ra chưa phân loại"));
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("providerEventId", event.getProviderEventId());
        detail.put("providerReference", event.getProviderReference());
        detail.put("transferType", transferType);
        detail.put("amount", event.getAmount());
        detail.put("classification", "UNRECONCILED");
        Instant occurredAt = firstNonNull(
                event.getProviderOccurredAtUtc(), event.getReceivedAtUtc(), clock.instant());
        return createEntry(new EntryDraft(
                sourceType, event.getId(), postingKind,
                occurredAt, true,
                transferType.equals("in")
                        ? "Ghi nhận SePay tiền vào chưa ghép"
                        : "Ghi nhận SePay tiền ra chưa ghép",
                null, null, null, null, event, detail, lines));
    }

    @Transactional
    public FinancialJournalEntry postRefund(PaymentRefund refund) {
        requireRefund(refund);
        PaymentProviderEvent completionEvent = refund.getCompletionProviderEvent();
        boolean cashAlreadyObserved = completionEvent != null
                && entryRepository.existsBySourceTypeAndSourceIdAndPostingKind(
                        FinancialSourceType.PAYMENT_PROVIDER_EVENT,
                        completionEvent.getId(),
                        FinancialPostingKind.PROVIDER_CASH_OBSERVED);
        FinancialSourceType sourceType = cashAlreadyObserved
                ? FinancialSourceType.PAYMENT_PROVIDER_EVENT
                : FinancialSourceType.PAYMENT_REFUND;
        String sourceId = cashAlreadyObserved ? completionEvent.getId() : refund.getId();
        FinancialPostingKind postingKind = cashAlreadyObserved
                ? FinancialPostingKind.REFUND_ALLOCATED
                : FinancialPostingKind.REFUND_COMPLETED;
        FinancialJournalEntry existing = existing(sourceType, sourceId, postingKind);
        if (existing != null) return existing;

        BigDecimal amount = money(
                refund.getActualRefundAmount() != null
                        ? refund.getActualRefundAmount() : refund.getAmount(),
                "refund amount");
        FinancialAccountCode liability = refundLiabilityAccount(refund.getSourceType());
        FinancialAccountCode cashAccount = cashAlreadyObserved
                ? FinancialAccountCode.UNRECONCILED_FUNDS
                : refund.getChannel() == RefundChannel.CASH_AT_COUNTER
                    ? FinancialAccountCode.CASH_ON_HAND
                    : FinancialAccountCode.BANK_SEPAY;
        List<LineDraft> lines = liability == cashAccount
                ? List.of()
                : List.of(
                        debit(liability, amount, "Giảm nghĩa vụ hoàn cho khách"),
                        credit(cashAccount, amount, cashAlreadyObserved
                                ? "Phân loại giao dịch SePay tiền ra đã quan sát"
                                : "Tiền đã hoàn cho khách"));
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("refundId", refund.getId());
        detail.put("refundCode", refund.getRefundCode());
        detail.put("amount", amount.longValueExact());
        detail.put("channel", refund.getChannel().name());
        detail.put("sourceType", refund.getSourceType().canonicalName());
        detail.put("cashAlreadyObserved", cashAlreadyObserved);
        if (liability == cashAccount) detail.put("classificationNoOp", true);
        Reservation reservation = refund.getReservation() != null
                ? refund.getReservation()
                : refund.getPaymentTransaction() != null
                    ? refund.getPaymentTransaction().getReservation() : null;
        return createEntry(new EntryDraft(
                sourceType, sourceId, postingKind,
                firstNonNull(refund.getCompletedAtUtc(), clock.instant()),
                completionEvent != null,
                "Hoàn tiền hoàn tất " + refund.getRefundCode(),
                reservation,
                refund.getPaymentTransaction(),
                refund,
                null,
                completionEvent,
                detail,
                lines));
    }

    @Transactional
    public FinancialJournalEntry postInvoice(ReservationInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            throw invalid("Invoice chưa được lưu trước khi journal");
        }
        FinancialSourceType sourceType = FinancialSourceType.RESERVATION_INVOICE;
        FinancialPostingKind postingKind = FinancialPostingKind.INVOICE_RECOGNIZED;
        String sourceId = String.valueOf(invoice.getId());
        FinancialJournalEntry existing = existing(sourceType, sourceId, postingKind);
        if (existing != null) return existing;

        BigDecimal total = money(invoice.getTotalAmount(), "invoice total");
        BigDecimal discount = nonNegative(invoice.getDiscountAmount(), "discount");
        BigDecimal tax = nonNegative(invoice.getTaxAmount(), "tax");
        BigDecimal service = nonNegative(invoice.getAddOnServiceAmount(), "add-on service")
                .add(nonNegative(invoice.getAdditionalFee(), "additional fee"));
        BigDecimal room = total.add(discount).subtract(tax).subtract(service);
        if (room.signum() < 0) {
            throw invalid("Invoice breakdown makes room revenue negative");
        }

        List<LineDraft> lines = new ArrayList<>();
        addIfPositive(lines, debit(FinancialAccountCode.CUSTOMER_DEPOSIT, total,
                "Kết chuyển tiền khách đã thu"));
        addIfPositive(lines, debit(FinancialAccountCode.DISCOUNT, discount,
                "Chiết khấu trên hóa đơn"));
        addIfPositive(lines, credit(FinancialAccountCode.ROOM_REVENUE, room,
                "Doanh thu phòng đã ghi nhận"));
        addIfPositive(lines, credit(FinancialAccountCode.SERVICE_REVENUE, service,
                "Doanh thu dịch vụ/phụ phí đã ghi nhận"));
        addIfPositive(lines, credit(FinancialAccountCode.TAX_PAYABLE, tax,
                "Thuế phải nộp"));

        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("invoiceId", invoice.getId());
        detail.put("invoiceNumber", invoice.getInvoiceNumber());
        detail.put("totalAmount", total.longValueExact());
        detail.put("roomRevenue", room.longValueExact());
        detail.put("serviceRevenue", service.longValueExact());
        detail.put("discountAmount", discount.longValueExact());
        detail.put("taxAmount", tax.longValueExact());
        detail.put("snapshotHash", invoice.getSnapshotHash());
        return createEntry(new EntryDraft(
                sourceType, sourceId, postingKind,
                firstNonNull(invoice.getIssuedAtUtc(), invoice.getCreatedAtUtc(), clock.instant()),
                false,
                "Ghi nhận doanh thu hóa đơn " + invoice.getInvoiceNumber(),
                invoice.getReservation(), null, null, invoice, null, detail, List.copyOf(lines)));
    }

    private FinancialJournalEntry postPaymentInternal(
            PaymentProviderEvent event,
            PaymentTransaction payment) {
        requirePayment(payment);
        FinancialSourceType sourceType = FinancialSourceType.PAYMENT_TRANSACTION;
        FinancialPostingKind postingKind = FinancialPostingKind.PAYMENT_RECEIVED;
        FinancialJournalEntry existing = existing(sourceType, payment.getId(), postingKind);
        if (existing != null) return existing;
        PaymentAllocation allocation = paymentAllocation(payment);
        FinancialAccountCode cashAccount = payment.getProvider() == PaymentProvider.CASH
                ? FinancialAccountCode.CASH_ON_HAND : FinancialAccountCode.BANK_SEPAY;
        List<LineDraft> lines = new ArrayList<>();
        addIfPositive(lines, debit(cashAccount, allocation.received(), "Tiền khách đã thanh toán"));
        addIfPositive(lines, credit(FinancialAccountCode.CUSTOMER_DEPOSIT,
                allocation.accepted(), "Tiền phân bổ cho reservation"));
        addIfPositive(lines, credit(FinancialAccountCode.REFUND_PAYABLE,
                allocation.refundRequired(), "Tiền phải hoàn cho khách"));
        return createEntry(paymentDraft(
                sourceType, payment.getId(), postingKind, event, payment,
                "Ghi nhận thanh toán " + payment.getTxnRef(), lines, allocation, false));
    }

    private FinancialJournalEntry postPaymentAllocation(
            PaymentProviderEvent event,
            PaymentTransaction payment) {
        requirePayment(payment);
        FinancialSourceType sourceType = FinancialSourceType.PAYMENT_PROVIDER_EVENT;
        FinancialPostingKind postingKind = FinancialPostingKind.PAYMENT_ALLOCATED;
        FinancialJournalEntry existing = existing(sourceType, event.getId(), postingKind);
        if (existing != null) return existing;
        PaymentAllocation allocation = paymentAllocation(payment);
        List<LineDraft> lines = new ArrayList<>();
        addIfPositive(lines, debit(FinancialAccountCode.UNRECONCILED_FUNDS,
                allocation.received(), "Phân loại tiền SePay đã quan sát"));
        addIfPositive(lines, credit(FinancialAccountCode.CUSTOMER_DEPOSIT,
                allocation.accepted(), "Tiền phân bổ cho reservation"));
        addIfPositive(lines, credit(FinancialAccountCode.REFUND_PAYABLE,
                allocation.refundRequired(), "Phần tiền vẫn phải hoàn"));
        return createEntry(paymentDraft(
                sourceType, event.getId(), postingKind, event, payment,
                "Ghép giao dịch SePay vào payment " + payment.getTxnRef(),
                lines, allocation, true));
    }

    private EntryDraft paymentDraft(
            FinancialSourceType sourceType,
            String sourceId,
            FinancialPostingKind postingKind,
            PaymentProviderEvent event,
            PaymentTransaction payment,
            String description,
            List<LineDraft> lines,
            PaymentAllocation allocation,
            boolean cashAlreadyObserved) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("paymentTransactionId", payment.getId());
        detail.put("txnRef", payment.getTxnRef());
        detail.put("provider", payment.getProvider().name());
        detail.put("purpose", payment.getPurpose().name());
        detail.put("receivedAmount", allocation.received().longValueExact());
        detail.put("acceptedAmount", allocation.accepted().longValueExact());
        detail.put("refundRequiredAmount", allocation.refundRequired().longValueExact());
        detail.put("cashAlreadyObserved", cashAlreadyObserved);
        return new EntryDraft(
                sourceType, sourceId, postingKind,
                firstNonNull(payment.getPaidAtUtc(), clock.instant()),
                event != null,
                description,
                payment.getReservation(), payment, null, null, event, detail,
                List.copyOf(lines));
    }

    private FinancialJournalEntry createEntry(EntryDraft draft) {
        FinancialJournalEntry existing = existing(
                draft.sourceType(), draft.sourceId(), draft.postingKind());
        if (existing != null) return existing;
        PostingDate postingDate = resolvePostingDate(draft.occurredAtUtc(), draft.providerDriven());
        // A concurrent retry may have posted the same source while this call was
        // waiting for the per-business-date mutex. Recheck under that mutex so
        // retries return the canonical entry instead of surfacing a unique-key
        // violation to the caller.
        existing = existing(draft.sourceType(), draft.sourceId(), draft.postingKind());
        if (existing != null) return existing;
        BigDecimal debitTotal = draft.lines().stream()
                .filter(line -> line.direction() == FinancialEntryDirection.DEBIT)
                .map(LineDraft::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = draft.lines().stream()
                .filter(line -> line.direction() == FinancialEntryDirection.CREDIT)
                .map(LineDraft::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw invalid("Journal debit/credit do not balance");
        }
        Instant now = clock.instant();
        FinancialJournalEntry entry = entryRepository.saveAndFlush(
                FinancialJournalEntry.builder()
                        .entryNumber(generateEntryNumber(postingDate.businessDate()))
                        .businessDate(postingDate.businessDate())
                        .originalBusinessDate(postingDate.originalBusinessDate())
                        .occurredAtUtc(draft.occurredAtUtc())
                        .postedAtUtc(now)
                        .sourceType(draft.sourceType())
                        .sourceId(draft.sourceId())
                        .postingKind(draft.postingKind())
                        .currency(CURRENCY)
                        .description(draft.description())
                        .latePosting(postingDate.latePosting())
                        .totalDebit(debitTotal)
                        .totalCredit(creditTotal)
                        .detailJson(draft.detail())
                        .reservation(draft.reservation())
                        .paymentTransaction(draft.payment())
                        .refund(draft.refund())
                        .invoice(draft.invoice())
                        .providerEvent(draft.providerEvent())
                        .createdAtUtc(now)
                        .build());
        List<FinancialJournalLine> persistedLines = new ArrayList<>();
        int lineNumber = 1;
        for (LineDraft line : draft.lines()) {
            if (line.amount().signum() <= 0) continue;
            persistedLines.add(FinancialJournalLine.builder()
                    .journalEntry(entry)
                    .lineNumber(lineNumber++)
                    .accountCode(line.account())
                    .direction(line.direction())
                    .amount(line.amount())
                    .description(line.description())
                    .createdAtUtc(now)
                    .build());
        }
        if (!persistedLines.isEmpty()) lineRepository.saveAllAndFlush(persistedLines);
        if (postingDate.latePosting()) {
            auditService.recordSystem(
                    draft.reservation(),
                    "FINANCIAL_JOURNAL_ENTRY",
                    entry.getEntryNumber(),
                    ReservationAuditAction.FINANCIAL_LATE_POSTING,
                    "Giao dịch ngân hàng đến sau khi ngày nghiệp vụ gốc đã khóa",
                    null,
                    null,
                    java.util.Map.of(
                            "entryNumber", entry.getEntryNumber(),
                            "originalBusinessDate", postingDate.originalBusinessDate().toString(),
                            "postedBusinessDate", postingDate.businessDate().toString(),
                            "sourceType", draft.sourceType().name(),
                            "sourceId", draft.sourceId()),
                    null,
                    "FINANCIAL_LATE_POSTING:" + entry.getEntryNumber());
        }
        return entry;
    }

    private PostingDate resolvePostingDate(Instant occurredAtUtc, boolean providerDriven) {
        LocalDate original = LocalDate.ofInstant(occurredAtUtc, HOTEL_ZONE);
        lockBusinessDate(original);
        if (!closeRepository.existsByBusinessDate(original)) {
            return new PostingDate(original, original, false);
        }
        if (!providerDriven) {
            throw new AppException(ErrorCode.BUSINESS_DAY_CLOSED);
        }
        LocalDate current = LocalDate.now(clock.withZone(HOTEL_ZONE));
        lockBusinessDate(current);
        if (closeRepository.existsByBusinessDate(current)) {
            throw new AppException(ErrorCode.BUSINESS_DAY_CLOSED,
                    "Ngày gốc và ngày hiện tại đều đã khóa; cần xử lý late posting ở ngày mở kế tiếp");
        }
        return new PostingDate(current, original, true);
    }

    private void lockBusinessDate(LocalDate businessDate) {
        businessDayLockService.lock(businessDate);
    }

    private PaymentAllocation paymentAllocation(PaymentTransaction payment) {
        BigDecimal received = money(
                payment.getReceivedAmount() != null
                        ? payment.getReceivedAmount() : payment.getAmount(),
                "received amount");
        BigDecimal accepted = nonNegative(
                payment.getAcceptedAmount() != null
                        ? BigDecimal.valueOf(payment.getAcceptedAmount())
                        : BigDecimal.valueOf(payment.getAmount()),
                "accepted amount");
        BigDecimal refundRequired = nonNegative(
                payment.getRefundRequiredAmount() != null
                        ? BigDecimal.valueOf(payment.getRefundRequiredAmount())
                        : received.subtract(accepted),
                "refund required amount");
        if (received.compareTo(accepted.add(refundRequired)) != 0) {
            throw invalid("receivedAmount must equal acceptedAmount + refundRequiredAmount");
        }
        return new PaymentAllocation(received, accepted, refundRequired);
    }

    private FinancialAccountCode refundLiabilityAccount(RefundSourceType sourceType) {
        if (sourceType == null) return FinancialAccountCode.CUSTOMER_DEPOSIT;
        return switch (sourceType) {
            case UNACCEPTED_PAYMENT, ADDITIONAL_TRANSFER, CHECKOUT_OVERPAYMENT ->
                    FinancialAccountCode.REFUND_PAYABLE;
            case UNMATCHED_TRANSFER -> FinancialAccountCode.UNRECONCILED_FUNDS;
            case ACCEPTED_ALLOCATION, MANUAL_RESERVATION, LEGACY ->
                    FinancialAccountCode.CUSTOMER_DEPOSIT;
        };
    }

    private void requirePayment(PaymentTransaction payment) {
        if (payment == null || payment.getId() == null
                || payment.getStatus() != PaymentStatus.SUCCESS
                || payment.getProvider() == null || payment.getPurpose() == null) {
            throw invalid("Payment is not a canonical SUCCESS source");
        }
    }

    private void requireRefund(PaymentRefund refund) {
        if (refund == null || refund.getId() == null
                || refund.getStatus() != RefundStatus.SUCCEEDED
                || refund.getChannel() == null || refund.getSourceType() == null) {
            throw invalid("Refund is not a canonical SUCCEEDED source");
        }
    }

    private FinancialJournalEntry existing(
            FinancialSourceType sourceType,
            String sourceId,
            FinancialPostingKind postingKind) {
        return entryRepository.findBySourceTypeAndSourceIdAndPostingKind(
                sourceType, sourceId, postingKind).orElse(null);
    }

    private BigDecimal money(Long amount, String field) {
        return money(amount == null ? null : BigDecimal.valueOf(amount), field);
    }

    private BigDecimal money(BigDecimal amount, String field) {
        BigDecimal value = integer(amount, field);
        if (value.signum() <= 0) throw invalid(field + " must be greater than zero");
        return value;
    }

    private BigDecimal nonNegative(BigDecimal amount, String field) {
        BigDecimal value = integer(amount == null ? BigDecimal.ZERO : amount, field);
        if (value.signum() < 0) throw invalid(field + " cannot be negative");
        return value;
    }

    private BigDecimal integer(BigDecimal amount, String field) {
        if (amount == null) throw invalid(field + " is required");
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid(field + " must be an integer VND amount");
        }
    }

    private AppException invalid(String message) {
        return new AppException(ErrorCode.FINANCIAL_POSTING_INVALID, message);
    }

    private LineDraft debit(FinancialAccountCode account, BigDecimal amount, String description) {
        return new LineDraft(account, FinancialEntryDirection.DEBIT, amount, description);
    }

    private LineDraft credit(FinancialAccountCode account, BigDecimal amount, String description) {
        return new LineDraft(account, FinancialEntryDirection.CREDIT, amount, description);
    }

    private void addIfPositive(List<LineDraft> lines, LineDraft line) {
        if (line.amount().signum() > 0) lines.add(line);
    }

    private String generateEntryNumber(LocalDate businessDate) {
        return "FJ-" + businessDate.toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }

    private record PaymentAllocation(
            BigDecimal received,
            BigDecimal accepted,
            BigDecimal refundRequired) {}

    private record PostingDate(
            LocalDate businessDate,
            LocalDate originalBusinessDate,
            boolean latePosting) {}

    private record LineDraft(
            FinancialAccountCode account,
            FinancialEntryDirection direction,
            BigDecimal amount,
            String description) {}

    private record EntryDraft(
            FinancialSourceType sourceType,
            String sourceId,
            FinancialPostingKind postingKind,
            Instant occurredAtUtc,
            boolean providerDriven,
            String description,
            Reservation reservation,
            PaymentTransaction payment,
            PaymentRefund refund,
            ReservationInvoice invoice,
            PaymentProviderEvent providerEvent,
            ObjectNode detail,
            List<LineDraft> lines) {}
}
