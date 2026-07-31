package com.hotel.backend.scheduled;

import com.hotel.backend.service.WorkScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkScheduleMaintenanceSchedulerTest {

    @Test
    void autoCloseStillRunsWhenAbsentMarkingFails() {
        WorkScheduleService service = mock(WorkScheduleService.class);
        WorkScheduleMaintenanceScheduler scheduler =
                new WorkScheduleMaintenanceScheduler(service);
        ReflectionTestUtils.setField(scheduler, "autoCheckoutGraceMinutes", 120L);
        doThrow(new IllegalStateException("temporary failure"))
                .when(service).markExpiredScheduledAssignments();
        when(service.autoCloseForgottenAssignments(120L)).thenReturn(2);

        scheduler.markExpiredSchedulesAbsent();

        verify(service).markExpiredScheduledAssignments();
        verify(service).autoCloseForgottenAssignments(120L);
    }
}
