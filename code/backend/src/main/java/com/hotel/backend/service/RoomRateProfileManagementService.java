package com.hotel.backend.service;

import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.StayPolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Owns immediate, immutable rate-version cutovers for RoomType CRUD.
 * Existing reservations keep their committed rate_profile_id and snapshot;
 * only quotes created after the cutover observe the new version.
 */
@Service
@RequiredArgsConstructor
public class RoomRateProfileManagementService {

    static final String DEFAULT_POLICY_CODE = "DEFAULT_MOTEL_POLICY";
    static final int FIRST_BLOCK_MINUTES = 120;
    static final int EXTRA_UNIT_MINUTES = 60;

    private final RoomRateProfileRepository rateProfileRepository;
    private final StayPolicyVersionRepository stayPolicyVersionRepository;

    public void validate(RoomTypeRequest request) {
        if (request == null) {
            throw invalid("Bảng giá loại phòng không hợp lệ");
        }
        requireAmount(request.getFirstBlockPrice(), "Giá 2 giờ đầu");
        requireAmount(request.getExtraUnitPrice(), "Giá mỗi giờ thêm");
        requireAmount(request.getOvernightPrice(), "Giá qua đêm");
        requireAmount(request.getDailyPrice(), "Giá ngày đêm");
        requireNonNegative(request.getExtraGuestPrice(), "Phụ thu khách thêm");
        if (request.getIncludedGuests() == null
                || request.getIncludedGuests() < 1) {
            throw invalid("Giá phòng phải bao gồm ít nhất 1 khách");
        }
        if (request.getMaxGuests() == null
                || request.getIncludedGuests() > request.getMaxGuests()) {
            throw invalid("Số khách đã bao gồm không được vượt sức chứa loại phòng");
        }
        if (request.getFirstBlockPrice().compareTo(
                request.getOvernightPrice()) > 0) {
            throw invalid("Giá 2 giờ đầu không được lớn hơn giá qua đêm");
        }
        if (request.getOvernightPrice().compareTo(
                request.getDailyPrice()) > 0) {
            throw invalid("Giá qua đêm không được lớn hơn giá ngày đêm");
        }
    }

    /**
     * Creates the first version or closes the effective version and inserts a
     * replacement. Caller must hold the RoomType row lock for updates.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RoomRateProfile applyImmediate(
            RoomType roomType,
            RoomTypeRequest request) {
        validate(request);
        if (roomType == null || roomType.getId() == null) {
            throw invalid("Loại phòng phải được lưu trước khi tạo bảng giá");
        }

        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<RoomRateProfile> activeProfiles = rateProfileRepository
                .findActiveByRoomTypeIdForUpdate(roomType.getId());
        RoomRateProfile current = activeProfiles.stream()
                .filter(profile -> isEffective(profile, observedAt))
                .findFirst()
                .orElse(null);
        long currentCount = activeProfiles.stream()
                .filter(profile -> isEffective(profile, observedAt))
                .count();
        if (currentCount > 1) {
            throw new AppException(
                    ErrorCode.PRICING_PROFILE_NOT_FOUND,
                    "Có nhiều bảng giá đang hiệu lực cho loại phòng");
        }

        StayPolicyVersion policy = requireEffectivePolicy(observedAt);
        if (current != null && sameRates(current, policy, request)) {
            return current;
        }

        RoomRateProfile nextFuture = activeProfiles.stream()
                .filter(profile -> profile.getEffectiveFromUtc().isAfter(observedAt))
                .min(Comparator.comparing(RoomRateProfile::getEffectiveFromUtc))
                .orElse(null);
        Instant cutover = observedAt;
        if (current != null
                && !cutover.isAfter(current.getEffectiveFromUtc())) {
            cutover = current.getEffectiveFromUtc().plus(1, ChronoUnit.MICROS);
        }
        if (nextFuture != null
                && !cutover.isBefore(nextFuture.getEffectiveFromUtc())) {
            throw new AppException(
                    ErrorCode.PRICING_PROFILE_NOT_FOUND,
                    "Không thể cập nhật tức thời vì đã có bảng giá tương lai xung đột");
        }

        if (current != null) {
            current.setEffectiveToUtc(cutover);
            current.setActive(false);
            rateProfileRepository.saveAndFlush(current);
        }

        int nextVersion = Objects.requireNonNullElse(
                rateProfileRepository.findMaxProfileVersion(roomType.getId()), 0) + 1;
        RoomRateProfile replacement = RoomRateProfile.builder()
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(nextVersion)
                .includedGuests(request.getIncludedGuests())
                .firstBlockMinutes(FIRST_BLOCK_MINUTES)
                .firstBlockPrice(request.getFirstBlockPrice())
                .extraUnitMinutes(EXTRA_UNIT_MINUTES)
                .extraUnitPrice(request.getExtraUnitPrice())
                .overnightPrice(request.getOvernightPrice())
                .dailyPrice(request.getDailyPrice())
                .extraGuestPrice(request.getExtraGuestPrice())
                .extraGuestBillingMode(ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(cutover)
                .effectiveToUtc(nextFuture != null
                        ? nextFuture.getEffectiveFromUtc() : null)
                .active(true)
                .createdBy(currentUser())
                .createdAtUtc(cutover)
                .build();
        return rateProfileRepository.saveAndFlush(replacement);
    }

    private StayPolicyVersion requireEffectivePolicy(Instant effectiveAt) {
        List<StayPolicyVersion> policies = stayPolicyVersionRepository
                .findEffectiveByPolicyCode(DEFAULT_POLICY_CODE, effectiveAt);
        if (policies.size() != 1) {
            throw new AppException(
                    ErrorCode.PRICING_PROFILE_NOT_FOUND,
                    "Không tìm thấy đúng một chính sách lưu trú đang hiệu lực");
        }
        return policies.get(0);
    }

    private boolean isEffective(RoomRateProfile profile, Instant at) {
        return !profile.getEffectiveFromUtc().isAfter(at)
                && (profile.getEffectiveToUtc() == null
                    || profile.getEffectiveToUtc().isAfter(at));
    }

    private boolean sameRates(
            RoomRateProfile current,
            StayPolicyVersion policy,
            RoomTypeRequest request) {
        return Objects.equals(current.getStayPolicyVersion().getId(), policy.getId())
                && Objects.equals(current.getIncludedGuests(), request.getIncludedGuests())
                && current.getFirstBlockMinutes() == FIRST_BLOCK_MINUTES
                && current.getExtraUnitMinutes() == EXTRA_UNIT_MINUTES
                && sameAmount(current.getFirstBlockPrice(), request.getFirstBlockPrice())
                && sameAmount(current.getExtraUnitPrice(), request.getExtraUnitPrice())
                && sameAmount(current.getOvernightPrice(), request.getOvernightPrice())
                && sameAmount(current.getDailyPrice(), request.getDailyPrice())
                && sameAmount(current.getExtraGuestPrice(), request.getExtraGuestPrice())
                && current.getExtraGuestBillingMode()
                    == ExtraGuestBillingMode.PER_PACKAGE_CYCLE;
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private void requireAmount(BigDecimal amount, String field) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 0) {
            throw invalid(field + " phải là số VND nguyên lớn hơn 0");
        }
    }

    private void requireNonNegative(BigDecimal amount, String field) {
        if (amount == null || amount.signum() < 0 || amount.scale() > 0) {
            throw invalid(field + " phải là số VND nguyên không âm");
        }
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder
                .getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof User user
                ? user : null;
    }

    private AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_REQUEST, message);
    }
}
