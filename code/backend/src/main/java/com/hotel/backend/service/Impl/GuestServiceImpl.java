package com.hotel.backend.service.Impl;
 
import com.hotel.backend.dto.response.GuestResponse;
import com.hotel.backend.dto.request.GuestRequest;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.GuestRepository;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.GuestService;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.util.EmailFormatValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
 
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
 
@Service
@RequiredArgsConstructor
public class GuestServiceImpl implements GuestService {
 
    private final GuestRepository guestRepository;
    private final ReservationAuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public List<GuestResponse> getAllGuests() {
        return guestRepository.findAllWithStayDetails().stream()
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
    public GuestResponse updateGuest(
            Long guestId,
            GuestRequest request,
            User currentUser) {
        var guest = guestRepository.findByIdForUpdate(guestId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy khách lưu trú"));
        validateGuest(request);
        Map<String, Object> oldValue = guestFields(guest);
        guest.setFullName(request.getFullName().trim());
        guest.setPhone(trimToNull(request.getPhone()));
        guest.setEmail(trimToNull(request.getEmail()));
        guest.setIdCardNumber(trimToNull(request.getIdCardNumber()));
        guest.setIdCardType(request.getIdCardType());
        guest.setDateOfBirth(request.getDateOfBirth());
        guest.setNationality(trimToNull(request.getNationality()));
        var saved = guestRepository.save(guest);
        var reservationRoom = saved.getReservationRoom();
        var reservation = reservationRoom == null
                ? null
                : reservationRoom.getReservationRoomType().getReservation();
        auditService.record(
                reservation,
                "GUEST",
                String.valueOf(saved.getId()),
                ReservationAuditAction.GUEST_PROFILE_UPDATED,
                "Cập nhật hồ sơ khách lưu trú " + saved.getFullName(),
                oldValue,
                guestFields(saved),
                Map.of("updatedByUserId", currentUser == null
                        ? "SYSTEM" : String.valueOf(currentUser.getId())),
                java.util.UUID.randomUUID().toString(),
                null);
        return GuestResponse.from(saved);
    }

    private void validateGuest(GuestRequest request) {
        if (request == null || request.getFullName() == null
                || request.getFullName().trim().length() < 2) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Họ tên khách phải có ít nhất 2 ký tự");
        }
        String phone = trimToNull(request.getPhone());
        if (phone != null) {
            String normalized = phone.replaceAll("[\\s().-]", "");
            if (!normalized.matches("^(0|\\+84)[0-9]{9,10}$")) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Số điện thoại khách không hợp lệ");
            }
        }
        if (!EmailFormatValidator.isValidOptional(request.getEmail())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Email khách không đúng định dạng");
        }
        String documentNumber = trimToNull(request.getIdCardNumber());
        if (documentNumber != null && documentNumber.length() < 4) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Số giấy tờ khách phải có ít nhất 4 ký tự");
        }
        if (request.getDateOfBirth() != null
                && !request.getDateOfBirth().isBefore(java.time.LocalDate.now())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Ngày sinh phải nằm trong quá khứ");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> guestFields(com.hotel.backend.entity.Guest guest) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("fullName", guest.getFullName());
        fields.put("phone", guest.getPhone());
        fields.put("email", guest.getEmail());
        fields.put("idCardType", guest.getIdCardType());
        fields.put("idCardNumber", guest.getIdCardNumber());
        fields.put("dateOfBirth", guest.getDateOfBirth());
        fields.put("nationality", guest.getNationality());
        return fields;
    }
}
