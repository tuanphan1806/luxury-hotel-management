package com.hotel.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @Test
    void missingRequiredHeaderReturnsBadRequestWithTheHeaderName() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RequiredHeaderController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/required-header")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("Thiếu header bắt buộc: 'Idempotency-Key'"));
    }

    @Test
    void appExceptionExposesStableCodeForClientRecovery() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RequiredHeaderController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/price-changed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5083))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.PRICE_CHANGED.getMessage()));
    }

    @Test
    void usernameConstraintRaceReturnsAUsefulConflict() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RequiredHeaderController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/duplicate-username")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Tên đăng nhập đã được sử dụng"));
    }

    @RestController
    static class RequiredHeaderController {

        @PostMapping("/required-header")
        String requiredHeader(
                @RequestHeader("Idempotency-Key") String idempotencyKey) {
            return idempotencyKey;
        }

        @PostMapping("/price-changed")
        String priceChanged() {
            throw new AppException(ErrorCode.PRICE_CHANGED);
        }

        @PostMapping("/duplicate-username")
        String duplicateUsername() {
            throw new DataIntegrityViolationException(
                    "duplicate key violates unique constraint \"uk_users_username_case_insensitive\"");
        }
    }
}
