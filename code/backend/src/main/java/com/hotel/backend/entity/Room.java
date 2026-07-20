package com.hotel.backend.entity;

import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.CleaningStatus;
import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
@Entity
@Table(name = "rooms")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Room extends AbstractEntity<Long> implements Serializable{

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "room_name", unique = true, length = 20)
    private String roomName;

    @ManyToOne
    @JoinColumn(name = "room_type_id")
    private RoomType roomType;

    private Integer floor;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private RoomStatus status = RoomStatus.AVAILABLE;

    @Builder.Default
    @Column(nullable = false)
    private Boolean sellable = true;

    @Column(name = "decommissioned_at")
    private LocalDateTime decommissionedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "cleaning_status")
    private CleaningStatus cleaningStatus = CleaningStatus.CLEAN;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "maintenance_reason", columnDefinition = "TEXT")
    private String maintenanceReason;

    @Column(name = "maintenance_expected_completed_date")
    private LocalDate maintenanceExpectedCompletedDate;

    @Builder.Default
    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("createdAt DESC")
    private List<RoomMaintenanceLog> maintenanceHistory = new ArrayList<>();

}
