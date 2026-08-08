package com.hotel.backend.scheduled;

import com.hotel.backend.service.WorkScheduleService;
import com.hotel.backend.service.WorkDailyShiftService;
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
        WorkDailyShiftService dailyShiftService = mock(WorkDailyShiftService.class);
        WorkScheduleMaintenanceScheduler scheduler =
                new WorkScheduleMaintenanceScheduler(service, dailyShiftService);
        ReflectionTestUtils.setField(scheduler, "autoCheckoutGraceMinutes", 120L);
        doThrow(new IllegalStateException("temporary failure"))
                .when(service).markExpiredScheduledAssignments();
        when(service.autoCloseForgottenAssignments(120L)).thenReturn(2);

        scheduler.markExpiredSchedulesAbsent();

        verify(service).markExpiredScheduledAssignments();
        verify(service).autoCloseForgottenAssignments(120L);
        verify(dailyShiftService).completeExpiredDailyShifts();
    }
}
