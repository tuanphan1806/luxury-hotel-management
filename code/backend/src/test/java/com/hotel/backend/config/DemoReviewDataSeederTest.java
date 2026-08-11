package com.hotel.backend.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class DemoReviewDataSeederTest {

    @Test
    void delegatesToReviewOnlyWorkflow() {
        DemoScenarioSeedService scenarioSeedService =
                mock(DemoScenarioSeedService.class);
        when(scenarioSeedService.seedReviewShowcase())
                .thenReturn(new DemoScenarioSeedService.DemoSeedSummary(
                        10, 0, 10, 0, 10, 20, 60, false, false));

        new DemoReviewDataSeeder(scenarioSeedService).run();

        verify(scenarioSeedService).seedReviewShowcase();
    }
}
