package com.hotel.backend.entity;

import com.hotel.backend.persistence.LocalTimeWithoutTimezoneType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "work_shift_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkShiftTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_time", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime endTime;

    @Column(name = "check_in_early_minutes", nullable = false)
    private Integer checkInEarlyMinutes;

    @Column(name = "late_tolerance_minutes", nullable = false)
    private Integer lateToleranceMinutes;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(nullable = false)
    private Boolean active;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;

    @UpdateTimestamp
    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;
}
