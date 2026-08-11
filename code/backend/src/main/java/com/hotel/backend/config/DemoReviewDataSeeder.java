package com.hotel.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot runner for the review showcase dataset. The delegated service uses
 * the normal reservation, pricing, payment, invoice and financial-journal path
 * so every review remains attached to a valid completed stay.
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.seed.demo-reviews-enabled",
        havingValue = "true")
public class DemoReviewDataSeeder implements CommandLineRunner {

    private final DemoScenarioSeedService scenarioSeedService;

    @Override
    public void run(String... args) {
        DemoScenarioSeedService.DemoSeedSummary summary =
                scenarioSeedService.seedReviewShowcase();
        log.info(
                "Demo review stays ready: created={}, skipped={}, payments={}, "
                        + "invoices={}, journals={}, reviews={}",
                summary.createdReservations(),
                summary.skippedReservations(),
                summary.createdPayments(),
                summary.createdInvoices(),
                summary.createdJournalEntries(),
                summary.createdReviews());
    }
}
