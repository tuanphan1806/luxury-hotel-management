package com.hotel.backend.service.Impl;

import com.hotel.backend.dto.request.FacilityRequest;
import com.hotel.backend.dto.response.FacilityResponse;
import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.entity.Facility;
import com.hotel.backend.exception.DuplicateResourceException;
import com.hotel.backend.exception.ResourceNotFoundException;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.service.FacilityService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.ReservationAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FacilityServiceImpl implements FacilityService {

    private final FacilityRepository facilityRepository;
    private final MediaAssetService mediaAssetService;
    private final ReservationAuditService auditService;

    // ── READ ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<FacilityResponse> getAll() {
        log.debug("Lấy tất cả facilities");
        return facilityRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public FacilityResponse getById(Long id) {
        log.debug("Lấy facility id={}", id);
        return mapToResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FacilityResponse> getByType(String type) {
        log.debug("Lọc facilities theo type={}", type);
        return facilityRepository.findByTypeIgnoreCaseOrderByFacilityNameAsc(type)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FacilityResponse> search(String keyword) {
        log.debug("Tìm kiếm facilities keyword={}", keyword);
        return facilityRepository.findByFacilityNameContainingIgnoreCaseOrderByFacilityNameAsc(keyword)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ── WRITE ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FacilityResponse create(FacilityRequest request) {
        log.info("Tạo facility: {}", request.getFacilityName());

        if (facilityRepository.existsByFacilityNameIgnoreCase(request.getFacilityName())) {
            throw new DuplicateResourceException("Facility", "facilityName", request.getFacilityName());
        }

        Facility facility = Facility.builder()
                .facilityName(request.getFacilityName())
                .facilityNameEn(request.getFacilityNameEn())
                .type(request.getType())
                .description(request.getDescription())
                .descriptionEn(request.getDescriptionEn())
                .imageUrl(request.getImageUrl()) 
                .build();

        Facility saved = facilityRepository.save(facility);
        saved.setImageUrl(mediaAssetService.replaceReference(
                null,
                saved.getImageUrl(),
                UploadFolder.FACILITIES,
                MediaAssetOwnerType.FACILITY,
                saved.getId()));
        auditFacility(saved, ReservationAuditAction.FACILITY_CREATED,
                "Tạo tiện nghi", null, facilitySnapshot(saved));
        log.info("Đã tạo facility id={}", saved.getId());
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public FacilityResponse update(Long id, FacilityRequest request) {
        log.info("Cập nhật facility id={}", id);

        Facility facility = findOrThrow(id);
        Map<String, Object> oldValue = facilitySnapshot(facility);
        String previousImageUrl = facility.getImageUrl();

        if (facilityRepository.existsByFacilityNameIgnoreCaseAndIdNot(request.getFacilityName(), id)) {
            throw new DuplicateResourceException("Facility", "facilityName", request.getFacilityName());
        }

        facility.setFacilityName(request.getFacilityName());
        facility.setFacilityNameEn(request.getFacilityNameEn());
        facility.setType(request.getType());
        facility.setDescription(request.getDescription());
        facility.setDescriptionEn(request.getDescriptionEn());
        facility.setImageUrl(mediaAssetService.replaceReference(
                previousImageUrl,
                request.getImageUrl(),
                UploadFolder.FACILITIES,
                MediaAssetOwnerType.FACILITY,
                facility.getId()));
        Facility saved = facilityRepository.save(facility);
        auditFacility(saved, ReservationAuditAction.FACILITY_UPDATED,
                "Cập nhật tiện nghi", oldValue, facilitySnapshot(saved));
        log.info("Đã cập nhật facility id={}", saved.getId());
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        log.info("Xóa facility id={}", id);
        Facility facility = findOrThrow(id);
        Map<String, Object> oldValue = facilitySnapshot(facility);

        mediaAssetService.releaseReference(
                facility.getImageUrl(),
                MediaAssetOwnerType.FACILITY,
                facility.getId());

        // Detach khỏi tất cả room types để tránh lỗi FK khi xóa
        facility.getRoomTypes().forEach(rt -> rt.getFacilities().remove(facility));

        facilityRepository.delete(facility);
        auditFacility(facility, ReservationAuditAction.FACILITY_DELETED,
                "Xóa tiện nghi", oldValue, null);
        log.info("Đã xóa facility id={}", id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Facility findOrThrow(Long id) {
        return facilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facility", id));
    }

    private void auditFacility(
            Facility facility,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue) {
        auditService.recordTarget(
                "FACILITY",
                String.valueOf(facility.getId()),
                action,
                details,
                oldValue,
                newValue,
                null,
                null,
                null);
    }

    private Map<String, Object> facilitySnapshot(Facility facility) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", facility.getId());
        value.put("facilityName", facility.getFacilityName());
        value.put("facilityNameEn", facility.getFacilityNameEn());
        value.put("type", facility.getType());
        value.put("imageUrl", facility.getImageUrl());
        return value;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private FacilityResponse mapToResponse(Facility entity) {
        return FacilityResponse.builder()
                .id(entity.getId())
                .facilityName(entity.getFacilityName())
                .facilityNameEn(entity.getFacilityNameEn())
                .type(entity.getType())
                .description(entity.getDescription())
                .descriptionEn(entity.getDescriptionEn())
                .imageUrl(entity.getImageUrl()) 
                .build();
    }
}
