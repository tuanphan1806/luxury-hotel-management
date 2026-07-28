package com.hotel.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.CloseBusinessDayRequest;
import com.hotel.backend.dto.response.BusinessDayCloseResponse;
import com.hotel.backend.dto.response.FinancialJournalEntryResponse;
import com.hotel.backend.dto.response.FinancialJournalLineResponse;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class BusinessDayCloseService {
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final EnumSet<CashierShiftStatus> ACTIVE_SHIFT_STATUSES =
            EnumSet.of(CashierShiftStatus.OPEN, CashierShiftStatus.CLOSING);
    private static final EnumSet<PaymentProviderEventStatus> UNRESOLVED_EVENT_STATUSES =
            EnumSet.of(PaymentProviderEventStatus.RECEIVED,
                    PaymentProviderEventStatus.PROCESSING,
                    PaymentProviderEventStatus.FAILED_RETRYABLE,
                    PaymentProviderEventStatus.REVIEW_REQUIRED);
    private static final EnumSet<RefundStatus> OUTSTANDING_REFUND_STATUSES =
            EnumSet.of(RefundStatus.AWAITING_CUSTOMER_INFO,
                    RefundStatus.READY_FOR_MANUAL_TRANSFER,
                    RefundStatus.REQUESTED,
                    RefundStatus.PROCESSING,
                    RefundStatus.FAILED,
                    RefundStatus.MANUAL_REVIEW);
    private static final List<PaymentStatus> FINANCIALLY_COMPLETED_PAYMENT_STATUSES =
            List.of(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);

    private final BusinessDayCloseRepository closeRepository;
    private final BusinessDayLockService businessDayLockService;
    private final FinancialJournalEntryRepository entryRepository;
    private final FinancialJournalLineRepository lineRepository;
    private final CashierShiftRepository shiftRepository;
    private final PaymentProviderEventRepository providerEventRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final ReservationInvoiceRepository invoiceRepository;
    private final ReservationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public BusinessDayCloseService(
            BusinessDayCloseRepository closeRepository,
            BusinessDayLockService businessDayLockService,
            FinancialJournalEntryRepository entryRepository,
            FinancialJournalLineRepository lineRepository,
            CashierShiftRepository shiftRepository,
            PaymentProviderEventRepository providerEventRepository,
            PaymentTransactionRepository paymentRepository,
            PaymentRefundRepository refundRepository,
            ReservationInvoiceRepository invoiceRepository,
            ReservationAuditService auditService,
            ObjectMapper objectMapper) {
        this(closeRepository, businessDayLockService, entryRepository, lineRepository,
                shiftRepository, providerEventRepository, paymentRepository,
                refundRepository, invoiceRepository, auditService, objectMapper,
                Clock.systemUTC());
    }

    BusinessDayCloseService(
            BusinessDayCloseRepository closeRepository,
            BusinessDayLockService businessDayLockService,
            FinancialJournalEntryRepository entryRepository,
            FinancialJournalLineRepository lineRepository,
            CashierShiftRepository shiftRepository,
            PaymentProviderEventRepository providerEventRepository,
            PaymentTransactionRepository paymentRepository,
            PaymentRefundRepository refundRepository,
            ReservationInvoiceRepository invoiceRepository,
            ReservationAuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.closeRepository = closeRepository;
        this.businessDayLockService = businessDayLockService;
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.shiftRepository = shiftRepository;
        this.providerEventRepository = providerEventRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.invoiceRepository = invoiceRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BusinessDayCloseResponse preview(LocalDate businessDate, User currentUser) {
        requireAdmin(currentUser);
        requireDate(businessDate);
        return closeRepository.findByBusinessDate(businessDate)
                .map(this::fromClosed)
                .orElseGet(() -> calculate(businessDate));
    }

    @Transactional
    public BusinessDayCloseResponse close(
            LocalDate businessDate,
            CloseBusinessDayRequest request,
            User currentUser) {
        requireAdmin(currentUser);
        requireDate(businessDate);
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        if (!businessDate.isBefore(today)) {
            throw new AppException(ErrorCode.BUSINESS_DAY_INVALID);
        }

        businessDayLockService.lock(businessDate);
        BusinessDayClose existing = closeRepository.findByBusinessDate(businessDate).orElse(null);
        if (existing != null) return fromClosed(existing);

        BusinessDayCloseResponse preview = calculate(businessDate);
        if (!preview.closeAllowed()) {
            throw new AppException(
                    ErrorCode.BUSINESS_DAY_CLOSE_BLOCKED,
                    "Không thể khóa ngày: " + String.join(", ", preview.blockers()));
        }

        Instant now = clock.instant();
        ObjectNode summary = summaryJson(preview);
        String summaryText;
        try {
            summaryText = objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể tạo snapshot khóa ngày", exception);
        }
        BusinessDayClose closed = closeRepository.saveAndFlush(BusinessDayClose.builder()
                .businessDate(businessDate)
                .status(BusinessDayCloseStatus.CLOSED)
                .closedBy(currentUser)
                .closedByName(displayName(currentUser))
                .closedByRole(currentUser.getType().name())
                .closedAtUtc(now)
                .journalEntryCount(preview.journalEntryCount())
                .totalDebit(preview.totalDebit())
                .totalCredit(preview.totalCredit())
                .paymentReceivedAmount(preview.paymentReceivedAmount())
                .refundCompletedAmount(preview.refundCompletedAmount())
                .recognizedRevenueAmount(preview.recognizedRevenueAmount())
                .pendingRefundPayableAmount(preview.pendingRefundPayableAmount())
                .cashVarianceAmount(preview.cashVarianceAmount())
                .summaryJson(summary)
                .summaryHash(sha256(summaryText))
                .note(trimToNull(request != null ? request.note() : null))
                .createdAtUtc(now)
                .build());
        auditService.recordTargetForUser(
                currentUser,
                "BUSINESS_DAY",
                businessDate.toString(),
                ReservationAuditAction.BUSINESS_DAY_CLOSED,
                "Khóa ngày nghiệp vụ " + businessDate,
                Map.of(
                        "journalEntryCount", closed.getJournalEntryCount(),
                        "totalDebit", closed.getTotalDebit(),
                        "totalCredit", closed.getTotalCredit(),
                        "summaryHash", closed.getSummaryHash()));
        return fromClosed(closed);
    }

    @Transactional(readOnly = true)
    public Page<BusinessDayCloseResponse> list(Pageable pageable, User currentUser) {
        requireAdmin(currentUser);
        return closeRepository.findAllByOrderByBusinessDateDesc(pageable).map(this::fromClosed);
    }

    @Transactional(readOnly = true)
    public Page<FinancialJournalEntryResponse> journal(
            LocalDate businessDate,
            Pageable pageable,
            User currentUser) {
        requireAdmin(currentUser);
        requireDate(businessDate);
        return entryRepository.findAllByBusinessDate(businessDate, pageable)
                .map(this::toJournalResponse);
    }

    private BusinessDayCloseResponse calculate(LocalDate businessDate) {
        Instant from = businessDate.atStartOfDay(HOTEL_ZONE).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(HOTEL_ZONE).toInstant();
        List<FinancialJournalEntry> entries =
                entryRepository.findAllByBusinessDateOrderByPostedAtUtcAscIdAsc(businessDate);
        List<FinancialJournalLine> lines =
                lineRepository.findAllByJournalEntryBusinessDate(businessDate);
        BigDecimal totalDebit = entries.stream().map(FinancialJournalEntry::getTotalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entries.stream().map(FinancialJournalEntry::getTotalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long unbalancedCount = entries.stream()
                .filter(entry -> entry.getTotalDebit().compareTo(entry.getTotalCredit()) != 0)
                .count();

        BigDecimal paymentReceived = sumLines(
                lines,
                FinancialEntryDirection.DEBIT,
                EnumSet.of(FinancialAccountCode.CASH_ON_HAND, FinancialAccountCode.BANK_SEPAY));
        BigDecimal refundCompleted = sumLines(
                lines,
                FinancialEntryDirection.CREDIT,
                EnumSet.of(FinancialAccountCode.CASH_ON_HAND, FinancialAccountCode.BANK_SEPAY));
        BigDecimal recognizedRevenue = sumLines(
                lines,
                FinancialEntryDirection.CREDIT,
                EnumSet.of(FinancialAccountCode.ROOM_REVENUE, FinancialAccountCode.SERVICE_REVENUE))
                .subtract(sumLines(lines, FinancialEntryDirection.DEBIT,
                        EnumSet.of(FinancialAccountCode.DISCOUNT)));
        BigDecimal unreconciled = sumLines(
                lines,
                FinancialEntryDirection.CREDIT,
                EnumSet.of(FinancialAccountCode.UNRECONCILED_FUNDS))
                .subtract(sumLines(lines, FinancialEntryDirection.DEBIT,
                        EnumSet.of(FinancialAccountCode.UNRECONCILED_FUNDS)));

        long openShiftCount = shiftRepository.countByBusinessDateAndStatusIn(
                businessDate, ACTIVE_SHIFT_STATUSES);
        List<CashierShift> shifts = shiftRepository.findAllByBusinessDate(businessDate);
        BigDecimal cashVariance = shifts.stream()
                .map(CashierShift::getVarianceAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long unresolvedEvents = providerEventRepository.countUnresolvedInRange(
                UNRESOLVED_EVENT_STATUSES, from, to);

        List<PaymentTransaction> payments = paymentRepository
                .findByStatusInAndPaidAtUtcGreaterThanEqualAndPaidAtUtcLessThan(
                        FINANCIALLY_COMPLETED_PAYMENT_STATUSES, from, to);
        List<PaymentRefund> refunds = refundRepository
                .findByStatusAndCompletedAtUtcGreaterThanEqualAndCompletedAtUtcLessThan(
                        RefundStatus.SUCCEEDED, from, to);
        List<ReservationInvoice> invoices = invoiceRepository
                .findByIssuedAtUtcGreaterThanEqualAndIssuedAtUtcLessThan(from, to);
        long unpostedPayments = payments.stream()
                .filter(payment -> !entryRepository.existsByPaymentTransactionId(payment.getId()))
                .count();
        long unpostedRefunds = refunds.stream()
                .filter(refund -> !entryRepository.existsByRefundId(refund.getId()))
                .count();
        long unpostedInvoices = invoices.stream()
                .filter(invoice -> !entryRepository.existsByInvoiceId(invoice.getId()))
                .count();
        BigDecimal pendingRefundPayable = refundRepository
                .findOperationalQueue(OUTSTANDING_REFUND_STATUSES).stream()
                .map(refund -> BigDecimal.valueOf(refund.getRequestedAmount() != null
                        ? refund.getRequestedAmount() : refund.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> blockers = new ArrayList<>();
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        if (!businessDate.isBefore(today)) blockers.add("DATE_NOT_FINISHED");
        if (openShiftCount > 0) blockers.add("OPEN_CASHIER_SHIFTS:" + openShiftCount);
        if (unresolvedEvents > 0) blockers.add("UNRESOLVED_PROVIDER_EVENTS:" + unresolvedEvents);
        if (unpostedPayments > 0) blockers.add("UNPOSTED_PAYMENTS:" + unpostedPayments);
        if (unpostedRefunds > 0) blockers.add("UNPOSTED_REFUNDS:" + unpostedRefunds);
        if (unpostedInvoices > 0) blockers.add("UNPOSTED_INVOICES:" + unpostedInvoices);
        if (unbalancedCount > 0) blockers.add("UNBALANCED_JOURNAL:" + unbalancedCount);
        if (unreconciled.signum() != 0) blockers.add("UNRECONCILED_FUNDS:" + unreconciled);

        return new BusinessDayCloseResponse(
                null,
                businessDate,
                null,
                false,
                blockers.isEmpty(),
                List.copyOf(blockers),
                null, null, null, null,
                entries.size(),
                totalDebit,
                totalCredit,
                paymentReceived,
                refundCompleted,
                recognizedRevenue,
                pendingRefundPayable,
                cashVariance,
                openShiftCount,
                unresolvedEvents,
                unpostedPayments,
                unpostedRefunds,
                unpostedInvoices,
                unreconciled,
                null);
    }

    private BusinessDayCloseResponse fromClosed(BusinessDayClose close) {
        return new BusinessDayCloseResponse(
                close.getId(),
                close.getBusinessDate(),
                close.getStatus(),
                true,
                false,
                List.of(),
                close.getClosedBy().getId(),
                close.getClosedByName(),
                close.getClosedByRole(),
                close.getClosedAtUtc(),
                close.getJournalEntryCount(),
                close.getTotalDebit(),
                close.getTotalCredit(),
                close.getPaymentReceivedAmount(),
                close.getRefundCompletedAmount(),
                close.getRecognizedRevenueAmount(),
                close.getPendingRefundPayableAmount(),
                close.getCashVarianceAmount(),
                0, 0, 0, 0, 0,
                BigDecimal.ZERO,
                close.getNote());
    }

    private FinancialJournalEntryResponse toJournalResponse(FinancialJournalEntry entry) {
        List<FinancialJournalLineResponse> lines = lineRepository
                .findAllByJournalEntryIdOrderByLineNumberAsc(entry.getId()).stream()
                .map(line -> new FinancialJournalLineResponse(
                        line.getLineNumber(), line.getAccountCode(), line.getDirection(),
                        line.getAmount(), line.getDescription()))
                .toList();
        return new FinancialJournalEntryResponse(
                entry.getId(), entry.getEntryNumber(), entry.getBusinessDate(),
                entry.getOriginalBusinessDate(), entry.getOccurredAtUtc(),
                entry.getPostedAtUtc(), entry.getSourceType(), entry.getSourceId(),
                entry.getPostingKind(), entry.getCurrency(), entry.getDescription(),
                entry.isLatePosting(), entry.getTotalDebit(), entry.getTotalCredit(),
                entry.getReservation() != null ? entry.getReservation().getId() : null,
                entry.getReservation() != null ? entry.getReservation().getReservationCode() : null,
                entry.getDetailJson(), lines);
    }

    private BigDecimal sumLines(
            List<FinancialJournalLine> lines,
            FinancialEntryDirection direction,
            EnumSet<FinancialAccountCode> accounts) {
        return lines.stream()
                .filter(line -> line.getDirection() == direction
                        && accounts.contains(line.getAccountCode()))
                .map(FinancialJournalLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ObjectNode summaryJson(BusinessDayCloseResponse preview) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("businessDate", preview.businessDate().toString());
        summary.put("journalEntryCount", preview.journalEntryCount());
        summary.put("totalDebit", preview.totalDebit().toPlainString());
        summary.put("totalCredit", preview.totalCredit().toPlainString());
        summary.put("paymentReceivedAmount", preview.paymentReceivedAmount().toPlainString());
        summary.put("refundCompletedAmount", preview.refundCompletedAmount().toPlainString());
        summary.put("recognizedRevenueAmount", preview.recognizedRevenueAmount().toPlainString());
        summary.put("pendingRefundPayableAmount", preview.pendingRefundPayableAmount().toPlainString());
        summary.put("cashVarianceAmount", preview.cashVarianceAmount().toPlainString());
        summary.put("unreconciledFundsBalance", preview.unreconciledFundsBalance().toPlainString());
        return summary;
    }

    private void requireAdmin(User user) {
        if (user == null || user.getId() == null || user.getType() != UserType.ADMIN) {
            throw new AppException(ErrorCode.CASHIER_SHIFT_FORBIDDEN,
                    "Chỉ ADMIN được xem và khóa ngày nghiệp vụ");
        }
    }

    private void requireDate(LocalDate businessDate) {
        if (businessDate == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thiếu ngày nghiệp vụ");
        }
    }

    private String displayName(User user) {
        if (user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName().trim();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername().trim();
        }
        return "ADMIN #" + user.getId();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo hash snapshot khóa ngày", exception);
        }
    }
}
