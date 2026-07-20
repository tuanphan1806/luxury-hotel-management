package com.hotel.backend.dto.response;
 
import com.hotel.backend.constant.IdCardType;
import com.hotel.backend.entity.Guest;
import lombok.*;
 
import java.time.LocalDate;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestResponse {
 
    private Long id;
    private Long reservationRoomId;
    private Long reservationId;
    private Long roomId;
    private String roomName;
    private String fullName;
    private String phone;
    private String email;
    private String idCardNumber;
    private IdCardType idCardType;
    private LocalDate dateOfBirth;
    private String nationality;
    private Boolean isPrimary;
 
    public static GuestResponse from(Guest guest) {
        var reservationRoom = guest.getReservationRoom();
        var room = reservationRoom != null ? reservationRoom.getRoom() : null;
        return GuestResponse.builder()
                .id(guest.getId())
                .reservationRoomId(reservationRoom != null ? reservationRoom.getId() : null)
                .reservationId(reservationRoom != null
                        ? reservationRoom.getReservationRoomType().getReservation().getId() : null)
                .roomId(room != null ? room.getId() : null)
                .roomName(room != null ? room.getRoomName() : null)
                .fullName(guest.getFullName())
                .phone(guest.getPhone())
                .email(guest.getEmail())
                .idCardNumber(guest.getIdCardNumber())
                .idCardType(guest.getIdCardType())
                .dateOfBirth(guest.getDateOfBirth())
                .nationality(guest.getNationality())
                .isPrimary(guest.getIsPrimary())
                .build();
    }
}
