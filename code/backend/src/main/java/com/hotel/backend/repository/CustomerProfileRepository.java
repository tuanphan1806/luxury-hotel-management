package com.hotel.backend.repository;

import com.hotel.backend.entity.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {
    Optional<CustomerProfile> findByLinkedUserId(Long linkedUserId);
    Optional<CustomerProfile> findFirstByPhone(String phone);
    Optional<CustomerProfile> findFirstByEmail(String email);
    List<CustomerProfile> findAllByEmail(String email);
    Optional<CustomerProfile> findFirstByEmailAndLinkedUserIsNull(String email);
    Optional<CustomerProfile> findFirstByIdCardNumber(String idCardNumber);

    @Query("""
        SELECT cp FROM CustomerProfile cp
        WHERE cp.linkedUser.id = :userId
          AND NOT EXISTS (
              SELECT 1 FROM Reservation r
              WHERE r.customerProfile = cp
          )
    """)
    Optional<CustomerProfile> findWithoutReservationsByLinkedUserId(@Param("userId") Long userId);
}
