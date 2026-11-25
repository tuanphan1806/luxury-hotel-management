package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Embeddable
@Getter @AllArgsConstructor @NoArgsConstructor
public class RoomServiceAllocation {
    @Column(name = "service_order_id", nullable = false)
    private Long orderId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
}
