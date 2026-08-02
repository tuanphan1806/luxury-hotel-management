package com.hotel.backend.service.chatbot;

import com.hotel.backend.dto.response.AvailabilityResponse;
import com.hotel.backend.dto.response.FacilityResponse;
import com.hotel.backend.dto.response.GalleryResponse;
import com.hotel.backend.dto.response.RoomTypeResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only public hotel data used by the chatbot.
 *
 * <p>The chatbot uses this port instead of issuing HTTP requests back into
 * the same Spring process. It therefore stays reliable during a cold start
 * and does not consume another servlet connection for in-process data.</p>
 */
public interface ChatbotPublicDataGateway {

    List<RoomTypeResponse> getRoomTypes();

    List<FacilityResponse> getFacilities();

    List<GalleryResponse> getGalleries();

    List<AvailabilityResponse> getAvailability(LocalDateTime checkIn, LocalDateTime checkOut);
}
