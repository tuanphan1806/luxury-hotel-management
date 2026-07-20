package com.hotel.backend.entity;
import jakarta.persistence.*;
import lombok.*;

import java.util.Date;

@Entity
@Table(name = "invalidated_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvalidatedToken {

    @Id
    private String token; // lưu jti hoặc token hash

    private Date expiryTime; // để scheduled job dọn dẹp

    @Column(name = "reason", length = 50)
    @Builder.Default
    private String reason = "UNKNOWN"; // "LOGOUT", "SESSION_REPLACED"
}