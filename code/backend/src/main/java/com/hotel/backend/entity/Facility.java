package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /**
     * Ordered catalogue images. imageUrl remains the compatibility alias for
     * the first item while older clients are being phased out.
     */
    @Builder.Default
    @ElementCollection
    @CollectionTable(
            name = "facility_images",
            joinColumns = @JoinColumn(name = "facility_id"))
    @OrderColumn(name = "display_order")
    @Column(name = "image_url", nullable = false, length = 500)
    @BatchSize(size = 50)
    private List<String> imageUrls = new ArrayList<>();
}
