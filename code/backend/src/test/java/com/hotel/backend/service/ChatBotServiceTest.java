package com.hotel.backend.service;

import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.dto.response.ChatResponse;
import com.hotel.backend.dto.response.AvailabilityResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatBotServiceTest {

    private final ChatBotService service = new ChatBotService(WebClient.builder());

    @Test
    void bookingWithoutStayTimeContinuesConversationInsteadOfGuessing() {
        ChatResponse response = service.askWithAction("Tôi muốn đặt 2 phòng Deluxe cho 4 khách", "chat-test-1");

        assertEquals("CONTINUE_RESERVATION", response.getAction());
        assertTrue(response.getAnswer().contains("ngày/giờ nhận phòng"));
    }

    @Test
    void parserDoesNotMistakeDateOrQuantityForTime() throws Exception {
        Method extractDateTimes = ChatBotService.class.getDeclaredMethod("extractDateTimes", String.class);
        extractDateTimes.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> matches = (List<Object>) extractDateTimes.invoke(
                service,
                "dat 2 phong deluxe tu 15/08/2026 14:00 den 17/08/2026 12:00 cho 4 khach"
        );

        assertEquals(2, matches.size());
        assertTrue(matches.get(0).toString().contains("time=14:00"));
        assertTrue(matches.get(1).toString().contains("time=12:00"));
    }

    @Test
    void availabilityAnswerPrefersServerPricingEstimateOverLegacyHourlyRate() throws Exception {
        Method formatAvailabilityAnswer = ChatBotService.class.getDeclaredMethod(
                "formatAvailabilityAnswer",
                String.class,
                LocalDateTime.class,
                LocalDateTime.class,
                List.class
        );
        formatAvailabilityAnswer.setAccessible(true);

        AvailabilityResponse availability = AvailabilityResponse.builder()
                .roomTypeId(1L)
                .roomTypeName("Phòng Deluxe")
                .availableRooms(2)
                .totalRooms(3)
                .pricePerHour(new BigDecimal("100000"))
                .estimatedPricePerRoom(new BigDecimal("220000"))
                .estimatedPackage(StayPackage.OVERNIGHT)
                .build();

        String answer = (String) formatAvailabilityAnswer.invoke(
                service,
                "Phòng Deluxe còn trống không?",
                LocalDateTime.of(2026, 8, 15, 20, 0),
                LocalDateTime.of(2026, 8, 16, 8, 0),
                List.of(availability)
        );

        assertTrue(answer.contains("ước tính 220.000 đ/phòng (qua đêm"));
        assertTrue(answer.contains("chưa gồm khách thêm/dịch vụ"));
        assertTrue(!answer.contains("/giờ"));
    }
}
