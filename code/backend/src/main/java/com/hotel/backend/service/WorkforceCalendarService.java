package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftRegistrationStatus;
import com.hotel.backend.constant.WorkShiftSessionStatus;
import com.hotel.backend.dto.request.WorkScheduleAssignmentRequest;
import com.hotel.backend.dto.request.WorkShiftRegistrationCreateRequest;
import com.hotel.backend.dto.request.WorkShiftRegistrationReviewRequest;
import com.hotel.backend.dto.request.WorkShiftRequirementRequest;
import com.hotel.backend.dto.response.WorkScheduleResponse;
import com.hotel.backend.dto.response.WorkShiftCalendarAssignmentResponse;
import com.hotel.backend.dto.response.WorkShiftCalendarDayResponse;
import com.hotel.backend.dto.response.WorkShiftCalendarSlotResponse;
import com.hotel.backend.dto.response.WorkShiftMonthCalendarResponse;
import com.hotel.backend.dto.response.WorkShiftRegistrationResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkScheduleAssignment;
import com.hotel.backend.entity.WorkShiftRegistrationRequest;
import com.hotel.backend.entity.WorkShiftRequirement;
import com.hotel.backend.entity.WorkShiftSession;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.WorkScheduleAssignmentRepository;
import com.hotel.backend.repository.WorkShiftRegistrationRequestRepository;
import com.hotel.backend.repository.WorkShiftRequirementRepository;
import com.hotel.backend.repository.WorkShiftTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class WorkforceCalendarService {

    static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int DEFAULT_REQUIRED_STAFF = 1;

    private final WorkShiftRequirementRepository requirementRepository;
    private final WorkShiftRegistrationRequestRepository registrationRepository;
    private final WorkShiftTemplateRepository templateRepository;
    private final WorkScheduleAssignmentRepository assignmentRepository;
    private final WorkScheduleService workScheduleService;
    private final ReservationAuditService auditService;
    private final Clock clock;

    @Autowired
    public WorkforceCalendarService(
            WorkShiftRequirementRepository requirementRepository,
            WorkShiftRegistrationRequestRepository registrationRepository,
            WorkShiftTemplateRepository templateRepository,
            WorkScheduleAssignmentRepository assignmentRepository,
            WorkScheduleService workScheduleService,
            ReservationAuditService auditService) {
        this(
                requirementRepository,
                registrationRepository,
                templateRepository,
                assignmentRepository,
                workScheduleService,
                auditService,
                Clock.systemUTC());
    }

    WorkforceCalendarService(
            WorkShiftRequirementRepository requirementRepository,
            WorkShiftRegistrationRequestRepository registrationRepository,
            WorkShiftTemplateRepository templateRepository,
            WorkScheduleAssignmentRepository assignmentRepository,
            WorkScheduleService workScheduleService,
            ReservationAuditService auditService,
            Clock clock) {
        this.requirementRepository = requirementRepository;
        this.registrationRepository = registrationRepository;
        this.templateRepository = templateRepository;
        this.assignmentRepository = assignmentRepository;
        this.workScheduleService = workScheduleService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkShiftRegistrationResponse getRequest(
            Long requestId,
            User currentUser) {
        User actor = requireOperator(currentUser);
        WorkShiftRegistrationRequest request = registrationRepository.findById(requestId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.WORK_SHIFT_REGISTRATION_NOT_FOUND));
        if (actor.getType() == UserType.STAFF
                && !request.getEmployee().getId().equals(actor.getId())) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return toRegistrationResponse(request);
    }

    @Transactional(readOnly = true)
    public WorkShiftCalendarSlotResponse getSlot(
            LocalDate workDate,
            Long shiftTemplateId,
            User currentUser) {
        return month(YearMonth.from(workDate), currentUser)
                .days()
                .stream()
                .filter(day -> day.date().equals(workDate))
                .flatMap(day -> day.slots().stream())
                .filter(slot -> slot.shiftTemplateId().equals(shiftTemplateId))
                .findFirst()
                .orElseThrow(() -> new AppException(
                        ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public WorkShiftMonthCalendarResponse month(YearMonth month, User currentUser) {
        User actor = requireOperator(currentUser);
        YearMonth safeMonth = Objects.requireNonNull(month, "month");
        LocalDate from = safeMonth.atDay(1);
        LocalDate to = safeMonth.atEndOfMonth();
        Instant fromUtc = from.atStartOfDay(HOTEL_ZONE).toInstant();
        Instant toUtc = to.plusDays(1).atStartOfDay(HOTEL_ZONE).toInstant();

        List<WorkShiftTemplate> allTemplates =
                templateRepository.findAllByOrderBySortOrderAscStartTimeAscIdAsc();
        List<WorkShiftRequirement> requirements =
                requirementRepository.findAllByWorkDateBetweenOrderByWorkDateAsc(from, to);
        boolean staffView = actor.getType() == UserType.STAFF;
        List<WorkScheduleAssignment> assignments =
                assignmentRepository.findInWindow(
                        fromUtc,
                        toUtc,
                        staffView ? actor.getId() : null,
                        null);
        List<WorkScheduleAssignmentRepository.SlotAssignmentCount> staffAssignmentCounts =
                staffView
                        ? assignmentRepository.countAssignedBySlotInWindow(from, to)
                        : List.of();
        List<WorkShiftRegistrationRequest> requests =
                registrationRepository.findInWindow(
                        from,
                        to,
                        actor.getType() == UserType.STAFF ? actor.getId() : null);

        Map<SlotKey, WorkShiftRequirement> requirementBySlot = new HashMap<>();
        requirements.forEach(item -> requirementBySlot.put(
                new SlotKey(item.getWorkDate(), item.getShiftTemplate().getId()),
                item));
        Map<SlotKey, List<WorkScheduleAssignment>> assignmentsBySlot = new HashMap<>();
        assignments.stream()
                .filter(item -> item.getStatus() != WorkScheduleStatus.CANCELLED)
                .forEach(item -> assignmentsBySlot
                        .computeIfAbsent(
                                new SlotKey(item.getWorkDate(), item.getShiftTemplate().getId()),
                                ignored -> new ArrayList<>())
                        .add(item));
        Map<SlotKey, Integer> assignedCountBySlot = new HashMap<>();
        if (staffView) {
            staffAssignmentCounts.forEach(item -> assignedCountBySlot.put(
                    new SlotKey(item.getWorkDate(), item.getShiftTemplateId()),
                    Math.toIntExact(item.getAssignedCount())));
        } else {
            assignmentsBySlot.forEach((key, value) ->
                    assignedCountBySlot.put(key, value.size()));
        }
        Map<SlotKey, List<WorkShiftRegistrationRequest>> requestsBySlot = new HashMap<>();
        requests.forEach(item -> requestsBySlot
                .computeIfAbsent(
                        new SlotKey(item.getWorkDate(), item.getShiftTemplate().getId()),
                        ignored -> new ArrayList<>())
                .add(item));

        var referencedTemplateIds = new java.util.HashSet<Long>();
        requirementBySlot.keySet().forEach(key -> referencedTemplateIds.add(key.shiftTemplateId()));
        assignedCountBySlot.keySet().forEach(key -> referencedTemplateIds.add(key.shiftTemplateId()));
        assignmentsBySlot.keySet().forEach(key -> referencedTemplateIds.add(key.shiftTemplateId()));
        requestsBySlot.keySet().forEach(key -> referencedTemplateIds.add(key.shiftTemplateId()));
        List<WorkShiftTemplate> visibleTemplates = allTemplates.stream()
                .filter(item -> Boolean.TRUE.equals(item.getActive())
                        || referencedTemplateIds.contains(item.getId()))
                .toList();

        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        List<WorkShiftCalendarDayResponse> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<WorkShiftCalendarSlotResponse> slots = new ArrayList<>();
            for (WorkShiftTemplate template : visibleTemplates) {
                SlotKey key = new SlotKey(date, template.getId());
                WorkShiftRequirement requirement = requirementBySlot.get(key);
                List<WorkScheduleAssignment> slotAssignments =
                        assignmentsBySlot.getOrDefault(key, List.of());
                List<WorkShiftRegistrationRequest> slotRequests =
                        requestsBySlot.getOrDefault(key, List.of());
                int requiredStaff = requirement != null
                        ? requirement.getRequiredStaff()
                        : DEFAULT_REQUIRED_STAFF;
                int assignedCount = assignedCountBySlot.getOrDefault(key, 0);
                boolean registrationOpen =
                        clock.instant().isBefore(window(template, date).endUtc());
                int pendingCount = actor.getType() == UserType.ADMIN
                        ? (int) slotRequests.stream()
                                .filter(item -> item.getStatus()
                                        == WorkShiftRegistrationStatus.PENDING)
                                .count()
                        : 0;
                WorkScheduleAssignment ownAssignment = actor.getType() == UserType.STAFF
                        ? slotAssignments.stream()
                                .filter(item -> item.getEmployee().getId().equals(actor.getId()))
                                .findFirst()
                                .orElse(null)
                        : null;
                WorkShiftRegistrationRequest ownRequest = actor.getType() == UserType.STAFF
                        ? preferredOwnRequest(slotRequests)
                        : null;
                slots.add(new WorkShiftCalendarSlotResponse(
                        template.getId(),
                        template.getCode(),
                        template.getName(),
                        template.getColor(),
                        template.getStartTime().toString(),
                        template.getEndTime().toString(),
                        !template.getEndTime().isAfter(template.getStartTime()),
                        requiredStaff,
                        assignedCount,
                        pendingCount,
                        Math.max(0, requiredStaff - assignedCount),
                        registrationOpen,
                        requirement != null ? requirement.getNote() : null,
                        ownAssignment != null ? toAssignmentResponse(ownAssignment) : null,
                        ownRequest != null ? toRegistrationResponse(ownRequest) : null,
                        actor.getType() == UserType.ADMIN
                                ? slotAssignments.stream()
                                        .map(this::toAssignmentResponse)
                                        .toList()
                                : List.of(),
                        actor.getType() == UserType.ADMIN
                                ? slotRequests.stream()
                                        .map(this::toRegistrationResponse)
                                        .toList()
                                : List.of()));
            }
            days.add(new WorkShiftCalendarDayResponse(
                    date,
                    date.isBefore(today),
                    date.equals(today),
                    slots));
        }
        return new WorkShiftMonthCalendarResponse(
                safeMonth.toString(),
                from,
                to,
                days);
    }

    @Transactional
    public WorkShiftRegistrationResponse createRequest(
            WorkShiftRegistrationCreateRequest request,
            User currentUser) {
        User staff = requireStaff(currentUser);
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        if (request.workDate().isBefore(today)) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE);
        }
        WorkShiftTemplate template = templateRepository.findById(request.shiftTemplateId())
                .filter(item -> Boolean.TRUE.equals(item.getActive()))
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND));
        ShiftWindow window = window(template, request.workDate());
        if (!clock.instant().isBefore(window.endUtc())) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE);
        }
        boolean alreadyAssigned = assignmentRepository.findInWindow(
                        window.startUtc(),
                        window.endUtc(),
                        staff.getId(),
                        null)
                .stream()
                .anyMatch(item -> item.getStatus() != WorkScheduleStatus.CANCELLED);
        if (alreadyAssigned) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_DUPLICATE);
        }
        if (registrationRepository
                .existsByEmployeeIdAndShiftTemplateIdAndWorkDateAndStatus(
                        staff.getId(),
                        template.getId(),
                        request.workDate(),
                        WorkShiftRegistrationStatus.PENDING)) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_DUPLICATE);
        }
        int requiredStaff = requirementRepository
                .findForUpdate(template.getId(), request.workDate())
                .map(WorkShiftRequirement::getRequiredStaff)
                .orElse(DEFAULT_REQUIRED_STAFF);
        long assignedCount = assignmentRepository
                .countByShiftTemplateIdAndWorkDateAndStatusNot(
                        template.getId(),
                        request.workDate(),
                        WorkScheduleStatus.CANCELLED);
        if (assignedCount >= requiredStaff) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_FULL);
        }

        WorkShiftRegistrationRequest registration =
                WorkShiftRegistrationRequest.builder()
                        .employee(staff)
                        .shiftTemplate(template)
                        .workDate(request.workDate())
                        .status(WorkShiftRegistrationStatus.PENDING)
                        .staffNote(trimToNull(request.note()))
                        .build();
        try {
            registration = registrationRepository.saveAndFlush(registration);
        } catch (DataIntegrityViolationException duplicate) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_DUPLICATE);
        }
        audit(
                staff,
                registration,
                ReservationAuditAction.SHIFT_REQUEST_CREATED,
                "Đăng ký " + template.getName() + " ngày " + request.workDate(),
                Map.of("status", registration.getStatus()));
        return toRegistrationResponse(registration);
    }

    @Transactional
    public WorkShiftRegistrationResponse cancelRequest(
            Long requestId,
            User currentUser) {
        User staff = requireStaff(currentUser);
        WorkShiftRegistrationRequest registration = requireRequestForUpdate(requestId);
        if (!registration.getEmployee().getId().equals(staff.getId())) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        if (registration.getStatus() == WorkShiftRegistrationStatus.CANCELLED) {
            return toRegistrationResponse(registration);
        }
        if (registration.getStatus() != WorkShiftRegistrationStatus.PENDING) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_CANNOT_MODIFY);
        }
        registration.setStatus(WorkShiftRegistrationStatus.CANCELLED);
        registration = registrationRepository.saveAndFlush(registration);
        audit(
                staff,
                registration,
                ReservationAuditAction.SHIFT_REQUEST_CANCELLED,
                "Hủy đăng ký " + registration.getShiftTemplate().getName(),
                Map.of("status", registration.getStatus()));
        return toRegistrationResponse(registration);
    }

    @Transactional
    public WorkShiftRegistrationResponse approveRequest(
            Long requestId,
            WorkShiftRegistrationReviewRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        WorkShiftRegistrationRequest registration = requireRequestForUpdate(requestId);
        if (registration.getStatus() == WorkShiftRegistrationStatus.APPROVED) {
            return toRegistrationResponse(registration);
        }
        ensurePending(registration);

        WorkShiftTemplate template = templateRepository
                .findByIdForUpdate(registration.getShiftTemplate().getId())
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND));
        if (!Boolean.TRUE.equals(template.getActive())) {
            throw new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND);
        }
        ShiftWindow window = window(template, registration.getWorkDate());
        if (!clock.instant().isBefore(window.endUtc())) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE);
        }
        int requiredStaff = requirementRepository
                .findForUpdate(template.getId(), registration.getWorkDate())
                .map(WorkShiftRequirement::getRequiredStaff)
                .orElse(DEFAULT_REQUIRED_STAFF);
        long assignedCount = assignmentRepository
                .countByShiftTemplateIdAndWorkDateAndStatusNot(
                        template.getId(),
                        registration.getWorkDate(),
                        WorkScheduleStatus.CANCELLED);
        if (assignedCount >= requiredStaff) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_FULL);
        }

        WorkScheduleResponse assignment = workScheduleService.create(
                new WorkScheduleAssignmentRequest(
                        registration.getEmployee().getId(),
                        template.getId(),
                        registration.getWorkDate(),
                        registration.getStaffNote()),
                admin);
        registration.setStatus(WorkShiftRegistrationStatus.APPROVED);
        registration.setAdminReason(trimToNull(request.reason()));
        registration.setReviewedBy(admin);
        registration.setReviewedAtUtc(clock.instant());
        registration.setAssignment(assignmentRepository.getReferenceById(assignment.id()));
        registration = registrationRepository.saveAndFlush(registration);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", registration.getStatus());
        detail.put("assignmentId", assignment.id());
        audit(
                admin,
                registration,
                ReservationAuditAction.SHIFT_REQUEST_APPROVED,
                "Duyệt đăng ký " + template.getName()
                        + " của " + displayName(registration.getEmployee()),
                detail);
        return toRegistrationResponse(registration);
    }

    @Transactional
    public WorkShiftRegistrationResponse rejectRequest(
            Long requestId,
            WorkShiftRegistrationReviewRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        WorkShiftRegistrationRequest registration = requireRequestForUpdate(requestId);
        if (registration.getStatus() == WorkShiftRegistrationStatus.REJECTED) {
            return toRegistrationResponse(registration);
        }
        ensurePending(registration);
        String reason = trimToNull(request.reason());
        if (reason == null) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Từ chối đăng ký ca phải nhập lý do");
        }
        registration.setStatus(WorkShiftRegistrationStatus.REJECTED);
        registration.setAdminReason(reason);
        registration.setReviewedBy(admin);
        registration.setReviewedAtUtc(clock.instant());
        registration = registrationRepository.saveAndFlush(registration);
        audit(
                admin,
                registration,
                ReservationAuditAction.SHIFT_REQUEST_REJECTED,
                "Từ chối đăng ký " + registration.getShiftTemplate().getName()
                        + " của " + displayName(registration.getEmployee()),
                Map.of("status", registration.getStatus(), "reason", reason));
        return toRegistrationResponse(registration);
    }

    @Transactional
    public WorkShiftCalendarSlotResponse updateRequirement(
            LocalDate workDate,
            Long shiftTemplateId,
            WorkShiftRequirementRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        if (workDate.isBefore(today)) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE);
        }
        WorkShiftTemplate template = templateRepository.findByIdForUpdate(shiftTemplateId)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND));
        if (!clock.instant().isBefore(window(template, workDate).endUtc())) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE);
        }
        long assignedCount = assignmentRepository
                .countByShiftTemplateIdAndWorkDateAndStatusNot(
                        template.getId(),
                        workDate,
                        WorkScheduleStatus.CANCELLED);
        if (request.requiredStaff() < assignedCount) {
            throw new AppException(ErrorCode.WORK_SHIFT_REQUIREMENT_BELOW_ASSIGNED);
        }
        WorkShiftRequirement requirement = requirementRepository
                .findForUpdate(template.getId(), workDate)
                .orElseGet(() -> WorkShiftRequirement.builder()
                        .shiftTemplate(template)
                        .workDate(workDate)
                        .build());
        int previous = requirement.getRequiredStaff() != null
                ? requirement.getRequiredStaff()
                : DEFAULT_REQUIRED_STAFF;
        requirement.setRequiredStaff(request.requiredStaff());
        requirement.setNote(trimToNull(request.note()));
        requirement.setUpdatedBy(admin);
        requirement = requirementRepository.saveAndFlush(requirement);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workDate", workDate);
        detail.put("shiftTemplateId", template.getId());
        detail.put("previousRequiredStaff", previous);
        detail.put("requiredStaff", requirement.getRequiredStaff());
        auditService.recordTargetForUser(
                admin,
                "WORK_SHIFT_REQUIREMENT",
                String.valueOf(requirement.getId()),
                ReservationAuditAction.SHIFT_REQUIREMENT_UPDATED,
                "Cập nhật nhu cầu " + template.getName() + " ngày " + workDate,
                detail);
        return slotForRequirement(template, requirement, (int) assignedCount);
    }

    private WorkShiftCalendarSlotResponse slotForRequirement(
            WorkShiftTemplate template,
            WorkShiftRequirement requirement,
            int assignedCount) {
        return new WorkShiftCalendarSlotResponse(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getColor(),
                template.getStartTime().toString(),
                template.getEndTime().toString(),
                !template.getEndTime().isAfter(template.getStartTime()),
                requirement.getRequiredStaff(),
                assignedCount,
                0,
                Math.max(0, requirement.getRequiredStaff() - assignedCount),
                clock.instant().isBefore(
                        window(template, requirement.getWorkDate()).endUtc()),
                requirement.getNote(),
                null,
                null,
                List.of(),
                List.of());
    }

    private WorkShiftRegistrationRequest preferredOwnRequest(
            List<WorkShiftRegistrationRequest> requests) {
        return requests.stream()
                .sorted(Comparator
                        .comparing((WorkShiftRegistrationRequest item) ->
                                item.getStatus() == WorkShiftRegistrationStatus.PENDING ? 0 : 1)
                        .thenComparing(
                                WorkShiftRegistrationRequest::getCreatedAtUtc,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
    }

    private WorkShiftCalendarAssignmentResponse toAssignmentResponse(
            WorkScheduleAssignment assignment) {
        WorkShiftSession session = assignment.getWorkShiftSession();
        long lateMinutes = 0L;
        if (session != null && session.getActualCheckInUtc() != null) {
            Instant lateBoundary = assignment.getScheduledStartUtc()
                    .plusSeconds(assignment.getLateToleranceMinutesSnapshot() * 60L);
            if (session.getActualCheckInUtc().isAfter(lateBoundary)) {
                lateMinutes = Duration.between(
                        assignment.getScheduledStartUtc(),
                        session.getActualCheckInUtc()).toMinutes();
            }
        }
        return new WorkShiftCalendarAssignmentResponse(
                assignment.getId(),
                assignment.getEmployee().getId(),
                assignment.getEmployeeNameSnapshot(),
                assignment.getStatus(),
                session != null ? session.getStatus() : null,
                lateMinutes > 0,
                lateMinutes);
    }

    private WorkShiftRegistrationResponse toRegistrationResponse(
            WorkShiftRegistrationRequest request) {
        return new WorkShiftRegistrationResponse(
                request.getId(),
                request.getEmployee().getId(),
                displayName(request.getEmployee()),
                request.getShiftTemplate().getId(),
                request.getShiftTemplate().getCode(),
                request.getShiftTemplate().getName(),
                request.getShiftTemplate().getColor(),
                request.getWorkDate(),
                request.getStatus(),
                request.getStaffNote(),
                request.getAdminReason(),
                request.getReviewedBy() != null ? request.getReviewedBy().getId() : null,
                request.getReviewedBy() != null ? displayName(request.getReviewedBy()) : null,
                request.getReviewedAtUtc(),
                request.getAssignment() != null ? request.getAssignment().getId() : null,
                request.getCreatedAtUtc(),
                request.getUpdatedAtUtc());
    }

    private WorkShiftRegistrationRequest requireRequestForUpdate(Long requestId) {
        return registrationRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.WORK_SHIFT_REGISTRATION_NOT_FOUND));
    }

    private void ensurePending(WorkShiftRegistrationRequest request) {
        if (request.getStatus() != WorkShiftRegistrationStatus.PENDING) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_CANNOT_MODIFY);
        }
    }

    private User requireOperator(User actor) {
        if (actor == null
                || (actor.getType() != UserType.ADMIN && actor.getType() != UserType.STAFF)) {
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

    private ShiftWindow window(WorkShiftTemplate template, LocalDate workDate) {
        LocalDateTime localStart = LocalDateTime.of(workDate, template.getStartTime());
        LocalDate endDate = template.getEndTime().isAfter(template.getStartTime())
                ? workDate
                : workDate.plusDays(1);
        LocalDateTime localEnd = LocalDateTime.of(endDate, template.getEndTime());
        return new ShiftWindow(
                localStart.atZone(HOTEL_ZONE).toInstant(),
                localEnd.atZone(HOTEL_ZONE).toInstant());
    }

    private void audit(
            User actor,
            WorkShiftRegistrationRequest request,
            ReservationAuditAction action,
            String message,
            Map<String, ?> detail) {
        auditService.recordTargetForUser(
                actor,
                "WORK_SHIFT_REQUEST",
                String.valueOf(request.getId()),
                action,
                message,
                detail);
    }

    private String displayName(User user) {
        return user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName().trim()
                : user.getUsername();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private record SlotKey(LocalDate workDate, Long shiftTemplateId) {
    }

    private record ShiftWindow(Instant startUtc, Instant endUtc) {
    }
}
