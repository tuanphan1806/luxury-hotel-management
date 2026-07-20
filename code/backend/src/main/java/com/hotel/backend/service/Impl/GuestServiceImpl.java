package com.hotel.backend.service.Impl;
 
import com.hotel.backend.dto.response.GuestResponse;
import com.hotel.backend.dto.request.GuestRequest;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.GuestRepository;
import com.hotel.backend.service.GuestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
 
import java.util.List;
 
@Service
@RequiredArgsConstructor
public class GuestServiceImpl implements GuestService {
 
    private final GuestRepository guestRepository;

    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getAllGuests() {
        return guestRepository.findAll().stream()
                .map(GuestResponse::from)
                .toList();
    }
 
    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getGuestsByReservationRoom(Long reservationRoomId) {
        return guestRepository.findByReservationRoomId(reservationRoomId)
                .stream()
                .map(GuestResponse::from)
                .toList();
    }
 
    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getGuestsByReservation(Long reservationId) {
        return guestRepository.findAllByReservationId(reservationId)
                .stream()
                .map(GuestResponse::from)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GuestResponse updateGuest(Long guestId, GuestRequest request) {
        var guest = guestRepository.findById(guestId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy khách lưu trú"));
        guest.setFullName(request.getFullName().trim());
        guest.setPhone(request.getPhone());
        guest.setEmail(request.getEmail());
        guest.setIdCardNumber(request.getIdCardNumber());
        guest.setIdCardType(request.getIdCardType());
        guest.setDateOfBirth(request.getDateOfBirth());
        guest.setNationality(request.getNationality());
        return GuestResponse.from(guestRepository.save(guest));
    }
}
