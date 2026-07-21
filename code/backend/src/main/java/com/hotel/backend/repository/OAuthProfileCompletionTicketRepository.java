package com.hotel.backend.repository;

import com.hotel.backend.entity.OAuthProfileCompletionTicket;
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
public interface OAuthProfileCompletionTicketRepository
        extends JpaRepository<OAuthProfileCompletionTicket, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ticket
              from OAuthProfileCompletionTicket ticket
             where ticket.tokenHash = :tokenHash
            """)
    Optional<OAuthProfileCompletionTicket> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from OAuthProfileCompletionTicket ticket where ticket.expiresAtUtc <= :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
