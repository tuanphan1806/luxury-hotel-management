package com.hotel.backend.service;

import com.hotel.backend.repository.PricingQuoteRepository;
import com.hotel.backend.repository.PricingQuoteLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class PricingQuoteCleanupScheduler {

    private final PricingQuoteLineRepository quoteLineRepository;
    private final PricingQuoteRepository quoteRepository;

    /**
     * Keep expired evidence for one day so PRICE_CHANGED/expired diagnostics
     * remain explainable without allowing the temporary table to grow forever.
     */
    @Scheduled(cron = "${hotel.pricing.quote-cleanup-cron:0 17 * * * *}")
    @Transactional
    public void deleteOldExpiredQuotes() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        // Delete children explicitly so cleanup is portable to test schemas
        // that do not reproduce PostgreSQL's ON DELETE CASCADE metadata.
        quoteLineRepository.deleteByQuoteExpiryBefore(cutoff);
        quoteRepository.deleteExpiredBefore(cutoff);
    }
}
