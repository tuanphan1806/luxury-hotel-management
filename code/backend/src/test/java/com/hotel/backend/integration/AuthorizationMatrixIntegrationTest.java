package com.hotel.backend.integration;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.UserToken;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import com.hotel.backend.constant.TokenType;
import com.hotel.backend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ma trận quyền tối thiểu của dashboard:
 * ADMIN có toàn quyền; STAFF được thao tác vận hành nhưng management chỉ đọc;
 * CUSTOMER không được truy cập API vận hành.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthorizationMatrixIntegrationTest {

    private static final long MISSING_ROOM_ID = 9_999_999L;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired UserTokenRepository userTokenRepository;
    @Autowired JwtService jwtService;

    private String adminToken;
    private String staffToken;
    private String customerToken;

    @BeforeEach
    void createUsers() {
        adminToken = tokenFor(createUser(UserType.ADMIN), UserType.ADMIN);
        staffToken = tokenFor(createUser(UserType.STAFF), UserType.STAFF);
        customerToken = tokenFor(createUser(UserType.CUSTOMER), UserType.CUSTOMER);
    }

    /** STAFF chỉ xem cấu hình phòng, không được xóa phòng thuộc management. */
    @Test
    void staffCannotDeleteRoomButAdminCanReachManagementAction() throws Exception {
        mockMvc.perform(delete("/api/rooms/{id}", MISSING_ROOM_ID)
                        .header("Authorization", bearer(staffToken)))
                .andExpect(status().isForbidden());

        // 404 chứng minh ADMIN đã qua lớp authorization và đi tới service.
        mockMvc.perform(delete("/api/rooms/{id}", MISSING_ROOM_ID)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    /** STAFF được đổi trạng thái vận hành; CUSTOMER phải bị chặn trước service. */
    @Test
    void staffCanReachOperationalRoomActionButCustomerCannot() throws Exception {
        mockMvc.perform(patch("/api/rooms/{id}/status", MISSING_ROOM_ID)
                        .param("status", "AVAILABLE")
                        .header("Authorization", bearer(staffToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/rooms/{id}/status", MISSING_ROOM_ID)
                        .param("status", "AVAILABLE")
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    /** Danh sách vận hành phòng dành cho lễ tân, không dành cho customer. */
    @Test
    void staffCanReadOperationalRoomListButCustomerCannot() throws Exception {
        mockMvc.perform(get("/api/rooms")
                        .header("Authorization", bearer(staffToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rooms")
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    /** Audit detail và audit tổng là dữ liệu giám sát chỉ dành cho ADMIN. */
    @Test
    void auditEndpointsAreAdminOnly() throws Exception {
        for (String token : List.of(staffToken, customerToken)) {
            mockMvc.perform(get("/api/admin/audit-logs")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/monitoring/summary")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/reservations/{id}/audit-logs", MISSING_ROOM_ID)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/monitoring/summary")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reservations/{id}/audit-logs", MISSING_ROOM_ID)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }

    /** Hàng đợi correction là ADMIN-only; STAFF chỉ được tạo request ở route reservation. */
    @Test
    void checkoutExceptionQueueIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/checkout-reconciliation-requests")
                        .header("Authorization", bearer(staffToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/checkout-reconciliation-requests")
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/checkout-reconciliation-requests")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void sePayManualRecoveryCandidatesAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/payments/sepay/recovery-candidates")
                        .header("Authorization", bearer(staffToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payments/sepay/recovery-candidates")
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payments/sepay/recovery-candidates")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
    }

    private User createUser(UserType type) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return userRepository.save(User.builder()
                .fullName("Authorization " + type)
                .username(type.name().toLowerCase() + "_" + suffix)
                .email(type.name().toLowerCase() + "_" + suffix + "@example.com")
                .phone("08" + Math.abs((type.name() + suffix).hashCode() % 100_000_000))
                .password("encoded-for-authorization-test")
                .type(type)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private String tokenFor(User user, UserType type) {
        String token = jwtService.generateAccessToken(
                user.getUsername(),
                List.of("ROLE_" + type.name()),
                user.getSecurityVersion());
        if (type == UserType.ADMIN || type == UserType.STAFF) {
            userTokenRepository.save(UserToken.builder()
                    .userId(user.getId())
                    .accessToken(jwtService.extractJti(token, TokenType.ACCESS_TOKEN))
                    .accessTokenExpiresAt(jwtService.extractExpiration(token, TokenType.ACCESS_TOKEN))
                    .refreshToken("test-refresh-" + UUID.randomUUID())
                    .refreshTokenExpiresAt(jwtService.extractExpiration(token, TokenType.ACCESS_TOKEN))
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
