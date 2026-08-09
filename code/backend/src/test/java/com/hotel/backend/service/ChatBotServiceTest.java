package com.hotel.backend.service;

import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.dto.response.ChatResponse;
import com.hotel.backend.dto.response.AvailabilityResponse;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.service.chatbot.ChatbotPublicDataGateway;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatBotServiceTest {

    private final ChatbotPublicDataGateway publicDataGateway = mock(ChatbotPublicDataGateway.class);
    private final ChatBotService service = new ChatBotService(
            WebClient.builder(),
            publicDataGateway
    );

    @Test
    void roomCatalogQuestionUsesInProcessPublicDataGateway() {
        when(publicDataGateway.getRoomTypes()).thenReturn(List.of(
                RoomTypeResponse.builder()
                        .id(1L)
                        .typeName("Phòng tiêu chuẩn")
                        .overnightPrice(new BigDecimal("170000"))
                        .dailyPrice(new BigDecimal("300000"))
                        .build()
        ));

        ChatResponse response = service.askWithAction(
                "Khách sạn có phòng tiêu chuẩn không?",
                "chat-gateway-test"
        );

        assertTrue(response.getAnswer().contains("Phòng tiêu chuẩn"));
        assertTrue(response.getAnswer().contains("170.000 đ"));
    }

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
    void availabilityAnswerUsesTheServerPricingEstimate() throws Exception {
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

    @Test
    void roomTierQuestionUsesDeterministicPackagePricingWithoutGemini() throws Exception {
        Method answerRoomTypeQuestion = ChatBotService.class.getDeclaredMethod(
                "answerRoomTypeQuestion",
                String.class,
                String.class,
                List.class
        );
        answerRoomTypeQuestion.setAccessible(true);

        RoomTypeResponse standard = RoomTypeResponse.builder()
                .id(1L)
                .typeName("Phòng tiêu chuẩn")
                .description("Phù hợp cho kỳ nghỉ gọn nhẹ")
                .firstBlockPrice(new BigDecimal("70000"))
                .overnightPrice(new BigDecimal("170000"))
                .dailyPrice(new BigDecimal("300000"))
                .build();

        @SuppressWarnings("unchecked")
        var answer = (java.util.Optional<String>) answerRoomTypeQuestion.invoke(
                service,
                "Khách sạn có những hạng phòng nào?",
                "khach san co nhung hang phong nao",
                List.of(standard)
        );

        assertTrue(answer.isPresent());
        assertTrue(answer.orElseThrow().contains("qua đêm 170.000 đ"));
        assertTrue(answer.orElseThrow().contains("ngày đêm 300.000 đ"));
        assertTrue(!answer.orElseThrow().contains("/giờ"));
    }
}
