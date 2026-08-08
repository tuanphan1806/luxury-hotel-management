package com.hotel.backend.entity;

import com.hotel.backend.constant.WorkDailyShiftStatus;
import com.hotel.backend.constant.WorkShiftAssignmentPolicy;
import com.hotel.backend.persistence.LocalTimeWithoutTimezoneType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "work_shift_requirements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_work_shift_requirement",
                columnNames = {"shift_template_id", "work_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkShiftRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_template_id", nullable = false)
    private WorkShiftTemplate shiftTemplate;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "required_staff", nullable = false)
    private Integer requiredStaff;

    @Column(name = "shift_code_snapshot", nullable = false, length = 32)
    private String shiftCodeSnapshot;

    @Column(name = "shift_name_snapshot", nullable = false, length = 100)
    private String shiftNameSnapshot;

    @Column(name = "shift_color_snapshot", nullable = false, length = 7)
    private String shiftColorSnapshot;

    @Column(name = "start_time_snapshot", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime startTimeSnapshot;

    @Column(name = "end_time_snapshot", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime endTimeSnapshot;

    @Column(name = "check_in_early_minutes_snapshot", nullable = false)
    private Integer checkInEarlyMinutesSnapshot;

    @Column(name = "late_tolerance_minutes_snapshot", nullable = false)
    private Integer lateToleranceMinutesSnapshot;

    @Column(name = "sort_order_snapshot", nullable = false)
    private Integer sortOrderSnapshot;

    @Column(name = "registration_open", nullable = false)
    private Boolean registrationOpen;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_policy_snapshot", nullable = false, length = 24)
    private WorkShiftAssignmentPolicy assignmentPolicySnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkDailyShiftStatus status;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancelled_at_utc")
    private Instant cancelledAtUtc;

    @Column(name = "completed_at_utc")
    private Instant completedAtUtc;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;

    @UpdateTimestamp
    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;
}
