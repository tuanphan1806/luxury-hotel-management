package com.hotel.backend.scheduled;

import com.hotel.backend.service.WorkScheduleService;
import com.hotel.backend.service.WorkDailyShiftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkScheduleMaintenanceScheduler {

    private final WorkScheduleService workScheduleService;
    private final WorkDailyShiftService workDailyShiftService;

    @Value("${app.work-schedule.auto-checkout-grace-minutes:120}")
    private long autoCheckoutGraceMinutes;

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void markExpiredSchedulesAbsent() {
        int absentCount = 0;
        int autoClosedCount = 0;
        int completedShiftCount = 0;
        try {
            absentCount = workScheduleService.markExpiredScheduledAssignments();
        } catch (RuntimeException failure) {
            log.error("Không thể đánh dấu lịch làm việc vắng mặt; sẽ thử lại ở chu kỳ sau", failure);
        }
        try {
            autoClosedCount = workScheduleService
                    .autoCloseForgottenAssignments(autoCheckoutGraceMinutes);
        } catch (RuntimeException failure) {
            log.error("Không thể tự đóng phiên làm việc bị quên; sẽ thử lại ở chu kỳ sau", failure);
        }
        try {
            completedShiftCount = workDailyShiftService.completeExpiredDailyShifts();
        } catch (RuntimeException failure) {
            log.error("Không thể hoàn tất ca ngày đủ điều kiện; sẽ thử lại ở chu kỳ sau", failure);
        }
        if (absentCount > 0 || autoClosedCount > 0 || completedShiftCount > 0) {
            log.info("Work-schedule maintenance completed: absent={}, autoClosed={}, dailyShiftsCompleted={}",
                    absentCount, autoClosedCount, completedShiftCount);
        }
    }
}
