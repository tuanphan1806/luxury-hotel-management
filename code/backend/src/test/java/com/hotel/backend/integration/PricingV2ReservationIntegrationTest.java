package com.hotel.backend.integration;

import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.RateSnapshotStage;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WalkInPaymentOption;
import com.hotel.backend.dto.request.AssignRoomRequest;
import com.hotel.backend.dto.request.CreateReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInCheckedInRequest;
import com.hotel.backend.dto.request.CreateWalkInReservationRequest;
import com.hotel.backend.dto.request.CustomerProfileRequest;
import com.hotel.backend.dto.request.ExtendReservationRequest;
import com.hotel.backend.dto.request.GuestRequest;
import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.request.PricingQuoteRoomRequest;
import com.hotel.backend.dto.request.RoomTypeItemRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.dto.request.UpdateReservationRequest;
import com.hotel.backend.dto.request.WalkInPriceOverrideRequest;
import com.hotel.backend.dto.response.PricingQuoteResponse;
import com.hotel.backend.dto.response.FinalPaymentResponse;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.WalkInReservationResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRateSnapshot;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.*;
import com.hotel.backend.service.PricingQuoteService;
import com.hotel.backend.service.ReservationInvoiceSnapshotService;
import com.hotel.backend.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "hotel.pricing.engine-v2-enabled=true",
        "hotel.pricing.engine-v2-room-type-codes="
                + "STANDARD,DELUXE,EXECUTIVE,SUITE,FAMILY,PRESIDENTIAL"
})
@ActiveProfiles("test")
@Transactional
class PricingV2ReservationIntegrationTest {

