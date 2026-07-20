package com.hotel.backend.repository;

import java.util.Date;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hotel.backend.entity.InvalidatedToken;

public interface InvalidatedTokenRepository extends JpaRepository<InvalidatedToken, String> {
    boolean existsByToken(String token);
    void deleteAllByExpiryTimeBefore(Date date);
    Optional<InvalidatedToken> findByToken(String token);

    @Modifying
    @Query(value = """
        INSERT INTO invalidated_tokens (token, expiry_time, reason)
        VALUES (:token, :expiryTime, :reason)
        """, nativeQuery = true)
    int insertInvalidatedToken(
            @Param("token") String token,
            @Param("expiryTime") Date expiryTime,
            @Param("reason") String reason);
}
