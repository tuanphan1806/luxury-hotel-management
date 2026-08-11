package com.hotel.backend.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

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

    @Test
    void constrainedQueryParameterReturnsBadRequestInsteadOfInternalServerError() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(null);
        when(violation.getMessage()).thenReturn("phải nhỏ hơn hoặc bằng 12");

        ConstraintViolationException exception = new ConstraintViolationException(Set.of(violation));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/reviews/room-type/1/page");

        var response = new GlobalExceptionHandler().handleConstraintViolation(exception, request);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Validation Failed", response.getBody().getError());
        assertEquals("Tham số yêu cầu không hợp lệ", response.getBody().getMessage());
        assertEquals("/api/reviews/room-type/1/page", response.getBody().getPath());
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
