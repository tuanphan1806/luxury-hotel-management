package com.hotel.backend.pricing;

import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.request.PricingQuoteRoomRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One canonical representation shared by quote creation and reservation
 * commitment. Keeping this in one place prevents semantically identical
 * requests from receiving different hashes because list order changed.
 */
@Component
public class PricingQuoteRequestNormalizer {

    public Map<String, Object> normalize(PricingQuoteRequest request) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("checkIn", request.getCheckIn());
        normalized.put("checkOut", request.getCheckOut());
        normalized.put("guestCount", request.getGuestCount());
        normalized.put("rooms", request.getRooms().stream()
                .sorted(Comparator.comparing(PricingQuoteRoomRequest::getRoomTypeId))
                .map(room -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("roomTypeId", room.getRoomTypeId());
                    value.put("quantity", room.getQuantity());
                    value.put("lineGuestCount", room.getLineGuestCount());
                    return value;
                })
                .toList());
        normalized.put("services", Optional.ofNullable(request.getServices())
                .orElse(List.of())
                .stream()
                .sorted(Comparator.comparing(ServiceOrderRequest::getServiceId))
                .map(service -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("serviceId", service.getServiceId());
                    value.put("quantity", service.getQuantity());
                    return value;
                })
                .toList());
        return normalized;
    }
}
