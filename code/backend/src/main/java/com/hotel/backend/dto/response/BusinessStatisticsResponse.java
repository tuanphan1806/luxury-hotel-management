package com.hotel.backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BusinessStatisticsResponse {
    private BusinessStatisticsResponse() {
    }

    public record Range(LocalDate from, LocalDate to, String timezone) {
    }

    public record Kpi(BigDecimal current, BigDecimal previous,
                      BigDecimal changePercent) {
    }

    public record DataQuality(
            String paymentCompleteness,
            String occupancyAccuracy,
            long legacyUnreconciledPaymentCount,
            BigDecimal legacyUnreconciledPaymentAmount,
            long unmatchedCashInEventCount,
            BigDecimal unmatchedCashInAmount,
            long unclassifiedCashOutEventCount,
            BigDecimal unclassifiedCashOutAmount,
            List<String> warnings) {
    }

    public record Overview(
            Range range,
            Kpi recognizedRevenue,
            Kpi bookings,
            Kpi occupancyRate,
            Kpi adr,
            Kpi revPar,
            BigDecimal grossCashInflow,
            BigDecimal acceptedCashInflow,
            BigDecimal refundOutflow,
            BigDecimal netCashFlow,
            BigDecimal outstandingReceivables,
            BigDecimal customerDeposits,
            BigDecimal refundPayable,
            DataQuality dataQuality,
            Instant generatedAtUtc) {
    }

    public record RevenuePoint(
            LocalDate period,
            LocalDate periodEndExclusive,
            BigDecimal recognizedRevenue,
            BigDecimal roomRevenue,
            BigDecimal addOnServiceRevenue,
            BigDecimal otherRevenue,
            BigDecimal additionalFee,
            BigDecimal lateCheckoutFee,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            long invoiceCount,
            BigDecimal grossCashInflow,
            BigDecimal acceptedCashInflow,
            BigDecimal refundOutflow,
            BigDecimal netCashFlow,
            BigDecimal unmatchedCashInflow,
            long unmatchedCashInEventCount,
            BigDecimal legacyUnreconciledPaymentAmount,
            long legacyUnreconciledPaymentCount) {
    }

    public record CashFlowPoint(
            LocalDate period,
            LocalDate periodEndExclusive,
            BigDecimal grossCashInflow,
            BigDecimal acceptedPaymentAmount,
            BigDecimal unacceptedReceivedAmount,
            BigDecimal refundOutflow,
            BigDecimal netCashFlow,
            BigDecimal unmatchedCashInflow,
            BigDecimal unclassifiedCashOutflow,
            BigDecimal netBankMovement,
            long paymentCount,
            long unmatchedCashInEventCount,
            long refundCount,
            long unclassifiedCashOutEventCount,
            BigDecimal legacyUnreconciledPaymentAmount,
            long legacyUnreconciledPaymentCount) {
    }

    public record BookingPoint(
            LocalDate period,
            LocalDate periodEndExclusive,
            long total,
            long paymentPending,
            long draft,
            long confirmed,
            long cancellationPending,
            long cancelled,
            long checkedIn,
            long checkedOut,
            long noShow) {
    }

    public record OccupancyPoint(
            LocalDate period,
            LocalDate periodEndExclusive,
            BigDecimal soldRoomHours,
            BigDecimal availableRoomHours,
            BigDecimal roomNightEquivalents,
            BigDecimal availableRoomNightEquivalents,
            BigDecimal occupancyRate,
            BigDecimal allocatedRoomRevenue,
            BigDecimal adr,
            BigDecimal revPar,
            String dataQuality) {
    }

    public record RoomTypePerformance(
            Long roomTypeId,
            String roomTypeCode,
            String roomTypeName,
            long bookingCount,
            long reservedRoomQuantity,
            BigDecimal soldRoomHours,
            BigDecimal availableRoomHours,
            BigDecimal occupancyRate,
            BigDecimal recognizedRoomRevenue,
            BigDecimal extraGuestRevenue,
            BigDecimal adr,
            BigDecimal revPar,
            String dataQuality) {
    }

    public record LedgerEntry(
            String entryKey,
            String eventType,
            Instant occurredAtUtc,
            LocalDateTime occurredAtLocal,
            String reservationCode,
            String reference,
            String provider,
            String status,
            BigDecimal amount,
            String direction,
            String dataQuality,
            String description) {
    }

    public record LedgerPage(
            List<LedgerEntry> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record ReservationRevenueEntry(
            LocalDate period,
            LocalDate periodEndExclusive,
            Long reservationId,
            String reservationCode,
            String reservationStatus,
            LocalDateTime plannedCheckIn,
            LocalDateTime plannedCheckOut,
            LocalDateTime actualCheckIn,
            LocalDateTime actualCheckOut,
            String invoiceNumber,
            Instant issuedAtUtc,
            LocalDateTime issuedAtLocal,
            String settlementStatus,
            String pricingVersion,
            BigDecimal roomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal addOnServiceAmount,
            BigDecimal additionalFee,
            BigDecimal lateCheckoutFee,
            BigDecimal otherRevenue,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal recognizedRevenue,
            BigDecimal grossCashInflow,
            BigDecimal acceptedCashInflow,
            BigDecimal refundOutflow,
            BigDecimal netCashFlow,
            String dataQuality) {
    }

    public record ReservationRevenuePage(
            List<ReservationRevenueEntry> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
