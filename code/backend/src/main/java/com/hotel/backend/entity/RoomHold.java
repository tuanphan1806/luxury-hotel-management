package com.hotel.backend.entity;

import com.hotel.backend.constant.HoldStatus;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "room_holds",
    indexes = {
        @Index(name = "idx_room_hold_reservation_room_type", columnList = "reservation_room_type_id"),
        @Index(name = "idx_room_hold_status", columnList = "status"),
        @Index(name = "idx_room_hold_expires_at", columnList = "expires_at")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoomHold extends AbstractEntity<Long> implements Serializable {

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_room_type_id", nullable = false)
    private ReservationRoomType reservationRoomType;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt; // trùng thời điểm hết hạn của mã QR thanh toán

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'ACTIVE'")
    @Builder.Default
    private HoldStatus status = HoldStatus.ACTIVE;
}
