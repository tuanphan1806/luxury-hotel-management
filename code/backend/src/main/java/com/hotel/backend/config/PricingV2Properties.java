package com.hotel.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "hotel.pricing")
public class PricingV2Properties {

    /**
     * V36 removed the legacy room_types.price source. A fresh/local runtime
     * therefore has to use versioned rate profiles unless an operator
     * deliberately activates the emergency sales stop.
     */
    private boolean engineV2Enabled = true;
    private String engineV2RoomTypeCodes = "";
    private boolean engineV2RequireQuote = false;
    private int quoteTtlMinutes = 15;
    private int maxStayDays = 365;

    public boolean supportsRoomType(String roomTypeCode) {
        if (!engineV2Enabled || roomTypeCode == null || roomTypeCode.isBlank()) {
            return false;
        }
        Set<String> canaryCodes = canaryRoomTypeCodes();
        return canaryCodes.isEmpty()
                || canaryCodes.contains("*")
                || canaryCodes.contains(roomTypeCode.trim().toUpperCase(Locale.ROOT));
    }

    public Set<String> canaryRoomTypeCodes() {
        if (engineV2RoomTypeCodes == null || engineV2RoomTypeCodes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(engineV2RoomTypeCodes.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public int safeQuoteTtlMinutes() {
        return Math.min(Math.max(quoteTtlMinutes, 1), 60);
    }

    public int safeMaxStayDays() {
        return Math.min(Math.max(maxStayDays, 1), 1095);
    }
}
