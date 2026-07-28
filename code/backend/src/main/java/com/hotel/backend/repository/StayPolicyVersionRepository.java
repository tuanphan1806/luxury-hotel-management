package com.hotel.backend.repository;

import com.hotel.backend.entity.StayPolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StayPolicyVersionRepository
        extends JpaRepository<StayPolicyVersion, Long> {

    Optional<StayPolicyVersion> findByPolicyCodeAndPolicyVersion(
            String policyCode,
            Integer policyVersion);

    @Query("""
            select policy from StayPolicyVersion policy
            where policy.policyCode = :policyCode
              and policy.active = true
              and policy.effectiveFromUtc <= :effectiveAtUtc
              and (
                  policy.effectiveToUtc is null
                  or policy.effectiveToUtc > :effectiveAtUtc
              )
            order by policy.policyVersion desc
            """)
    List<StayPolicyVersion> findEffectiveByPolicyCode(
            @Param("policyCode") String policyCode,
            @Param("effectiveAtUtc") Instant effectiveAtUtc);
}
