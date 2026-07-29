package com.hotel.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoDataSeederTest {

    @Mock
    private DemoScenarioSeedService scenarioSeedService;

    @Test
    void delegatesScenarioCreationToTransactionalService() {
        when(scenarioSeedService.seed()).thenReturn(
                new DemoScenarioSeedService.DemoSeedSummary(
                        18,
                        0,
                        16,
                        3,
                        10,
                        29,
                        true));

        new DemoDataSeeder(scenarioSeedService).run();

        verify(scenarioSeedService).seed();
    }
}
