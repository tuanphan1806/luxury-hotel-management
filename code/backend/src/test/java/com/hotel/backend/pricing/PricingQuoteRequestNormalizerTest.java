package com.hotel.backend.pricing;

import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.request.PricingQuoteRoomRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricingQuoteRequestNormalizerTest {

    private final PricingQuoteRequestNormalizer normalizer =
            new PricingQuoteRequestNormalizer();

    @Test
    void serviceNotesDoNotInvalidateAnOtherwiseIdenticalFinancialQuote() {
        PricingQuoteRequest first = request("Chuẩn bị trước khi khách đến");
        PricingQuoteRequest second = request("Ghi chú được sửa sau khi báo giá");

        assertThat(normalizer.normalize(first))
                .isEqualTo(normalizer.normalize(second));
    }

    @Test
    void serviceQuantityRemainsPartOfTheFinancialQuoteIdentity() {
        PricingQuoteRequest first = request("Không ảnh hưởng giá");
        PricingQuoteRequest second = request("Không ảnh hưởng giá");
        second.getServices().get(0).setQuantity(2);

        assertThat(normalizer.normalize(first))
                .isNotEqualTo(normalizer.normalize(second));
    }

    private PricingQuoteRequest request(String notes) {
        return PricingQuoteRequest.builder()
                .checkIn(LocalDateTime.of(2026, 8, 1, 20, 0))
                .checkOut(LocalDateTime.of(2026, 8, 2, 8, 0))
                .guestCount(1)
                .rooms(List.of(PricingQuoteRoomRequest.builder()
                        .roomTypeId(1L)
                        .quantity(1)
                        .lineGuestCount(1)
                        .build()))
                .services(List.of(ServiceOrderRequest.builder()
                        .serviceId(7L)
                        .quantity(1)
                        .notes(notes)
                        .build()))
                .build();
    }
}
