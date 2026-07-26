package com.hotel.backend.config;

import com.hotel.backend.entity.AddOnService;
import com.hotel.backend.repository.AddOnServiceRepository;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.GalleryRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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

    private DataSeeder seeder() {
        DataSeeder seeder = new DataSeeder(
                facilityRepository,
                addOnServiceRepository,
                roomTypeRepository,
                roomRepository,
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
