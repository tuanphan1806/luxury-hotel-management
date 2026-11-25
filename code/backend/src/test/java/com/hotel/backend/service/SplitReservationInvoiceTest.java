package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.*;
import com.hotel.backend.dto.response.ReservationServiceResponse;
import com.hotel.backend.entity.*;
import com.hotel.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SplitReservationInvoiceTest {
    @Test
    void sharedBillAndRoomFolioReconcileWithDiscountTaxAndActualBankReceiptOnSettlementDay() {
        var invoices = mock(ReservationInvoiceRepository.class);
        var transactions = mock(PaymentTransactionRepository.class);
        var refunds = mock(PaymentRefundService.class);
        var services = mock(ReservationAddOnService.class);
        var journal = mock(FinancialJournalService.class);
        var snapshots = new ReservationInvoiceSnapshotService(invoices, transactions, refunds, services,
                mock(ReservationRateSnapshotRepository.class), new ObjectMapper().findAndRegisterModules(), journal,
                new LegacyRoomDeparturePricing(mock(PricingService.class)));
        var left = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(2);
        var line = ReservationRoomType.builder().roomType(RoomType.builder().code("TEST").typeName("Standard").build())
                .roomPrice(amount(100000)).quantity(2).subtotal(amount(200000)).build();
        var a = ReservationRoom.builder().room(Room.builder().roomName("101").build()).status(AssignStatus.CHECKED_OUT)
                .departure(RoomDeparture.builder().roomName("101").folioNumber("TEST-P1").departedAt(left)
                        .billingMode(RoomBillingMode.SEPARATE).roomCharge(amount(100000)).extraCharge(amount(0))
                        .includedLateFee(amount(0)).serviceCharge(amount(50000)).additionalFee(15000L)
                        .allocatedPayment(amount(165000)).cashCollected(0L).qrCollected(145000L).build()).build();
        a.setId(1L);
        a.getDepartureServices().add(new RoomServiceAllocation(3L, "Breakfast", 1, amount(50000)));
        var b = ReservationRoom.builder().room(Room.builder().roomName("102").build()).status(AssignStatus.CHECKED_OUT).build();
        b.setId(2L);
        line.getRooms().addAll(List.of(a, b));
        var stay = Reservation.builder().reservationCode("TEST").status(ReservationStatus.CHECKED_OUT)
                .pricingVersion(PricingAlgorithmVersion.LEGACY_V1).roomTypes(Set.of(line))
                .customerProfile(CustomerProfile.builder().fullName("QA").build()).checkOut(left).actualCheckOut(left)
                .totalAmount(amount(385000)).checkoutAdditionalFee(amount(45000)).discountAmount(amount(20000)).taxAmount(amount(10000)).build();
        stay.setId(7L);
        when(services.listInternal(7L)).thenReturn(List.of(ReservationServiceResponse.builder().id(3L)
                .serviceName("Breakfast").quantity(3).billableQuantity(3).status(ReservationServiceStatus.FULFILLED)
                .totalPrice(amount(150000)).build()));
        var qr = PaymentTransaction.builder().id("folio-qr").provider(PaymentProvider.SEPAY).status(PaymentStatus.REFUNDED)
                .purpose(PaymentPurpose.FINAL_PAYMENT).amount(145000L).acceptedAmount(145000L)
                .receivedAmount(150000L).refundAmount(5000L).build();
        var cash = PaymentTransaction.builder().id("cash").provider(PaymentProvider.CASH).status(PaymentStatus.SUCCESS)
                .amount(240000L).acceptedAmount(240000L).receivedAmount(240000L).build();
        when(transactions.findByReservationId(7L)).thenReturn(List.of(qr, cash));
        when(refunds.getNetPaidAmount(7L)).thenReturn(385000L);
        when(invoices.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Instant before = Instant.now();
        var invoice = snapshots.createSnapshot(stay);
        var folio = invoice.getRoomFolios().get(0);
        assertEquals(amount(165000), folio.allocatedPayment());
        assertEquals(145000, folio.qrCollected());
        assertEquals(amount(385000), invoice.getActualRoomCharge().add(invoice.getAddOnServiceAmount())
                .add(invoice.getCheckoutAdditionalFee()).subtract(invoice.getDiscountAmount()).add(invoice.getTaxAmount()));
        assertEquals(invoice.getActualRoomCharge(), invoice.getRoomTypes().get(0).getActualRoomCharge());
        assertEquals(amount(220000), invoice.getTotalAmount().subtract(invoice.getSeparatelySettledAmount()));
        assertEquals(List.of("102"), invoice.getGroupRoomNames());
        assertEquals(150000L, invoice.getPayments().stream().filter(p -> "folio-qr".equals(p.getTransactionId())).findFirst().orElseThrow().getReceivedAmount());
        assertEquals(390000L, invoice.getGrossPaidAmount());
        assertEquals(5000L, invoice.getCompletedRefundAmount());
        assertEquals(385000L, invoice.getNetPaidAmount());
        assertEquals(0L, invoice.getBalanceAmount());
        assertEquals(left, invoice.getActualCheckOut());
        assertFalse(invoice.getIssuedAtUtc().isBefore(before));
        var saved = ArgumentCaptor.forClass(ReservationInvoice.class);
        verify(journal).postInvoice(saved.capture());
        assertEquals(invoice.getIssuedAtUtc(), saved.getValue().getIssuedAtUtc());
        when(invoices.findByReservationId(7L)).thenReturn(Optional.of(saved.getValue()));
        assertEquals(invoice.getIssuedAtUtc(), snapshots.createSnapshot(stay).getIssuedAtUtc());
        verifyNoMoreInteractions(journal);
    }

    private static BigDecimal amount(long vnd) { return BigDecimal.valueOf(vnd); }
}
