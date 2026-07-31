package com.hotel.backend.entity;

import com.hotel.backend.constant.WorkScheduleStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "work_schedule_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkScheduleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private User employee;

    @Column(name = "employee_name_snapshot", nullable = false, length = 150)
    private String employeeNameSnapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_template_id", nullable = false)
    private WorkShiftTemplate shiftTemplate;

    @Column(name = "shift_code_snapshot", nullable = false, length = 32)
    private String shiftCodeSnapshot;

    @Column(name = "shift_name_snapshot", nullable = false, length = 100)
    private String shiftNameSnapshot;

    @Column(name = "shift_color_snapshot", nullable = false, length = 7)
    private String shiftColorSnapshot;

    @Column(name = "check_in_early_minutes_snapshot", nullable = false)
    private Integer checkInEarlyMinutesSnapshot;

    @Column(name = "late_tolerance_minutes_snapshot", nullable = false)
    private Integer lateToleranceMinutesSnapshot;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "scheduled_start_utc", nullable = false)
    private Instant scheduledStartUtc;

    @Column(name = "scheduled_end_utc", nullable = false)
    private Instant scheduledEndUtc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkScheduleStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancelled_at_utc")
    private Instant cancelledAtUtc;

    @Column(length = 1000)
    private String note;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @OneToOne(mappedBy = "assignment", fetch = FetchType.LAZY)
    private WorkShiftSession workShiftSession;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;

    @UpdateTimestamp
    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;
}
