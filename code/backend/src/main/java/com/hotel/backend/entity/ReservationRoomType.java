package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "reservation_room_types")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ReservationRoomType extends AbstractEntity<Long> implements Serializable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "room_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal roomPrice; // snapshot giá tại thời điểm đặt

    @Column(name = "subtotal", precision = 12, scale = 2, nullable = false)
    private BigDecimal subtotal; // = roomPrice x quantity x số đêm

    @Builder.Default
    @OneToMany(mappedBy = "reservationRoomType", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ReservationRoom> rooms = new HashSet<>();

    @OneToOne(mappedBy = "reservationRoomType", cascade = CascadeType.ALL, orphanRemoval = true)
    private RoomHold roomHold;
}