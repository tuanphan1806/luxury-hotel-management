package com.hotel.backend.service.Impl;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.dto.request.RoomTypeStatusRequest;
import com.hotel.backend.dto.response.FacilityResponse;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.entity.Facility;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.DuplicateResourceException;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.exception.ResourceNotFoundException;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.ReviewRepository;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.PricingQuoteLineRepository;
import com.hotel.backend.service.RoomTypeService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.service.RoomRateProfileManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
    private final RoomRateProfileRepository roomRateProfileRepository;
    private final PricingV2Properties pricingV2Properties;
    private final RoomRateProfileManagementService roomRateProfileManagementService;
    private final RoomRepository roomRepository;
    private final ReservationRoomTypeRepository reservationRoomTypeRepository;
    private final PricingQuoteLineRepository pricingQuoteLineRepository;

    // ── READ ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> getAll() {
        log.debug("Lấy tất cả room types");
        List<RoomType> roomTypes = roomTypeRepository.findAllActiveWithFacilities();
        Map<Long, ReviewRepository.RoomTypeRatingSummary> ratings = loadRatings(roomTypes);
        Map<Long, RoomRateProfile> publicRates = loadPublicRates(roomTypes);
        return roomTypes.stream()
                .filter(roomType -> publicRates.containsKey(roomType.getId()))
                .map(roomType -> mapToResponse(
                        roomType,
                        ratings.get(roomType.getId()),
                        publicRates.get(roomType.getId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> getAllForAdmin() {
        List<RoomType> roomTypes = roomTypeRepository.findAllWithFacilities();
        Map<Long, ReviewRepository.RoomTypeRatingSummary> ratings = loadRatings(roomTypes);
        Map<Long, RoomRateProfile> publicRates = loadPublicRates(roomTypes);
        return roomTypes.stream()
                .map(roomType -> mapToResponse(
                        roomType,
                        ratings.get(roomType.getId()),
                        publicRates.get(roomType.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoomTypeResponse getById(Long id) {
        log.debug("Lấy room type id={}", id);
        RoomType roomType = findOrThrow(id);
        RoomRateProfile publicRate = loadPublicRates(List.of(roomType)).get(id);
        if (publicRate == null) {
            throw new AppException(
                    ErrorCode.PRICING_PROFILE_NOT_FOUND,
                    "Loại phòng chưa có đúng một bảng giá giờ/đêm/ngày đang hiệu lực");
        }
        return mapToResponse(
                roomType,
                loadRatings(List.of(roomType)).get(id),
                publicRate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> getByPriceRange(BigDecimal min, BigDecimal max) {
        log.debug("Lọc room type theo giá {} - {}", min, max);
        List<RoomType> allRoomTypes = roomTypeRepository.findAllActiveWithFacilities();
        Map<Long, RoomRateProfile> allPublicRates = loadPublicRates(allRoomTypes);
        List<RoomType> roomTypes = allRoomTypes.stream()
                .filter(roomType -> {
                    RoomRateProfile rate = allPublicRates.get(roomType.getId());
                    return rate != null
                            && rate.getOvernightPrice().compareTo(min) >= 0
                            && rate.getOvernightPrice().compareTo(max) <= 0;
                })
                .sorted(java.util.Comparator.comparing(roomType ->
                        allPublicRates.get(roomType.getId()).getOvernightPrice()))
                .toList();
        Map<Long, ReviewRepository.RoomTypeRatingSummary> ratings = loadRatings(roomTypes);
        return roomTypes.stream()
                .map(roomType -> mapToResponse(
                        roomType,
                        ratings.get(roomType.getId()),
                        allPublicRates.get(roomType.getId())))
                .collect(Collectors.toList());
    }

    // ── WRITE ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RoomTypeResponse create(RoomTypeRequest request) {
        log.info("Tạo room type: {}", request.getTypeName());
        roomRateProfileManagementService.validate(request);

        if (roomTypeRepository.existsByTypeNameIgnoreCase(request.getTypeName())) {
            throw new DuplicateResourceException("RoomType", "typeName", request.getTypeName());
        }

        List<String> requestedImages = requestedImagesForCreate(request);
        RoomType roomType = RoomType.builder()
                .typeName(request.getTypeName())
                .typeNameEn(request.getTypeNameEn())
                .description(request.getDescription())
                .descriptionEn(request.getDescriptionEn())
                .maxGuests(request.getMaxGuests())
                .active(true)
                .imageUrl(primaryImage(requestedImages))
                .imageUrls(new ArrayList<>(requestedImages))
                .build();

        resolveAndAssignFacilities(roomType, request.getFacilityIds());

        RoomType saved = roomTypeRepository.saveAndFlush(roomType);
        List<String> claimedImages = mediaAssetService.replaceReferences(
                List.of(),
                requestedImages,
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                saved.getId(),
                3);
        saved.setImageUrls(new ArrayList<>(claimedImages));
        saved.setImageUrl(primaryImage(claimedImages));
        RoomRateProfile rateProfile = roomRateProfileManagementService
                .applyImmediate(saved, request);
        auditRoomType(saved, ReservationAuditAction.ROOM_TYPE_CREATED,
                "Tạo hạng phòng và bảng giá", null,
                roomTypeSnapshot(saved, rateProfile));
        log.info("Đã tạo room type id={}", saved.getId());
        return mapToResponse(
                saved,
                null,
                rateProfile);
    }

    @Override
    @Transactional
    public RoomTypeResponse update(Long id, RoomTypeRequest request) {
        log.info("Cập nhật room type id={}", id);

        RoomType roomType = roomTypeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoomType", id));
        // Initialize the collection while the entity is managed; update keeps
        // the historical fetch contract without changing the REST response.
        roomType.getFacilities().size();
        RoomRateProfile oldRate = loadPublicRates(List.of(roomType)).get(id);
        Map<String, Object> oldValue = roomTypeSnapshot(roomType, oldRate);
        List<String> previousImages = currentImages(roomType);
        List<String> requestedImages = requestedImagesForUpdate(previousImages, request);
        roomRateProfileManagementService.validate(request);

        if (roomTypeRepository.existsByTypeNameIgnoreCaseAndIdNot(request.getTypeName(), id)) {
            throw new DuplicateResourceException("RoomType", "typeName", request.getTypeName());
        }

        int previousCapacity = roomType.getMaxGuests();
        if (request.getMaxGuests() < previousCapacity) {
            List<String> affectedReservations = reservationRoomTypeRepository
                    .findActiveReservationCodesExceedingCapacity(
                            id, request.getMaxGuests());
            if (!affectedReservations.isEmpty()) {
                String visibleCodes = affectedReservations.stream()
                        .limit(5)
                        .collect(Collectors.joining(", "));
                String remaining = affectedReservations.size() > 5
                        ? " và " + (affectedReservations.size() - 5)
                                + " đơn khác"
                        : "";
                throw new AppException(
                        ErrorCode.ROOM_TYPE_CAPACITY_CONFLICT,
                        "Không thể giảm sức chứa xuống "
                                + request.getMaxGuests()
                                + " khách/phòng vì sẽ ảnh hưởng các đơn đang hoạt động: "
                                + visibleCodes + remaining
                                + ". Hãy hoàn tất/hủy các đơn này hoặc giữ sức chứa hiện tại.");
            }
        }
        if (request.getMaxGuests() > previousCapacity) {
            roomType.setMaxGuests(request.getMaxGuests());
            roomTypeRepository.saveAndFlush(roomType);
        }
        RoomRateProfile rateProfile = roomRateProfileManagementService
                .applyImmediate(roomType, request);

        roomType.setTypeName(request.getTypeName());
        roomType.setTypeNameEn(request.getTypeNameEn());
        roomType.setDescription(request.getDescription());
        roomType.setDescriptionEn(request.getDescriptionEn());
        roomType.setMaxGuests(request.getMaxGuests());
        List<String> claimedImages = mediaAssetService.replaceReferences(
                previousImages,
                requestedImages,
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                roomType.getId(),
                3);
        roomType.setImageUrls(new ArrayList<>(claimedImages));
        roomType.setImageUrl(primaryImage(claimedImages));

        // Xóa toàn bộ facilities cũ, gán lại từ request
      roomType.getFacilities().clear();
        resolveAndAssignFacilities(roomType, request.getFacilityIds());

        RoomType saved = roomTypeRepository.saveAndFlush(roomType);
        auditRoomType(saved, ReservationAuditAction.ROOM_TYPE_UPDATED,
                "Cập nhật hạng phòng và bảng giá", oldValue,
                roomTypeSnapshot(saved, rateProfile));
        log.info("Đã cập nhật room type id={}", saved.getId());
        return mapToResponse(
                saved,
                loadRatings(List.of(saved)).get(saved.getId()),
                rateProfile);
    }

    @Override
    @Transactional
    public RoomTypeResponse setActive(Long id, RoomTypeStatusRequest request) {
        RoomType roomType = roomTypeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoomType", id));
        roomType.getFacilities().size();
        boolean requestedActive = Boolean.TRUE.equals(request.getActive());
        boolean currentActive = Boolean.TRUE.equals(roomType.getActive());
        RoomRateProfile currentRate = requireSingleEffectiveRateForActivation(
                roomType, requestedActive);
        if (requestedActive == currentActive) {
            return mapToResponse(
                    roomType,
                    loadRatings(List.of(roomType)).get(id),
                    currentRate);
        }

        String reason = request.getReason() == null
                ? "" : request.getReason().trim();
        if (!requestedActive && reason.isEmpty()) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Cần nhập lý do ngừng hoạt động loại phòng");
        }

        Map<String, Object> oldValue = roomTypeSnapshot(roomType, currentRate);
        roomType.setActive(requestedActive);
        RoomType saved = roomTypeRepository.saveAndFlush(roomType);
        auditRoomType(
                saved,
                ReservationAuditAction.ROOM_TYPE_UPDATED,
                requestedActive
                        ? "Kích hoạt lại hạng phòng"
                        : "Ngừng hoạt động hạng phòng: " + reason,
                oldValue,
                roomTypeSnapshot(saved, currentRate));
        return mapToResponse(
                saved,
                loadRatings(List.of(saved)).get(id),
                currentRate);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        log.info("Xóa room type id={}", id);
        RoomType roomType = roomTypeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoomType", id));
        roomType.getFacilities().size();
        if (Boolean.TRUE.equals(roomType.getActive())) {
            throw new AppException(
                    ErrorCode.ROOM_TYPE_CANNOT_DELETE,
                    "Hãy ngừng hoạt động loại phòng trước khi xóa vĩnh viễn");
        }

        List<String> dependencies = new ArrayList<>();
        long roomCount = roomRepository.countByRoomTypeId(id);
        long reservationCount = reservationRoomTypeRepository.countByRoomTypeId(id);
        long reviewCount = reviewRepository.countByRoomTypeId(id);
        long quoteCount = pricingQuoteLineRepository.countByRoomTypeId(id);
        if (roomCount > 0) dependencies.add(roomCount + " phòng");
        if (reservationCount > 0) dependencies.add(reservationCount + " dòng đặt phòng");
        if (reviewCount > 0) dependencies.add(reviewCount + " đánh giá");
        if (quoteCount > 0) dependencies.add(quoteCount + " báo giá");
        if (!dependencies.isEmpty()) {
            throw new AppException(
                    ErrorCode.ROOM_TYPE_CANNOT_DELETE,
                    "Loại phòng đã có " + String.join(", ", dependencies)
                            + "; chỉ được giữ ở trạng thái ngừng hoạt động");
        }

        Map<String, Object> oldValue = roomTypeSnapshot(
                roomType,
                loadPublicRates(List.of(roomType)).get(id));
        List<String> images = currentImages(roomType);
        roomType.getFacilities().clear();
        roomTypeRepository.delete(roomType);
        roomTypeRepository.flush();
        mediaAssetService.releaseReferences(
                images,
                MediaAssetOwnerType.ROOM_TYPE,
                roomType.getId());
        auditRoomType(roomType, ReservationAuditAction.ROOM_TYPE_DELETED,
                "Xóa hạng phòng", oldValue, null);
        log.info("Đã xóa room type id={}", id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private RoomType findOrThrow(Long id) {
        return roomTypeRepository.findActiveByIdWithFacilities(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoomType", id));
    }

    private RoomRateProfile requireSingleEffectiveRateForActivation(
            RoomType roomType,
            boolean activating) {
        List<RoomRateProfile> rates = roomRateProfileRepository
                .findEffectiveByRoomTypeIds(List.of(roomType.getId()), Instant.now());
        if (activating && rates.size() != 1) {
            throw new AppException(
                    ErrorCode.ROOM_TYPE_CANNOT_ACTIVATE,
                    "Loại phòng cần đúng một bảng giá giờ/đêm/ngày đang hiệu lực trước khi kích hoạt");
        }
        return rates.size() == 1 ? rates.get(0) : null;
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

    private Map<Long, RoomRateProfile> loadPublicRates(
            List<RoomType> roomTypes) {
        List<Long> supportedIds = roomTypes.stream()
                .filter(roomType -> roomType.getId() != null)
                .map(RoomType::getId)
                .toList();
        if (supportedIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<RoomRateProfile>> grouped = roomRateProfileRepository
                .findEffectiveByRoomTypeIds(supportedIds, Instant.now())
                .stream()
                .collect(Collectors.groupingBy(
                        profile -> profile.getRoomType().getId()));
        Map<Long, RoomRateProfile> result = new LinkedHashMap<>();
        for (Long roomTypeId : supportedIds) {
            List<RoomRateProfile> rates = grouped.getOrDefault(
                    roomTypeId, List.of());
            if (rates.size() == 1) {
                result.put(roomTypeId, rates.get(0));
            } else {
                log.warn(
                        "Không thể công bố bảng giá hạng phòng id={}: số version đang hiệu lực={}",
                        roomTypeId,
                        rates.size());
            }
        }
        return Map.copyOf(result);
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

    private Map<String, Object> roomTypeSnapshot(
            RoomType roomType,
            RoomRateProfile rateProfile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", roomType.getId());
        value.put("code", roomType.getCode());
        value.put("typeName", roomType.getTypeName());
        value.put("typeNameEn", roomType.getTypeNameEn());
        value.put("active", roomType.getActive());
        value.put("maxGuests", roomType.getMaxGuests());
        value.put("imageUrl", roomType.getImageUrl());
        value.put("imageUrls", currentImages(roomType));
        value.put("facilityIds", roomType.getFacilities().stream()
                .map(Facility::getId)
                .sorted()
                .toList());
        if (rateProfile != null) {
            value.put("rateProfileVersion", rateProfile.getProfileVersion());
            value.put("includedGuests", rateProfile.getIncludedGuests());
            value.put("firstBlockMinutes", rateProfile.getFirstBlockMinutes());
            value.put("firstBlockPrice", rateProfile.getFirstBlockPrice());
            value.put("extraUnitMinutes", rateProfile.getExtraUnitMinutes());
            value.put("extraUnitPrice", rateProfile.getExtraUnitPrice());
            value.put("overnightPrice", rateProfile.getOvernightPrice());
            value.put("dailyPrice", rateProfile.getDailyPrice());
            value.put("extraGuestPrice", rateProfile.getExtraGuestPrice());
        }
        return value;
    }

    private RoomTypeResponse mapToResponse(
            RoomType entity,
            ReviewRepository.RoomTypeRatingSummary rating,
            RoomRateProfile publicRate) {
        List<FacilityResponse.Summary> facilitySummaries = entity.getFacilities()
                .stream()
                .map(f -> FacilityResponse.Summary.builder()
                        .id(f.getId())
                        .facilityName(f.getFacilityName())
                        .facilityNameEn(f.getFacilityNameEn())
                        .type(f.getType())
                        .imageUrl(f.getImageUrl())
                        .imageUrls(facilityImages(f))
                        .build())
                .collect(Collectors.toList());

        return RoomTypeResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .typeName(entity.getTypeName())
                .typeNameEn(entity.getTypeNameEn())
                .description(entity.getDescription())
                .descriptionEn(entity.getDescriptionEn())
                .packagePricingEnabled(
                        pricingV2Properties.isEngineV2Enabled()
                                && pricingV2Properties.supportsRoomType(
                                entity.getCode()))
                .pricingAvailable(publicRate != null)
                .active(Boolean.TRUE.equals(entity.getActive()))
                .includedGuests(publicRate != null
                        ? publicRate.getIncludedGuests() : null)
                .firstBlockMinutes(publicRate != null
                        ? publicRate.getFirstBlockMinutes() : null)
                .firstBlockPrice(publicRate != null
                        ? publicRate.getFirstBlockPrice() : null)
                .extraUnitMinutes(publicRate != null
                        ? publicRate.getExtraUnitMinutes() : null)
                .extraUnitPrice(publicRate != null
                        ? publicRate.getExtraUnitPrice() : null)
                .overnightPrice(publicRate != null
                        ? publicRate.getOvernightPrice() : null)
                .dailyPrice(publicRate != null
                        ? publicRate.getDailyPrice() : null)
                .extraGuestPrice(publicRate != null
                        ? publicRate.getExtraGuestPrice() : null)
                .maxGuests(entity.getMaxGuests())
                .imageUrl(entity.getImageUrl())
                .imageUrls(currentImages(entity))
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

    private List<String> requestedImagesForCreate(RoomTypeRequest request) {
        if (request.getImageUrls() != null) {
            return normalizeImages(request.getImageUrls());
        }
        return normalizeImages(List.of(request.getImageUrl() == null ? "" : request.getImageUrl()));
    }

    private List<String> requestedImagesForUpdate(
            List<String> current,
            RoomTypeRequest request) {
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

    private List<String> currentImages(RoomType roomType) {
        List<String> images = normalizeImages(roomType.getImageUrls());
        if (!images.isEmpty()) {
            return images;
        }
        return normalizeImages(List.of(
                roomType.getImageUrl() == null ? "" : roomType.getImageUrl()));
    }

    private List<String> facilityImages(Facility facility) {
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
