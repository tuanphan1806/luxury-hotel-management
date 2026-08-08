package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftSessionStatus;
import com.hotel.backend.dto.request.WorkAttendanceRequest;
import com.hotel.backend.dto.request.WorkScheduleAssignmentRequest;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkScheduleAssignment;
import com.hotel.backend.entity.WorkShiftSession;
import com.hotel.backend.entity.WorkShiftRequirement;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.WorkScheduleAssignmentRepository;
import com.hotel.backend.repository.WorkShiftSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T08:20:00Z");

    @Mock WorkScheduleAssignmentRepository repository;
    @Mock WorkShiftSessionRepository sessionRepository;
    @Mock UserRepository userRepository;
    @Mock WorkDailyShiftService dailyShiftService;
    @Mock CashierShiftService cashierShiftService;
    @Mock ReservationAuditService auditService;

    private WorkScheduleService service;
    private User admin;
    private User staff;
    private WorkShiftTemplate nightTemplate;

    @BeforeEach
    void setUp() {
        service = new WorkScheduleService(
                repository,
                sessionRepository,
                userRepository,
                dailyShiftService,
                cashierShiftService,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        admin = user(1L, "Quản trị viên", UserType.ADMIN);
        staff = user(7L, "Nhân viên lễ tân", UserType.STAFF);
        nightTemplate = WorkShiftTemplate.builder()
                .id(3L)
                .code("TOI")
                .name("Ca tối")
                .startTime(LocalTime.of(22, 0))
                .endTime(LocalTime.of(6, 0))
                .checkInEarlyMinutes(30)
                .lateToleranceMinutes(15)
                .color("#0F2A43")
                .sortOrder(30)
                .active(true)
                .build();
    }

    @Test
    void createSnapshotsOvernightWindowInHotelTimezone() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(staff));
        when(dailyShiftService.requireOpen(3L, LocalDate.of(2026, 8, 1)))
                .thenReturn(dailyShift(LocalDate.of(2026, 8, 1)));
        when(repository.saveAndFlush(any(WorkScheduleAssignment.class)))
                .thenAnswer(invocation -> {
                    WorkScheduleAssignment assignment = invocation.getArgument(0);
                    assignment.setId(81L);
                    return assignment;
                });

        var response = service.create(
                new WorkScheduleAssignmentRequest(
                        7L, 3L, LocalDate.of(2026, 8, 1), "Trực đêm"),
                admin);

        assertEquals(Instant.parse("2026-08-01T15:00:00Z"), response.scheduledStartUtc());
        assertEquals(Instant.parse("2026-08-01T23:00:00Z"), response.scheduledEndUtc());
        assertEquals(WorkScheduleStatus.SCHEDULED, response.status());
        verify(auditService).recordTargetForUser(
                eq(admin), eq("WORK_SCHEDULE"), eq("81"),
                eq(ReservationAuditAction.WORK_SCHEDULE_CREATED), any(), any());
    }

    @Test
    void lateCheckInCreatesActiveSessionAndCashierShiftExactlyOnce() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(20 * 60L),
                NOW.plusSeconds(7 * 60 * 60L));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L)).thenReturn(Optional.empty());
        when(sessionRepository.findActiveByEmployeeIdForUpdate(7L)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(WorkShiftSession.class)))
                .thenAnswer(invocation -> {
                    WorkShiftSession session = invocation.getArgument(0);
                    session.setId(901L);
                    return session;
                });

        var response = service.checkIn(81L, new WorkAttendanceRequest(null), staff);

        assertEquals(WorkScheduleStatus.SCHEDULED, response.status());
        assertEquals(WorkShiftSessionStatus.ACTIVE, response.sessionStatus());
        assertEquals(901L, response.sessionId());
        assertEquals(NOW, response.actualCheckInUtc());
        assertTrue(response.late());
        assertEquals(20L, response.lateMinutes());
        verify(cashierShiftService).openForWorkSession(assignment.getWorkShiftSession(), staff);
        verify(auditService).recordTargetForUser(
                eq(staff), eq("WORK_SHIFT_SESSION"), eq("901"),
                eq(ReservationAuditAction.WORK_SHIFT_CHECKED_IN), any(), any());
    }

    @Test
    void secondActiveSessionIsRejectedBeforeOpeningAnotherCashierShift() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(10 * 60L),
                NOW.plusSeconds(8 * 60 * 60L));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L)).thenReturn(Optional.empty());
        when(sessionRepository.findActiveByEmployeeIdForUpdate(7L))
                .thenReturn(Optional.of(activeSession(77L, assignment)));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.checkIn(81L, new WorkAttendanceRequest(null), staff));

        assertEquals(ErrorCode.WORK_SCHEDULE_ALREADY_ACTIVE, exception.getErrorCode());
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(cashierShiftService, never()).openForWorkSession(any(), any());
    }

    @Test
    void checkInAfterScheduledEndIsRejectedWithoutWriting() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(9 * 60 * 60L),
                NOW.minusSeconds(60));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L)).thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> service.checkIn(81L, new WorkAttendanceRequest(null), staff));

        assertEquals(ErrorCode.WORK_SCHEDULE_CHECK_IN_EXPIRED, exception.getErrorCode());
        assertEquals(WorkScheduleStatus.SCHEDULED, assignment.getStatus());
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(cashierShiftService, never()).openForWorkSession(any(), any());
    }

    @Test
    void earlyCheckoutRequiresReasonAndLeavesBothStatesUntouched() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(60 * 60L),
                NOW.plusSeconds(7 * 60 * 60L));
        WorkShiftSession session = activeSession(901L, assignment);
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L)).thenReturn(Optional.of(session));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.checkOut(81L, new WorkAttendanceRequest(null), staff));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        assertEquals(WorkScheduleStatus.SCHEDULED, assignment.getStatus());
        assertEquals(WorkShiftSessionStatus.ACTIVE, session.getStatus());
        verify(cashierShiftService, never()).closeForWorkSession(any(), any(), any());
    }

    @Test
    void checkoutAfterShiftClosesCashierAndFulfilsSchedule() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(9 * 60 * 60L),
                NOW.minusSeconds(60));
        WorkShiftSession session = activeSession(901L, assignment);
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(session)).thenReturn(session);
        when(repository.saveAndFlush(assignment)).thenReturn(assignment);

        var response = service.checkOut(
                81L,
                new WorkAttendanceRequest("Bàn giao đủ"),
                staff);

        assertEquals(WorkScheduleStatus.FULFILLED, response.status());
        assertEquals(WorkShiftSessionStatus.CLOSED, response.sessionStatus());
        assertEquals(NOW, response.actualCheckOutUtc());
        assertFalse(response.autoCheckOut());
        verify(cashierShiftService).closeForWorkSession(session, staff, "Bàn giao đủ");
        verify(dailyShiftService).completeIfEligible(3L, LocalDate.of(2026, 8, 1));
    }

    @Test
    void forgottenCheckoutClosesSessionAndCashierAtScheduledBoundary() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(11 * 60 * 60L),
                NOW.minusSeconds(3 * 60 * 60L));
        WorkShiftSession session = activeSession(901L, assignment);
        when(sessionRepository.findExpiredAssignmentIds(
                WorkShiftSessionStatus.ACTIVE,
                NOW.minusSeconds(2 * 60 * 60L),
                PageRequest.of(0, 100)))
                .thenReturn(List.of(81L));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L))
                .thenReturn(Optional.of(session));

        int count = service.autoCloseForgottenAssignments(120);

        assertEquals(1, count);
        assertEquals(WorkScheduleStatus.FULFILLED, assignment.getStatus());
        assertEquals(WorkShiftSessionStatus.AUTO_CLOSED, session.getStatus());
        assertEquals(assignment.getScheduledEndUtc(), session.getActualCheckOutUtc());
        verify(cashierShiftService).closeForWorkSessionAutomatically(session);
        verify(auditService).recordTargetForUser(
                eq(null), eq("WORK_SHIFT_SESSION"), eq("901"),
                eq(ReservationAuditAction.WORK_SHIFT_AUTO_CHECKED_OUT), any(), any());
        verify(sessionRepository).saveAll(List.of(session));
        verify(repository).saveAll(List.of(assignment));
    }

    @Test
    void forgottenCheckoutRevalidatesCandidateAfterAcquiringLocks() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.FULFILLED,
                NOW.minusSeconds(11 * 60 * 60L),
                NOW.minusSeconds(3 * 60 * 60L));
        WorkShiftSession session = activeSession(901L, assignment);
        session.setStatus(WorkShiftSessionStatus.CLOSED);
        session.setActualCheckOutUtc(NOW.minusSeconds(30));
        when(sessionRepository.findExpiredAssignmentIds(
                WorkShiftSessionStatus.ACTIVE,
                NOW.minusSeconds(2 * 60 * 60L),
                PageRequest.of(0, 100)))
                .thenReturn(List.of(81L));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L))
                .thenReturn(Optional.of(session));

        int count = service.autoCloseForgottenAssignments(120);

        assertEquals(0, count);
        verify(cashierShiftService, never()).closeForWorkSessionAutomatically(any());
        verify(sessionRepository, never()).saveAll(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void expiredScheduleWithoutSessionIsMarkedAbsent() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(9 * 60 * 60L),
                NOW.minusSeconds(60));
        when(repository.findExpiredScheduledIds(NOW, PageRequest.of(0, 100)))
                .thenReturn(List.of(81L));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L)).thenReturn(Optional.empty());

        int count = service.markExpiredScheduledAssignments();

        assertEquals(1, count);
        assertEquals(WorkScheduleStatus.ABSENT, assignment.getStatus());
        verify(repository).saveAll(List.of(assignment));
    }

    @Test
    void expiredScheduleCandidateIsNotMarkedAbsentWhenCheckInWonTheLockRace() {
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(9 * 60 * 60L),
                NOW.minusSeconds(60));
        WorkShiftSession session = activeSession(901L, assignment);
        when(repository.findExpiredScheduledIds(NOW, PageRequest.of(0, 100)))
                .thenReturn(List.of(81L));
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));
        when(sessionRepository.findByAssignmentIdForUpdate(81L))
                .thenReturn(Optional.of(session));

        int count = service.markExpiredScheduledAssignments();

        assertEquals(0, count);
        assertEquals(WorkScheduleStatus.SCHEDULED, assignment.getStatus());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void staffCannotOperateAnotherEmployeesAssignment() {
        User other = user(8L, "Nhân viên khác", UserType.STAFF);
        WorkScheduleAssignment assignment = assignment(
                81L,
                WorkScheduleStatus.SCHEDULED,
                NOW.minusSeconds(10 * 60L),
                NOW.plusSeconds(8 * 60 * 60L));
        assignment.setEmployee(other);
        when(repository.findByIdForUpdate(81L)).thenReturn(Optional.of(assignment));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.checkIn(81L, new WorkAttendanceRequest(null), staff));

        assertEquals(ErrorCode.WORK_SCHEDULE_FORBIDDEN, exception.getErrorCode());
    }

    private WorkShiftSession activeSession(Long id, WorkScheduleAssignment assignment) {
        WorkShiftSession session = WorkShiftSession.builder()
                .id(id)
                .assignment(assignment)
                .employee(assignment.getEmployee())
                .status(WorkShiftSessionStatus.ACTIVE)
                .actualCheckInUtc(assignment.getScheduledStartUtc())
                .checkInBy(assignment.getEmployee())
                .build();
        assignment.setWorkShiftSession(session);
        return session;
    }

    private WorkScheduleAssignment assignment(
            Long id,
            WorkScheduleStatus status,
            Instant scheduledStart,
            Instant scheduledEnd) {
        return WorkScheduleAssignment.builder()
                .id(id)
                .employee(staff)
                .employeeNameSnapshot(staff.getFullName())
                .shiftTemplate(nightTemplate)
                .shiftCodeSnapshot(nightTemplate.getCode())
                .shiftNameSnapshot(nightTemplate.getName())
                .shiftColorSnapshot(nightTemplate.getColor())
                .checkInEarlyMinutesSnapshot(30)
                .lateToleranceMinutesSnapshot(15)
                .workDate(LocalDate.of(2026, 8, 1))
                .scheduledStartUtc(scheduledStart)
                .scheduledEndUtc(scheduledEnd)
                .status(status)
                .createdBy(admin)
                .build();
    }

    private WorkShiftRequirement dailyShift(LocalDate workDate) {
        return WorkShiftRequirement.builder()
                .id(301L)
                .shiftTemplate(nightTemplate)
                .workDate(workDate)
                .requiredStaff(1)
                .shiftCodeSnapshot(nightTemplate.getCode())
                .shiftNameSnapshot(nightTemplate.getName())
                .shiftColorSnapshot(nightTemplate.getColor())
                .startTimeSnapshot(nightTemplate.getStartTime())
                .endTimeSnapshot(nightTemplate.getEndTime())
                .checkInEarlyMinutesSnapshot(30)
                .lateToleranceMinutesSnapshot(15)
                .sortOrderSnapshot(30)
                .build();
    }

    private User user(Long id, String fullName, UserType type) {
        User user = User.builder()
                .username("user" + id)
                .fullName(fullName)
                .email("user" + id + "@example.com")
                .type(type)
                .status(UserStatus.ACTIVE)
                .build();
        user.setId(id);
        return user;
    }
}
