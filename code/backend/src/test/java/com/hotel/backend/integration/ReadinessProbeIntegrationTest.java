package com.hotel.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessProbeIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean DataSource dataSource;

    @Test void publicHostingProbeNeverBorrowsDatabaseConnectionButAggregateStillChecksIt() throws Exception {
        clearInvocations(dataSource);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
        verify(dataSource, never()).getConnection();
        verify(dataSource, never()).getConnection(anyString(), anyString());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        verify(dataSource, atLeastOnce()).getConnection();
    }

    @Test void otherActuatorEndpointsRemainProtected() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
    }
}
