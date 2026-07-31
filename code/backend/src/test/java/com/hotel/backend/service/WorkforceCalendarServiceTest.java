package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftRegistrationStatus;
import com.hotel.backend.dto.request.WorkShiftRegistrationCreateRequest;
import com.hotel.backend.dto.request.WorkShiftRegistrationReviewRequest;
import com.hotel.backend.dto.request.WorkShiftRequirementRequest;
import com.hotel.backend.dto.response.WorkScheduleResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkScheduleAssignment;
import com.hotel.backend.entity.WorkShiftRegistrationRequest;
import com.hotel.backend.entity.WorkShiftRequirement;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.WorkScheduleAssignmentRepository;
import com.hotel.backend.repository.WorkShiftRegistrationRequestRepository;
import com.hotel.backend.repository.WorkShiftRequirementRepository;
import com.hotel.backend.repository.WorkShiftTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkforceCalendarServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T03:00:00Z");
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 8, 3);

    @Mock WorkShiftRequirementRepository requirementRepository;
    @Mock WorkShiftRegistrationRequestRepository registrationRepository;
    @Mock WorkShiftTemplateRepository templateRepository;
    @Mock WorkScheduleAssignmentRepository assignmentRepository;
    @Mock WorkScheduleService workScheduleService;
    @Mock ReservationAuditService auditService;

    private WorkforceCalendarService service;
    private User admin;
    private User staff;
    private User anotherStaff;
    private WorkShiftTemplate morning;

    @BeforeEach
    void setUp() {
        service = new WorkforceCalendarService(
                requirementRepository,
                registrationRepository,
                templateRepository,
                assignmentRepository,
                workScheduleService,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        admin = user(1L, "Quản trị viên", UserType.ADMIN);
        staff = user(7L, "Nhân viên A", UserType.STAFF);
        anotherStaff = user(8L, "Nhân viên B", UserType.STAFF);
        morning = WorkShiftTemplate.builder()
                .id(10L)
                .code("SANG")
                .name("Ca sáng")
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .checkInEarlyMinutes(30)
                .lateToleranceMinutes(15)
                .color("#B8944F")
                .sortOrder(10)
                .active(true)
                .build();
    }

    @Test
    void staffCalendarShowsAggregateAvailabilityButNeverOtherEmployeeDetails() {
        WorkScheduleAssignment own = assignment(101L, staff);
        WorkScheduleAssignment other = assignment(102L, anotherStaff);
        WorkShiftRegistrationRequest ownRequest = registration(201L, staff);
        when(templateRepository.findAllByOrderBySortOrderAscStartTimeAscIdAsc())
                .thenReturn(List.of(morning));
        when(requirementRepository.findAllByWorkDateBetweenOrderByWorkDateAsc(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of(WorkShiftRequirement.builder()
                        .id(301L)
                        .shiftTemplate(morning)
                        .workDate(WORK_DATE)
                        .requiredStaff(3)
                        .build()));
        when(assignmentRepository.findInWindow(any(), any(), eq(null), eq(null)))
                .thenReturn(List.of(own, other));
        when(registrationRepository.findInWindow(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                7L))
                .thenReturn(List.of(ownRequest));

        var response = service.month(YearMonth.of(2026, 8), staff);
        var slot = response.days().stream()
                .filter(day -> day.date().equals(WORK_DATE))
                .findFirst()
                .orElseThrow()
                .slots()
                .get(0);

        assertEquals(2, slot.assignedCount());
        assertEquals(1, slot.availableSlots());
        assertEquals(0, slot.pendingRequestCount());
        assertTrue(slot.registrationOpen());
        assertNotNull(slot.currentUserAssignment());
        assertEquals(7L, slot.currentUserAssignment().employeeId());
        assertNotNull(slot.currentUserRequest());
        assertTrue(slot.assignments().isEmpty());
        assertTrue(slot.requests().isEmpty());
    }

    @Test
    void adminCalendarCanInspectAssignedEmployeesAndPendingRequests() {
        WorkScheduleAssignment assigned = assignment(101L, staff);
        WorkShiftRegistrationRequest pending = registration(201L, anotherStaff);
        when(templateRepository.findAllByOrderBySortOrderAscStartTimeAscIdAsc())
                .thenReturn(List.of(morning));
        when(requirementRepository.findAllByWorkDateBetweenOrderByWorkDateAsc(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of());
        when(assignmentRepository.findInWindow(any(), any(), eq(null), eq(null)))
                .thenReturn(List.of(assigned));
        when(registrationRepository.findInWindow(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                null))
                .thenReturn(List.of(pending));

        var response = service.month(YearMonth.of(2026, 8), admin);
        var slot = response.days().stream()
                .filter(day -> day.date().equals(WORK_DATE))
                .findFirst()
                .orElseThrow()
                .slots()
                .get(0);

        assertEquals(1, slot.assignedCount());
        assertEquals(1, slot.pendingRequestCount());
        assertEquals("Nhân viên A", slot.assignments().get(0).employeeName());
        assertEquals("Nhân viên B", slot.requests().get(0).employeeName());
        assertNull(slot.currentUserAssignment());
        assertNull(slot.currentUserRequest());
    }

    @Test
    void calendarClosesOnlyTheSlotsWhoseEndTimeHasPassed() {
        service = new WorkforceCalendarService(
                requirementRepository,
                registrationRepository,
                templateRepository,
                assignmentRepository,
                workScheduleService,
                auditService,
                Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC));
        when(templateRepository.findAllByOrderBySortOrderAscStartTimeAscIdAsc())
                .thenReturn(List.of(morning));
        when(requirementRepository.findAllByWorkDateBetweenOrderByWorkDateAsc(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of());
        when(assignmentRepository.findInWindow(any(), any(), eq(null), eq(null)))
                .thenReturn(List.of());
        when(registrationRepository.findInWindow(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                7L))
                .thenReturn(List.of());

        var response = service.month(YearMonth.of(2026, 8), staff);
        var endedToday = response.days().get(0).slots().get(0);
        var future = response.days().get(1).slots().get(0);

        assertFalse(endedToday.registrationOpen());
        assertTrue(future.registrationOpen());
    }

    @Test
    void staffCannotRequestAFullShift() {
        when(templateRepository.findById(10L)).thenReturn(Optional.of(morning));
        when(assignmentRepository.findInWindow(any(), any(), eq(7L), eq(null)))
                .thenReturn(List.of());
        when(registrationRepository
                .existsByEmployeeIdAndShiftTemplateIdAndWorkDateAndStatus(
                        7L, 10L, WORK_DATE, WorkShiftRegistrationStatus.PENDING))
                .thenReturn(false);
        when(requirementRepository.findForUpdate(10L, WORK_DATE))
                .thenReturn(Optional.empty());
        when(assignmentRepository.countByShiftTemplateIdAndWorkDateAndStatusNot(
                10L, WORK_DATE, WorkScheduleStatus.CANCELLED))
                .thenReturn(1L);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.createRequest(
                        new WorkShiftRegistrationCreateRequest(
                                10L, WORK_DATE, "Có thể nhận ca"),
                        staff));

        assertEquals(ErrorCode.WORK_SHIFT_REGISTRATION_FULL, exception.getErrorCode());
        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void approvingRequestRevalidatesCapacityAndCreatesExistingScheduleAtomically() {
        WorkShiftRegistrationRequest pending = registration(201L, staff);
        when(registrationRepository.findByIdForUpdate(201L))
                .thenReturn(Optional.of(pending));
        when(templateRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(morning));
        when(requirementRepository.findForUpdate(10L, WORK_DATE))
                .thenReturn(Optional.empty());
        when(assignmentRepository.countByShiftTemplateIdAndWorkDateAndStatusNot(
                10L, WORK_DATE, WorkScheduleStatus.CANCELLED))
                .thenReturn(0L);
        when(workScheduleService.create(any(), eq(admin)))
                .thenReturn(scheduleResponse(501L));
        WorkScheduleAssignment assigned = assignment(501L, staff);
        when(assignmentRepository.getReferenceById(501L)).thenReturn(assigned);
        when(registrationRepository.saveAndFlush(pending))
                .thenReturn(pending);

        var response = service.approveRequest(
                201L,
                new WorkShiftRegistrationReviewRequest("Phù hợp nhu cầu"),
                admin);

        assertEquals(WorkShiftRegistrationStatus.APPROVED, response.status());
        assertEquals(501L, response.assignmentId());
        verify(workScheduleService).create(any(), eq(admin));
        verify(auditService).recordTargetForUser(
                eq(admin),
                eq("WORK_SHIFT_REQUEST"),
                eq("201"),
                eq(ReservationAuditAction.SHIFT_REQUEST_APPROVED),
                any(),
                any());
    }

    @Test
    void requirementCannotBeReducedBelowAlreadyAssignedStaff() {
        when(templateRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(morning));
        when(assignmentRepository.countByShiftTemplateIdAndWorkDateAndStatusNot(
                10L, WORK_DATE, WorkScheduleStatus.CANCELLED))
                .thenReturn(2L);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.updateRequirement(
                        WORK_DATE,
                        10L,
                        new WorkShiftRequirementRequest(1, null),
                        admin));

        assertEquals(
                ErrorCode.WORK_SHIFT_REQUIREMENT_BELOW_ASSIGNED,
                exception.getErrorCode());
        verify(requirementRepository, never()).saveAndFlush(any());
    }

    @Test
    void requirementCannotChangeAfterTheShiftHasEndedToday() {
        service = new WorkforceCalendarService(
                requirementRepository,
                registrationRepository,
                templateRepository,
                assignmentRepository,
                workScheduleService,
                auditService,
                Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC));
        LocalDate endedDate = LocalDate.of(2026, 8, 1);
        when(templateRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(morning));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.updateRequirement(
                        endedDate,
                        10L,
                        new WorkShiftRequirementRequest(2, null),
                        admin));

        assertEquals(
                ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE,
                exception.getErrorCode());
        verify(requirementRepository, never()).saveAndFlush(any());
    }

    private WorkShiftRegistrationRequest registration(Long id, User employee) {
        return WorkShiftRegistrationRequest.builder()
                .id(id)
                .employee(employee)
                .shiftTemplate(morning)
                .workDate(WORK_DATE)
                .status(WorkShiftRegistrationStatus.PENDING)
                .staffNote("Có thể nhận ca")
                .createdAtUtc(NOW)
                .updatedAtUtc(NOW)
                .build();
    }

    private WorkScheduleAssignment assignment(Long id, User employee) {
        return WorkScheduleAssignment.builder()
                .id(id)
                .employee(employee)
                .employeeNameSnapshot(employee.getFullName())
                .shiftTemplate(morning)
                .shiftCodeSnapshot(morning.getCode())
                .shiftNameSnapshot(morning.getName())
                .shiftColorSnapshot(morning.getColor())
                .checkInEarlyMinutesSnapshot(30)
                .lateToleranceMinutesSnapshot(15)
                .workDate(WORK_DATE)
                .scheduledStartUtc(Instant.parse("2026-08-02T23:00:00Z"))
                .scheduledEndUtc(Instant.parse("2026-08-03T07:00:00Z"))
                .status(WorkScheduleStatus.SCHEDULED)
                .build();
    }

    private WorkScheduleResponse scheduleResponse(Long id) {
        return new WorkScheduleResponse(
                id,
                staff.getId(),
                staff.getFullName(),
                morning.getId(),
                morning.getCode(),
                morning.getName(),
                morning.getColor(),
                WORK_DATE,
                Instant.parse("2026-08-02T23:00:00Z"),
                Instant.parse("2026-08-03T07:00:00Z"),
                30,
                15,
                WorkScheduleStatus.SCHEDULED,
                null,
                null,
                null,
                null,
                false,
                false,
                0,
                null,
                null,
                null,
                NOW,
                NOW);
    }

    private User user(Long id, String name, UserType type) {
        User user = User.builder()
                .username(type.name().toLowerCase() + id)
                .fullName(name)
                .type(type)
                .status(UserStatus.ACTIVE)
                .build();
        user.setId(id);
        return user;
    }
}
