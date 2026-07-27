package com.hotel.backend.repository;

import com.hotel.backend.entity.PricingQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PricingQuoteRepository extends JpaRepository<PricingQuote, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select quote from PricingQuote quote
            join fetch quote.stayPolicyVersion
            where quote.id = :id
            """)
    Optional<PricingQuote> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("""
            delete from PricingQuote quote
            where quote.expiresAtUtc < :cutoff
              and not exists (
                  select commitment.id
                  from PricingQuoteCommitment commitment
                  where commitment.pricingQuote = quote
              )
            """)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
