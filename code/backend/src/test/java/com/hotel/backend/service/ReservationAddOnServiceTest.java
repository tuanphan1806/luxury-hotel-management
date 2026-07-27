package com.hotel.backend.service;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.AddOnServiceCategory;
import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.ReservationServiceStatusRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.entity.AddOnService;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationServiceOrder;
import com.hotel.backend.entity.User;
import com.hotel.backend.event.CheckoutReconciliationChangedEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.AddOnServiceRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationServiceOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationAddOnServiceTest {

    @Mock private AddOnServiceRepository catalogRepository;
    @Mock private ReservationServiceOrderRepository orderRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private PaymentReservationAccessPolicy accessPolicy;
    @Mock private ReservationAuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReservationAddOnService service;
    private Reservation reservation;
    private User staff;

    @BeforeEach
    void setUp() {
        service = new ReservationAddOnService(
                catalogRepository,
                orderRepository,
                reservationRepository,
                accessPolicy,
                auditService,
                eventPublisher);
        reservation = Reservation.builder()
                .reservationCode("RES-ADDON-1")
                .status(ReservationStatus.CHECKED_IN)
                .checkIn(LocalDateTime.of(2026, 7, 20, 14, 0))
                .checkOut(LocalDateTime.of(2026, 7, 22, 14, 0))
                .actualCheckIn(LocalDateTime.of(2026, 7, 20, 14, 15))
                .guestCount(2)
                .totalAmount(new BigDecimal("1000000.00"))
                .build();
        reservation.setId(41L);
        staff = User.builder().username("staff").type(UserType.STAFF).build();
        staff.setId(7L);
    }

    @Test
    void bookingQuoteUsesQuantityTimesChargeableNights() {
        AddOnService rollaway = catalog(
                3L, "EXTRA_ROLLAWAY_BED", AddOnPricingUnit.PER_NIGHT, "200000.00");
        when(catalogRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(rollaway));

        ReservationAddOnService.BookingQuote quote = service.quoteBookingTime(
                List.of(ServiceOrderRequest.builder().serviceId(3L).quantity(2).build()),
                4,
                LocalDateTime.of(2026, 7, 20, 14, 0),
                LocalDateTime.of(2026, 7, 22, 14, 0));

        assertThat(quote.totalAmount()).isEqualByComparingTo("800000.00");
        assertThat(quote.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.multiplier()).isEqualTo(2);
            assertThat(line.billableQuantity()).isEqualTo(4);
        });
    }

    @Test
    void pricingV2BookingQuoteUsesAuthoritativePackageCycles() {
        AddOnService rollaway = catalog(
                3L, "EXTRA_ROLLAWAY_BED", AddOnPricingUnit.PER_NIGHT, "200000.00");
        when(catalogRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(rollaway));

        ReservationAddOnService.BookingQuote quote =
                service.quoteBookingTimeForPackageCycles(
                        List.of(ServiceOrderRequest.builder()
                                .serviceId(3L)
                                .quantity(2)
                                .build()),
                        4,
                        1);

        assertThat(quote.totalAmount()).isEqualByComparingTo("400000.00");
        assertThat(quote.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.multiplier()).isEqualTo(1);
            assertThat(line.billableQuantity()).isEqualTo(2);
        });
    }

    @Test
    void perGuestDefaultsToReservationGuestCount() {
        AddOnService breakfast = catalog(
                1L, "IN_ROOM_BREAKFAST", AddOnPricingUnit.PER_GUEST, "50000.00");
        when(catalogRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(breakfast));

        ReservationAddOnService.BookingQuote quote = service.quoteBookingTime(
                List.of(ServiceOrderRequest.builder().serviceId(1L).build()),
                3,
                LocalDateTime.of(2026, 7, 20, 14, 0),
                LocalDateTime.of(2026, 7, 20, 20, 0));

        assertThat(quote.totalAmount()).isEqualByComparingTo("150000.00");
        assertThat(quote.lines().get(0).quantity()).isEqualTo(3);
    }

    @Test
    void requestedInStayServiceDoesNotIncreaseReservationDebt() {
        AddOnService projector = catalog(
                4L, "MINI_PROJECTOR", AddOnPricingUnit.PER_ITEM, "100000.00");
        when(reservationRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(reservation));
        when(catalogRepository.findById(4L)).thenReturn(Optional.of(projector));
        when(orderRepository.save(any(ReservationServiceOrder.class)))
                .thenAnswer(invocation -> {
                    ReservationServiceOrder order = invocation.getArgument(0);
                    order.setId(91L);
                    return order;
                });

        var response = service.requestInStay(
                41L,
                ServiceOrderRequest.builder().serviceId(4L).quantity(1).build(),
                staff,
                null);

        assertThat(response.getStatus()).isEqualTo(ReservationServiceStatus.REQUESTED);
        assertThat(reservation.getTotalAmount()).isEqualByComparingTo("1000000.00");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void confirmationAddsDebtExactlyOnceAndPublishesReconciliationEvent() {
        ReservationServiceOrder order = pendingOrder("200000.00");
        when(reservationRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(reservation));
        when(orderRepository.findByIdAndReservationIdForUpdate(91L, 41L))
                .thenReturn(Optional.of(order));

        ReservationServiceStatusRequest request = ReservationServiceStatusRequest.builder()
                .status(ReservationServiceStatus.CONFIRMED)
                .build();
        service.updateStatus(41L, 91L, request, staff);
        service.updateStatus(41L, 91L, request, staff);

        assertThat(reservation.getTotalAmount()).isEqualByComparingTo("1200000.00");
        assertThat(order.getStatus()).isEqualTo(ReservationServiceStatus.CONFIRMED);
        ArgumentCaptor<CheckoutReconciliationChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(CheckoutReconciliationChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().reservationId()).isEqualTo(41L);
    }

    @Test
    void cancellingConfirmedServiceSubtractsDebtExactlyOnceAndRequiresReason() {
        ReservationServiceOrder order = pendingOrder("200000.00");
        order.setStatus(ReservationServiceStatus.CONFIRMED);
        reservation.setTotalAmount(new BigDecimal("1200000.00"));
        when(reservationRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(reservation));
        when(orderRepository.findByIdAndReservationIdForUpdate(91L, 41L))
                .thenReturn(Optional.of(order));

        ReservationServiceStatusRequest request = ReservationServiceStatusRequest.builder()
                .status(ReservationServiceStatus.CANCELLED)
                .cancellationReason("Khách không còn nhu cầu")
                .build();
        service.updateStatus(41L, 91L, request, staff);
        service.updateStatus(41L, 91L, request, staff);

        assertThat(reservation.getTotalAmount()).isEqualByComparingTo("1000000.00");
        assertThat(order.getStatus()).isEqualTo(ReservationServiceStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Khách không còn nhu cầu");
    }

    @Test
    void cancellationWithoutReasonIsRejectedWithoutChangingDebt() {
        ReservationServiceOrder order = pendingOrder("200000.00");
        order.setStatus(ReservationServiceStatus.CONFIRMED);
        reservation.setTotalAmount(new BigDecimal("1200000.00"));
        when(reservationRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(reservation));
        when(orderRepository.findByIdAndReservationIdForUpdate(91L, 41L))
                .thenReturn(Optional.of(order));

        ReservationServiceStatusRequest request = ReservationServiceStatusRequest.builder()
                .status(ReservationServiceStatus.CANCELLED)
                .build();

        assertThatThrownBy(() -> service.updateStatus(41L, 91L, request, staff))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("lý do");
        assertThat(reservation.getTotalAmount()).isEqualByComparingTo("1200000.00");
        assertThat(order.getStatus()).isEqualTo(ReservationServiceStatus.CONFIRMED);
    }

    @Test
    void inactiveCatalogItemCannotBeAddedButExistingSnapshotIsUnaffected() {
        AddOnService inactive = catalog(
                5L, "ROOM_DECORATION", AddOnPricingUnit.PER_USE, "500000.00");
        inactive.setActive(false);
        when(catalogRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.quoteBookingTime(
                List.of(ServiceOrderRequest.builder().serviceId(5L).build()),
                2,
                reservation.getCheckIn(),
                reservation.getCheckOut()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("không khả dụng");
    }

    private AddOnService catalog(
            Long id,
            String code,
            AddOnPricingUnit pricingUnit,
            String price) {
        AddOnService item = AddOnService.builder()
                .code(code)
                .name(code)
                .category(AddOnServiceCategory.OTHER)
                .price(new BigDecimal(price))
                .pricingUnit(pricingUnit)
                .bookingEnabled(true)
                .inStayEnabled(true)
                .active(true)
                .build();
        item.setId(id);
        return item;
    }

    private ReservationServiceOrder pendingOrder(String amount) {
        AddOnService item = catalog(
                3L, "EXTRA_ROLLAWAY_BED", AddOnPricingUnit.PER_ITEM, amount);
        ReservationServiceOrder order = ReservationServiceOrder.builder()
                .reservation(reservation)
                .service(item)
                .origin(ReservationServiceOrigin.IN_STAY)
                .serviceCodeSnapshot(item.getCode())
                .serviceNameSnapshot(item.getName())
                .unitPriceSnapshot(new BigDecimal(amount))
                .pricingUnitSnapshot(AddOnPricingUnit.PER_ITEM)
                .quantity(1)
                .pricingMultiplier(1)
                .billableQuantity(1)
                .totalPrice(new BigDecimal(amount))
                .status(ReservationServiceStatus.REQUESTED)
                .requestedAtUtc(java.time.Instant.now())
                .build();
        order.setId(91L);
        return order;
    }
}
