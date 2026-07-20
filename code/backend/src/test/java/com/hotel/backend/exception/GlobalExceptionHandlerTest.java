package com.hotel.backend.exception;

import org.junit.jupiter.api.Test;
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

    @RestController
    static class RequiredHeaderController {

        @PostMapping("/required-header")
        String requiredHeader(
                @RequestHeader("Idempotency-Key") String idempotencyKey) {
            return idempotencyKey;
        }
    }
}
