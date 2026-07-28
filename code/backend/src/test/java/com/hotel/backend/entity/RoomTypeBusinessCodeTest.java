package com.hotel.backend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTypeBusinessCodeTest {

    @Test
    void explicitCanonicalCodeRemainsReadableAndStable() {
        RoomType roomType = RoomType.builder()
                .code(" presidential ")
                .typeName("Phòng Tổng thống")
                .build();

        roomType.ensureBusinessCode();

        assertThat(roomType.getCode()).isEqualTo("PRESIDENTIAL");
    }

    @Test
    void customCodeIsDeterministicBoundedAndCollisionResistant() {
        RoomType first = RoomType.builder()
                .typeName("Phòng hướng hồ rất dài với ban công đặc biệt")
                .build();
        RoomType sameName = RoomType.builder()
                .typeName("Phòng hướng hồ rất dài với ban công đặc biệt")
                .build();
        RoomType differentName = RoomType.builder()
                .typeName("Phòng hướng hồ rất dài với ban công cao cấp")
                .build();

        first.ensureBusinessCode();
        sameName.ensureBusinessCode();
        differentName.ensureBusinessCode();

        assertThat(first.getCode())
                .startsWith("CUSTOM_")
                .hasSizeLessThanOrEqualTo(40)
                .isEqualTo(sameName.getCode())
                .isNotEqualTo(differentName.getCode());
    }
}
