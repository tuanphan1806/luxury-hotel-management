package com.hotel.backend.repository;

import com.hotel.backend.entity.OAuthLoginTicket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface OAuthLoginTicketRepository extends JpaRepository<OAuthLoginTicket, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ticket
              from OAuthLoginTicket ticket
              join fetch ticket.user
             where ticket.tokenHash = :tokenHash
            """)
    Optional<OAuthLoginTicket> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from OAuthLoginTicket ticket where ticket.expiresAtUtc <= :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
