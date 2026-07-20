// package com.hotel.backend.config;

// import com.hotel.backend.constant.AssignStatus;
// import com.hotel.backend.constant.CleaningStatus;
// import com.hotel.backend.constant.CustomerProfileSource;
// import com.hotel.backend.constant.HoldStatus;
// import com.hotel.backend.constant.IdCardType;
// import com.hotel.backend.constant.PaymentProvider;
// import com.hotel.backend.constant.PaymentStatus;
// import com.hotel.backend.constant.ReservationStatus;
// import com.hotel.backend.constant.RoomStatus;
// import com.hotel.backend.entity.CustomerProfile;
// import com.hotel.backend.entity.Guest;
// import com.hotel.backend.entity.PaymentTransaction;
// import com.hotel.backend.entity.Reservation;
// import com.hotel.backend.entity.ReservationRoom;
// import com.hotel.backend.entity.ReservationRoomType;
// import com.hotel.backend.entity.Review;
// import com.hotel.backend.entity.Room;
// import com.hotel.backend.entity.RoomHold;
// import com.hotel.backend.entity.RoomType;
// import com.hotel.backend.entity.User;
// import com.hotel.backend.repository.CustomerProfileRepository;
// import com.hotel.backend.repository.GuestRepository;
// import com.hotel.backend.repository.PaymentTransactionRepository;
// import com.hotel.backend.repository.ReservationRepository;
// import com.hotel.backend.repository.ReservationRoomRepository;
// import com.hotel.backend.repository.ReservationRoomTypeRepository;
// import com.hotel.backend.repository.ReviewRepository;
// import com.hotel.backend.repository.RoomHoldRepository;
// import com.hotel.backend.repository.RoomRepository;
// import com.hotel.backend.repository.RoomTypeRepository;
// import com.hotel.backend.repository.UserRepository;
// import lombok.RequiredArgsConstructor;
// import org.springframework.boot.CommandLineRunner;
// import org.springframework.context.annotation.Profile;
// import org.springframework.core.annotation.Order;
// import org.springframework.stereotype.Component;
// import org.springframework.transaction.annotation.Transactional;

// import java.math.BigDecimal;
// import java.time.LocalDate;
// import java.time.LocalDateTime;
// import java.time.temporal.ChronoUnit;

// @Component
// @Profile("dev")
// @RequiredArgsConstructor
// @Order(2)
// public class DemoDataSeeder implements CommandLineRunner {

//     private final ReservationRepository reservationRepository;
//     private final ReservationRoomTypeRepository reservationRoomTypeRepository;
//     private final ReservationRoomRepository reservationRoomRepository;
//     private final RoomHoldRepository roomHoldRepository;
//     private final RoomTypeRepository roomTypeRepository;
//     private final RoomRepository roomRepository;
//     private final UserRepository userRepository;
//     private final CustomerProfileRepository customerProfileRepository;
//     private final GuestRepository guestRepository;
//     private final PaymentTransactionRepository paymentTransactionRepository;
//     private final ReviewRepository reviewRepository;

//     @Override
//     @Transactional
//     public void run(String... args) {
//         User customer1 = user("customer1");
//         User customer2 = user("customer2");
//         User customer3 = user("vtmai");
//         User staff1 = user("staff1");

//         RoomType standard = roomType("Standard");
//         RoomType deluxe = roomType("Deluxe");
//         RoomType suite = roomType("Suite");
//         RoomType family = roomType("Family");

//         seedDraftReservation(customer1, standard);
//         seedConfirmedReservation(customer2, deluxe);
//         seedCheckedInReservation(customer3, suite, room("401"), staff1);
//         seedCheckedOutReservation(customer1, family, room("302"), staff1);
//     }

//     private void seedDraftReservation(User customer, RoomType roomType) {
//         String code = "DEMO-DRAFT-001";
//         if (reservationRepository.existsByReservationCode(code)) {
//             return;
//         }

//         LocalDateTime checkIn = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
//         LocalDateTime checkOut = checkIn.plusDays(1);
//         BigDecimal total = calculateTotal(roomType, 1, checkIn, checkOut);

//         Reservation reservation = createReservation(code, customer, checkIn, checkOut, total,
//                 1, "Demo online booking: DRAFT + hold ACTIVE", ReservationStatus.DRAFT);
//         ReservationRoomType rrt = createReservationRoomType(reservation, roomType, 1, total);
//         createRoomHold(rrt, HoldStatus.ACTIVE, LocalDateTime.now().plusMinutes(15));
//         createReservationRoom(rrt, null, null, AssignStatus.PENDING_ASSIGN);
//     }

//     private void seedConfirmedReservation(User customer, RoomType roomType) {
//         String code = "DEMO-CONFIRMED-001";
//         if (reservationRepository.existsByReservationCode(code)) {
//             return;
//         }

