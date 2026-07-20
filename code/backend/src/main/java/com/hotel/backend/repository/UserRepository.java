package com.hotel.backend.repository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;

public interface UserRepository extends JpaRepository<User,Long>{
    @Query(value = "select u from User u where lower(u.fullName) like :keyword or lower(u.username) like :keyword or lower(u.email) like :keyword or lower(u.phone) like :keyword")
    Page<User> searchByKeyword(String keyword, Pageable pageable);

    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);
    boolean existsByUsernameAndIdNot(String username, Long id);
    boolean existsByUsernameIgnoreCaseAndIdNot(String username, Long id);
    boolean existsByEmailAndIdNot(String email, Long id);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);
    boolean existsByPhoneAndIdNot(String phone, Long id);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByPasswordResetTokenHashAndPasswordResetExpiresAtAfter(
            String passwordResetTokenHash,
            LocalDateTime now);
    Optional<User> findByPhone(String phone);
    Optional<User> findByVerificationCodeAndStatusAndEmailVerifiedFalseAndVerificationExpiresAtAfter(
            String verificationCode,
            UserStatus status,
            LocalDateTime now);
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameIgnoreCase(String username);
    List<User> findByStatusAndEmailVerifiedFalseAndCreatedAtBefore(
            UserStatus status,
            LocalDateTime createdAt);
    long countByType(UserType type);
    List<User> findByTypeAndStatus(UserType type, UserStatus status);
}
