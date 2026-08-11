package com.hotel.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoReviewSeedCatalogTest {

    @Test
    void providesTenStableReviewFixtures() {
        var entries = DemoReviewSeedCatalog.entries();

        assertThat(entries).hasSize(10);
        assertThat(DemoReviewSeedCatalog.roomTypeCodes())
                .containsExactly(
                        "STANDARD", "DELUXE", "EXECUTIVE",
                        "SUITE", "FAMILY", "PRESIDENTIAL")
                .doesNotHaveDuplicates();
        assertThat(entries.size()
                * DemoReviewSeedCatalog.roomTypeCodes().size())
                .isEqualTo(60);
        assertThat(entries)
                .extracting(DemoReviewSeedCatalog.Entry::reservationCode)
                .doesNotHaveDuplicates();
        assertThat(entries)
                .allSatisfy(entry -> {
                    assertThat(entry.rating()).isBetween(1, 5);
                    assertThat(entry.commentFor("Phòng tiêu chuẩn"))
                            .contains("Phòng tiêu chuẩn")
                            .hasSizeLessThanOrEqualTo(1000);
                });
    }
}
