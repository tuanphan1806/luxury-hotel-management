package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.WorkShiftTemplateRequest;
import com.hotel.backend.dto.response.WorkShiftTemplateResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.entity.WorkShiftTemplate;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.WorkShiftTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkShiftTemplateService {

    private final WorkShiftTemplateRepository repository;
    private final ReservationAuditService auditService;

    @Transactional(readOnly = true)
    public List<WorkShiftTemplateResponse> list(boolean includeInactive, User currentUser) {
        User actor = requireOperator(currentUser);
        boolean canSeeInactive = actor.getType() == UserType.ADMIN && includeInactive;
        return (canSeeInactive
                ? repository.findAllByOrderBySortOrderAscStartTimeAscIdAsc()
                : repository.findAllByActiveTrueOrderBySortOrderAscStartTimeAscIdAsc())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkShiftTemplateResponse get(Long id, User currentUser) {
        requireOperator(currentUser);
        return toResponse(require(id));
    }

    @Transactional
    public WorkShiftTemplateResponse create(WorkShiftTemplateRequest request, User currentUser) {
        User actor = requireAdmin(currentUser);
        validateTimes(request.startTime(), request.endTime());
        String code = normalizeCode(request.code());
        if (repository.existsByCodeIgnoreCase(code)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE, "Mã ca làm việc đã tồn tại");
        }
        WorkShiftTemplate template = WorkShiftTemplate.builder()
                .code(code)
                .name(request.name().trim())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .checkInEarlyMinutes(request.checkInEarlyMinutes())
                .lateToleranceMinutes(request.lateToleranceMinutes())
                .color(request.color().toUpperCase())
                .sortOrder(request.sortOrder())
                .active(request.active())
                .createdBy(actor)
                .updatedBy(actor)
                .build();
        try {
            template = repository.saveAndFlush(template);
        } catch (DataIntegrityViolationException duplicate) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE, "Mã ca làm việc đã tồn tại");
        }
        auditService.recordTargetForUser(
                actor,
                "WORK_SHIFT_TEMPLATE",
                String.valueOf(template.getId()),
                ReservationAuditAction.WORK_SHIFT_TEMPLATE_CREATED,
                "Tạo mẫu ca " + template.getName(),
                snapshot(template));
        return toResponse(template);
    }

    @Transactional
    public WorkShiftTemplateResponse update(
            Long id,
            WorkShiftTemplateRequest request,
            User currentUser) {
        User actor = requireAdmin(currentUser);
        validateTimes(request.startTime(), request.endTime());
        WorkShiftTemplate template = require(id);
        Map<String, Object> before = snapshot(template);
        String code = normalizeCode(request.code());
        if (repository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE, "Mã ca làm việc đã tồn tại");
        }
        template.setCode(code);
        template.setName(request.name().trim());
        template.setStartTime(request.startTime());
        template.setEndTime(request.endTime());
        template.setCheckInEarlyMinutes(request.checkInEarlyMinutes());
        template.setLateToleranceMinutes(request.lateToleranceMinutes());
        template.setColor(request.color().toUpperCase());
        template.setSortOrder(request.sortOrder());
        template.setActive(request.active());
        template.setUpdatedBy(actor);
        try {
            template = repository.saveAndFlush(template);
        } catch (DataIntegrityViolationException duplicate) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE, "Mã ca làm việc đã tồn tại");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", snapshot(template));
        detail.put("historicalSchedulesPreserved", true);
        auditService.recordTargetForUser(
                actor,
                "WORK_SHIFT_TEMPLATE",
                String.valueOf(template.getId()),
                ReservationAuditAction.WORK_SHIFT_TEMPLATE_UPDATED,
                "Cập nhật mẫu ca " + template.getName(),
                detail);
        return toResponse(template);
    }

    @Transactional(readOnly = true)
    public WorkShiftTemplate requireActive(Long id) {
        WorkShiftTemplate template = require(id);
        if (!Boolean.TRUE.equals(template.getActive())) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_CANNOT_MODIFY,
                    "Mẫu ca đã ngừng sử dụng");
        }
        return template;
    }

    private WorkShiftTemplate require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.WORK_SHIFT_TEMPLATE_NOT_FOUND));
    }

    private void validateTimes(LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Giờ bắt đầu và kết thúc ca phải khác nhau");
        }
    }

    private User requireOperator(User user) {
        if (user == null || (user.getType() != UserType.ADMIN && user.getType() != UserType.STAFF)) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return user;
    }

    private User requireAdmin(User user) {
        if (user == null || user.getType() != UserType.ADMIN) {
            throw new AppException(ErrorCode.WORK_SCHEDULE_FORBIDDEN);
        }
        return user;
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }

    private Map<String, Object> snapshot(WorkShiftTemplate template) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", template.getCode());
        values.put("name", template.getName());
        values.put("startTime", template.getStartTime());
        values.put("endTime", template.getEndTime());
        values.put("checkInEarlyMinutes", template.getCheckInEarlyMinutes());
        values.put("lateToleranceMinutes", template.getLateToleranceMinutes());
        values.put("active", template.getActive());
        return values;
    }

    private WorkShiftTemplateResponse toResponse(WorkShiftTemplate template) {
        return new WorkShiftTemplateResponse(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getStartTime(),
                template.getEndTime(),
                !template.getEndTime().isAfter(template.getStartTime()),
                template.getCheckInEarlyMinutes(),
                template.getLateToleranceMinutes(),
                template.getColor(),
                template.getSortOrder(),
                template.getActive(),
                template.getCreatedAtUtc(),
                template.getUpdatedAtUtc());
    }
}
