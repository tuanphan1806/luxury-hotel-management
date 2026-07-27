package com.hotel.backend.controller;

import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.PricingQuoteResponse;
import com.hotel.backend.security.ClientIpResolver;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.PricingQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingQuoteService pricingQuoteService;
    private final AuthRateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Operation(
            summary = "Create a server-authoritative room price quote",
            description = "Returns versioned line-level pricing. Reservation creation recalculates it in a transaction.")
    @PostMapping("/quote")
    public ApiResponse<PricingQuoteResponse> quote(
            @Valid @RequestBody PricingQuoteRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        rateLimitService.check(
                "pricing-quote-ip:" + clientIp,
                120,
                Duration.ofMinutes(15));
        return ApiResponse.success(pricingQuoteService.createQuote(request));
    }
}
