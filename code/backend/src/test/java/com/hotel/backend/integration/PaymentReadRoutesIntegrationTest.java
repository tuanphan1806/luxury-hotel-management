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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for payment read routes with spring.jpa.open-in-view=false.
 *
 * This class intentionally does not use a test-level transaction: a surrounding
 * transaction would keep lazy relations open and hide the production failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentReadRoutesIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired CustomerProfileRepository customerProfileRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired JwtService jwtService;

    private User customer;
    private CustomerProfile customerProfile;
    private Reservation reservation;
    private PaymentTransaction payment;
    private String accessToken;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        customer = userRepository.save(User.builder()
                .fullName("Payment read customer")
                .username("payment_read_" + suffix)
                .email("payment_read_" + suffix + "@example.com")
                .phone("08" + Math.abs(suffix.hashCode() % 100_000_000))
                .password("encoded-for-route-test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());
        customerProfile = customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .source(CustomerProfileSource.ONLINE)
                .linkedUser(customer)
                .build());
        reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("PAY-READ-" + suffix)
                .customerProfile(customerProfile)
                .checkIn(LocalDateTime.now().plusDays(3))
                .checkOut(LocalDateTime.now().plusDays(4))
                .totalAmount(BigDecimal.valueOf(100_000))
                .guestCount(1)
                .status(ReservationStatus.DRAFT)
                .build());
        payment = paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PAY-READ-TXN-" + suffix)
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.SUCCESS)
                .amount(50_000L)
                .expectedAmount(50_000L)
                .receivedAmount(50_000L)
                .acceptedAmount(50_000L)
                .currency("VND")
                .build());
        accessToken = jwtService.generateAccessToken(
                customer.getUsername(),
                List.of("ROLE_CUSTOMER"),
                customer.getSecurityVersion());
    }

    @AfterEach
    void tearDown() {
        if (payment != null && payment.getId() != null) {
            paymentTransactionRepository.deleteById(payment.getId());
        }
        if (reservation != null && reservation.getId() != null) {
            reservationRepository.deleteById(reservation.getId());
        }
        if (customerProfile != null && customerProfile.getId() != null) {
            customerProfileRepository.deleteById(customerProfile.getId());
        }
        if (customer != null && customer.getId() != null) {
            userRepository.deleteById(customer.getId());
        }
    }

    @Test
    void customerCanReadOwnedPaymentWithoutLazyInitializationFailure() throws Exception {
        mockMvc.perform(get("/api/payments/{transactionId}", payment.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(payment.getId()))
                .andExpect(jsonPath("$.bookingId").value(reservation.getId()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void customerCanReadOwnedBookingLedgerWithoutLazyInitializationFailure() throws Exception {
        mockMvc.perform(get("/api/payments/booking/{reservationId}", reservation.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transactionId").value(payment.getId()))
                .andExpect(jsonPath("$[0].bookingId").value(reservation.getId()))
                .andExpect(jsonPath("$[0].acceptedAmount").value(50_000));
    }
}
