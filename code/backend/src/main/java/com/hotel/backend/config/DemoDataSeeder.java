package com.hotel.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Opt-in runner for coherent local reservation and finance scenarios.
 *
 * <p>The production profile keeps this disabled. Master data is seeded by
 * {@link DataSeeder}; this runner deliberately starts afterwards and delegates
 * the transactional work to {@link DemoScenarioSeedService}.</p>
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.seed.demo-scenarios-enabled",
        havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private final DemoScenarioSeedService scenarioSeedService;

    @Override
    public void run(String... args) {
        DemoScenarioSeedService.DemoSeedSummary summary =
                scenarioSeedService.seed();
        log.info(
                "Demo scenarios ready: created={}, skipped={}, payments={}, "
                        + "refunds={}, invoices={}, journals={}, cashShiftOpened={}",
                summary.createdReservations(),
                summary.skippedReservations(),
                summary.createdPayments(),
                summary.createdRefunds(),
                summary.createdInvoices(),
                summary.createdJournalEntries(),
                summary.cashShiftOpened());
    }
}
