package com.hotel.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OperationsAttentionResponse {
    private int total;
    private List<Item> items;

    @Data
    @Builder
    public static class Item {
        private String type;
        private String severity;
        private Long reservationId;
        private String reservationCode;
        private String customerName;
        private String title;
        private String detail;
        private LocalDateTime dueAt;
        private Long amount;
    }
}
