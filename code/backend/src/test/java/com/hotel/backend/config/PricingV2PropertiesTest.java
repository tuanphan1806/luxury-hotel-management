package com.hotel.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingV2PropertiesTest {

    @Test
    void versionedPricingIsEnabledByDefaultAfterLegacyPriceRemoval() {
        PricingV2Properties properties = new PricingV2Properties();

        assertTrue(properties.supportsRoomType("STANDARD"));
        assertTrue(properties.supportsRoomType("CUSTOM_NEW_ROOM_123"));
    }

    @Test
    void wildcardSupportsRoomTypesCreatedAfterDeployment() {
        PricingV2Properties properties = new PricingV2Properties();
        properties.setEngineV2Enabled(true);
        properties.setEngineV2RoomTypeCodes("*");

        assertTrue(properties.supportsRoomType("CUSTOM_NEW_ROOM_123"));
    }

    @Test
    void disabledEngineRejectsEvenWildcardRoomTypes() {
        PricingV2Properties properties = new PricingV2Properties();
        properties.setEngineV2Enabled(false);
        properties.setEngineV2RoomTypeCodes("*");

        assertFalse(properties.supportsRoomType("STANDARD"));
    }
}