//         LocalDateTime checkIn = LocalDateTime.now().plusDays(5).withHour(14).withMinute(0).withSecond(0).withNano(0);
//         LocalDateTime checkOut = checkIn.plusDays(2);
//         BigDecimal total = calculateTotal(roomType, 1, checkIn, checkOut);

//         Reservation reservation = createReservation(code, customer, checkIn, checkOut, total,
//                 2, "Demo online booking: paid deposit/full and confirmed", ReservationStatus.CONFIRMED);
//         ReservationRoomType rrt = createReservationRoomType(reservation, roomType, 1, total);
//         createRoomHold(rrt, HoldStatus.CONVERTED, LocalDateTime.now().plusMinutes(15));
//         createReservationRoom(rrt, null, null, AssignStatus.PENDING_ASSIGN);
//         createSuccessPayment(reservation, "DEMO-PAY-CONFIRMED-001", PaymentProvider.VNPAY,
//                 total.longValue(), "Demo VNPay payment for confirmed reservation");
//     }

//     private void seedCheckedInReservation(User customer, RoomType roomType, Room room, User staff) {
//         String code = "DEMO-CHECKED-IN-001";
//         if (reservationRepository.existsByReservationCode(code)) {
//             return;
//         }

//         LocalDateTime checkIn = LocalDateTime.now().minusHours(2).withMinute(0).withSecond(0).withNano(0);
//         LocalDateTime checkOut = checkIn.plusDays(1);
//         BigDecimal total = calculateTotal(roomType, 1, checkIn, checkOut);

//         Reservation reservation = createReservation(code, customer, checkIn, checkOut, total,
//                 1, "Demo walk-in/online booking currently checked in", ReservationStatus.CHECKED_IN);
//         ReservationRoomType rrt = createReservationRoomType(reservation, roomType, 1, total);
//         ReservationRoom rr = createReservationRoom(rrt, room, staff, AssignStatus.CHECKED_IN);
//         createGuest(rr, "Vũ Thị Mai", "0901000006", "customer3@gmail.com", "012345678901",
//                 IdCardType.CCCD, LocalDate.of(1994, 4, 12), true);
//         createSuccessPayment(reservation, "DEMO-PAY-CHECKED-IN-001", PaymentProvider.VNPAY,
//                 total.divide(BigDecimal.valueOf(2)).longValue(), "Demo partial payment for checked-in reservation");

//         room.setStatus(RoomStatus.CHECKED_IN);
//         room.setCleaningStatus(CleaningStatus.CLEAN);
//         roomRepository.save(room);
//     }

//     private void seedCheckedOutReservation(User customer, RoomType roomType, Room room, User staff) {
//         String code = "DEMO-CHECKED-OUT-001";
//         if (reservationRepository.existsByReservationCode(code)) {
//             return;
//         }

//         LocalDateTime checkIn = LocalDateTime.now().minusDays(4).withHour(14).withMinute(0).withSecond(0).withNano(0);
//         LocalDateTime checkOut = checkIn.plusDays(2);
//         BigDecimal total = calculateTotal(roomType, 1, checkIn, checkOut);

//         Reservation reservation = createReservation(code, customer, checkIn, checkOut, total,
//                 3, "Demo completed stay with full payment and review", ReservationStatus.CHECKED_OUT);
//         ReservationRoomType rrt = createReservationRoomType(reservation, roomType, 1, total);
//         ReservationRoom rr = createReservationRoom(rrt, room, staff, AssignStatus.CHECKED_OUT);
//         createGuest(null, "Phạm Thị Hoa", "0901000004", "customer1@gmail.com", "098765432109",
//                 IdCardType.CCCD, LocalDate.of(1991, 9, 20), true);
//         createSuccessPayment(reservation, "DEMO-PAY-CHECKED-OUT-001", PaymentProvider.CASH,
//                 total.longValue(), "Demo cash payment for checked-out reservation");
//         createReview(customer, reservation, roomType, 5, "Phòng rộng, sạch, nhân viên hỗ trợ rất nhanh.");

//         room.setStatus(RoomStatus.AVAILABLE);
//         room.setCleaningStatus(CleaningStatus.CLEAN);
//         roomRepository.save(room);
//     }

//     private Reservation createReservation(String code, User customer, LocalDateTime checkIn, LocalDateTime checkOut,
//                                           BigDecimal total, Integer guestCount, String note,
//                                           ReservationStatus status) {
//         Reservation reservation = Reservation.builder()
//                 .reservationCode(code)
//                 .customerProfile(customerProfile(customer))
//                 .checkIn(checkIn)
//                 .checkOut(checkOut)
//                 .totalAmount(total)
//                 .guestCount(guestCount)
//                 .note(note)
//                 .status(status)
//                 .build();
//         return reservationRepository.save(reservation);
//     }

