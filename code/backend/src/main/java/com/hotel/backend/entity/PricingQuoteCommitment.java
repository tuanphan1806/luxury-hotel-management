package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One-time-use marker linking an immutable quote to the reservation created
 * from it. Quote evidence stays immutable; consumption is recorded separately.
 */
@Entity
@Immutable
@Table(
        name = "pricing_quote_commitments",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_pricing_quote_commitment_quote",
                        columnNames = "pricing_quote_id"),
                @UniqueConstraint(
                        name = "uk_pricing_quote_commitment_reservation",
                        columnNames = "reservation_id")
        })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PricingQuoteCommitment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_quote_id", nullable = false)
    private PricingQuote pricingQuote;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "committed_at_utc", nullable = false)
    private Instant committedAtUtc;
}
