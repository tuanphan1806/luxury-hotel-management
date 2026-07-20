package com.hotel.backend.entity;

import com.hotel.backend.constant.AssignStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

import java.io.Serializable;


@Entity
@Table(name = "reservation_rooms")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ReservationRoom extends AbstractEntity<Long> implements Serializable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_room_type_id", nullable = false)
    private ReservationRoomType reservationRoomType;

    // nullable — check-in mới gán phòng cụ thể
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = true)
    private Room room;

    // nullable — chưa assign thì chưa có ai assign
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by", nullable = true)
    private User assignedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'PENDING_ASSIGN'")
    @Builder.Default
    private AssignStatus status = AssignStatus.PENDING_ASSIGN;

    @Builder.Default
    @OneToMany(mappedBy = "reservationRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Guest> guests = new HashSet<>();
}