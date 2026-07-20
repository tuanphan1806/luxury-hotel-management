package com.hotel.backend.repository;

import java.util.Date;

import org.springframework.stereotype.Repository;

import com.hotel.backend.entity.UserToken;

import org.springframework.data.jpa.repository.JpaRepository;

@Repository
public interface UserTokenRepository extends JpaRepository<UserToken, Long> {
    void deleteAllByRefreshTokenExpiresAtBefore(Date date);
}
