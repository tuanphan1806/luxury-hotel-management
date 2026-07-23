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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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

        List<String> requestedImages = requestedImagesForCreate(request);
        Facility facility = Facility.builder()
                .facilityName(request.getFacilityName())
                .facilityNameEn(request.getFacilityNameEn())
                .type(request.getType())
                .description(request.getDescription())
                .descriptionEn(request.getDescriptionEn())
                .imageUrl(primaryImage(requestedImages))
                .imageUrls(new ArrayList<>(requestedImages))
                .build();

        Facility saved = facilityRepository.save(facility);
        List<String> claimedImages = mediaAssetService.replaceReferences(
                List.of(),
                requestedImages,
                UploadFolder.FACILITIES,
                MediaAssetOwnerType.FACILITY,
                saved.getId(),
                2);
        saved.setImageUrls(new ArrayList<>(claimedImages));
        saved.setImageUrl(primaryImage(claimedImages));
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
        List<String> previousImages = currentImages(facility);
        List<String> requestedImages = requestedImagesForUpdate(previousImages, request);

        if (facilityRepository.existsByFacilityNameIgnoreCaseAndIdNot(request.getFacilityName(), id)) {
            throw new DuplicateResourceException("Facility", "facilityName", request.getFacilityName());
        }

        facility.setFacilityName(request.getFacilityName());
        facility.setFacilityNameEn(request.getFacilityNameEn());
        facility.setType(request.getType());
        facility.setDescription(request.getDescription());
        facility.setDescriptionEn(request.getDescriptionEn());
        List<String> claimedImages = mediaAssetService.replaceReferences(
                previousImages,
                requestedImages,
                UploadFolder.FACILITIES,
                MediaAssetOwnerType.FACILITY,
                facility.getId(),
                2);
        facility.setImageUrls(new ArrayList<>(claimedImages));
        facility.setImageUrl(primaryImage(claimedImages));
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

        mediaAssetService.releaseReferences(
                currentImages(facility),
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
        value.put("imageUrls", currentImages(facility));
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
                .imageUrls(currentImages(entity))
                .build();
    }

    private List<String> requestedImagesForCreate(FacilityRequest request) {
        if (request.getImageUrls() != null) {
            return normalizeImages(request.getImageUrls());
        }
        return normalizeImages(List.of(request.getImageUrl() == null ? "" : request.getImageUrl()));
    }

    private List<String> requestedImagesForUpdate(
            List<String> current,
            FacilityRequest request) {
        if (request.getImageUrls() != null) {
            return normalizeImages(request.getImageUrls());
        }
        if (request.getImageUrl() == null) {
            return current;
        }

        List<String> updated = new ArrayList<>(current);
        String primary = request.getImageUrl().trim();
        if (primary.isEmpty()) {
            if (!updated.isEmpty()) {
                updated.remove(0);
            }
        } else if (updated.isEmpty()) {
            updated.add(primary);
        } else {
            updated.set(0, primary);
        }
        return normalizeImages(updated);
    }

    private List<String> currentImages(Facility facility) {
        List<String> images = normalizeImages(facility.getImageUrls());
        if (!images.isEmpty()) {
            return images;
        }
        return normalizeImages(List.of(
                facility.getImageUrl() == null ? "" : facility.getImageUrl()));
    }

    private List<String> normalizeImages(Collection<String> images) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (images != null) {
            images.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(normalized::add);
        }
        return new ArrayList<>(normalized);
    }

    private String primaryImage(List<String> images) {
        return images == null || images.isEmpty() ? null : images.get(0);
    }
}
