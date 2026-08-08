package com.hotel.backend.service;

import com.hotel.backend.constant.CashierShiftStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WorkDailyShiftStatus;
import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftAssignmentPolicy;
import com.hotel.backend.constant.WorkShiftRegistrationStatus;
import com.hotel.backend.constant.WorkShiftSessionStatus;
import com.hotel.backend.dto.request.CancelWorkDailyShiftRequest;
import com.hotel.backend.dto.request.WorkDailyShiftRequest;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkScheduleAssignment;
import com.hotel.backend.entity.WorkShiftRegistrationRequest;
import com.hotel.backend.entity.WorkShiftRequirement;
import com.hotel.backend.entity.WorkShiftSession;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CashierShiftRepository;
import com.hotel.backend.repository.WorkScheduleAssignmentRepository;
import com.hotel.backend.repository.WorkShiftRegistrationRequestRepository;
import com.hotel.backend.repository.WorkShiftRequirementRepository;
import com.hotel.backend.repository.WorkShiftSessionRepository;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkDailyShiftServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T08:20:00Z");

    @Mock WorkShiftRequirementRepository requirementRepository;
    @Mock WorkShiftTemplateRepository templateRepository;
    @Mock WorkScheduleAssignmentRepository assignmentRepository;
    @Mock WorkShiftRegistrationRequestRepository registrationRepository;
    @Mock WorkShiftSessionRepository sessionRepository;
    @Mock CashierShiftRepository cashierShiftRepository;
    @Mock ReservationAuditService auditService;

    private WorkDailyShiftService service;
    private User admin;
    private WorkShiftTemplate template;

    @BeforeEach
    void setUp() {
        service = new WorkDailyShiftService(
                requirementRepository,
                templateRepository,
                assignmentRepository,
                registrationRepository,
                sessionRepository,
                cashierShiftRepository,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        admin = user(1L, "Admin", UserType.ADMIN);
        template = WorkShiftTemplate.builder()
                .id(2L)
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
    void createAlwaysPersistsAnOpenShiftWithPolicySnapshot() {
        LocalDate workDate = LocalDate.of(2026, 8, 2);
        when(templateRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(template));
        when(requirementRepository.findForUpdate(2L, workDate)).thenReturn(Optional.empty());
        when(requirementRepository.saveAndFlush(any(WorkShiftRequirement.class)))
                .thenAnswer(invocation -> {
                    WorkShiftRequirement value = invocation.getArgument(0);
                    value.setId(15L);
                    return value;
                });

        var response = service.create(new WorkDailyShiftRequest(
                2L,
                workDate,
                "Ca sáng tăng cường",
                LocalTime.of(6, 0),
                LocalTime.of(14, 0),
                2,
                true,
                WorkShiftAssignmentPolicy.MANUAL_APPROVAL,
                30,
                15,
                "#FF0000",
                "Cuối tuần"), admin);

        assertThat(response.dailyShiftStatus()).isEqualTo(WorkDailyShiftStatus.OPEN);
        assertThat(response.assignmentPolicy())
                .isEqualTo(WorkShiftAssignmentPolicy.MANUAL_APPROVAL);
        assertThat(response.registrationOpen()).isTrue();
        assertThat(response.shiftColor()).isEqualTo("#B8944F");
    }

    @Test
    void cancelBeforeStartCancelsAssignmentsAndPendingRequestsButKeepsHistory() {
        WorkShiftRequirement requirement = futureRequirement();
        WorkScheduleAssignment assignment = WorkScheduleAssignment.builder()
                .id(31L)
                .employee(user(7L, "Staff", UserType.STAFF))
                .shiftTemplate(template)
                .workDate(requirement.getWorkDate())
                .status(WorkScheduleStatus.SCHEDULED)
                .build();
        WorkShiftRegistrationRequest registration = WorkShiftRegistrationRequest.builder()
                .id(41L)
                .employee(user(8L, "Staff 2", UserType.STAFF))
                .shiftTemplate(template)
                .workDate(requirement.getWorkDate())
                .status(WorkShiftRegistrationStatus.PENDING)
                .build();
        when(requirementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requirement));
        when(assignmentRepository.findSlotForUpdate(2L, requirement.getWorkDate()))
                .thenReturn(List.of(assignment));
        when(registrationRepository.findSlotByStatusForUpdate(
                2L, requirement.getWorkDate(), WorkShiftRegistrationStatus.PENDING))
                .thenReturn(List.of(registration));
        when(requirementRepository.saveAndFlush(requirement)).thenReturn(requirement);

        service.cancel(20L, new CancelWorkDailyShiftRequest("Khách sạn giảm nhu cầu"), admin);

        assertThat(requirement.getStatus()).isEqualTo(WorkDailyShiftStatus.CANCELLED);
        assertThat(requirement.getRegistrationOpen()).isTrue();
        assertThat(requirement.getCancellationReason()).isEqualTo("Khách sạn giảm nhu cầu");
        assertThat(assignment.getStatus()).isEqualTo(WorkScheduleStatus.CANCELLED);
        assertThat(registration.getStatus()).isEqualTo(WorkShiftRegistrationStatus.CANCELLED);
        verify(assignmentRepository).saveAll(List.of(assignment));
        verify(registrationRepository).saveAll(List.of(registration));
    }

    @Test
    void restoreReopensFutureCancelledShiftWithoutRestoringOldCommitments() {
        WorkShiftRequirement requirement = futureRequirement();
        requirement.setStatus(WorkDailyShiftStatus.CANCELLED);
        requirement.setCancelledBy(admin);
        requirement.setCancelledAtUtc(NOW.minusSeconds(60));
        requirement.setCancellationReason("Giảm nhu cầu");
        when(requirementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requirement));
        when(requirementRepository.saveAndFlush(requirement)).thenReturn(requirement);

        var response = service.restore(20L, admin);

        assertThat(requirement.getStatus()).isEqualTo(WorkDailyShiftStatus.OPEN);
        assertThat(requirement.getCancelledAtUtc()).isNull();
        assertThat(requirement.getCancelledBy()).isNull();
        assertThat(requirement.getCancellationReason()).isNull();
        assertThat(requirement.getRegistrationOpen()).isTrue();
        assertThat(response.assignedCount()).isZero();
        verify(assignmentRepository, never()).saveAll(any());
        verify(registrationRepository, never()).saveAll(any());
    }

    @Test
    void restoreRejectsShiftThatWasNotCancelled() {
        WorkShiftRequirement requirement = futureRequirement();
        when(requirementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requirement));

        assertThatThrownBy(() -> service.restore(20L, admin))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.WORK_DAILY_SHIFT_CANNOT_RESTORE));
        verify(requirementRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteUnusedRemovesOnlyFutureShiftWithoutOperationalHistory() {
        WorkShiftRequirement requirement = futureRequirement();
        when(requirementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requirement));
        when(assignmentRepository.existsByShiftTemplateIdAndWorkDate(
                2L, requirement.getWorkDate())).thenReturn(false);
        when(registrationRepository.existsByShiftTemplateIdAndWorkDate(
                2L, requirement.getWorkDate())).thenReturn(false);

        var deleted = service.deleteUnused(20L, admin);

        assertThat(deleted.dailyShiftId()).isEqualTo(20L);
        verify(requirementRepository).delete(requirement);
        verify(requirementRepository).flush();
    }

    @Test
    void deleteUnusedRejectsShiftWithAnyRegistrationHistory() {
        WorkShiftRequirement requirement = futureRequirement();
        when(requirementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requirement));
        when(assignmentRepository.existsByShiftTemplateIdAndWorkDate(
                2L, requirement.getWorkDate())).thenReturn(false);
        when(registrationRepository.existsByShiftTemplateIdAndWorkDate(
                2L, requirement.getWorkDate())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteUnused(20L, admin))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.WORK_DAILY_SHIFT_CANNOT_DELETE));
        verify(requirementRepository, never()).delete(any());
    }

    @Test
    void cancelIsRejectedAfterAnyEmployeeHasCheckedIn() {
        WorkShiftRequirement requirement = futureRequirement();
        WorkScheduleAssignment assignment = WorkScheduleAssignment.builder()
                .id(31L)
                .shiftTemplate(template)
                .workDate(requirement.getWorkDate())
                .status(WorkScheduleStatus.SCHEDULED)
                .workShiftSession(WorkShiftSession.builder()
                        .id(91L)
                        .status(WorkShiftSessionStatus.ACTIVE)
                        .build())
                .build();
        when(requirementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requirement));
        when(assignmentRepository.findSlotForUpdate(2L, requirement.getWorkDate()))
                .thenReturn(List.of(assignment));

        assertThatThrownBy(() -> service.cancel(
                20L,
                new CancelWorkDailyShiftRequest("Không còn nhu cầu"),
                admin))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.WORK_DAILY_SHIFT_CANNOT_CANCEL));
        verify(requirementRepository, never()).saveAndFlush(any());
    }

    @Test
    void dueShiftWaitsUntilAssignmentSessionAndCashierAreTerminal() {
        WorkShiftRequirement requirement = dueRequirement();
        when(requirementRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(requirement));
        when(assignmentRepository.existsByShiftTemplateIdAndWorkDateAndStatusIn(
                2L, requirement.getWorkDate(), List.of(WorkScheduleStatus.SCHEDULED)))
                .thenReturn(true);

        assertThat(service.completeIfEligible(21L)).isFalse();
        assertThat(requirement.getStatus()).isEqualTo(WorkDailyShiftStatus.OPEN);

        when(assignmentRepository.existsByShiftTemplateIdAndWorkDateAndStatusIn(
                2L, requirement.getWorkDate(), List.of(WorkScheduleStatus.SCHEDULED)))
                .thenReturn(false);
        when(sessionRepository.existsForWorkShiftByStatus(
                2L, requirement.getWorkDate(), WorkShiftSessionStatus.ACTIVE))
                .thenReturn(true);
        assertThat(service.completeIfEligible(21L)).isFalse();

        when(sessionRepository.existsForWorkShiftByStatus(
                2L, requirement.getWorkDate(), WorkShiftSessionStatus.ACTIVE))
                .thenReturn(false);
        when(cashierShiftRepository.existsActiveForWorkShift(
                2L,
                requirement.getWorkDate(),
                List.of(CashierShiftStatus.OPEN, CashierShiftStatus.CLOSING)))
                .thenReturn(true);
        assertThat(service.completeIfEligible(21L)).isFalse();
        verify(requirementRepository, never()).save(requirement);
    }

    @Test
    void dueShiftCompletesOnlyAfterAllOperationalWorkIsTerminal() {
        WorkShiftRequirement requirement = dueRequirement();
        WorkShiftRegistrationRequest pending = WorkShiftRegistrationRequest.builder()
                .id(44L)
                .employee(user(8L, "Staff", UserType.STAFF))
                .shiftTemplate(template)
                .workDate(requirement.getWorkDate())
                .status(WorkShiftRegistrationStatus.PENDING)
                .build();
        when(requirementRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(requirement));
        when(assignmentRepository.existsByShiftTemplateIdAndWorkDateAndStatusIn(
                2L, requirement.getWorkDate(), List.of(WorkScheduleStatus.SCHEDULED)))
                .thenReturn(false);
        when(sessionRepository.existsForWorkShiftByStatus(
                2L, requirement.getWorkDate(), WorkShiftSessionStatus.ACTIVE))
                .thenReturn(false);
        when(cashierShiftRepository.existsActiveForWorkShift(
                2L,
                requirement.getWorkDate(),
                List.of(CashierShiftStatus.OPEN, CashierShiftStatus.CLOSING)))
                .thenReturn(false);
        when(registrationRepository.findSlotByStatusForUpdate(
                2L, requirement.getWorkDate(), WorkShiftRegistrationStatus.PENDING))
                .thenReturn(List.of(pending));

        assertThat(service.completeIfEligible(21L)).isTrue();

        assertThat(requirement.getStatus()).isEqualTo(WorkDailyShiftStatus.COMPLETED);
        assertThat(requirement.getCompletedAtUtc()).isEqualTo(NOW);
        assertThat(requirement.getRegistrationOpen()).isFalse();
        assertThat(pending.getStatus()).isEqualTo(WorkShiftRegistrationStatus.CANCELLED);
        verify(requirementRepository).save(requirement);
    }

    @Test
    void openShiftWaitingForClosureDoesNotAcceptNewAssignments() {
        WorkShiftRequirement requirement = dueRequirement();
        when(requirementRepository.findByShiftTemplateIdAndWorkDate(
                2L, requirement.getWorkDate()))
                .thenReturn(Optional.of(requirement));

        assertThatThrownBy(() -> service.requireOpen(2L, requirement.getWorkDate()))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.WORK_DAILY_SHIFT_NOT_OPEN));
    }

    private WorkShiftRequirement futureRequirement() {
        return requirement(20L, LocalDate.of(2026, 8, 2));
    }

    private WorkShiftRequirement dueRequirement() {
        return requirement(21L, LocalDate.of(2026, 8, 1));
    }

    private WorkShiftRequirement requirement(Long id, LocalDate workDate) {
        return WorkShiftRequirement.builder()
                .id(id)
                .shiftTemplate(template)
                .workDate(workDate)
                .requiredStaff(1)
                .shiftCodeSnapshot("SANG")
                .shiftNameSnapshot("Ca sáng")
                .shiftColorSnapshot("#B8944F")
                .startTimeSnapshot(LocalTime.of(6, 0))
                .endTimeSnapshot(LocalTime.of(14, 0))
                .checkInEarlyMinutesSnapshot(30)
                .lateToleranceMinutesSnapshot(15)
                .sortOrderSnapshot(10)
                .registrationOpen(true)
                .assignmentPolicySnapshot(WorkShiftAssignmentPolicy.MANUAL_APPROVAL)
                .status(WorkDailyShiftStatus.OPEN)
                .build();
    }

    private User user(Long id, String fullName, UserType type) {
        User value = User.builder()
                .fullName(fullName)
                .username("user" + id)
                .email("user" + id + "@example.com")
                .type(type)
                .build();
        value.setId(id);
        return value;
    }
}
