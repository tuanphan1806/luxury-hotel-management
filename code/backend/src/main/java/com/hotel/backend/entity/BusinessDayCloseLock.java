package com.hotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "business_day_close_locks")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessDayCloseLock {
    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;
}
