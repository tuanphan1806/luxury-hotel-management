package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;


import java.io.Serializable;
import java.math.BigDecimal;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "room_types")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoomType extends AbstractEntity<Long> implements Serializable{

    @Column(name = "code", nullable = false, unique = true, length = 40, updatable = false)
    private String code;

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

    /**
     * Ordered room gallery. imageUrl remains the compatibility alias for the
     * first item so reservation/payment consumers do not change contract.
     */
    @Builder.Default
    @ElementCollection
    @CollectionTable(
            name = "room_type_images",
            joinColumns = @JoinColumn(name = "room_type_id"))
    @OrderColumn(name = "display_order")
    @Column(name = "image_url", nullable = false, length = 500)
    @BatchSize(size = 50)
    private List<String> imageUrls = new ArrayList<>();

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

    @PrePersist
    void ensureBusinessCode() {
        if (code != null && !code.isBlank()) {
            String normalizedCode = normalizeCode(code);
            code = normalizedCode.isBlank()
                    ? randomBusinessCode()
                    : fitCode(normalizedCode, code);
            return;
        }
        String source = typeNameEn != null && !typeNameEn.isBlank()
                ? typeNameEn : typeName;
        String normalized = normalizeCode(source);
        if (normalized.isBlank()) {
            normalized = randomBusinessCode();
        } else {
            String suffix = stableSuffix(source);
            int maximumSlugLength = 40 - "CUSTOM__".length() - suffix.length();
            String slug = normalized.substring(
                    0, Math.min(normalized.length(), maximumSlugLength));
            normalized = "CUSTOM_" + slug + "_" + suffix;
        }
        code = normalized;
    }

    private String randomBusinessCode() {
        return "ROOM_TYPE_" + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return ascii.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String fitCode(String normalized, String source) {
        if (normalized.length() <= 40) {
            return normalized;
        }
        String suffix = stableSuffix(source);
        int prefixLength = 40 - suffix.length() - 1;
        return normalized.substring(0, prefixLength) + "_" + suffix;
    }

    private String stableSuffix(String source) {
        return UUID.nameUUIDFromBytes(
                        source.trim().getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
    }
}
