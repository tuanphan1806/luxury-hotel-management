package com.hotel.backend.repository;

import com.hotel.backend.entity.PricingQuoteLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PricingQuoteLineRepository
        extends JpaRepository<PricingQuoteLine, Long> {

    List<PricingQuoteLine> findByPricingQuoteIdOrderByIdAsc(UUID pricingQuoteId);

    @Modifying
    @Query("""
            delete from PricingQuoteLine line
            where line.pricingQuote.expiresAtUtc < :cutoff
              and not exists (
                  select commitment.id
                  from PricingQuoteCommitment commitment
                  where commitment.pricingQuote = line.pricingQuote
              )
            """)
    int deleteByQuoteExpiryBefore(@Param("cutoff") Instant cutoff);
}