    @Autowired
    private PricingQuoteService pricingQuoteService;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationInvoiceSnapshotService invoiceSnapshotService;
    @Autowired
    private RoomTypeRepository roomTypeRepository;
    @Autowired
    private RoomRateProfileRepository roomRateProfileRepository;
    @Autowired
    private AddOnServiceRepository addOnServiceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationRateSnapshotRepository snapshotRepository;
    @Autowired
    private PricingQuoteCommitmentRepository commitmentRepository;
    @Autowired
    private RoomHoldRepository roomHoldRepository;
    @Autowired
    private ReservationServiceOrderRepository
            reservationServiceOrderRepository;
    @Autowired
    private ReservationRoomTypeRepository reservationRoomTypeRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void staffWalkInReservationUsesV2RateAndSnapshotWhenCanaryIsEnabled() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        LocalDateTime checkOut = LocalDateTime.now().plusHours(21);
        ReservationResponse created =
                reservationService.createWalkInReservation(
                        CreateWalkInReservationRequest.builder()
                                .customer(walkInCustomer("Walk-in V2"))
                                .checkOut(checkOut)
                                .guestCount(1)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(1)
                                                .build()))
                                .build());
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        ReservationRoomType line = reservationRoomTypeRepository
                .findDetailsByReservationId(persisted.getId())
                .get(0);
        List<ReservationRateSnapshot> snapshots = snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        line.getId());

        assertEquals(
                PricingAlgorithmVersion.MOTEL_PACKAGE_V2,
                persisted.getPricingVersion());
        assertEquals(0, new java.math.BigDecimal("300000.00")
                .compareTo(persisted.getTotalAmount()));
        assertEquals(1, line.getLineGuestCount());
        assertEquals(0, new java.math.BigDecimal("300000.00")
                .compareTo(line.getSubtotal()));
        assertEquals(1, snapshots.size());
        assertEquals(
                RateSnapshotStage.COMMITMENT,
                snapshots.get(0).getSnapshotStage());
        assertTrue(persisted.getInventoryProtectedUntil()
                .isAfter(persisted.getCheckOut()));
        assertTrue(
                roomHoldRepository.findByReservationId(persisted.getId())
                        .isEmpty());
    }

    @Test
    void multiRoomWalkInPersistsPerRoomPriceLineSubtotalAndReservationTotal() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        ReservationResponse created =
                reservationService.createWalkInReservation(
                        CreateWalkInReservationRequest.builder()
                                .customer(walkInCustomer("Walk-in hai phòng"))
                                .checkOut(LocalDateTime.now().plusHours(2))
                                .guestCount(2)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(2)
                                                .build()))
                                .build());
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        ReservationRoomType line = reservationRoomTypeRepository
                .findDetailsByReservationId(persisted.getId())
                .get(0);

        assertEquals(
                PricingAlgorithmVersion.MOTEL_PACKAGE_V2,
                persisted.getPricingVersion());
        assertEquals(2, line.getQuantity());
        assertEquals(2, line.getLineGuestCount());
        assertEquals(0, new java.math.BigDecimal("70000.00")
                .compareTo(line.getRoomPrice()));
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(line.getSubtotal()));
        assertEquals(0, line.getSubtotal()
                .compareTo(persisted.getTotalAmount()));
        assertEquals(1, snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        line.getId())
                .size());
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(created.getPlannedRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(created.getActualRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(created.getPlannedTotalAmount()));
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(created.getActualTotalAmount()));
        assertEquals(1, created.getRoomTypes().size());
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(created.getRoomTypes().get(0)
                        .getPlannedSubtotal()));
        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(created.getRoomTypes().get(0)
                        .getActualSubtotal()));
    }

    @Test
    void mixedRoomTypesExposeLineTotalsThatReconcileToReservationAndInvoice() {
        RoomType standard = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        RoomType deluxe = roomTypeRepository.findByCode("DELUXE")
                .orElseThrow();
        ReservationResponse created =
                reservationService.createWalkInReservation(
                        CreateWalkInReservationRequest.builder()
                                .customer(walkInCustomer(
                                        "Walk-in nhiều hạng phòng"))
                                .checkOut(LocalDateTime.now().plusHours(2))
                                .guestCount(4)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(standard.getId())
                                                .quantity(2)
                                                .lineGuestCount(2)
                                                .build(),
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(deluxe.getId())
                                                .quantity(1)
                                                .lineGuestCount(2)
                                                .build()))
                                .build());
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        List<ReservationRoomType> lines =
                reservationRoomTypeRepository
                        .findDetailsByReservationId(persisted.getId());
        ReservationRoomType standardLine = lines.stream()
                .filter(line -> line.getRoomType().getId()
                        .equals(standard.getId()))
                .findFirst()
                .orElseThrow();
        ReservationRoomType deluxeLine = lines.stream()
                .filter(line -> line.getRoomType().getId()
                        .equals(deluxe.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(0, new java.math.BigDecimal("140000.00")
                .compareTo(standardLine.getSubtotal()));
        assertEquals(0, new java.math.BigDecimal("100000.00")
                .compareTo(deluxeLine.getSubtotal()));
        assertEquals(0, new java.math.BigDecimal("240000.00")
                .compareTo(persisted.getTotalAmount()));
        assertEquals(2, created.getRoomTypes().size());
        assertEquals(0, created.getRoomTypes().stream()
                .map(item -> item.getActualSubtotal())
                .reduce(java.math.BigDecimal.ZERO,
                        java.math.BigDecimal::add)
                .compareTo(created.getActualTotalAmount()));

        persisted.setStatus(ReservationStatus.CHECKED_OUT);
        persisted.setActualCheckOut(persisted.getCheckOut());
        reservationRepository.save(persisted);
        ReservationInvoiceResponse invoice =
                invoiceSnapshotService.createSnapshot(persisted);
        assertEquals(2, invoice.getRoomTypes().size());
        assertEquals(0, invoice.getRoomTypes().stream()
                .map(ReservationInvoiceResponse.RoomTypeLine
                        ::getActualSubtotal)
                .reduce(java.math.BigDecimal.ZERO,
                        java.math.BigDecimal::add)
                .compareTo(invoice.getTotalAmount()));
    }

    @Test
    void allCanonicalRatesAndGuestSurchargesReconcileFromQuoteThroughInvoice() {
        List<String> codes = List.of(
                "STANDARD",
                "DELUXE",
                "EXECUTIVE",
                "SUITE",
                "FAMILY",
                "PRESIDENTIAL");
        Map<String, BigDecimal> expectedRoomCharges = Map.of(
                "STANDARD", new BigDecimal("70000.00"),
                "DELUXE", new BigDecimal("100000.00"),
                "EXECUTIVE", new BigDecimal("120000.00"),
                "SUITE", new BigDecimal("150000.00"),
                "FAMILY", new BigDecimal("130000.00"),
                "PRESIDENTIAL", new BigDecimal("200000.00"));
        Map<String, BigDecimal> expectedGuestCharges = Map.of(
                "STANDARD", new BigDecimal("50000.00"),
                "DELUXE", new BigDecimal("50000.00"),
                "EXECUTIVE", new BigDecimal("50000.00"),
                "SUITE", new BigDecimal("100000.00"),
                "FAMILY", new BigDecimal("100000.00"),
                "PRESIDENTIAL", new BigDecimal("100000.00"));
        List<RoomType> roomTypes = codes.stream()
                .map(code -> roomTypeRepository.findByCode(code)
                        .orElseThrow())
                .toList();
        int guestCount = roomTypes.stream()
                .mapToInt(RoomType::getMaxGuests)
                .sum();
        LocalDateTime checkIn = LocalDateTime.now().plusDays(10)
                .withHour(8).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime checkOut = checkIn.plusHours(2);

        PricingQuoteResponse quote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(guestCount)
                        .rooms(roomTypes.stream()
                                .map(roomType ->
                                        PricingQuoteRoomRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(1)
                                                .lineGuestCount(
                                                        roomType.getMaxGuests())
                                                .build())
                                .toList())
                        .build());

        assertEquals(6, quote.getLines().size());
        assertEquals(0, new BigDecimal("770000.00")
                .compareTo(quote.getRoomCharge()));
        assertEquals(0, new BigDecimal("450000.00")
                .compareTo(quote.getExtraGuestCharge()));
        assertEquals(0, new BigDecimal("1220000.00")
                .compareTo(quote.getTotalAmount()));
        quote.getLines().forEach(line -> {
            BigDecimal expectedRoom = expectedRoomCharges.get(
                    line.getRoomTypeCode());
            BigDecimal expectedGuest = expectedGuestCharges.get(
                    line.getRoomTypeCode());
            assertNotNull(expectedRoom);
            assertNotNull(expectedGuest);
            assertEquals(0, expectedRoom.compareTo(line.getRoomCharge()));
            assertEquals(0, expectedGuest.compareTo(
                    line.getExtraGuestCharge()));
            assertEquals(0, expectedRoom.add(expectedGuest)
                    .compareTo(line.getLineTotalBeforeServices()));
        });

        ReservationResponse created = reservationService.createReservation(
                customer(),
                CreateReservationRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(guestCount)
                        .roomTypes(roomTypes.stream()
                                .map(roomType -> RoomTypeItemRequest.builder()
                                        .roomTypeId(roomType.getId())
                                        .quantity(1)
                                        .lineGuestCount(
                                                roomType.getMaxGuests())
                                        .build())
                                .toList())
                        .quoteId(quote.getQuoteId())
                        .quoteHash(quote.getQuoteHash())
                        .build());
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        assertEquals(0, quote.getTotalAmount()
                .compareTo(persisted.getTotalAmount()));
        assertEquals(6, persisted.getRoomTypes().size());
        assertEquals(6, snapshotRepository
                .findByReservationIdOrderByLineAndSequence(
                        persisted.getId())
                .size());

        persisted.setStatus(ReservationStatus.CHECKED_OUT);
        persisted.setActualCheckIn(checkIn);
        persisted.setActualCheckOut(checkOut);
        reservationRepository.save(persisted);
        ReservationInvoiceResponse invoice =
                invoiceSnapshotService.createSnapshot(persisted);
        assertEquals(6, invoice.getRoomTypes().size());
        assertEquals(0, new BigDecimal("770000.00")
                .compareTo(invoice.getActualRoomCharge()));
        assertEquals(0, new BigDecimal("450000.00")
                .compareTo(invoice.getExtraGuestCharge()));
        assertEquals(0, new BigDecimal("1220000.00")
                .compareTo(invoice.getTotalAmount()));
        assertEquals(0, invoice.getRoomTypes().stream()
                .map(item -> item.getActualSubtotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(invoice.getTotalAmount()));
    }

    @Test
    void mixedRoomExtensionKeepsPlannedAndActualOrderTotalsReconciled() {
        RoomType standard = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        RoomType deluxe = roomTypeRepository.findByCode("DELUXE")
                .orElseThrow();
        LocalDateTime checkIn = LocalDateTime.now().plusDays(12)
                .withHour(8).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime checkOut = checkIn.plusHours(2);
        PricingQuoteResponse quote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(3)
                        .rooms(List.of(
                                PricingQuoteRoomRequest.builder()
                                        .roomTypeId(standard.getId())
                                        .quantity(1)
                                        .lineGuestCount(1)
                                        .build(),
                                PricingQuoteRoomRequest.builder()
                                        .roomTypeId(deluxe.getId())
                                        .quantity(1)
                                        .lineGuestCount(2)
                                        .build()))
                        .build());
        User customer = customer();
        ReservationResponse created = reservationService.createReservation(
                customer,
                CreateReservationRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(3)
                        .roomTypes(List.of(
                                RoomTypeItemRequest.builder()
                                        .roomTypeId(standard.getId())
                                        .quantity(1)
                                        .lineGuestCount(1)
                                        .build(),
                                RoomTypeItemRequest.builder()
                                        .roomTypeId(deluxe.getId())
                                        .quantity(1)
                                        .lineGuestCount(2)
                                        .build()))
                        .quoteId(quote.getQuoteId())
                        .quoteHash(quote.getQuoteHash())
                        .build());
        Reservation reservation = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        ExtendReservationRequest extension = new ExtendReservationRequest();
        extension.setNewCheckOut(checkIn.plusHours(3));
        extension.setReason("Kiểm tra tổng giá nhiều hạng phòng");
        ReservationResponse extended = reservationService.extendStay(
                reservation.getId(), extension);

        assertEquals(0, new BigDecimal("170000.00")
                .compareTo(extended.getPlannedRoomCharge()));
        assertEquals(0, new BigDecimal("215000.00")
                .compareTo(extended.getActualRoomCharge()));
        assertEquals(0, new BigDecimal("45000.00")
                .compareTo(extended.getPostCommitmentRoomIncrease()));
        assertEquals(0, new BigDecimal("170000.00")
                .compareTo(extended.getPlannedTotalAmount()));
        assertEquals(0, new BigDecimal("215000.00")
                .compareTo(extended.getActualTotalAmount()));
        assertEquals(0, extended.getRoomTypes().stream()
                .map(item -> item.getActualSubtotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(extended.getActualTotalAmount()));

        FinalPaymentResponse finalPayment =
                reservationService.calculateFinalPayment(
                        reservation.getId(), customer);
        assertEquals(170_000L, finalPayment.getPlannedRoomCharge());
        assertEquals(215_000L, finalPayment.getRoomCharge());
        assertEquals(45_000L,
                finalPayment.getPostCommitmentRoomIncrease());
        assertEquals(215_000L, finalPayment.getTotalAmount());

        // Invoice is produced by a later checkout/print request in production.
        // Refresh the persistence context so the reservation collection is
        // loaded from the committed line rows, not from the creation aggregate.
        entityManager.flush();
        entityManager.clear();
        Reservation persisted = reservationRepository
                .findByIdWithDetails(reservation.getId())
                .orElseThrow();
        persisted.setStatus(ReservationStatus.CHECKED_OUT);
        persisted.setActualCheckIn(checkIn);
        persisted.setActualCheckOut(checkIn.plusHours(3));
        reservationRepository.save(persisted);
        ReservationInvoiceResponse invoice =
                invoiceSnapshotService.createSnapshot(persisted);
        assertEquals(
                new BigDecimal("170000.00"),
                invoice.getPlannedRoomCharge());
        assertEquals(
                new BigDecimal("215000.00"),
                invoice.getActualRoomCharge());
        assertEquals(
                new BigDecimal("45000.00"),
                invoice.getPostCommitmentRoomIncrease());
        assertEquals(
                new BigDecimal("215000.00"),
                invoice.getTotalAmount());
        assertEquals(0, invoice.getRoomTypes().stream()
                .map(item -> item.getActualSubtotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(invoice.getTotalAmount()));
    }

    @Test
    void atomicWalkInPricesDeclaredGuestsAndPersistsExtraGuestEvidence() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        Room room = availableRoom(roomType);
        User staff = staff();
        CreateWalkInCheckedInRequest request =
                CreateWalkInCheckedInRequest.builder()
                        .customer(walkInCustomer("Atomic V2"))
                        .checkOut(LocalDateTime.now().plusHours(21))
                        .guestCount(2)
                        .rooms(List.of(AssignRoomRequest.builder()
                                .roomId(room.getId())
                                .guests(List.of(walkInGuest("Khách đã khai báo")))
                                .build()))
                        .paymentOption(WalkInPaymentOption.UNPAID)
                        .build();

        WalkInReservationResponse created =
                reservationService.createWalkInCheckedIn(
                        request, staff, "127.0.0.1");
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(created.getReservation().getId())
                .orElseThrow();
        ReservationRoomType line = reservationRoomTypeRepository
                .findDetailsByReservationId(persisted.getId())
                .get(0);
        ReservationRateSnapshot snapshot = snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        line.getId())
                .get(0);

        assertEquals(ReservationStatus.CHECKED_IN, persisted.getStatus());
        assertEquals(
                PricingAlgorithmVersion.MOTEL_PACKAGE_V2,
                persisted.getPricingVersion());
        assertEquals(0, new java.math.BigDecimal("350000.00")
                .compareTo(persisted.getTotalAmount()));
        assertEquals(2, line.getLineGuestCount());
        assertEquals(0, new java.math.BigDecimal("350000.00")
                .compareTo(line.getSubtotal()));
        assertEquals(1, snapshot.getExtraGuestCount());
        assertEquals(0, new java.math.BigDecimal("50000.00")
                .compareTo(snapshot.getExtraGuestCharge()));
        assertTrue(
                roomHoldRepository.findByReservationId(persisted.getId())
                        .isEmpty());

        persisted.setStatus(ReservationStatus.CHECKED_OUT);
        persisted.setActualCheckOut(persisted.getCheckOut());
        reservationRepository.save(persisted);
        ReservationInvoiceResponse invoice =
                invoiceSnapshotService.createSnapshot(persisted);
        ReservationInvoiceResponse.RoomTypeLine invoiceLine =
                invoice.getRoomTypes().get(0);
        assertEquals(0, new java.math.BigDecimal("300000.00")
                .compareTo(invoiceLine.getPlannedRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("300000.00")
                .compareTo(invoiceLine.getActualRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("50000.00")
                .compareTo(invoiceLine.getPlannedExtraGuestCharge()));
        assertEquals(0, new java.math.BigDecimal("50000.00")
                .compareTo(invoiceLine.getExtraGuestCharge()));
        assertEquals(0, new java.math.BigDecimal("350000.00")
                .compareTo(invoiceLine.getPlannedSubtotal()));
        assertEquals(0, new java.math.BigDecimal("350000.00")
                .compareTo(invoiceLine.getActualSubtotal()));
    }

    @Test
    void approvedWalkInPriceOverrideRetainsAuditedLegacyBoundary() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        Room room = availableRoom(roomType);
        User staff = staff();
        WalkInReservationResponse created =
                reservationService.createWalkInCheckedIn(
                        CreateWalkInCheckedInRequest.builder()
                                .customer(walkInCustomer("Override V2 boundary"))
                                .checkOut(LocalDateTime.now().plusHours(21))
                                .guestCount(1)
                                .rooms(List.of(AssignRoomRequest.builder()
                                        .roomId(room.getId())
                                        .guests(List.of(walkInGuest(
                                                "Khách giá duyệt")))
                                        .build()))
                                .priceOverrides(List.of(
                                        WalkInPriceOverrideRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .newUnitPrice(
                                                        new java.math.BigDecimal(
                                                                "123000"))
                                                .reasonCode(
                                                        "APPROVED_WALK_IN_RATE")
                                                .note("Quản lý đã duyệt")
                                                .build()))
                                .paymentOption(WalkInPaymentOption.UNPAID)
                                .build(),
                        staff,
                        "127.0.0.1");

        Reservation persisted = reservationRepository
                .findById(created.getReservation().getId())
                .orElseThrow();
        assertEquals(
                PricingAlgorithmVersion.LEGACY_V1,
                persisted.getPricingVersion());
        assertEquals(0, new java.math.BigDecimal("123000.00")
                .compareTo(persisted.getTotalAmount()));
    }

    @Test
    void quoteUsesAProfileUntilItsScheduledEffectiveEnd() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        RoomRateProfile profile = roomRateProfileRepository
                .findByRoomTypeIdAndProfileVersion(roomType.getId(), 1)
                .orElseThrow();
        profile.setEffectiveToUtc(Instant.now().plusSeconds(3600));
        roomRateProfileRepository.saveAndFlush(profile);

        LocalDateTime checkIn =
                LocalDateTime.now().plusDays(1).toLocalDate().atTime(10, 0);
        PricingQuoteResponse quote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkIn.plusHours(2))
                        .guestCount(1)
                        .rooms(List.of(PricingQuoteRoomRequest.builder()
                                .roomTypeId(roomType.getId())
                                .quantity(1)
                                .lineGuestCount(1)
                                .build()))
                        .build());

        assertNotNull(quote.getQuoteId());
        assertEquals(0, profile.getFirstBlockPrice()
                .compareTo(quote.getRoomCharge()));
    }

    @Test
    void quoteRejectsAProfileWhoseStayPolicyIsInactive() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        RoomRateProfile profile = roomRateProfileRepository
                .findByRoomTypeIdAndProfileVersion(roomType.getId(), 1)
                .orElseThrow();
        profile.getStayPolicyVersion().setActive(false);
        entityManager.flush();

        LocalDateTime checkIn =
                LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        AppException exception = assertThrows(
                AppException.class,
                () -> pricingQuoteService.createQuote(
                        PricingQuoteRequest.builder()
                                .checkIn(checkIn)
                                .checkOut(checkIn.plusHours(2))
                                .guestCount(1)
                                .rooms(List.of(
                                        PricingQuoteRoomRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(1)
                                                .lineGuestCount(1)
                                                .build()))
                                .build()));

        assertEquals(
                ErrorCode.PRICING_PROFILE_NOT_FOUND,
                exception.getErrorCode());
    }

    @Test
    void quoteIsRevalidatedConsumedAndSnapshottedAtomically() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        LocalDateTime checkIn =
                LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        LocalDateTime checkOut = checkIn.plusHours(2);
        PricingQuoteRequest quoteRequest = PricingQuoteRequest.builder()
                .checkIn(checkIn)
                .checkOut(checkOut)
                .guestCount(1)
                .rooms(List.of(PricingQuoteRoomRequest.builder()
                        .roomTypeId(roomType.getId())
                        .quantity(1)
                        .lineGuestCount(1)
                        .build()))
                .build();
        PricingQuoteResponse quote =
                pricingQuoteService.createQuote(quoteRequest);

        CreateReservationRequest reservationRequest =
                CreateReservationRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(1)
                        .roomTypes(List.of(RoomTypeItemRequest.builder()
                                .roomTypeId(roomType.getId())
                                .quantity(1)
                                .lineGuestCount(1)
                                .build()))
                        .quoteId(quote.getQuoteId())
                        .quoteHash(quote.getQuoteHash())
                        .build();

        User customer = customer();
        ReservationResponse created =
                reservationService.createReservation(
                        customer, reservationRequest);
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        assertEquals(
                PricingAlgorithmVersion.MOTEL_PACKAGE_V2,
                persisted.getPricingVersion());
        assertEquals(quote.getTotalAmount(), persisted.getTotalAmount());
        assertEquals(
                quote.getInventoryProtectedUntil(),
                persisted.getInventoryProtectedUntil());
        assertEquals(ReservationStatus.PAYMENT_PENDING, persisted.getStatus());
        assertTrue(
                commitmentRepository.existsByPricingQuoteId(
                        quote.getQuoteId()));
        assertEquals(
                1,
                snapshotRepository
                        .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                                persisted.getRoomTypes().iterator().next().getId())
                        .size());
        assertTrue(
                roomHoldRepository.findByReservationId(persisted.getId())
                        .isEmpty(),
                "Báo giá/đặt đơn không được giữ phòng trước khi tạo QR");

        persisted.setStatus(ReservationStatus.DRAFT);
        reservationRepository.save(persisted);
        AppException guestChange = assertThrows(
                AppException.class,
                () -> reservationService.updateReservation(
                        persisted.getId(),
                        UpdateReservationRequest.builder()
                                .guestCount(2)
                                .build(),
                        customer));
        assertEquals(
                ErrorCode.PRICING_QUOTE_MISMATCH,
                guestChange.getErrorCode(),
                "Không được đổi số khách mà không tạo lại snapshot/báo giá");

        AppException reused = assertThrows(
                AppException.class,
                () -> reservationService.createReservation(
                        customer, reservationRequest));
        assertEquals(
                ErrorCode.PRICING_QUOTE_MISMATCH,
                reused.getErrorCode());
    }

    @Test
    void extensionRevalidatesInventoryAndAppendsPricingEvidence() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        LocalDateTime checkIn =
                LocalDateTime.now().plusDays(2)
                        .withHour(8).withMinute(0)
                        .withSecond(0).withNano(0);
        LocalDateTime checkOut = checkIn.plusHours(2);
        PricingQuoteResponse quote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(1)
                        .rooms(List.of(
                                PricingQuoteRoomRequest.builder()
                                        .roomTypeId(roomType.getId())
                                        .quantity(1)
                                        .lineGuestCount(1)
                                        .build()))
                        .build());
        User customer = customer();
        ReservationResponse created = reservationService
                .createReservation(
                        customer,
                        CreateReservationRequest.builder()
                                .checkIn(checkIn)
                                .checkOut(checkOut)
                                .guestCount(1)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(
                                                        roomType.getId())
                                                .quantity(1)
                                                .lineGuestCount(1)
                                                .build()))
                                .quoteId(quote.getQuoteId())
                                .quoteHash(quote.getQuoteHash())
                                .build());
        Reservation reservation = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);
        LocalDateTime newCheckOut = checkOut.plusHours(1);
        ExtendReservationRequest extension =
                new ExtendReservationRequest();
        extension.setNewCheckOut(newCheckOut);
        extension.setReason("Khách muốn ở thêm một giờ");

        ReservationResponse extended = reservationService.extendStay(
                reservation.getId(), extension);
        entityManager.flush();
        entityManager.clear();

        Reservation persisted = reservationRepository
                .findByIdWithDetails(extended.getId())
                .orElseThrow();
        assertEquals(newCheckOut, persisted.getCheckOut());
        assertTrue(
                persisted.getTotalAmount().compareTo(
                        quote.getTotalAmount()) > 0);
        assertTrue(
                persisted.getInventoryProtectedUntil()
                        .isAfter(newCheckOut));
        assertEquals(0, new java.math.BigDecimal("70000.00")
                .compareTo(extended.getPlannedRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("90000.00")
                .compareTo(extended.getActualRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("70000.00")
                .compareTo(extended.getPlannedTotalAmount()));
        assertEquals(0, new java.math.BigDecimal("90000.00")
                .compareTo(extended.getActualTotalAmount()));
        assertEquals(0, new java.math.BigDecimal("20000.00")
                .compareTo(
                        extended.getPostCommitmentRoomIncrease()));
        assertEquals(0, new java.math.BigDecimal("70000.00")
                .compareTo(extended.getRoomTypes().get(0)
                        .getPlannedSubtotal()));
        assertEquals(0, new java.math.BigDecimal("90000.00")
                .compareTo(extended.getRoomTypes().get(0)
                        .getActualSubtotal()));
        List<com.hotel.backend.entity.ReservationRateSnapshot> snapshots =
                snapshotRepository
                        .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                                persisted.getRoomTypes()
                                        .iterator().next().getId());
        assertEquals(2, snapshots.size());
        assertEquals(
                RateSnapshotStage.EXTENSION,
                snapshots.get(1).getSnapshotStage());
        FinalPaymentResponse finalPayment =
                reservationService.calculateFinalPayment(
                        persisted.getId(), customer);
        assertEquals(
                PricingAlgorithmVersion.MOTEL_PACKAGE_V2,
                finalPayment.getPricingVersion());
        assertEquals(70_000L, finalPayment.getPlannedRoomCharge());
        assertEquals(90_000L, finalPayment.getRoomCharge());
        assertEquals(0L, finalPayment.getExtraGuestCharge());
        assertEquals(
                20_000L,
                finalPayment.getPostCommitmentRoomIncrease());
        assertEquals(90_000L, finalPayment.getTotalAmount());
        persisted.setStatus(ReservationStatus.CHECKED_OUT);
        persisted.setActualCheckOut(newCheckOut);
        reservationRepository.save(persisted);
        ReservationInvoiceResponse invoice =
                invoiceSnapshotService.createSnapshot(persisted);
        assertEquals(
                PricingAlgorithmVersion.MOTEL_PACKAGE_V2,
                invoice.getPricingVersion());
        assertEquals(0, new java.math.BigDecimal("70000.00")
                .compareTo(invoice.getPlannedRoomCharge()));
        assertEquals(0, new java.math.BigDecimal("90000.00")
                .compareTo(invoice.getActualRoomCharge()));
        assertEquals(0, java.math.BigDecimal.ZERO
                .compareTo(invoice.getExtraGuestCharge()));
        assertEquals(
                RateSnapshotStage.EXTENSION.name(),
                snapshots.get(1).getSnapshotStage().name());
        assertEquals(
                snapshots.get(1).getSnapshotHash(),
                invoice.getRoomTypes().get(0)
                        .getPricingSnapshotHash());
        assertTrue(
                roomHoldRepository.findByReservationId(persisted.getId())
                        .isEmpty(),
                "Gia hạn không được tự tạo RoomHold/QR");
    }

    @Test
    void extensionRepricesConfirmedPerNightBookingServiceFromSnapshot() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        var extraBed = addOnServiceRepository
                .findByCodeIgnoreCase("EXTRA_ROLLAWAY_BED")
                .orElseThrow();
        LocalDateTime checkIn = LocalDateTime.now().plusDays(3)
                .withHour(8).withMinute(0)
                .withSecond(0).withNano(0);
        LocalDateTime checkOut = checkIn.plusHours(2);
        List<ServiceOrderRequest> services = List.of(
                ServiceOrderRequest.builder()
                        .serviceId(extraBed.getId())
                        .quantity(1)
                        .build());
        PricingQuoteResponse quote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(1)
                        .rooms(List.of(
                                PricingQuoteRoomRequest.builder()
                                        .roomTypeId(roomType.getId())
                                        .quantity(1)
                                        .lineGuestCount(1)
                                        .build()))
                        .services(services)
                        .build());
        User customer = customer();
        ReservationResponse created = reservationService
                .createReservation(
                        customer,
                        CreateReservationRequest.builder()
                                .checkIn(checkIn)
                                .checkOut(checkOut)
                                .guestCount(1)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(1)
                                                .lineGuestCount(1)
                                                .build()))
                                .services(services)
                                .quoteId(quote.getQuoteId())
                                .quoteHash(quote.getQuoteHash())
                                .build());
        Reservation reservation = reservationRepository
                .findByIdWithDetails(created.getId())
                .orElseThrow();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        ExtendReservationRequest extension =
                new ExtendReservationRequest();
        extension.setNewCheckOut(checkIn.plusHours(25));
        extension.setReason("Gia hạn sang ngày thứ hai");
        reservationService.extendStay(
                reservation.getId(), extension);
        entityManager.flush();
        entityManager.clear();

        var serviceOrder = reservationServiceOrderRepository
                .findDetailedByReservationId(reservation.getId())
                .stream()
                .filter(order -> "EXTRA_ROLLAWAY_BED".equals(
                        order.getServiceCodeSnapshot()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, serviceOrder.getPricingMultiplier());
        assertEquals(2, serviceOrder.getBillableQuantity());
        assertEquals(
                0,
                new java.math.BigDecimal("400000.00")
                        .compareTo(serviceOrder.getTotalPrice()));
        assertEquals(
                0,
                new java.math.BigDecimal("400000.00")
                        .compareTo(
                                reservationServiceOrderRepository
                                        .sumTotalPriceByReservationIdAndStatusIn(
                                                reservation.getId(),
                                                List.of(
                                                        com.hotel.backend.constant
                                                                .ReservationServiceStatus
                                                                .CONFIRMED))));
    }

    @Test
    void extensionIsRejectedWhenProtectedEntitlementWouldOverlapSoldInventory() {
        RoomType roomType = roomTypeRepository.findByCode("STANDARD")
                .orElseThrow();
        int totalRooms = roomTypeRepository
                .countAvailableRoomsByType(roomType.getId());
        assertTrue(totalRooms > 0, "Seed phải có phòng STANDARD bán được");

        LocalDateTime checkIn = LocalDateTime.now().plusDays(5)
                .withHour(8).withMinute(0)
                .withSecond(0).withNano(0);
        LocalDateTime originalCheckOut = checkIn.plusHours(2);
        User customer = customer();

        PricingQuoteResponse originalQuote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(checkIn)
                        .checkOut(originalCheckOut)
                        .guestCount(1)
                        .rooms(List.of(PricingQuoteRoomRequest.builder()
                                .roomTypeId(roomType.getId())
                                .quantity(1)
                                .lineGuestCount(1)
                                .build()))
                        .build());
        ReservationResponse originalCreated = reservationService
                .createReservation(
                        customer,
                        CreateReservationRequest.builder()
                                .checkIn(checkIn)
                                .checkOut(originalCheckOut)
                                .guestCount(1)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(1)
                                                .lineGuestCount(1)
                                                .build()))
                                .quoteId(originalQuote.getQuoteId())
                                .quoteHash(originalQuote.getQuoteHash())
                                .build());
        Reservation original = reservationRepository
                .findByIdWithDetails(originalCreated.getId())
                .orElseThrow();
        original.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(original);

        // The original two-hour stay protects inventory until 10:30.
        // Selling every STANDARD room from 10:45 is valid before extension,
        // but must block an extension whose entitlement protects until 11:30.
        LocalDateTime competingCheckIn =
                originalCheckOut.plusMinutes(45);
        LocalDateTime competingCheckOut =
                competingCheckIn.plusHours(2);
        PricingQuoteResponse competingQuote = pricingQuoteService.createQuote(
                PricingQuoteRequest.builder()
                        .checkIn(competingCheckIn)
                        .checkOut(competingCheckOut)
                        .guestCount(totalRooms)
                        .rooms(List.of(PricingQuoteRoomRequest.builder()
                                .roomTypeId(roomType.getId())
                                .quantity(totalRooms)
                                .lineGuestCount(totalRooms)
                                .build()))
                        .build());
        ReservationResponse competingCreated = reservationService
                .createReservation(
                        customer(),
                        CreateReservationRequest.builder()
                                .checkIn(competingCheckIn)
                                .checkOut(competingCheckOut)
                                .guestCount(totalRooms)
                                .roomTypes(List.of(
                                        RoomTypeItemRequest.builder()
                                                .roomTypeId(roomType.getId())
                                                .quantity(totalRooms)
                                                .lineGuestCount(totalRooms)
                                                .build()))
                                .quoteId(competingQuote.getQuoteId())
                                .quoteHash(competingQuote.getQuoteHash())
                                .build());
        Reservation competing = reservationRepository
                .findByIdWithDetails(competingCreated.getId())
                .orElseThrow();
        competing.setStatus(ReservationStatus.DRAFT);
        reservationRepository.saveAndFlush(competing);

        ExtendReservationRequest extension =
                new ExtendReservationRequest();
        extension.setNewCheckOut(originalCheckOut.plusHours(1));
        extension.setReason("Thử gia hạn chồng tồn phòng đã bán");

        AppException conflict = assertThrows(
                AppException.class,
                () -> reservationService.extendStay(
                        original.getId(), extension));
        assertEquals(ErrorCode.ROOM_NOT_AVAILABLE, conflict.getErrorCode());
        assertEquals(
                originalCheckOut,
                reservationRepository.findById(original.getId())
                        .orElseThrow()
                        .getCheckOut(),
                "Gia hạn thất bại không được đổi thời gian của đơn hiện tại");
    }

    private User customer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .fullName("Pricing V2 " + suffix)
                .username("pricing-v2-" + suffix)
                .email("pricing-v2-" + suffix + "@example.com")
                .phone("09" + Math.abs(
                        UUID.randomUUID().getMostSignificantBits()
                                % 100_000_000L))
                .password("test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User staff() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .fullName("Pricing staff " + suffix)
                .username("pricing-staff-" + suffix)
                .email("pricing-staff-" + suffix + "@example.com")
                .phone("08" + Math.abs(
                        UUID.randomUUID().getMostSignificantBits()
                                % 100_000_000L))
                .password("test")
                .type(UserType.STAFF)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private CustomerProfileRequest walkInCustomer(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String phone = "09" + String.format(
                "%08d",
                Math.abs(UUID.randomUUID().getMostSignificantBits()
                        % 100_000_000L));
        return CustomerProfileRequest.builder()
                .fullName(prefix + " " + suffix)
                .phone(phone)
                .email("walk-in-" + suffix + "@example.com")
                .idCardNumber("01234567" + suffix.substring(0, 4))
                .build();
    }

    private GuestRequest walkInGuest(String name) {
        return GuestRequest.builder()
                .fullName(name)
                .isPrimary(true)
                .build();
    }

    private Room availableRoom(RoomType roomType) {
        return roomRepository.findByRoomTypeId(roomType.getId()).stream()
                .filter(room -> room.getStatus() == RoomStatus.AVAILABLE)
                .filter(room -> room.getCleaningStatus() == CleaningStatus.CLEAN)
                .filter(room -> Boolean.TRUE.equals(room.getSellable()))
                .filter(room -> room.getDecommissionedAt() == null)
                .findFirst()
                .orElseThrow();
    }
}
