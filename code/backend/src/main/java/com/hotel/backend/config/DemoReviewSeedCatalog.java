package com.hotel.backend.config;

import java.util.List;

/**
 * Stable, deterministic review fixtures used only by the opt-in demo seeder.
 * Keeping the content catalog separate makes the expected count and identity
 * easy to test without bootstrapping Spring or a database.
 */
final class DemoReviewSeedCatalog {

    private static final List<String> ROOM_TYPE_CODES = List.of(
            "STANDARD", "DELUXE", "EXECUTIVE",
            "SUITE", "FAMILY", "PRESIDENTIAL");

    private DemoReviewSeedCatalog() {
    }

    static List<Entry> entries() {
        return List.of(
                entry(1, "customer1", 5,
                        "%s sạch sẽ, đúng như hình và nhân viên hỗ trợ rất nhanh."),
                entry(2, "customer2", 4,
                        "%s yên tĩnh, giường thoải mái và thủ tục nhận phòng rõ ràng."),
                entry(3, "vtmai", 5,
                        "Gia đình tôi hài lòng với %s; tiện nghi đầy đủ và phòng được chuẩn bị chu đáo."),
                entry(4, "btngocc", 4,
                        "%s có bố cục hợp lý, ảnh mô tả sát thực tế và khu vực chung được giữ sạch."),
                entry(5, "tvkhoa", 3,
                        "%s nhìn chung ổn, phòng sạch nhưng thời gian chờ hỗ trợ lúc cao điểm còn hơi lâu."),
                entry(6, "customer1", 5,
                        "Trải nghiệm tại %s rất tốt, không gian riêng tư và giấc ngủ thoải mái."),
                entry(7, "customer2", 4,
                        "%s phù hợp với nhu cầu của chúng tôi, giá và các khoản thanh toán đều minh bạch."),
                entry(8, "vtmai", 5,
                        "Tôi sẽ chọn lại %s vì phòng sáng, gọn gàng và đội ngũ lễ tân thân thiện."),
                entry(9, "btngocc", 4,
                        "%s được bảo trì tốt, tiện nghi hoạt động ổn định và check-out thuận tiện."),
                entry(10, "tvkhoa", 5,
                        "Kỳ nghỉ ở %s rất trọn vẹn; phòng đẹp, sạch và dịch vụ đúng như cam kết."));
    }

    static List<String> roomTypeCodes() {
        return ROOM_TYPE_CODES;
    }

    private static Entry entry(
            int sequence,
            String username,
            int rating,
            String commentTemplate) {
        return new Entry(
                sequence,
                username,
                rating,
                commentTemplate);
    }

    record Entry(
            int sequence,
            String username,
            int rating,
            String commentTemplate) {

        String reservationCode() {
            return "DEMO-REVIEW-STAY-%02d".formatted(sequence);
        }

        String commentFor(String roomTypeName) {
            return commentTemplate.formatted(roomTypeName);
        }
    }
}
