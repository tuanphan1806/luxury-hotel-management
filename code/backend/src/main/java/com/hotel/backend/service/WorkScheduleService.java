package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftAssignmentPolicy;
import com.hotel.backend.constant.WorkShiftSessionStatus;
import com.hotel.backend.dto.request.CancelWorkScheduleRequest;
import com.hotel.backend.dto.request.WorkAttendanceRequest;
import com.hotel.backend.dto.request.WorkScheduleAssignmentRequest;
import com.hotel.backend.dto.response.WorkScheduleResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkScheduleAssignment;
import com.hotel.backend.entity.WorkShiftRequirement;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.entity.WorkShiftSession;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.WorkScheduleAssignmentRepository;
import com.hotel.backend.repository.WorkShiftSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkScheduleService {

    static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_RANGE_DAYS = 93;
    private static final int MAINTENANCE_BATCH_SIZE = 100;

    private final WorkScheduleAssignmentRepository repository;
    private final WorkShiftSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final WorkDailyShiftService dailyShiftService;
    private final CashierShiftService cashierShiftService;
    private final ReservationAuditService auditService;
    private final Clock clock;

    @Autowired
    public WorkScheduleService(
            WorkScheduleAssignmentRepository repository,
            WorkShiftSessionRepository sessionRepository,
            UserRepository userRepository,
            WorkDailyShiftService dailyShiftService,
            CashierShiftService cashierShiftService,
            ReservationAuditService auditService) {
        this(repository, sessionRepository, userRepository, dailyShiftService, cashierShiftService,
                auditService, Clock.systemUTC());
    }

    WorkScheduleService(
            WorkScheduleAssignmentRepository repository,
            WorkShiftSessionRepository sessionRepository,
            UserRepository userRepository,
            WorkDailyShiftService dailyShiftService,
            CashierShiftService cashierShiftService,
            ReservationAuditService auditService,
            Clock clock) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.dailyShiftService = dailyShiftService;
        this.cashierShiftService = cashierShiftService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkScheduleResponse> list(
            LocalDate from,
            LocalDate to,
            Long requestedEmployeeId,
            WorkScheduleStatus status,
            User currentUser) {
        User actor = requireOperator(currentUser);
        validateRange(from, to);
        Long employeeId = actor.getType() == UserType.STAFF
                ? actor.getId()
                : requestedEmployeeId;
        Instant fromUtc = from.atStartOfDay(HOTEL_ZONE).toInstant();
        Instant toUtc = to.plusDays(1).atStartOfDay(HOTEL_ZONE).toInstant();
        return repository.findInWindow(fromUtc, toUtc, employeeId, status)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkScheduleResponse get(Long assignmentId, User currentUser) {
        User actor = requireOperator(currentUser);
        WorkScheduleAssignment assignment = repository.findById(assignmentId)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        ensureCanView(actor, assignment);
        return toResponse(assignment);
    }

    @Transactional(readOnly = true)
    public WorkScheduleResponse current(User currentUser) {
        User actor = requireStaff(currentUser);
        return sessionRepository
                .findFirstByEmployeeIdAndStatusOrderByActualCheckInUtcDesc(
                        actor.getId(), WorkShiftSessionStatus.ACTIVE)
                .map(session -> {
                    session.getAssignment().setWorkShiftSession(session);
                    return toResponse(session.getAssignment());
                })
                .orElse(null);
    }

    @Transactional
    public WorkScheduleResponse create(
            WorkScheduleAssignmentRequest request,
            User currentUser) {
        User actor = requireAdmin(currentUser);
        User employee = requireActiveStaff(request.employeeId());
        WorkShiftRequirement dailyShift = dailyShiftService.requireAvailableForNewAssignment(
                request.shiftTemplateId(), request.workDate());
        return createAssignment(employee, dailyShift, actor, request.note(), "ADMIN_SCHEDULE");
    }

    /**
     * Internal entry point for a daily shift configured with AUTO_ASSIGN.
     * The public REST API never calls this directly; the registration workflow
     * owns the locked daily-shift capacity check and invokes it atomically.
     */
    @Transactional
    public WorkScheduleResponse createAutomaticRegistration(
            WorkShiftRequirement dailyShift,
            User currentStaff,
            String note) {
        User actor = requireStaff(currentStaff);
        if (dailyShift.getAssignmentPolicySnapshot()
                != WorkShiftAssignmentPolicy.AUTO_ASSIGN) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_OPEN);
        }
        User employee = requireActiveStaff(actor.getId());
        return createAssignment(
                employee, dailyShift, actor, note, "STAFF_REGISTRATION_AUTO_ASSIGN");
    }

    private WorkScheduleResponse createAssignment(
            User employee,
            WorkShiftRequirement dailyShift,
            User actor,
            String note,
            String source) {
        WorkScheduleAssignment assignment = WorkScheduleAssignment.builder()
                .employee(employee)
                .createdBy(actor)
                .updatedBy(actor)
                .status(WorkScheduleStatus.SCHEDULED)
                .note(trimToNull(note))
                .build();
        applyScheduleSnapshot(assignment, employee, dailyShift);
        assignment = saveSchedule(assignment);
        auditSchedule(
                actor,
                assignment,
                ReservationAuditAction.WORK_SCHEDULE_CREATED,
                "Phân lịch " + assignment.getShiftNameSnapshot()
                        + " cho " + assignment.getEmployeeNameSnapshot(),
                Map.of("source", source));
        return toResponse(assignment);
    }

    @Transactional
    public WorkScheduleResponse update(
        Long assignmentId,
        WorkScheduleAssignmentRequest request,
        User currentUser) {
        User actor = requireAdmin(currentUser);
        User employee = requireActiveStaff(request.employeeId());
        // All mutations that may conflict with cancelling/editing a daily shift
        // use the same lock order: daily shift first, assignment second.
        WorkShiftRequirement dailyShift = dailyShiftService.requireOpen(
                request.shiftTemplateId(), request.workDate());
        WorkScheduleAssignment assignment = requireForUpdate(assignmentId);
        ensureMutable(assignment);
        Map<String, Object> before = scheduleSnapshot(assignment);
        boolean movingToAnotherShift = !assignment.getShiftTemplate().getId()
                .equals(dailyShift.getShiftTemplate().getId())
                || !assignment.getWorkDate().equals(dailyShift.getWorkDate());
        if (movingToAnotherShift) {
            dailyShiftService.ensureAssignmentCapacity(dailyShift);
        }
        assignment.setEmployee(employee);
        assignment.setUpdatedBy(actor);
        assignment.setNote(trimToNull(request.note()));
        applyScheduleSnapshot(assignment, employee, dailyShift);
        assignment = saveSchedule(assignment);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", scheduleSnapshot(assignment));
        auditSchedule(
                actor,
                assignment,
                ReservationAuditAction.WORK_SCHEDULE_UPDATED,
                "Điều chỉnh lịch làm việc của " + assignment.getEmployeeNameSnapshot(),
                detail);
        return toResponse(assignment);
    }

    @Transactional
    public WorkScheduleResponse cancel(
            Long assignmentId,
            CancelWorkScheduleRequest request,
            User currentUser) {
        User actor = requireAdmin(currentUser);
        WorkScheduleAssignment assignment = requireForUpdate(assignmentId);
        ensureMutable(assignment);
        assignment.setStatus(WorkScheduleStatus.CANCELLED);
        assignment.setCancellationReason(request.reason().trim());
        assignment.setCancelledAtUtc(clock.instant());
        assignment.setCancelledBy(actor);
        assignment.setUpdatedBy(actor);
        assignment = repository.saveAndFlush(assignment);
        auditSchedule(
                actor,
                assignment,
                ReservationAuditAction.WORK_SCHEDULE_CANCELLED,
                "Hủy lịch làm việc của " + assignment.getEmployeeNameSnapshot(),
                Map.of("reason", assignment.getCancellationReason()));
        return toResponse(assignment);
    }

    @Transactional
    public WorkScheduleResponse checkIn(
            Long assignmentId,
            WorkAttendanceRequest request,
            User currentUser) {
        User actor = requireStaff(currentUser);
        WorkScheduleAssignment assignment = requireForUpdate(assignmentId);
        ensureOwner(actor, assignment);
        WorkShiftSession existingSession = sessionRepository
                .findByAssignmentIdForUpdate(assignmentId)
                .orElse(null);
        if (existingSession != null) {
            assignment.setWorkShiftSession(existingSession);
            return toResponse(assignment);
        }
        if (assignment.getStatus() != WorkScheduleStatus.SCHEDULED) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_CANNOT_MODIFY);
        }
        Instant now = clock.instant();
        Instant earliest = assignment.getScheduledStartUtc()
                .minusSeconds(assignment.getCheckInEarlyMinutesSnapshot() * 60L);
        if (now.isBefore(earliest)) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_CHECK_IN_TOO_EARLY);
        }
        if (!now.isBefore(assignment.getScheduledEndUtc())) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_CHECK_IN_EXPIRED);
        }
        sessionRepository.findActiveByEmployeeIdForUpdate(actor.getId())
                .ifPresent(activeSession -> {
                    throw new AppException(ErrorCode.WORK_SCHEDULE_ALREADY_ACTIVE);
                });

        WorkShiftSession session = WorkShiftSession.builder()
                .assignment(assignment)
                .employee(actor)
                .status(WorkShiftSessionStatus.ACTIVE)
                .actualCheckInUtc(now)
                .checkInBy(actor)
                .note(trimToNull(request.note()))
                .build();
        try {
            session = sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException duplicate) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_ALREADY_ACTIVE);
        }
        assignment.setWorkShiftSession(session);
        cashierShiftService.openForWorkSession(session, actor);

        Map<String, Object> detail = attendanceDetail(assignment, session, now);
        detail.put("cashierShift", "OPENED_AUTOMATICALLY");
        auditSession(
                actor,
                session,
                ReservationAuditAction.WORK_SHIFT_CHECKED_IN,
                "Check-in " + assignment.getShiftNameSnapshot(),
                detail);
        return toResponse(assignment);
    }

    @Transactional
    public WorkScheduleResponse checkOut(
            Long assignmentId,
            WorkAttendanceRequest request,
            User currentUser) {
        User actor = requireStaff(currentUser);
        // Resolve the owning slot without a write lock, then acquire locks in
        // the canonical order used by daily-shift mutations: daily shift ->
        // assignment -> attendance session -> cashier shift. The assignment is
        // revalidated after its lock in case an ADMIN moved it concurrently.
        WorkScheduleAssignment route = repository.findById(assignmentId)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        WorkShiftRequirement lockedDailyShift = dailyShiftService
                .lockForAttendanceClosure(
                        route.getShiftTemplate().getId(), route.getWorkDate());
        WorkScheduleAssignment assignment = requireForUpdate(assignmentId);
        if (!assignment.getShiftTemplate().getId()
                .equals(lockedDailyShift.getShiftTemplate().getId())
                || !assignment.getWorkDate().equals(lockedDailyShift.getWorkDate())) {
            throw new AppException(
                    ErrorCode.WORK_SCHEDULE_CANNOT_MODIFY,
                    "Lịch làm việc vừa được điều chỉnh; vui lòng tải lại trước khi checkout");
        }
        ensureOwner(actor, assignment);
        WorkShiftSession session = sessionRepository
                .findByAssignmentIdForUpdate(assignmentId)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SCHEDULE_NOT_ACTIVE));
        assignment.setWorkShiftSession(session);
        if (session.getStatus() == WorkShiftSessionStatus.CLOSED
                || session.getStatus() == WorkShiftSessionStatus.AUTO_CLOSED) {
            return toResponse(assignment);
        }
        if (session.getStatus() != WorkShiftSessionStatus.ACTIVE) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_NOT_ACTIVE);
        }
        Instant now = clock.instant();
        if (now.isBefore(assignment.getScheduledEndUtc())
                && trimToNull(request.note()) == null) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Checkout trước giờ kết thúc ca phải nhập lý do");
        }
        cashierShiftService.closeForWorkSession(session, actor, trimToNull(request.note()));
        session.setStatus(WorkShiftSessionStatus.CLOSED);
        session.setActualCheckOutUtc(now);
        session.setCheckOutBy(actor);
        session.setNote(mergeNote(session.getNote(), request.note()));
        session = sessionRepository.saveAndFlush(session);
        assignment.setStatus(WorkScheduleStatus.FULFILLED);
        assignment.setUpdatedBy(actor);
        assignment = repository.saveAndFlush(assignment);
        assignment.setWorkShiftSession(session);

        Map<String, Object> detail = attendanceDetail(assignment, session, now);
        detail.put("cashierShift", "CLOSED_AUTOMATICALLY");
        auditSession(
                actor,
                session,
                ReservationAuditAction.WORK_SHIFT_CHECKED_OUT,
                "Check-out " + assignment.getShiftNameSnapshot(),
                detail);
        dailyShiftService.completeIfEligible(
                assignment.getShiftTemplate().getId(),
                assignment.getWorkDate());
        return toResponse(assignment);
    }

    @Transactional
    public int markExpiredScheduledAssignments() {
        Instant now = clock.instant();
        List<Long> candidateIds = repository.findExpiredScheduledIds(
                now,
                PageRequest.of(0, MAINTENANCE_BATCH_SIZE));
        List<WorkScheduleAssignment> expired = new ArrayList<>();
        for (Long assignmentId : candidateIds) {
            WorkScheduleAssignment assignment = repository.findByIdForUpdate(assignmentId)
                    .orElse(null);
            if (assignment == null
                    || assignment.getStatus() != WorkScheduleStatus.SCHEDULED
                    || !assignment.getScheduledEndUtc().isBefore(now)) {
                continue;
            }
            // Check again after locking the assignment. A check-in that started
            // just before the scheduled boundary may have created its session
            // while the maintenance query was selecting candidates.
            if (sessionRepository.findByAssignmentIdForUpdate(assignmentId).isPresent()) {
                continue;
            }
            assignment.setStatus(WorkScheduleStatus.ABSENT);
            assignment.setUpdatedBy(null);
            auditSchedule(
                    null,
                    assignment,
                    ReservationAuditAction.WORK_SHIFT_MARKED_ABSENT,
                    "Tự động đánh dấu vắng " + assignment.getEmployeeNameSnapshot(),
                    Map.of("scheduledEndUtc", assignment.getScheduledEndUtc()));
            expired.add(assignment);
        }
        if (!expired.isEmpty()) repository.saveAll(expired);
        return expired.size();
    }

    @Transactional
    public int autoCloseForgottenAssignments(long graceMinutes) {
        long safeGraceMinutes = Math.max(15L, Math.min(graceMinutes, 12L * 60L));
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(safeGraceMinutes * 60L);
        List<Long> candidateIds = sessionRepository.findExpiredAssignmentIds(
                WorkShiftSessionStatus.ACTIVE,
                cutoff,
                PageRequest.of(0, MAINTENANCE_BATCH_SIZE));
        List<WorkShiftSession> forgotten = new ArrayList<>();
        for (Long assignmentId : candidateIds) {
            // Keep the same lock order as manual checkout:
            // assignment -> attendance session -> cashier shift.
            WorkScheduleAssignment assignment = repository.findByIdForUpdate(assignmentId)
                    .orElse(null);
            if (assignment == null) continue;
            WorkShiftSession session = sessionRepository
                    .findByAssignmentIdForUpdate(assignmentId)
                    .orElse(null);
            // Another application instance or a manual checkout may have
            // completed this candidate while it was waiting for the lock.
            if (session == null
                    || session.getStatus() != WorkShiftSessionStatus.ACTIVE
                    || !assignment.getScheduledEndUtc().isBefore(cutoff)) {
                continue;
            }
            assignment.setWorkShiftSession(session);
            cashierShiftService.closeForWorkSessionAutomatically(session);
            assignment.setStatus(WorkScheduleStatus.FULFILLED);
            // Effective attendance ends at the scheduled boundary. The separate
            // flag makes it explicit that this is a system fallback, not a staff action.
            session.setStatus(WorkShiftSessionStatus.AUTO_CLOSED);
            session.setActualCheckOutUtc(assignment.getScheduledEndUtc());
            session.setCheckOutBy(null);
            assignment.setUpdatedBy(null);
            session.setNote(mergeNote(
                    session.getNote(),
                    "Hệ thống tự động checkout sau " + safeGraceMinutes
                            + " phút vì nhân viên quên thao tác"));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("scheduledEndUtc", assignment.getScheduledEndUtc());
            detail.put("processedAtUtc", now);
            detail.put("graceMinutes", safeGraceMinutes);
            detail.put("cashierShift", "CLOSED_AUTOMATICALLY");
            auditSession(
                    null,
                    session,
                    ReservationAuditAction.WORK_SHIFT_AUTO_CHECKED_OUT,
                    "Tự động checkout " + assignment.getEmployeeNameSnapshot(),
                    detail);
            forgotten.add(session);
        }
        if (!forgotten.isEmpty()) {
            sessionRepository.saveAll(forgotten);
            repository.saveAll(forgotten.stream()
                    .map(WorkShiftSession::getAssignment)
                    .toList());
        }
        return forgotten.size();
    }

    private void applyScheduleSnapshot(
            WorkScheduleAssignment assignment,
            User employee,
            WorkShiftRequirement dailyShift) {
        WorkShiftTemplate template = dailyShift.getShiftTemplate();
        LocalDate workDate = dailyShift.getWorkDate();
        LocalDateTime localStart = LocalDateTime.of(
                workDate, dailyShift.getStartTimeSnapshot());
        LocalDate endDate = dailyShift.getEndTimeSnapshot()
                .isAfter(dailyShift.getStartTimeSnapshot())
                ? workDate
                : workDate.plusDays(1);
        LocalDateTime localEnd = LocalDateTime.of(
                endDate, dailyShift.getEndTimeSnapshot());
        assignment.setEmployeeNameSnapshot(displayName(employee));
        assignment.setShiftTemplate(template);
        assignment.setShiftCodeSnapshot(dailyShift.getShiftCodeSnapshot());
        assignment.setShiftNameSnapshot(dailyShift.getShiftNameSnapshot());
        assignment.setShiftColorSnapshot(dailyShift.getShiftColorSnapshot());
        assignment.setCheckInEarlyMinutesSnapshot(
                dailyShift.getCheckInEarlyMinutesSnapshot());
        assignment.setLateToleranceMinutesSnapshot(
                dailyShift.getLateToleranceMinutesSnapshot());
        assignment.setWorkDate(workDate);
        assignment.setScheduledStartUtc(localStart.atZone(HOTEL_ZONE).toInstant());
        assignment.setScheduledEndUtc(localEnd.atZone(HOTEL_ZONE).toInstant());
    }

    private WorkScheduleAssignment saveSchedule(WorkScheduleAssignment assignment) {
        try {
            return repository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException conflict) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_OVERLAP);
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Khoảng ngày làm việc không hợp lệ");
        }
        if (Duration.between(
                from.atStartOfDay(HOTEL_ZONE),
                to.plusDays(1).atStartOfDay(HOTEL_ZONE)).toDays() > MAX_RANGE_DAYS) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ xem tối đa 93 ngày trong một lần");
        }
    }

    private User requireActiveStaff(Long employeeId) {
        User employee = userRepository.findById(employeeId)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SCHEDULE_INVALID_EMPLOYEE));
        if (employee.getType() != UserType.STAFF || employee.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_INVALID_EMPLOYEE);
        }
        return employee;
    }

    private WorkScheduleAssignment requireForUpdate(Long id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
    }

    private void ensureMutable(WorkScheduleAssignment assignment) {
        if (assignment.getStatus() != WorkScheduleStatus.SCHEDULED
                || assignment.getWorkShiftSession() != null) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_CANNOT_MODIFY);
        }
    }

    private void ensureOwner(User actor, WorkScheduleAssignment assignment) {
        if (!assignment.getEmployee().getId().equals(actor.getId())) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
    }

    private void ensureCanView(User actor, WorkScheduleAssignment assignment) {
        if (actor.getType() != UserType.ADMIN
                && !assignment.getEmployee().getId().equals(actor.getId())) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
    }

    private User requireOperator(User actor) {
        if (actor == null || (actor.getType() != UserType.ADMIN && actor.getType() != UserType.STAFF)) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return actor;
    }

    private User requireAdmin(User actor) {
        if (actor == null || actor.getType() != UserType.ADMIN) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return actor;
    }

    private User requireStaff(User actor) {
        if (actor == null || actor.getType() != UserType.STAFF) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return actor;
    }

    private void auditSchedule(
            User actor,
            WorkScheduleAssignment assignment,
            ReservationAuditAction action,
            String message,
            Map<String, ?> detail) {
        auditService.recordTargetForUser(
                actor,
                "WORK_SCHEDULE",
                String.valueOf(assignment.getId()),
                action,
                message,
                detail);
    }

    private void auditSession(
            User actor,
            WorkShiftSession session,
            ReservationAuditAction action,
            String message,
            Map<String, ?> detail) {
        auditService.recordTargetForUser(
                actor,
                "WORK_SHIFT_SESSION",
                String.valueOf(session.getId()),
                action,
                message,
                detail);
    }

    private Map<String, Object> scheduleSnapshot(WorkScheduleAssignment assignment) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("employeeId", assignment.getEmployee().getId());
        detail.put("employeeName", assignment.getEmployeeNameSnapshot());
        detail.put("shiftTemplateId", assignment.getShiftTemplate().getId());
        detail.put("shiftCode", assignment.getShiftCodeSnapshot());
        detail.put("workDate", assignment.getWorkDate());
        detail.put("scheduledStartUtc", assignment.getScheduledStartUtc());
        detail.put("scheduledEndUtc", assignment.getScheduledEndUtc());
        return detail;
    }

    private Map<String, Object> attendanceDetail(
            WorkScheduleAssignment assignment,
            WorkShiftSession session,
            Instant actionAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workScheduleAssignmentId", assignment.getId());
        detail.put("workShiftSessionId", session.getId());
        detail.put("employeeId", assignment.getEmployee().getId());
        detail.put("scheduledStartUtc", assignment.getScheduledStartUtc());
        detail.put("scheduledEndUtc", assignment.getScheduledEndUtc());
        detail.put("actionAtUtc", actionAt);
        detail.put("lateMinutes", lateMinutes(assignment, session));
        return detail;
    }

    private WorkScheduleResponse toResponse(WorkScheduleAssignment assignment) {
        WorkShiftSession session = assignment.getWorkShiftSession();
        long lateMinutes = lateMinutes(assignment, session);
        return new WorkScheduleResponse(
                assignment.getId(),
                assignment.getEmployee().getId(),
                assignment.getEmployeeNameSnapshot(),
                assignment.getShiftTemplate().getId(),
                assignment.getShiftCodeSnapshot(),
                assignment.getShiftNameSnapshot(),
                assignment.getShiftColorSnapshot(),
                assignment.getWorkDate(),
                assignment.getScheduledStartUtc(),
                assignment.getScheduledEndUtc(),
                assignment.getCheckInEarlyMinutesSnapshot(),
                assignment.getLateToleranceMinutesSnapshot(),
                assignment.getStatus(),
                session != null ? session.getId() : null,
                session != null ? session.getStatus() : null,
                session != null ? session.getActualCheckInUtc() : null,
                session != null ? session.getActualCheckOutUtc() : null,
                session != null
                        && session.getStatus() == WorkShiftSessionStatus.AUTO_CLOSED,
                lateMinutes > 0,
                lateMinutes,
                session != null && session.getCashierShift() != null
                        ? session.getCashierShift().getId()
                        : null,
                combinedNote(assignment.getNote(), session != null ? session.getNote() : null),
                assignment.getCancellationReason(),
                assignment.getCreatedAtUtc(),
                assignment.getUpdatedAtUtc());
    }

    private long lateMinutes(
            WorkScheduleAssignment assignment,
            WorkShiftSession session) {
        if (session == null || session.getActualCheckInUtc() == null) return 0L;
        Instant lateBoundary = assignment.getScheduledStartUtc()
                .plusSeconds(assignment.getLateToleranceMinutesSnapshot() * 60L);
        if (!session.getActualCheckInUtc().isAfter(lateBoundary)) return 0L;
        return Duration.between(assignment.getScheduledStartUtc(),
                session.getActualCheckInUtc()).toMinutes();
    }

    private String combinedNote(String scheduleNote, String attendanceNote) {
        String schedule = trimToNull(scheduleNote);
        String attendance = trimToNull(attendanceNote);
        if (schedule == null) return attendance;
        if (attendance == null || attendance.equals(schedule)) return schedule;
        String combined = schedule + "\n" + attendance;
        return combined.substring(0, Math.min(1000, combined.length()));
    }

    private String displayName(User user) {
        return user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName().trim()
                : user.getUsername();
    }

    private String mergeNote(String existing, String addition) {
        String normalized = trimToNull(addition);
        if (normalized == null) return existing;
        if (existing == null || existing.isBlank()) return normalized;
        return (existing + "\n" + normalized).substring(
                0, Math.min(1000, existing.length() + 1 + normalized.length()));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
