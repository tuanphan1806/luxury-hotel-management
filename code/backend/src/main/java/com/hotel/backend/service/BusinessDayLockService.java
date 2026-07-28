package com.hotel.backend.service;

import com.hotel.backend.repository.BusinessDayCloseLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDate;

/** Shared transactional mutex used by journal posting and business-day close. */
@Service
@RequiredArgsConstructor
public class BusinessDayLockService {
    private final JdbcTemplate jdbcTemplate;
    private final BusinessDayCloseLockRepository repository;
    private volatile Boolean h2;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LocalDate businessDate) {
        if (isH2()) {
            jdbcTemplate.update("""
                    MERGE INTO business_day_close_locks (business_date, created_at_utc)
                    KEY (business_date) VALUES (?, CURRENT_TIMESTAMP)
                    """, businessDate);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO business_day_close_locks (business_date, created_at_utc)
                    VALUES (?, CURRENT_TIMESTAMP) ON CONFLICT (business_date) DO NOTHING
                    """, businessDate);
        }
        repository.findByBusinessDateForUpdate(businessDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Không thể khóa mutex ngày nghiệp vụ " + businessDate));
    }

    private boolean isH2() {
        Boolean cached = h2;
        if (cached != null) return cached;
        String product = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try {
                return connection.getMetaData().getDatabaseProductName();
            } catch (SQLException exception) {
                throw new IllegalStateException("Không thể xác định database cho day lock", exception);
            }
        });
        h2 = product != null && product.toLowerCase().contains("h2");
        return h2;
    }
}