//     private ReservationRoomType createReservationRoomType(Reservation reservation, RoomType roomType,
//                                                           int quantity, BigDecimal subtotal) {
//         ReservationRoomType rrt = ReservationRoomType.builder()
//                 .reservation(reservation)
//                 .roomType(roomType)
//                 .quantity(quantity)
//                 .roomPrice(roomType.getPrice())
//                 .subtotal(subtotal)
//                 .build();
//         return reservationRoomTypeRepository.save(rrt);
//     }

//     private ReservationRoom createReservationRoom(ReservationRoomType rrt, Room room, User assignedBy,
//                                                   AssignStatus status) {
//         ReservationRoom rr = ReservationRoom.builder()
//                 .reservationRoomType(rrt)
//                 .room(room)
//                 .assignedBy(assignedBy)
//                 .status(status)
//                 .build();
//         return reservationRoomRepository.save(rr);
//     }

//     private void createRoomHold(ReservationRoomType rrt, HoldStatus status, LocalDateTime expiresAt) {
//         RoomHold hold = RoomHold.builder()
//                 .reservationRoomType(rrt)
//                 .status(status)
//                 .expiresAt(expiresAt)
//                 .build();
//         roomHoldRepository.save(hold);
//     }

//     private void createSuccessPayment(Reservation reservation, String txnRef, PaymentProvider provider,
//                                       Long amount, String orderInfo) {
//         if (paymentTransactionRepository.findByTxnRef(txnRef).isPresent()) {
//             return;
//         }

//         PaymentTransaction transaction = PaymentTransaction.builder()
//                 .reservation(reservation)
//                 .txnRef(txnRef)
//                 .providerTxnId(txnRef + "-PROVIDER")
//                 .provider(provider)
//                 .status(PaymentStatus.SUCCESS)
//                 .amount(amount)
//                 .currency("VND")
//                 .orderInfo(orderInfo)
//                 .ipAddress("127.0.0.1")
//                 .responseCode("00")
//                 .message("Demo payment success")
//                 .paidAt(LocalDateTime.now().minusMinutes(30))
//                 .build();
//         paymentTransactionRepository.save(transaction);
//     }

//     private void createGuest(ReservationRoom reservationRoom, String fullName, String phone, String email,
//                              String idCardNumber, IdCardType idCardType, LocalDate dateOfBirth,
//                              boolean primary) {
//         Guest guest = Guest.builder()
//                 .reservationRoom(reservationRoom)
//                 .fullName(fullName)
//                 .phone(phone)
//                 .email(email)
//                 .idCardNumber(idCardNumber)
//                 .idCardType(idCardType)
//                 .dateOfBirth(dateOfBirth)
//                 .nationality("Việt Nam")
//                 .isPrimary(primary)
//                 .checkedOutAt(reservationRoom == null ? LocalDateTime.now().minusDays(2) : null)
//                 .build();
//         guestRepository.save(guest);
//     }

//     private void createReview(User user, Reservation reservation, RoomType roomType, Integer rating, String comment) {
//         if (reviewRepository.existsByUserIdAndReservationIdAndRoomTypeId(
//                 user.getId(), reservation.getId(), roomType.getId())) {
//             return;
//         }

//         Review review = Review.builder()
//                 .user(user)
//                 .reservation(reservation)
//                 .roomType(roomType)
//                 .rating(rating)
//                 .comment(comment)
//                 .build();
//         reviewRepository.save(review);
//     }

//     private BigDecimal calculateTotal(RoomType roomType, int quantity,
//                                   LocalDateTime checkIn, LocalDateTime checkOut) {
//     long totalMinutes = ChronoUnit.MINUTES.between(checkIn, checkOut);
//     long totalHours = (totalMinutes + 59) / 60;
//     BigDecimal extraHourFee = new BigDecimal("10000");
//     BigDecimal pricePerRoom = totalHours <= 1
//             ? roomType.getPrice()
//             : roomType.getPrice().add(extraHourFee.multiply(BigDecimal.valueOf(totalHours - 1)));
//     return pricePerRoom.multiply(BigDecimal.valueOf(quantity));
// }

//     private User user(String username) {
//         return userRepository.findByUsername(username)
//                 .orElseThrow(() -> new IllegalStateException("Missing seeded user: " + username));
//     }

//     private CustomerProfile customerProfile(User user) {
//         return customerProfileRepository.findByLinkedUserId(user.getId())
//                 .orElseGet(() -> customerProfileRepository.save(CustomerProfile.builder()
//                         .fullName(user.getFullName())
//                         .phone(user.getPhone())
//                         .email(user.getEmail())
//                         .address(user.getAddress())
//                         .source(CustomerProfileSource.ONLINE)
//                         .linkedUser(user)
//                         .build()));
//     }

//     private RoomType roomType(String typeName) {
//         return roomTypeRepository.findByTypeName(typeName)
//                 .orElseThrow(() -> new IllegalStateException("Missing seeded room type: " + typeName));
//     }

//     private Room room(String roomName) {
//         return roomRepository.findByRoomName(roomName)
//                 .orElseThrow(() -> new IllegalStateException("Missing seeded room: " + roomName));
//     }
// }
