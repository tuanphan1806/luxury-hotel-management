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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.HashMap;
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
    private final LocalDate accountingGoLiveDate;

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
            ObjectMapper objectMapper,
            @Value("${app.accounting.go-live-date:}") String accountingGoLiveDate) {
        this(closeRepository, businessDayLockService, entryRepository, lineRepository,
                shiftRepository, providerEventRepository, paymentRepository,
                refundRepository, invoiceRepository, auditService, objectMapper,
                Clock.systemUTC(), parseGoLiveDate(accountingGoLiveDate));
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
            Clock clock,
            LocalDate accountingGoLiveDate) {
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
        this.accountingGoLiveDate = accountingGoLiveDate;
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
        Page<FinancialJournalEntry> entries =
                entryRepository.findAllByBusinessDate(businessDate, pageable);
        List<Long> entryIds = entries.getContent().stream()
                .map(FinancialJournalEntry::getId)
                .toList();
        Map<Long, List<FinancialJournalLine>> linesByEntry = new HashMap<>();
        if (!entryIds.isEmpty()) {
            lineRepository
                    .findAllByJournalEntryIdInOrderByJournalEntryIdAscLineNumberAsc(entryIds)
                    .forEach(line -> linesByEntry
                            .computeIfAbsent(line.getJournalEntry().getId(), ignored -> new ArrayList<>())
                            .add(line));
        }
        return entries.map(entry -> toJournalResponse(
                entry,
                linesByEntry.getOrDefault(entry.getId(), List.of())));
    }

    private BusinessDayCloseResponse calculate(LocalDate businessDate) {
        Instant from = businessDate.atStartOfDay(HOTEL_ZONE).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(HOTEL_ZONE).toInstant();
        FinancialJournalEntryRepository.BusinessDayJournalSummary journalSummary =
                entryRepository.summarizeBusinessDate(businessDate);
        List<FinancialJournalLineRepository.BusinessDayAccountTotal> accountTotals =
                lineRepository.summarizeAccounts(businessDate);
        long journalEntryCount = journalSummary == null
                ? 0L : nullSafeLong(journalSummary.getEntryCount());
        BigDecimal totalDebit = journalSummary == null
                ? BigDecimal.ZERO : nullSafeMoney(journalSummary.getTotalDebit());
        BigDecimal totalCredit = journalSummary == null
                ? BigDecimal.ZERO : nullSafeMoney(journalSummary.getTotalCredit());
        long unbalancedCount = journalSummary == null
                ? 0L : nullSafeLong(journalSummary.getUnbalancedCount());

        BigDecimal paymentReceived = sumAccountTotals(
                accountTotals,
                FinancialEntryDirection.DEBIT,
                EnumSet.of(FinancialAccountCode.CASH_ON_HAND, FinancialAccountCode.BANK_SEPAY));
        BigDecimal refundCompleted = sumAccountTotals(
                accountTotals,
                FinancialEntryDirection.CREDIT,
                EnumSet.of(FinancialAccountCode.CASH_ON_HAND, FinancialAccountCode.BANK_SEPAY));
        BigDecimal recognizedRevenue = sumAccountTotals(
                accountTotals,
                FinancialEntryDirection.CREDIT,
                EnumSet.of(FinancialAccountCode.ROOM_REVENUE, FinancialAccountCode.SERVICE_REVENUE))
                .subtract(sumAccountTotals(accountTotals, FinancialEntryDirection.DEBIT,
                        EnumSet.of(FinancialAccountCode.DISCOUNT)));
        BigDecimal unreconciled = sumAccountTotals(
                accountTotals,
                FinancialEntryDirection.CREDIT,
                EnumSet.of(FinancialAccountCode.UNRECONCILED_FUNDS))
                .subtract(sumAccountTotals(accountTotals, FinancialEntryDirection.DEBIT,
                        EnumSet.of(FinancialAccountCode.UNRECONCILED_FUNDS)));

        long openShiftCount = shiftRepository.countByBusinessDateAndStatusIn(
                businessDate, ACTIVE_SHIFT_STATUSES);
        BigDecimal cashVariance = nullSafeMoney(
                shiftRepository.sumVarianceByBusinessDate(businessDate));
        long unresolvedEvents = providerEventRepository.countUnresolvedInRange(
                UNRESOLVED_EVENT_STATUSES, from, to);

        long unpostedPayments = paymentRepository.countUnpostedFinancialTransactions(
                FINANCIALLY_COMPLETED_PAYMENT_STATUSES, from, to);
        long unpostedRefunds = refundRepository.countUnpostedCompletedRefunds(
                RefundStatus.SUCCEEDED, from, to);
        long unpostedInvoices = invoiceRepository.countUnpostedIssuedInvoices(from, to);
        BigDecimal pendingRefundPayable = BigDecimal.valueOf(
                nullSafeLong(refundRepository.sumOutstandingRequestedAmount(
                        OUTSTANDING_REFUND_STATUSES)));

        List<String> blockers = new ArrayList<>();
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        if (accountingGoLiveDate == null) {
            blockers.add("ACCOUNTING_GO_LIVE_DATE_NOT_CONFIGURED");
        } else if (businessDate.isBefore(accountingGoLiveDate)) {
            blockers.add("BEFORE_ACCOUNTING_GO_LIVE_DATE:" + accountingGoLiveDate);
        }
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
                journalEntryCount,
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

    private FinancialJournalEntryResponse toJournalResponse(
            FinancialJournalEntry entry,
            List<FinancialJournalLine> journalLines) {
        List<FinancialJournalLineResponse> lines = journalLines.stream()
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

    private BigDecimal sumAccountTotals(
            List<FinancialJournalLineRepository.BusinessDayAccountTotal> totals,
            FinancialEntryDirection direction,
            EnumSet<FinancialAccountCode> accounts) {
        return totals.stream()
                .filter(total -> total.getDirection() == direction
                        && accounts.contains(total.getAccountCode()))
                .map(FinancialJournalLineRepository.BusinessDayAccountTotal::getAmount)
                .map(this::nullSafeMoney)
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
        summary.put("pendingRefundSnapshotScope", "OPEN_OBLIGATIONS_AT_CLOSE_EXECUTION_TIME");
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

    private BigDecimal nullSafeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long nullSafeLong(Long value) {
        return value == null ? 0L : value;
    }

    private static LocalDate parseGoLiveDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "APP_ACCOUNTING_GO_LIVE_DATE phải có định dạng yyyy-MM-dd", exception);
        }
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
