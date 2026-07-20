package com.hotel.backend.integration;

import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test cho các route "của tôi" sau khi frontend chuyển refresh token
 * sang cookie HttpOnly. Route phải trả 401 khi chưa có access token để
 * Axios interceptor thực hiện refresh; khi có token hợp lệ phải trả 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthenticatedCustomerRoutesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerProfileRepository customerProfileRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private JwtService jwtService;

    private String accessToken;
    private User customer;

    @BeforeEach
    void createActiveCustomer() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        customer = userRepository.save(User.builder()
                .fullName("Khách kiểm thử bảo mật")
                .username("security_customer_" + suffix)
                .email("security_" + suffix + "@example.com")
                .phone("09" + Math.abs(suffix.hashCode() % 100_000_000))
                .password("encoded-for-route-test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());

        customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .source(CustomerProfileSource.ONLINE)
                .linkedUser(customer)
                .build());

        accessToken = jwtService.generateAccessToken(
                customer.getUsername(),
                List.of("ROLE_CUSTOMER"),
                customer.getSecurityVersion());
    }

    /** Chưa có access token phải trả 401, không trả 403 do /{id} match nhầm /my. */
    @Test
    void myReservationsWithoutAccessTokenReturnsUnauthorizedForRefreshFlow() throws Exception {
        mockMvc.perform(get("/api/reservations/my"))
                .andExpect(status().isUnauthorized());
    }

    /** Access token CUSTOMER hợp lệ được xem danh sách reservation của chính mình. */
    @Test
    void myReservationsWithAccessTokenReturnsOwnList() throws Exception {
        mockMvc.perform(get("/api/reservations/my")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * Dữ liệu cũ có thể đã tạo reservation dưới hồ sơ guest dù user
     * đã đăng nhập. Khi mở My Booking, backend phải liên kết lại theo
     * email đã xác thực và trả reservation đó cho đúng chủ sở hữu.
     */
    @Test
    void myReservationsClaimsLegacyGuestBookingWithVerifiedEmail() throws Exception {
        CustomerProfile legacyGuestProfile = customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName())
                .phone("0812345678")
                .email(customer.getEmail())
                .source(CustomerProfileSource.ONLINE)
                .build());

        String reservationCode = "LEGACY-" + UUID.randomUUID().toString().substring(0, 8);
        reservationRepository.save(Reservation.builder()
                .reservationCode(reservationCode)
                .guestToken(UUID.randomUUID().toString())
                .customerProfile(legacyGuestProfile)
                .checkIn(LocalDateTime.now().plusDays(2))
                .checkOut(LocalDateTime.now().plusDays(3))
                .totalAmount(BigDecimal.valueOf(500_000))
                .guestCount(1)
                .status(ReservationStatus.CONFIRMED)
                .build());

        mockMvc.perform(get("/api/reservations/my")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reservationCode == '%s')]", reservationCode).exists());
    }

    /** /reviews/my là route bảo vệ; anonymous phải nhận 401 để frontend refresh token. */
    @Test
    void myReviewsWithoutAccessTokenReturnsUnauthorizedForRefreshFlow() throws Exception {
        mockMvc.perform(get("/api/reviews/my"))
                .andExpect(status().isUnauthorized());
    }

    /** JWT filter không được skip /reviews/my khi user gửi Bearer token hợp lệ. */
    @Test
    void myReviewsWithAccessTokenReturnsOwnList() throws Exception {
        mockMvc.perform(get("/api/reviews/my")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * Trang VNPay return phải xác minh trạng thái bằng UUID giao dịch tại backend,
     * không tin tham số status trên URL và không yêu cầu access token của khách.
     */
    @Test
    void publicPaymentResultReturnsOnlyVerifiedTransactionState() throws Exception {
        CustomerProfile profile = customerProfileRepository.findByLinkedUserId(customer.getId()).orElseThrow();
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("PAY-" + UUID.randomUUID().toString().substring(0, 8))
                .customerProfile(profile)
                .checkIn(LocalDateTime.now().plusDays(2))
                .checkOut(LocalDateTime.now().plusDays(3))
                .totalAmount(BigDecimal.valueOf(600_000))
                .guestCount(1)
                .status(ReservationStatus.DRAFT)
                .build());
        PaymentTransaction transaction = paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PUBLIC-RESULT-" + UUID.randomUUID())
                .provider(PaymentProvider.VNPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.SUCCESS)
                .amount(300_000L)
                .currency("VND")
                .build());

        mockMvc.perform(get("/api/payments/result/{transactionId}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getId()))
                .andExpect(jsonPath("$.transactionReference").value(transaction.getTxnRef()))
                .andExpect(jsonPath("$.bookingId").value(reservation.getId()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.purpose").value("DEPOSIT"))
                .andExpect(jsonPath("$.customerProfile").doesNotExist())
                .andExpect(jsonPath("$.guestToken").doesNotExist());
    }

    /** Guest token được truyền bằng header, không xuất hiện trong query string/access log. */
    @Test
    void guestLookupAcceptsTokenHeaderWithoutAuthentication() throws Exception {
        String guestToken = UUID.randomUUID().toString();
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("GUEST-" + UUID.randomUUID().toString().substring(0, 8))
                .guestToken(guestToken)
                .customerProfile(customerProfileRepository.findByLinkedUserId(customer.getId()).orElseThrow())
                .checkIn(LocalDateTime.now().plusDays(4))
                .checkOut(LocalDateTime.now().plusDays(5))
                .totalAmount(BigDecimal.valueOf(450_000))
                .guestCount(1)
                .status(ReservationStatus.CONFIRMED)
                .build());

        mockMvc.perform(post("/api/reservations/lookup")
                        .header("X-Guest-Token", guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(reservation.getId()))
                .andExpect(jsonPath("$.data.reservationCode").value(reservation.getReservationCode()));
    }
}
