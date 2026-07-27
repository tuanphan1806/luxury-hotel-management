package com.hotel.backend.config;

import com.hotel.backend.entity.AddOnService;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.repository.AddOnServiceRepository;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.GalleryRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.repository.StayPolicyVersionRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSeederTest {

    @Mock private FacilityRepository facilityRepository;
    @Mock private AddOnServiceRepository addOnServiceRepository;
    @Mock private RoomTypeRepository roomTypeRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private StayPolicyVersionRepository stayPolicyVersionRepository;
    @Mock private RoomRateProfileRepository roomRateProfileRepository;
    @Mock private GalleryRepository galleryRepository;
    @Mock private UserRepository userRepository;
    @Mock private CustomerProfileRepository customerProfileRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void addOnOnlySeedDoesNotTouchOtherMasterData() throws Exception {
        DataSeeder seeder = seeder();
        ReflectionTestUtils.setField(seeder, "masterDataEnabled", false);
        ReflectionTestUtils.setField(seeder, "addOnServicesEnabled", true);
        when(addOnServiceRepository.findByCodeIgnoreCase(anyString()))
                .thenReturn(Optional.empty());

        seeder.run();

        ArgumentCaptor<AddOnService> captor = ArgumentCaptor.forClass(AddOnService.class);
        verify(addOnServiceRepository, times(5)).save(captor.capture());
        List<AddOnService> services = captor.getAllValues();
        assertThat(services)
                .extracting(AddOnService::getCode)
                .containsExactly(
                        "IN_ROOM_BREAKFAST",
                        "EXTRA_ROLLAWAY_BED",
                        "MINI_PROJECTOR",
                        "PRIVATE_BBQ_SET",
                        "ROOM_DECORATION");
        assertThat(services)
                .extracting(AddOnService::getImageUrl)
                .allMatch(url -> url.startsWith(
                        "https://res.cloudinary.com/demo/image/upload/hotel-media/static/add_on_services/"));

        verify(facilityRepository, never()).findAll();
        verify(roomTypeRepository, never()).findAll();
        verify(roomRepository, never()).findAll();
        verify(galleryRepository, never()).findAll();
        verify(userRepository, never()).findAll();
    }

    @Test
    void disabledSeedDoesNotTouchAnyRepository() throws Exception {
        DataSeeder seeder = seeder();
        ReflectionTestUtils.setField(seeder, "masterDataEnabled", false);
        ReflectionTestUtils.setField(seeder, "addOnServicesEnabled", false);

        seeder.run();

        verify(addOnServiceRepository, never()).findByCodeIgnoreCase(anyString());
        verify(facilityRepository, never()).findAll();
        verify(roomTypeRepository, never()).findAll();
        verify(roomRepository, never()).findAll();
        verify(galleryRepository, never()).findAll();
        verify(userRepository, never()).findAll();
    }

    @Test
    void pricingSeedUsesStableRoomTypeCodesAndExpectedDefaultRates() {
        DataSeeder seeder = seeder();
        StayPolicyVersion policy = StayPolicyVersion.builder()
                .id(10L)
                .policyCode("DEFAULT_MOTEL_POLICY")
                .policyVersion(1)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .build();
        when(stayPolicyVersionRepository.findEffectiveByPolicyCode(
                eq("DEFAULT_MOTEL_POLICY"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(policy));
        when(roomRateProfileRepository.existsByRoomTypeId(anyLong()))
                .thenReturn(false);

        ReflectionTestUtils.invokeMethod(
                seeder,
                "seedRoomRateProfiles",
                seededRoomTypes());

        ArgumentCaptor<RoomRateProfile> captor =
                ArgumentCaptor.forClass(RoomRateProfile.class);
        verify(roomRateProfileRepository, times(6)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(
                        profile -> profile.getRoomType().getCode(),
                        RoomRateProfile::getIncludedGuests,
                        RoomRateProfile::getFirstBlockPrice,
                        RoomRateProfile::getExtraUnitPrice,
                        RoomRateProfile::getOvernightPrice,
                        RoomRateProfile::getDailyPrice,
                        RoomRateProfile::getExtraGuestPrice)
                .containsExactly(
                        tuple("STANDARD", 1,
                                new BigDecimal("70000"), new BigDecimal("20000"),
                                new BigDecimal("170000"), new BigDecimal("300000"),
                                new BigDecimal("50000")),
                        tuple("DELUXE", 2,
                                new BigDecimal("100000"), new BigDecimal("25000"),
                                new BigDecimal("220000"), new BigDecimal("400000"),
                                new BigDecimal("50000")),
                        tuple("EXECUTIVE", 2,
                                new BigDecimal("120000"), new BigDecimal("30000"),
                                new BigDecimal("270000"), new BigDecimal("480000"),
                                new BigDecimal("50000")),
                        tuple("SUITE", 2,
                                new BigDecimal("150000"), new BigDecimal("35000"),
                                new BigDecimal("350000"), new BigDecimal("600000"),
                                new BigDecimal("50000")),
                        tuple("FAMILY", 4,
                                new BigDecimal("130000"), new BigDecimal("30000"),
                                new BigDecimal("330000"), new BigDecimal("550000"),
                                new BigDecimal("50000")),
                        tuple("PRESIDENTIAL", 4,
                                new BigDecimal("200000"), new BigDecimal("50000"),
                                new BigDecimal("450000"), new BigDecimal("850000"),
                                new BigDecimal("50000")));
    }

    @Test
    void pricingSeedNeverOverwritesAnyExistingProfileVersion() {
        DataSeeder seeder = seeder();
        StayPolicyVersion policy = StayPolicyVersion.builder()
                .id(10L)
                .policyCode("DEFAULT_MOTEL_POLICY")
                .policyVersion(1)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .build();
        when(stayPolicyVersionRepository.findEffectiveByPolicyCode(
                eq("DEFAULT_MOTEL_POLICY"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(policy));
        when(roomRateProfileRepository.existsByRoomTypeId(anyLong()))
                .thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                seeder,
                "seedRoomRateProfiles",
                seededRoomTypes());

        verify(roomRateProfileRepository, never()).save(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pricingSeedRespectsAnInactiveExistingPolicy() {
        DataSeeder seeder = seeder();
        when(stayPolicyVersionRepository.findEffectiveByPolicyCode(
                eq("DEFAULT_MOTEL_POLICY"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(stayPolicyVersionRepository.count()).thenReturn(1L);

        ReflectionTestUtils.invokeMethod(
                seeder,
                "seedRoomRateProfiles",
                seededRoomTypes());

        verify(roomRateProfileRepository, never()).save(
                org.mockito.ArgumentMatchers.any());
    }

    private Map<String, RoomType> seededRoomTypes() {
        Map<String, RoomType> roomTypes = new LinkedHashMap<>();
        roomTypes.put("Standard", roomType(1L, "STANDARD", 2));
        roomTypes.put("Deluxe", roomType(2L, "DELUXE", 3));
        roomTypes.put("Executive", roomType(3L, "EXECUTIVE", 3));
        roomTypes.put("Suite", roomType(4L, "SUITE", 4));
        roomTypes.put("Family", roomType(5L, "FAMILY", 6));
        roomTypes.put("Presidential Suite", roomType(6L, "PRESIDENTIAL", 6));
        return roomTypes;
    }

    private RoomType roomType(long id, String code, int maxGuests) {
        RoomType roomType = RoomType.builder()
                .code(code)
                .typeName(code)
                .maxGuests(maxGuests)
                .build();
        roomType.setId(id);
        return roomType;
    }

    private DataSeeder seeder() {
        DataSeeder seeder = new DataSeeder(
                facilityRepository,
                addOnServiceRepository,
                roomTypeRepository,
                roomRepository,
                stayPolicyVersionRepository,
                roomRateProfileRepository,
                galleryRepository,
                userRepository,
                customerProfileRepository,
                passwordEncoder);
        ReflectionTestUtils.setField(
                seeder,
                "seedMediaBaseUrl",
                "https://res.cloudinary.com/demo/image/upload/hotel-media/static");
        ReflectionTestUtils.setField(seeder, "demoUsersEnabled", false);
        return seeder;
    }
}
