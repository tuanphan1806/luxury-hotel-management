package com.hotel.backend.entity;

import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import java.time.LocalDateTime;
import java.util.Date;

@Entity
@Table(name = "user_tokens")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UserToken {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "access_token", length = 500)
    private String accessToken; // JTI của access token

    @Column(name = "access_token_expires_at")
    private Date accessTokenExpiresAt;

    @Column(name = "refresh_token", nullable = false, length = 500)
    private String refreshToken; // lưu JTI

    @Column(name = "refresh_token_expires_at")
    private Date refreshTokenExpiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
