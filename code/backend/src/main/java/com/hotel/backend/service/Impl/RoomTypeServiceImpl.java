package com.hotel.backend.service.Impl;

import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.dto.response.FacilityResponse;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.entity.Facility;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.DuplicateResourceException;
import com.hotel.backend.exception.ResourceNotFoundException;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.ReviewRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.service.RoomTypeService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.ReservationAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomTypeServiceImpl implements RoomTypeService {

    private final RoomTypeRepository roomTypeRepository;
    private final FacilityRepository facilityRepository;
    private final ReviewRepository reviewRepository;
    private final MediaAssetService mediaAssetService;
    private final ReservationAuditService auditService;

    // ── READ ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> getAll() {
        log.debug("Lấy tất cả room types");
        List<RoomType> roomTypes = roomTypeRepository.findAllWithFacilities();
        Map<Long, ReviewRepository.RoomTypeRatingSummary> ratings = loadRatings(roomTypes);
        return roomTypes.stream()
                .map(roomType -> mapToResponse(roomType, ratings.get(roomType.getId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public RoomTypeResponse getById(Long id) {
        log.debug("Lấy room type id={}", id);
        RoomType roomType = findOrThrow(id);
        return mapToResponse(roomType, loadRatings(List.of(roomType)).get(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> getByPriceRange(BigDecimal min, BigDecimal max) {
        log.debug("Lọc room type theo giá {} - {}", min, max);
        List<RoomType> roomTypes = roomTypeRepository.findByPriceBetweenOrderByPriceAsc(min, max);
        Map<Long, ReviewRepository.RoomTypeRatingSummary> ratings = loadRatings(roomTypes);
        return roomTypes.stream()
                .map(roomType -> mapToResponse(roomType, ratings.get(roomType.getId())))
                .collect(Collectors.toList());
    }

    // ── WRITE ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RoomTypeResponse create(RoomTypeRequest request) {
        log.info("Tạo room type: {}", request.getTypeName());

        if (roomTypeRepository.existsByTypeNameIgnoreCase(request.getTypeName())) {
            throw new DuplicateResourceException("RoomType", "typeName", request.getTypeName());
        }

        RoomType roomType = RoomType.builder()
                .typeName(request.getTypeName())
                .typeNameEn(request.getTypeNameEn())
                .description(request.getDescription())
                .descriptionEn(request.getDescriptionEn())
                .price(request.getPrice())
                .maxGuests(request.getMaxGuests())
                .imageUrl(request.getImageUrl())
                .build();

        resolveAndAssignFacilities(roomType, request.getFacilityIds());

        RoomType saved = roomTypeRepository.save(roomType);
        saved.setImageUrl(mediaAssetService.replaceReference(
                null,
                saved.getImageUrl(),
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                saved.getId()));
        auditRoomType(saved, ReservationAuditAction.ROOM_TYPE_CREATED,
                "Tạo hạng phòng", null, roomTypeSnapshot(saved));
        log.info("Đã tạo room type id={}", saved.getId());
        return mapToResponse(saved, null);
    }

    @Override
    @Transactional
    public RoomTypeResponse update(Long id, RoomTypeRequest request) {
        log.info("Cập nhật room type id={}", id);

        RoomType roomType = findOrThrow(id);
        Map<String, Object> oldValue = roomTypeSnapshot(roomType);
        String previousImageUrl = roomType.getImageUrl();

        if (roomTypeRepository.existsByTypeNameIgnoreCaseAndIdNot(request.getTypeName(), id)) {
            throw new DuplicateResourceException("RoomType", "typeName", request.getTypeName());
        }

        roomType.setTypeName(request.getTypeName());
        roomType.setTypeNameEn(request.getTypeNameEn());
        roomType.setDescription(request.getDescription());
        roomType.setDescriptionEn(request.getDescriptionEn());
        roomType.setPrice(request.getPrice());
        roomType.setMaxGuests(request.getMaxGuests());
        roomType.setImageUrl(mediaAssetService.replaceReference(
                previousImageUrl,
                request.getImageUrl(),
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                roomType.getId()));

        // Xóa toàn bộ facilities cũ, gán lại từ request
      roomType.getFacilities().clear();
        resolveAndAssignFacilities(roomType, request.getFacilityIds());

        RoomType saved = roomTypeRepository.save(roomType);
        auditRoomType(saved, ReservationAuditAction.ROOM_TYPE_UPDATED,
                "Cập nhật hạng phòng", oldValue, roomTypeSnapshot(saved));
        log.info("Đã cập nhật room type id={}", saved.getId());
        return mapToResponse(saved, loadRatings(List.of(saved)).get(saved.getId()));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        log.info("Xóa room type id={}", id);
        RoomType roomType = findOrThrow(id);
        Map<String, Object> oldValue = roomTypeSnapshot(roomType);
        mediaAssetService.releaseReference(
                roomType.getImageUrl(),
                MediaAssetOwnerType.ROOM_TYPE,
                roomType.getId());
     roomType.getFacilities().clear();  // dọn bảng room_type_facilities trước
        roomTypeRepository.delete(roomType);
        auditRoomType(roomType, ReservationAuditAction.ROOM_TYPE_DELETED,
                "Xóa hạng phòng", oldValue, null);
        log.info("Đã xóa room type id={}", id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private RoomType findOrThrow(Long id) {
        return roomTypeRepository.findByIdWithFacilities(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoomType", id));
    }

    /**
     * Resolve danh sách facilityIds → Facility entities rồi gán vào RoomType.
     * Ném lỗi nếu có bất kỳ ID nào không tồn tại trong DB.
     */
    private void resolveAndAssignFacilities(RoomType roomType, Set<Long> facilityIds) {
        if (facilityIds == null || facilityIds.isEmpty()) return;

        Set<Facility> found = facilityRepository.findAllByIdIn(facilityIds);

        if (found.size() != facilityIds.size()) {
            Set<Long> foundIds = found.stream().map(Facility::getId).collect(Collectors.toSet());
            Set<Long> missing  = facilityIds.stream()
                    .filter(fid -> !foundIds.contains(fid))
                    .collect(Collectors.toSet());
            throw new ResourceNotFoundException("Facility không tồn tại với ids: " + missing);
        }

      found.forEach(facility -> roomType.getFacilities().add(facility));
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private Map<Long, ReviewRepository.RoomTypeRatingSummary> loadRatings(List<RoomType> roomTypes) {
        if (roomTypes.isEmpty()) return Map.of();
        List<Long> roomTypeIds = roomTypes.stream().map(RoomType::getId).toList();
        return reviewRepository.summarizeByRoomTypeIds(roomTypeIds).stream()
                .collect(Collectors.toMap(
                        ReviewRepository.RoomTypeRatingSummary::getRoomTypeId,
                        Function.identity()));
    }

    private void auditRoomType(
            RoomType roomType,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue) {
        auditService.recordTarget(
                "ROOM_TYPE",
                String.valueOf(roomType.getId()),
                action,
                details,
                oldValue,
                newValue,
                null,
                null,
                null);
    }

    private Map<String, Object> roomTypeSnapshot(RoomType roomType) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", roomType.getId());
        value.put("typeName", roomType.getTypeName());
        value.put("typeNameEn", roomType.getTypeNameEn());
        value.put("price", roomType.getPrice());
        value.put("maxGuests", roomType.getMaxGuests());
        value.put("imageUrl", roomType.getImageUrl());
        value.put("facilityIds", roomType.getFacilities().stream()
                .map(Facility::getId)
                .sorted()
                .toList());
        return value;
    }

    private RoomTypeResponse mapToResponse(
            RoomType entity,
            ReviewRepository.RoomTypeRatingSummary rating) {
        List<FacilityResponse.Summary> facilitySummaries = entity.getFacilities()
                .stream()
                .map(f -> FacilityResponse.Summary.builder()
                        .id(f.getId())
                        .facilityName(f.getFacilityName())
                        .facilityNameEn(f.getFacilityNameEn())
                        .type(f.getType())
                        .imageUrl(f.getImageUrl())
                        .build())
                .collect(Collectors.toList());

        return RoomTypeResponse.builder()
                .id(entity.getId())
                .typeName(entity.getTypeName())
                .typeNameEn(entity.getTypeNameEn())
                .description(entity.getDescription())
                .descriptionEn(entity.getDescriptionEn())
                .price(entity.getPrice())
                .maxGuests(entity.getMaxGuests())
                .imageUrl(entity.getImageUrl())
                .facilities(facilitySummaries)
                .averageRating(rating != null && rating.getAverageRating() != null
                        ? Math.round(rating.getAverageRating() * 10.0) / 10.0
                        : 0.0)
                .totalReviews(rating != null && rating.getTotalReviews() != null
                        ? rating.getTotalReviews()
                        : 0L)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
