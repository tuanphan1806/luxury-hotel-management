package com.hotel.backend.service;
 
import com.hotel.backend.dto.response.GuestResponse;
import com.hotel.backend.dto.request.GuestRequest;
 
import java.util.List;
 
public interface GuestService {
    List<GuestResponse> getAllGuests();
    List<GuestResponse> getGuestsByReservationRoom(Long reservationRoomId);
    List<GuestResponse> getGuestsByReservation(Long reservationId);
    GuestResponse updateGuest(Long guestId, GuestRequest request);
}
