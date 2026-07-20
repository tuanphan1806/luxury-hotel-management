package com.hotel.backend.entity;

import com.hotel.backend.constant.CustomerProfileSource;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "customer_profiles",
    indexes = {
        @Index(name = "idx_customer_profile_phone", columnList = "phone"),
        @Index(name = "idx_customer_profile_email", columnList = "email"),
        @Index(name = "idx_customer_profile_id_card", columnList = "id_card_number")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfile extends AbstractEntity<Long> implements Serializable {

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(length = 20)
    private String phone;

    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "id_card_number", length = 50)
    private String idCardNumber;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerProfileSource source = CustomerProfileSource.STAFF_CREATED;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_user_id", unique = true)
    private User linkedUser;

    @Builder.Default
    @OneToMany(mappedBy = "customerProfile", fetch = FetchType.LAZY)
    private Set<Reservation> reservations = new HashSet<>();
}
