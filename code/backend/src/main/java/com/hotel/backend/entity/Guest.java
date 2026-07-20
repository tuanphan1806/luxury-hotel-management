package com.hotel.backend.entity;

import com.hotel.backend.constant.IdCardType;
import jakarta.persistence.*;
import lombok.*;


import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name = "guests")
@Getter
@Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Guest extends AbstractEntity<Long> implements Serializable{

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_room_id")
    private ReservationRoom reservationRoom;// giữ trong thời gian retention để tra cứu lịch sử lưu trú

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(length = 20)
    private String phone;

    private String email;

    @Column(name = "id_card_number", length = 50)
    private String idCardNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_card_type")
    private IdCardType idCardType;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String nationality;
    @Builder.Default
    @Column(name = "is_primary")
    private Boolean isPrimary = false;

    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

}
