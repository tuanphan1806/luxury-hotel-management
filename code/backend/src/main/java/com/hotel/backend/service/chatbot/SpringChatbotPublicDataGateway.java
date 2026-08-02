package com.hotel.backend.service.chatbot;

import com.hotel.backend.dto.response.AvailabilityResponse;
import com.hotel.backend.dto.response.FacilityResponse;
import com.hotel.backend.dto.response.GalleryResponse;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.service.FacilityService;
import com.hotel.backend.service.GalleryService;
import com.hotel.backend.service.ReservationService;
import com.hotel.backend.service.RoomTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SpringChatbotPublicDataGateway implements ChatbotPublicDataGateway {

    private final RoomTypeService roomTypeService;
    private final FacilityService facilityService;
    private final GalleryService galleryService;
    private final ReservationService reservationService;

    @Override
    public List<RoomTypeResponse> getRoomTypes() {
        return roomTypeService.getAll();
    }

    @Override
    public List<FacilityResponse> getFacilities() {
        return facilityService.getAll();
    }

    @Override
    public List<GalleryResponse> getGalleries() {
        return galleryService.getAll();
    }

    @Override
    public List<AvailabilityResponse> getAvailability(LocalDateTime checkIn, LocalDateTime checkOut) {
        return reservationService.checkAvailability(checkIn, checkOut);
    }
}
