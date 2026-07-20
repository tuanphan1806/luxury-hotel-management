package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.HashSet;
import java.util.Set;
import java.io.Serializable;
@Entity
@Table(name = "facilities")
@Getter
@Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Facility extends AbstractEntity<Long> implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_name")
    private String facilityName;

    @Column(name = "facility_name_en")
    private String facilityNameEn;

    @Column(name = "type")
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;

    @Builder.Default
    @ManyToMany(mappedBy = "facilities")
    private Set<RoomType> roomTypes = new HashSet<>();

    @Column(name = "image_url", length = 500)
    private String imageUrl;
}
