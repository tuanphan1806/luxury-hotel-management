package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.CashierShiftStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WorkDailyShiftStatus;
import com.hotel.backend.constant.WorkShiftAssignmentPolicy;
import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftRegistrationStatus;
import com.hotel.backend.constant.WorkShiftSessionStatus;
import com.hotel.backend.dto.request.CancelWorkDailyShiftRequest;
import com.hotel.backend.dto.request.WorkDailyShiftBulkItemRequest;
import com.hotel.backend.dto.request.WorkDailyShiftBulkRequest;
import com.hotel.backend.dto.request.WorkDailyShiftRequest;
import com.hotel.backend.dto.response.WorkDailyShiftBulkCreateResponse;
import com.hotel.backend.dto.response.WorkDailyShiftBulkPreviewItemResponse;
import com.hotel.backend.dto.response.WorkDailyShiftBulkPreviewResponse;
import com.hotel.backend.dto.response.WorkShiftCalendarSlotResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkScheduleAssignment;
import com.hotel.backend.entity.WorkShiftRegistrationRequest;
import com.hotel.backend.entity.WorkShiftRequirement;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CashierShiftRepository;
import com.hotel.backend.repository.WorkScheduleAssignmentRepository;
import com.hotel.backend.repository.WorkShiftRegistrationRequestRepository;
import com.hotel.backend.repository.WorkShiftRequirementRepository;
import com.hotel.backend.repository.WorkShiftSessionRepository;
import com.hotel.backend.repository.WorkShiftTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkDailyShiftService {

    static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_RANGE_DAYS = 93;
    private static final int MAX_BULK_CANDIDATES = 2_000;
    private static final int COMPLETION_BATCH_SIZE = 200;

    private final WorkShiftRequirementRepository requirementRepository;
    private final WorkShiftTemplateRepository templateRepository;
    private final WorkScheduleAssignmentRepository assignmentRepository;
    private final WorkShiftRegistrationRequestRepository registrationRepository;
    private final WorkShiftSessionRepository sessionRepository;
    private final CashierShiftRepository cashierShiftRepository;
    private final ReservationAuditService auditService;
    private final Clock clock;

    @Autowired
    public WorkDailyShiftService(
            WorkShiftRequirementRepository requirementRepository,
            WorkShiftTemplateRepository templateRepository,
            WorkScheduleAssignmentRepository assignmentRepository,
            WorkShiftRegistrationRequestRepository registrationRepository,
            WorkShiftSessionRepository sessionRepository,
            CashierShiftRepository cashierShiftRepository,
            ReservationAuditService auditService) {
        this(requirementRepository, templateRepository, assignmentRepository,
                registrationRepository, sessionRepository, cashierShiftRepository,
                auditService, Clock.systemUTC());
    }

    WorkDailyShiftService(
            WorkShiftRequirementRepository requirementRepository,
            WorkShiftTemplateRepository templateRepository,
            WorkScheduleAssignmentRepository assignmentRepository,
            WorkShiftRegistrationRequestRepository registrationRepository,
            WorkShiftSessionRepository sessionRepository,
            CashierShiftRepository cashierShiftRepository,
            ReservationAuditService auditService,
            Clock clock) {
        this.requirementRepository = requirementRepository;
        this.templateRepository = templateRepository;
        this.assignmentRepository = assignmentRepository;
        this.registrationRepository = registrationRepository;
        this.sessionRepository = sessionRepository;
        this.cashierShiftRepository = cashierShiftRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkShiftCalendarSlotResponse get(Long id, User currentUser) {
        requireAdmin(currentUser);
        WorkShiftRequirement requirement = requirementRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_FOUND));
        int assigned = Math.toIntExact(assignmentRepository
                .countByShiftTemplateIdAndWorkDateAndStatusNot(
                        requirement.getShiftTemplate().getId(),
                        requirement.getWorkDate(),
                        WorkScheduleStatus.CANCELLED));
        return toResponse(requirement, assigned);
    }

    @Transactional
    public WorkShiftCalendarSlotResponse create(
            WorkDailyShiftRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        validateWorkDate(request.workDate());
        validateShiftConfiguration(
                request.workDate(), request.startTime(), request.endTime(),
                request.registrationOpen(), request.assignmentPolicy());
        WorkShiftTemplate template = requireActiveTemplateForUpdate(request.shiftTemplateId());
        if (requirementRepository.findForUpdate(template.getId(), request.workDate()).isPresent()) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_ALREADY_EXISTS);
        }
        WorkShiftRequirement requirement = buildRequirement(
                template,
                request.workDate(),
                request.shiftName(),
                request.startTime(),
                request.endTime(),
                request.requiredStaff(),
                request.registrationOpen(),
                request.assignmentPolicy(),
                request.checkInEarlyMinutes(),
                request.lateToleranceMinutes(),
                request.note(),
                admin);
        requirement = save(requirement);
        audit(
                admin,
                requirement,
                ReservationAuditAction.DAILY_SHIFT_CREATED,
                "Mở " + requirement.getShiftNameSnapshot() + " ngày " + requirement.getWorkDate(),
                snapshot(requirement));
        return toResponse(requirement, 0);
    }

    @Transactional
    public WorkShiftCalendarSlotResponse update(
            Long id,
            WorkDailyShiftRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        WorkShiftRequirement requirement = requireForUpdate(id);
        ensureMutable(requirement);
        validateWorkDate(requirement.getWorkDate());
        if (!clock.instant().isBefore(shiftStartUtc(requirement))) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_MODIFY,
                    "Ca đã bắt đầu nên chỉ được xử lý qua chấm công và kết ca");
        }
        validateShiftConfiguration(
                request.workDate(), request.startTime(), request.endTime(),
                request.registrationOpen(), request.assignmentPolicy());
        if (!requirement.getWorkDate().equals(request.workDate())
                || !requirement.getShiftTemplate().getId().equals(request.shiftTemplateId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không thể đổi ngày hoặc mẫu của ca đã tạo; hãy hủy và tạo ca mới");
        }
        long assignedCount = assignmentRepository
                .countByShiftTemplateIdAndWorkDateAndStatusNot(
                        requirement.getShiftTemplate().getId(),
                        requirement.getWorkDate(),
                        WorkScheduleStatus.CANCELLED);
        if (request.requiredStaff() < assignedCount) {
            throw new AppException(ErrorCode.WORK_SHIFT_REQUIREMENT_BELOW_ASSIGNED);
        }
        boolean hasPendingRequests = registrationRepository
                .existsByShiftTemplateIdAndWorkDateAndStatus(
                        requirement.getShiftTemplate().getId(),
                        requirement.getWorkDate(),
                        WorkShiftRegistrationStatus.PENDING);
        if (hasPendingRequests
                && (!request.registrationOpen()
                || request.assignmentPolicy() != WorkShiftAssignmentPolicy.MANUAL_APPROVAL)) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_MODIFY,
                    "Hãy xử lý các yêu cầu chờ duyệt trước khi đóng đăng ký hoặc đổi chính sách nhận ca");
        }
        String automaticColor = WorkShiftColorPolicy.forStartTime(request.startTime());
        int automaticSortOrder = WorkShiftColorPolicy.sortOrderForStartTime(request.startTime());
        boolean scheduleChanged = !requirement.getShiftNameSnapshot().equals(request.shiftName().trim())
                || !requirement.getStartTimeSnapshot().equals(request.startTime())
                || !requirement.getEndTimeSnapshot().equals(request.endTime())
                || !requirement.getCheckInEarlyMinutesSnapshot().equals(request.checkInEarlyMinutes())
                || !requirement.getLateToleranceMinutesSnapshot().equals(request.lateToleranceMinutes())
                || !requirement.getShiftColorSnapshot().equalsIgnoreCase(automaticColor)
                || !requirement.getSortOrderSnapshot().equals(automaticSortOrder);
        if (assignedCount > 0 && scheduleChanged) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_MODIFY);
        }
        Map<String, Object> before = snapshot(requirement);
        applyEditableFields(
                requirement,
                request.shiftName(),
                request.startTime(),
                request.endTime(),
                request.requiredStaff(),
                request.registrationOpen(),
                request.assignmentPolicy(),
                request.checkInEarlyMinutes(),
                request.lateToleranceMinutes(),
                request.note());
        requirement.setUpdatedBy(admin);
        requirement = save(requirement);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", snapshot(requirement));
        audit(
                admin,
                requirement,
                ReservationAuditAction.DAILY_SHIFT_UPDATED,
                "Cập nhật " + requirement.getShiftNameSnapshot()
                        + " ngày " + requirement.getWorkDate(),
                detail);
        return toResponse(requirement, Math.toIntExact(assignedCount));
    }

    @Transactional
    public WorkShiftCalendarSlotResponse cancel(
            Long id,
            CancelWorkDailyShiftRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        WorkShiftRequirement requirement = requireForUpdate(id);
        if (requirement.getStatus() == WorkDailyShiftStatus.CANCELLED) {
            return toResponse(requirement, 0);
        }
        ensureMutable(requirement);
        validateWorkDate(requirement.getWorkDate());
        Instant now = clock.instant();
        if (!now.isBefore(shiftStartUtc(requirement))) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_CANCEL);
        }
        Long templateId = requirement.getShiftTemplate().getId();
        List<WorkScheduleAssignment> assignments = assignmentRepository
                .findSlotForUpdate(templateId, requirement.getWorkDate());
        if (assignments.stream().anyMatch(item -> item.getWorkShiftSession() != null)) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_CANCEL);
        }
        List<WorkShiftRegistrationRequest> pendingRequests = registrationRepository
                .findSlotByStatusForUpdate(
                        templateId,
                        requirement.getWorkDate(),
                        WorkShiftRegistrationStatus.PENDING);
        String reason = request.reason().trim();
        List<Long> cancelledAssignmentIds = new ArrayList<>();
        for (WorkScheduleAssignment assignment : assignments) {
            if (assignment.getStatus() == WorkScheduleStatus.CANCELLED) continue;
            assignment.setStatus(WorkScheduleStatus.CANCELLED);
            assignment.setCancelledBy(admin);
            assignment.setCancelledAtUtc(now);
            assignment.setCancellationReason(reason);
            assignment.setUpdatedBy(admin);
            cancelledAssignmentIds.add(assignment.getId());
            auditService.recordTargetForUser(
                    admin,
                    "WORK_SCHEDULE",
                    String.valueOf(assignment.getId()),
                    ReservationAuditAction.WORK_SCHEDULE_CANCELLED,
                    "Hủy phân công vì ca làm việc bị hủy",
                    Map.of("dailyShiftId", requirement.getId(), "reason", reason));
        }
        if (!cancelledAssignmentIds.isEmpty()) {
            assignmentRepository.saveAll(assignments);
        }
        List<Long> cancelledRequestIds = new ArrayList<>();
        for (WorkShiftRegistrationRequest registration : pendingRequests) {
            registration.setStatus(WorkShiftRegistrationStatus.CANCELLED);
            registration.setAdminReason(reason);
            cancelledRequestIds.add(registration.getId());
        }
        if (!cancelledRequestIds.isEmpty()) {
            registrationRepository.saveAll(pendingRequests);
        }
        requirement.setStatus(WorkDailyShiftStatus.CANCELLED);
        // Status=CANCELLED already blocks registration. Keep the configured
        // preference so a future restore does not silently change the policy.
        requirement.setCancelledBy(admin);
        requirement.setCancelledAtUtc(now);
        requirement.setCancellationReason(reason);
        requirement.setUpdatedBy(admin);
        requirement = save(requirement);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("cancelledAssignmentIds", cancelledAssignmentIds);
        detail.put("cancelledRequestIds", cancelledRequestIds);
        audit(
                admin,
                requirement,
                ReservationAuditAction.DAILY_SHIFT_CANCELLED,
                "Hủy " + requirement.getShiftNameSnapshot()
                        + " ngày " + requirement.getWorkDate(),
                detail);
        return toResponse(requirement, 0);
    }

    @Transactional
    public WorkShiftCalendarSlotResponse restore(Long id, User currentUser) {
        User admin = requireAdmin(currentUser);
        WorkShiftRequirement requirement = requireForUpdate(id);
        if (requirement.getStatus() != WorkDailyShiftStatus.CANCELLED) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_RESTORE,
                    "Chỉ ca đã hủy mới có thể khôi phục");
        }
        if (!clock.instant().isBefore(shiftStartUtc(requirement))) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_RESTORE,
                    "Chỉ có thể khôi phục ca chưa bắt đầu");
        }

        String previousReason = requirement.getCancellationReason();
        requirement.setStatus(WorkDailyShiftStatus.OPEN);
        requirement.setCancelledBy(null);
        requirement.setCancelledAtUtc(null);
        requirement.setCancellationReason(null);
        requirement.setUpdatedBy(admin);
        requirement = save(requirement);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousCancellationReason", previousReason);
        detail.put("registrationOpen", requirement.getRegistrationOpen());
        detail.put("assignmentsRestored", false);
        detail.put("registrationRequestsRestored", false);
        audit(
                admin,
                requirement,
                ReservationAuditAction.DAILY_SHIFT_RESTORED,
                "Khôi phục " + requirement.getShiftNameSnapshot()
                        + " ngày " + requirement.getWorkDate(),
                detail);
        return toResponse(requirement, 0);
    }

    /**
     * Permanently removes only an unused future calendar slot. Once any
     * assignment or registration request exists, cancellation is the only
     * supported operation so workforce history remains auditable.
     */
    @Transactional
    public WorkShiftCalendarSlotResponse deleteUnused(Long id, User currentUser) {
        User admin = requireAdmin(currentUser);
        WorkShiftRequirement requirement = requireForUpdate(id);
        if (!clock.instant().isBefore(shiftStartUtc(requirement))) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_DELETE,
                    "Không thể xóa ca đã bắt đầu; hãy giữ lại để đối chiếu lịch sử");
        }

        Long templateId = requirement.getShiftTemplate().getId();
        LocalDate workDate = requirement.getWorkDate();
        if (assignmentRepository.existsByShiftTemplateIdAndWorkDate(templateId, workDate)
                || registrationRepository.existsByShiftTemplateIdAndWorkDate(
                        templateId, workDate)) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_DELETE,
                    "Ca đã có phân công hoặc yêu cầu đăng ký; hãy hủy ca để giữ lịch sử");
        }

        WorkShiftCalendarSlotResponse response = toResponse(requirement, 0);
        audit(
                admin,
                requirement,
                ReservationAuditAction.DAILY_SHIFT_DELETED,
                "Xóa ca trống " + requirement.getShiftNameSnapshot()
                        + " ngày " + requirement.getWorkDate(),
                snapshot(requirement));
        requirementRepository.delete(requirement);
        requirementRepository.flush();
        return response;
    }

    /**
     * Completes due daily shifts only after attendance and cashier workflows
     * have reached terminal states. A wall-clock boundary alone is never enough.
     */
    @Transactional
    public int completeExpiredDailyShifts() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        List<Long> candidateIds = requirementRepository.findOpenIdsThroughDate(
                today,
                PageRequest.of(0, COMPLETION_BATCH_SIZE));
        int completed = 0;
        for (Long id : candidateIds) {
            if (completeIfEligible(id, now)) completed++;
        }
        return completed;
    }

    @Transactional
    public boolean completeIfEligible(Long dailyShiftId) {
        return completeIfEligible(dailyShiftId, clock.instant());
    }

    @Transactional
    public boolean completeIfEligible(Long shiftTemplateId, LocalDate workDate) {
        WorkShiftRequirement requirement = requirementRepository
                .findForUpdate(shiftTemplateId, workDate)
                .orElse(null);
        return completeLockedIfEligible(requirement, clock.instant());
    }

    private boolean completeIfEligible(Long dailyShiftId, Instant now) {
        WorkShiftRequirement requirement = requirementRepository.findByIdForUpdate(dailyShiftId)
                .orElse(null);
        return completeLockedIfEligible(requirement, now);
    }

    private boolean completeLockedIfEligible(
            WorkShiftRequirement requirement,
            Instant now) {
        if (requirement == null || requirement.getStatus() != WorkDailyShiftStatus.OPEN) {
            return false;
        }
        if (now.isBefore(shiftEndUtc(requirement))) return false;

        Long templateId = requirement.getShiftTemplate().getId();
        LocalDate workDate = requirement.getWorkDate();
        if (assignmentRepository.existsByShiftTemplateIdAndWorkDateAndStatusIn(
                templateId,
                workDate,
                List.of(WorkScheduleStatus.SCHEDULED))) {
            return false;
        }
        if (sessionRepository.existsForWorkShiftByStatus(
                templateId,
                workDate,
                WorkShiftSessionStatus.ACTIVE)) {
            return false;
        }
        if (cashierShiftRepository.existsActiveForWorkShift(
                templateId,
                workDate,
                List.of(CashierShiftStatus.OPEN, CashierShiftStatus.CLOSING))) {
            return false;
        }

        List<WorkShiftRegistrationRequest> pendingRequests = registrationRepository
                .findSlotByStatusForUpdate(
                        templateId,
                        workDate,
                        WorkShiftRegistrationStatus.PENDING);
        for (WorkShiftRegistrationRequest registration : pendingRequests) {
            registration.setStatus(WorkShiftRegistrationStatus.CANCELLED);
            registration.setAdminReason("Ca đã kết thúc trước khi yêu cầu được xử lý");
        }
        if (!pendingRequests.isEmpty()) registrationRepository.saveAll(pendingRequests);

        requirement.setStatus(WorkDailyShiftStatus.COMPLETED);
        requirement.setRegistrationOpen(false);
        requirement.setCompletedAtUtc(now);
        requirement.setUpdatedBy(null);
        requirementRepository.save(requirement);
        audit(
                null,
                requirement,
                ReservationAuditAction.DAILY_SHIFT_COMPLETED,
                "Hoàn tất " + requirement.getShiftNameSnapshot()
                        + " ngày " + requirement.getWorkDate(),
                Map.of(
                        "completedAtUtc", now,
                        "expiredPendingRequestCount", pendingRequests.size()));
        return true;
    }

    @Transactional(readOnly = true)
    public WorkDailyShiftBulkPreviewResponse previewBulk(
            WorkDailyShiftBulkRequest request,
            User currentUser) {
        requireAdmin(currentUser);
        BulkPlan plan = buildBulkPlan(request, false);
        return previewResponse(plan);
    }

    @Transactional
    public WorkDailyShiftBulkCreateResponse createBulk(
            WorkDailyShiftBulkRequest request,
            User currentUser) {
        User admin = requireAdmin(currentUser);
        BulkPlan plan = buildBulkPlan(request, true);
        List<WorkShiftRequirement> created = new ArrayList<>();
        for (BulkCandidate candidate : plan.candidates()) {
            if (candidate.existingShift() != null) continue;
            WorkDailyShiftBulkItemRequest item = candidate.item();
            created.add(buildRequirement(
                    candidate.template(),
                    candidate.workDate(),
                    item.shiftName(),
                    item.startTime(),
                    item.endTime(),
                    item.requiredStaff(),
                    item.registrationOpen(),
                    item.assignmentPolicy(),
                    item.checkInEarlyMinutes(),
                    item.lateToleranceMinutes(),
                    item.note(),
                    admin));
        }
        if (!created.isEmpty()) {
            try {
                created = requirementRepository.saveAllAndFlush(created);
            } catch (DataIntegrityViolationException conflict) {
                throw new AppException(ErrorCode.WORK_DAILY_SHIFT_ALREADY_EXISTS);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("from", request.from());
            detail.put("to", request.to());
            detail.put("createdCount", created.size());
            detail.put("skippedExistingCount", plan.skippedExistingCount());
            detail.put("templateIds", request.shifts().stream()
                    .map(WorkDailyShiftBulkItemRequest::shiftTemplateId)
                    .toList());
            auditService.recordTargetForUser(
                    admin,
                    "WORK_DAILY_SHIFT_BULK",
                    request.from() + "_" + request.to(),
                    ReservationAuditAction.DAILY_SHIFT_BULK_CREATED,
                    "Tạo nhanh " + created.size() + " ca làm việc",
                    detail);
        }
        List<WorkShiftCalendarSlotResponse> responses = created.stream()
                .sorted(Comparator
                        .comparing(WorkShiftRequirement::getWorkDate)
                        .thenComparing(WorkShiftRequirement::getSortOrderSnapshot)
                        .thenComparing(WorkShiftRequirement::getId))
                .map(item -> toResponse(item, 0))
                .toList();
        return new WorkDailyShiftBulkCreateResponse(
                plan.candidates().size(),
                responses.size(),
                plan.skippedExistingCount(),
                responses);
    }

    @Transactional
    public WorkShiftRequirement requireOpen(Long templateId, LocalDate workDate) {
        WorkShiftRequirement requirement = requirementRepository
                .findForUpdate(templateId, workDate)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_FOUND));
        ensureOpen(requirement);
        // OPEN after the scheduled boundary means the shift is waiting for
        // attendance/cashier closure. It must not accept a new assignment.
        if (!clock.instant().isBefore(shiftEndUtc(requirement))) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_OPEN,
                    "Ca đã hết thời gian nhận phân công hoặc đăng ký mới");
        }
        return requirement;
    }

    /**
     * Locks the daily shift that owns an already active attendance workflow.
     * Unlike {@link #requireOpen(Long, LocalDate)}, this method intentionally
     * allows the scheduled end boundary to have passed so a late checkout can
     * still close its attendance and cashier session. Keeping this lock ahead
     * of the assignment lock prevents a deadlock with daily-shift cancel/update
     * operations, which use the same daily-shift -> assignment order.
     */
    @Transactional
    public WorkShiftRequirement lockForAttendanceClosure(
            Long templateId,
            LocalDate workDate) {
        WorkShiftRequirement requirement = requirementRepository
                .findForUpdate(templateId, workDate)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_FOUND));
        // A repeated checkout may arrive after the first request already moved
        // the daily shift to COMPLETED. Let the caller return the persisted
        // closed session instead of breaking idempotent retry semantics.
        if (requirement.getStatus() == WorkDailyShiftStatus.CANCELLED) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_OPEN);
        }
        return requirement;
    }

    @Transactional
    public WorkShiftRequirement requireAvailableForNewAssignment(
            Long templateId,
            LocalDate workDate) {
        WorkShiftRequirement requirement = requireOpen(templateId, workDate);
        ensureAssignmentCapacity(requirement);
        return requirement;
    }

    public void ensureAssignmentCapacity(WorkShiftRequirement requirement) {
        long assignedCount = assignmentRepository
                .countByShiftTemplateIdAndWorkDateAndStatusNot(
                        requirement.getShiftTemplate().getId(),
                        requirement.getWorkDate(),
                        WorkScheduleStatus.CANCELLED);
        if (assignedCount >= requirement.getRequiredStaff()) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_FULL);
        }
    }

    private BulkPlan buildBulkPlan(WorkDailyShiftBulkRequest request, boolean lockTemplates) {
        validateRange(request.from(), request.to());
        Set<Long> templateIds = new HashSet<>();
        for (WorkDailyShiftBulkItemRequest item : request.shifts()) {
            validateShiftConfiguration(
                    request.from(), item.startTime(), item.endTime(),
                    item.registrationOpen(), item.assignmentPolicy());
            if (!templateIds.add(item.shiftTemplateId())) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Mỗi mẫu ca chỉ được cấu hình một lần trong thao tác tạo nhanh");
            }
        }
        List<Long> orderedIds = templateIds.stream().sorted().toList();
        List<WorkShiftTemplate> templates = lockTemplates
                ? templateRepository.findAllByIdForUpdate(orderedIds)
                : templateRepository.findAllById(orderedIds);
        Map<Long, WorkShiftTemplate> templateById = new HashMap<>();
        templates.forEach(template -> templateById.put(template.getId(), template));
        if (templates.size() != orderedIds.size()
                || templates.stream().anyMatch(template -> !Boolean.TRUE.equals(template.getActive()))) {
            throw new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND);
        }
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = request.from(); !date.isAfter(request.to()); date = date.plusDays(1)) {
            if (request.weekdays().contains(date.getDayOfWeek())) dates.add(date);
        }
        long candidateCount = (long) dates.size() * request.shifts().size();
        if (candidateCount > MAX_BULK_CANDIDATES) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Một lần chỉ được tạo tối đa 2.000 ca làm việc");
        }
        List<WorkShiftRequirement> existing = requirementRepository
                .findAllByShiftTemplateIdInAndWorkDateBetween(
                        orderedIds, request.from(), request.to());
        Map<SlotKey, WorkShiftRequirement> existingByKey = new HashMap<>();
        existing.forEach(item -> existingByKey.put(new SlotKey(
                item.getShiftTemplate().getId(), item.getWorkDate()), item));
        List<BulkCandidate> candidates = new ArrayList<>();
        for (LocalDate date : dates) {
            for (WorkDailyShiftBulkItemRequest item : request.shifts()) {
                validateShiftEnd(date, item.startTime(), item.endTime());
                WorkShiftTemplate template = templateById.get(item.shiftTemplateId());
                WorkShiftRequirement existingShift = existingByKey.get(
                        new SlotKey(template.getId(), date));
                candidates.add(new BulkCandidate(
                        date,
                        template,
                        item,
                        existingShift));
            }
        }
        int skipped = (int) candidates.stream()
                .filter(candidate -> candidate.existingShift() != null)
                .count();
        return new BulkPlan(candidates, skipped);
    }

    private WorkDailyShiftBulkPreviewResponse previewResponse(BulkPlan plan) {
        List<WorkDailyShiftBulkPreviewItemResponse> items = plan.candidates().stream()
                .map(candidate -> new WorkDailyShiftBulkPreviewItemResponse(
                        candidate.workDate(),
                        candidate.template().getId(),
                        candidate.item().shiftName().trim(),
                        candidate.item().startTime().toString(),
                        candidate.item().endTime().toString(),
                        candidate.existingShift() != null ? "SKIP_EXISTING" : "CREATE",
                        candidate.existingShift() != null
                                ? candidate.existingShift().getStatus()
                                : null,
                        existingReason(candidate.existingShift())))
                .toList();
        return new WorkDailyShiftBulkPreviewResponse(
                items.size(),
                items.size() - plan.skippedExistingCount(),
                plan.skippedExistingCount(),
                items);
    }

    private String existingReason(WorkShiftRequirement existingShift) {
        if (existingShift == null) return null;
        return switch (existingShift.getStatus()) {
            case OPEN -> "Ca cùng mẫu đang mở; giữ nguyên cấu hình và phân công hiện tại";
            case CANCELLED -> "Ca cùng mẫu đã hủy; hãy khôi phục hoặc xóa ca trống trước khi tạo lại";
            case COMPLETED -> "Ca cùng mẫu đã hoàn tất và chỉ được xem lịch sử";
        };
    }

    private WorkShiftRequirement buildRequirement(
            WorkShiftTemplate template,
            LocalDate workDate,
            String shiftName,
            LocalTime startTime,
            LocalTime endTime,
            int requiredStaff,
            boolean registrationOpen,
            WorkShiftAssignmentPolicy assignmentPolicy,
            int checkInEarlyMinutes,
            int lateToleranceMinutes,
            String note,
            User admin) {
        WorkShiftRequirement requirement = WorkShiftRequirement.builder()
                .shiftTemplate(template)
                .workDate(workDate)
                .shiftCodeSnapshot(template.getCode())
                .sortOrderSnapshot(template.getSortOrder())
                .status(WorkDailyShiftStatus.OPEN)
                .assignmentPolicySnapshot(assignmentPolicy)
                .createdBy(admin)
                .updatedBy(admin)
                .build();
        applyEditableFields(
                requirement,
                shiftName,
                startTime,
                endTime,
                requiredStaff,
                registrationOpen,
                assignmentPolicy,
                checkInEarlyMinutes,
                lateToleranceMinutes,
                note);
        return requirement;
    }

    private void applyEditableFields(
            WorkShiftRequirement requirement,
            String shiftName,
            LocalTime startTime,
            LocalTime endTime,
            int requiredStaff,
            boolean registrationOpen,
            WorkShiftAssignmentPolicy assignmentPolicy,
            int checkInEarlyMinutes,
            int lateToleranceMinutes,
            String note) {
        requirement.setShiftNameSnapshot(shiftName.trim());
        requirement.setStartTimeSnapshot(startTime);
        requirement.setEndTimeSnapshot(endTime);
        requirement.setRequiredStaff(requiredStaff);
        requirement.setRegistrationOpen(registrationOpen
                && assignmentPolicy != WorkShiftAssignmentPolicy.ADMIN_ONLY);
        requirement.setAssignmentPolicySnapshot(assignmentPolicy);
        requirement.setCheckInEarlyMinutesSnapshot(checkInEarlyMinutes);
        requirement.setLateToleranceMinutesSnapshot(lateToleranceMinutes);
        requirement.setShiftColorSnapshot(WorkShiftColorPolicy.forStartTime(startTime));
        requirement.setSortOrderSnapshot(WorkShiftColorPolicy.sortOrderForStartTime(startTime));
        requirement.setNote(trimToNull(note));
    }

    private WorkShiftRequirement save(WorkShiftRequirement requirement) {
        try {
            return requirementRepository.saveAndFlush(requirement);
        } catch (DataIntegrityViolationException conflict) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_ALREADY_EXISTS);
        }
    }

    private WorkShiftTemplate requireActiveTemplateForUpdate(Long id) {
        WorkShiftTemplate template = templateRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND));
        if (!Boolean.TRUE.equals(template.getActive())) {
            throw new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    private WorkShiftRequirement requireForUpdate(Long id) {
        return requirementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_FOUND));
    }

    private void ensureOpen(WorkShiftRequirement requirement) {
        if (requirement.getStatus() != WorkDailyShiftStatus.OPEN) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_NOT_OPEN);
        }
    }

    private void ensureMutable(WorkShiftRequirement requirement) {
        if (requirement.getStatus() != WorkDailyShiftStatus.OPEN) {
            throw new AppException(ErrorCode.WORK_DAILY_SHIFT_CANNOT_MODIFY);
        }
    }

    private void validateWorkDate(LocalDate workDate) {
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        if (workDate == null || workDate.isBefore(today)) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE);
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        validateWorkDate(from);
        if (to == null || to.isBefore(from)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Khoảng ngày tạo ca không hợp lệ");
        }
        long days = Duration.between(
                from.atStartOfDay(HOTEL_ZONE),
                to.plusDays(1).atStartOfDay(HOTEL_ZONE)).toDays();
        if (days > MAX_RANGE_DAYS) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ được tạo ca trong tối đa 93 ngày mỗi lần");
        }
    }

    private void validateShiftConfiguration(
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            boolean registrationOpen,
            WorkShiftAssignmentPolicy assignmentPolicy) {
        validateWindow(startTime, endTime);
        validateShiftEnd(workDate, startTime, endTime);
        if (assignmentPolicy == WorkShiftAssignmentPolicy.ADMIN_ONLY && registrationOpen) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Ca chỉ do ADMIN phân công không thể mở đăng ký cho STAFF");
        }
    }

    private void validateWindow(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null || startTime.equals(endTime)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Giờ bắt đầu và kết thúc ca phải khác nhau");
        }
    }

    private void validateShiftEnd(
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime) {
        if (workDate == null || startTime == null || endTime == null) return;
        LocalDate endDate = endTime.isAfter(startTime)
                ? workDate
                : workDate.plusDays(1);
        if (!endDate.atTime(endTime).atZone(HOTEL_ZONE).toInstant().isAfter(clock.instant())) {
            throw new AppException(ErrorCode.WORK_SHIFT_REGISTRATION_PAST_DATE,
                    "Không thể tạo hoặc sửa một ca đã kết thúc");
        }
    }

    private Instant shiftStartUtc(WorkShiftRequirement requirement) {
        return requirement.getWorkDate()
                .atTime(requirement.getStartTimeSnapshot())
                .atZone(HOTEL_ZONE)
                .toInstant();
    }

    private Instant shiftEndUtc(WorkShiftRequirement requirement) {
        LocalDate endDate = requirement.getEndTimeSnapshot()
                .isAfter(requirement.getStartTimeSnapshot())
                ? requirement.getWorkDate()
                : requirement.getWorkDate().plusDays(1);
        return endDate
                .atTime(requirement.getEndTimeSnapshot())
                .atZone(HOTEL_ZONE)
                .toInstant();
    }

    private User requireAdmin(User actor) {
        if (actor == null || actor.getType() != UserType.ADMIN) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return actor;
    }

    private Map<String, Object> snapshot(WorkShiftRequirement requirement) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dailyShiftId", requirement.getId());
        detail.put("workDate", requirement.getWorkDate());
        detail.put("shiftTemplateId", requirement.getShiftTemplate().getId());
        detail.put("shiftName", requirement.getShiftNameSnapshot());
        detail.put("startTime", requirement.getStartTimeSnapshot());
        detail.put("endTime", requirement.getEndTimeSnapshot());
        detail.put("requiredStaff", requirement.getRequiredStaff());
        detail.put("registrationOpen", requirement.getRegistrationOpen());
        detail.put("assignmentPolicy", requirement.getAssignmentPolicySnapshot());
        detail.put("status", requirement.getStatus());
        return detail;
    }

    private void audit(
            User actor,
            WorkShiftRequirement requirement,
            ReservationAuditAction action,
            String message,
            Map<String, ?> detail) {
        auditService.recordTargetForUser(
                actor,
                "WORK_DAILY_SHIFT",
                String.valueOf(requirement.getId()),
                action,
                message,
                detail);
    }

    private WorkShiftCalendarSlotResponse toResponse(
            WorkShiftRequirement requirement,
            int assignedCount) {
        boolean open = requirement.getStatus() == WorkDailyShiftStatus.OPEN;
        boolean beforeShiftEnd = clock.instant().isBefore(shiftEndUtc(requirement));
        boolean registrationOpen = open
                && beforeShiftEnd
                && Boolean.TRUE.equals(requirement.getRegistrationOpen())
                && requirement.getAssignmentPolicySnapshot()
                        != WorkShiftAssignmentPolicy.ADMIN_ONLY;
        int availableSlots = open && beforeShiftEnd
                ? Math.max(0, requirement.getRequiredStaff() - assignedCount)
                : 0;
        return new WorkShiftCalendarSlotResponse(
                requirement.getId(),
                requirement.getStatus(),
                requirement.getShiftTemplate().getId(),
                requirement.getShiftCodeSnapshot(),
                requirement.getShiftNameSnapshot(),
                requirement.getShiftColorSnapshot(),
                requirement.getStartTimeSnapshot().toString(),
                requirement.getEndTimeSnapshot().toString(),
                !requirement.getEndTimeSnapshot().isAfter(requirement.getStartTimeSnapshot()),
                !clock.instant().isBefore(shiftStartUtc(requirement)),
                !clock.instant().isBefore(shiftEndUtc(requirement)),
                requirement.getCompletedAtUtc(),
                requirement.getCancellationReason(),
                requirement.getCheckInEarlyMinutesSnapshot(),
                requirement.getLateToleranceMinutesSnapshot(),
                requirement.getRequiredStaff(),
                assignedCount,
                0,
                availableSlots,
                registrationOpen,
                requirement.getAssignmentPolicySnapshot(),
                requirement.getNote(),
                null,
                null,
                List.of(),
                List.of());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record SlotKey(Long templateId, LocalDate workDate) {
    }

    private record BulkCandidate(
            LocalDate workDate,
            WorkShiftTemplate template,
            WorkDailyShiftBulkItemRequest item,
            WorkShiftRequirement existingShift) {
    }

    private record BulkPlan(List<BulkCandidate> candidates, int skippedExistingCount) {
    }
}
