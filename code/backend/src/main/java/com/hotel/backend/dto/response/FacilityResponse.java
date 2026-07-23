package com.hotel.backend.dto.response;

import lombok.*;

import java.util.List;


/**
 * Payload trả về cho client sau mọi thao tác với Facility.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FacilityResponse {

    private Long id;
    private String facilityName;
    private String facilityNameEn;
    private String type;
    private String description;
    private String descriptionEn;
    private String icon;
    private String imageUrl;
    private List<String> imageUrls;

    /** Dạng rút gọn — nhúng trong RoomTypeResponse.facilities. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private Long id;
        private String facilityName;
        private String facilityNameEn;
        private String type;
        private String imageUrl;
        private List<String> imageUrls;
    }
}
