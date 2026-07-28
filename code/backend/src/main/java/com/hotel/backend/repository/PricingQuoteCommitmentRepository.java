package com.hotel.backend.repository;

import com.hotel.backend.entity.PricingQuoteCommitment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PricingQuoteCommitmentRepository
        extends JpaRepository<PricingQuoteCommitment, Long> {

    boolean existsByPricingQuoteId(UUID pricingQuoteId);
}
