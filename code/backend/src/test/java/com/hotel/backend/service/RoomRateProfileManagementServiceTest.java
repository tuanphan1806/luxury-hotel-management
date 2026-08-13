package com.hotel.backend.service;

import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.StayPolicyVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomRateProfileManagementServiceTest {

    @Mock RoomRateProfileRepository rateProfileRepository;
    @Mock StayPolicyVersionRepository stayPolicyVersionRepository;

    private RoomRateProfileManagementService service;
    private RoomType roomType;
    private StayPolicyVersion policy;

    @BeforeEach
    void setUp() {
        service = new RoomRateProfileManagementService(
                rateProfileRepository,
                stayPolicyVersionRepository);
        roomType = RoomType.builder()
                .code("CUSTOM_TEST")
                .typeName("Phòng thử")
                .maxGuests(3)
                .build();
        roomType.setId(7L);
        policy = StayPolicyVersion.builder()
                .id(11L)
                .policyCode("DEFAULT_MOTEL_POLICY")
                .policyVersion(3)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void createsCompleteInitialRateProfileForANewRoomType() {
        when(rateProfileRepository.findActiveByRoomTypeIdForUpdate(7L))
                .thenReturn(List.of());
        when(stayPolicyVersionRepository.findEffectiveByPolicyCode(
                org.mockito.ArgumentMatchers.eq("DEFAULT_MOTEL_POLICY"),
                any(Instant.class)))
                .thenReturn(List.of(policy));
        when(rateProfileRepository.findMaxProfileVersion(7L)).thenReturn(0);
        when(rateProfileRepository.saveAndFlush(any(RoomRateProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomRateProfile created = service.applyImmediate(roomType, request());

        assertEquals(1, created.getProfileVersion());
        assertEquals(120, created.getFirstBlockMinutes());
        assertEquals(60, created.getExtraUnitMinutes());
        assertEquals(new BigDecimal("170000"), created.getOvernightPrice());
        assertEquals(new BigDecimal("300000"), created.getDailyPrice());
        assertEquals(ExtraGuestBillingMode.PER_PACKAGE_CYCLE,
                created.getExtraGuestBillingMode());
        assertTrue(created.getActive());
    }

    @Test
    void unchangedRatesDoNotCreateANewFinancialVersion() {
        RoomRateProfile current = currentRate();
        when(rateProfileRepository.findActiveByRoomTypeIdForUpdate(7L))
                .thenReturn(List.of(current));
        when(stayPolicyVersionRepository.findEffectiveByPolicyCode(
                org.mockito.ArgumentMatchers.eq("DEFAULT_MOTEL_POLICY"),
                any(Instant.class)))
                .thenReturn(List.of(policy));

        RoomRateProfile result = service.applyImmediate(roomType, request());

        assertEquals(current, result);
        verify(rateProfileRepository, never())
                .findMaxProfileVersion(7L);
        verify(rateProfileRepository, never())
                .saveAndFlush(any(RoomRateProfile.class));
    }

    @Test
    void changedRatesCloseTheOldVersionBeforeCreatingTheNextVersion() {
        RoomRateProfile current = currentRate();
        RoomTypeRequest changed = request();
        changed.setOvernightPrice(new BigDecimal("180000"));
        changed.setDailyPrice(new BigDecimal("320000"));
        when(rateProfileRepository.findActiveByRoomTypeIdForUpdate(7L))
                .thenReturn(List.of(current));
        when(stayPolicyVersionRepository.findEffectiveByPolicyCode(
                org.mockito.ArgumentMatchers.eq("DEFAULT_MOTEL_POLICY"),
                any(Instant.class)))
                .thenReturn(List.of(policy));
        when(rateProfileRepository.findMaxProfileVersion(7L)).thenReturn(4);
        when(rateProfileRepository.saveAndFlush(any(RoomRateProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomRateProfile replacement = service.applyImmediate(roomType, changed);

        assertFalse(current.getActive());
        assertTrue(current.getEffectiveToUtc() != null);
        assertEquals(5, replacement.getProfileVersion());
        assertEquals(new BigDecimal("180000"), replacement.getOvernightPrice());
        assertEquals(current.getEffectiveToUtc(), replacement.getEffectiveFromUtc());
        verify(rateProfileRepository, times(2))
                .saveAndFlush(any(RoomRateProfile.class));
    }

    @Test
    void rejectsIncludedGuestsAboveRoomCapacity() {
        RoomTypeRequest invalid = request();
        invalid.setIncludedGuests(4);

        assertThrows(AppException.class, () -> service.validate(invalid));
    }

    @Test
    void requiresPositiveSurchargeWhenRoomAllowsExtraGuests() {
        RoomTypeRequest invalid = request();
        invalid.setExtraGuestPrice(BigDecimal.ZERO);

        assertThrows(AppException.class, () -> service.validate(invalid));
    }

    @Test
    void allowsZeroSurchargeWhenMaximumEqualsIncludedOccupancy() {
        RoomTypeRequest valid = request();
        valid.setMaxGuests(2);
        valid.setIncludedGuests(2);
        valid.setExtraGuestPrice(BigDecimal.ZERO);

        service.validate(valid);
    }

    private RoomTypeRequest request() {
        return RoomTypeRequest.builder()
                .typeName("Phòng thử")
                .maxGuests(3)
                .includedGuests(2)
                .firstBlockPrice(new BigDecimal("70000"))
                .extraUnitPrice(new BigDecimal("20000"))
                .overnightPrice(new BigDecimal("170000"))
                .dailyPrice(new BigDecimal("300000"))
                .extraGuestPrice(new BigDecimal("50000"))
                .build();
    }

    private RoomRateProfile currentRate() {
        return RoomRateProfile.builder()
                .id(19L)
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(4)
                .includedGuests(2)
                .firstBlockMinutes(120)
                .firstBlockPrice(new BigDecimal("70000"))
                .extraUnitMinutes(60)
                .extraUnitPrice(new BigDecimal("20000"))
                .overnightPrice(new BigDecimal("170000"))
                .dailyPrice(new BigDecimal("300000"))
                .extraGuestPrice(new BigDecimal("50000"))
                .extraGuestBillingMode(ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
