package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;


import java.io.Serializable;
import java.math.BigDecimal;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "room_types")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoomType extends AbstractEntity<Long> implements Serializable{

    @Column(name = "type_name", length = 100)
    private String typeName;

    @Column(name = "type_name_en", length = 100)
    private String typeNameEn;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Column(name = "max_guests", nullable = false)
    private Integer maxGuests = 2;
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @ManyToMany
    @JoinTable(
        name = "room_type_facilities",
        joinColumns = @JoinColumn(name = "room_type_id"),
        inverseJoinColumns = @JoinColumn(name = "facility_id")
    )
    @Builder.Default
    private Set<Facility> facilities = new HashSet<>();

    @OneToMany(mappedBy = "roomType")
    private Set<Room> rooms;

    @OneToMany(mappedBy = "roomType")
    private Set<ReservationRoomType> reservations;
}
