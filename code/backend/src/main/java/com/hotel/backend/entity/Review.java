package com.hotel.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.io.Serializable;

@Entity
@Table(
    name = "reviews",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_review_user_reservation_room_type",
            columnNames = {"user_id", "reservation_id", "room_type_id"}
        )
    },
    indexes = {
        @Index(name = "idx_review_room_type", columnList = "room_type_id"),
        @Index(name = "idx_review_reservation", columnList = "reservation_id")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Review extends AbstractEntity<Long> implements Serializable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // review theo loại phòng, không phải phòng vật lý
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    // link reservation để xác minh khách đã ở thật
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Min(1) @Max(5)
    @Column(nullable = false)
    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String comment; // nullable — không bắt buộc viết comment
}
