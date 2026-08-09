package com.hotel.backend.repository;

import com.hotel.backend.entity.RoomRateProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoomRateProfileRepository
        extends JpaRepository<RoomRateProfile, Long> {

    Optional<RoomRateProfile> findByRoomTypeIdAndProfileVersion(
            Long roomTypeId,
            Integer profileVersion);

    boolean existsByRoomTypeId(Long roomTypeId);

    /**
     * Returns every profile whose own validity window and referenced stay
     * policy are effective at the requested instant. A finite future
     * effective-to timestamp remains usable until that instant; this is
     * required for zero-downtime rate-version cutovers.
     */
    @Query("""
            select profile from RoomRateProfile profile
            join fetch profile.roomType roomType
            join fetch profile.stayPolicyVersion policy
            where roomType.code = :roomTypeCode
              and profile.active = true
              and profile.effectiveFromUtc <= :effectiveAtUtc
              and (
                  profile.effectiveToUtc is null
                  or profile.effectiveToUtc > :effectiveAtUtc
              )
              and policy.active = true
              and policy.effectiveFromUtc <= :effectiveAtUtc
              and (
                  policy.effectiveToUtc is null
                  or policy.effectiveToUtc > :effectiveAtUtc
              )
            order by profile.profileVersion desc
            """)
    List<RoomRateProfile> findEffectiveByRoomTypeCode(
            @Param("roomTypeCode") String roomTypeCode,
            @Param("effectiveAtUtc") Instant effectiveAtUtc);

    /**
     * Batch variant used by the public room catalogue. Keeping this as one
     * query avoids issuing one rate lookup for every room card.
     */
    @Query("""
            select profile from RoomRateProfile profile
            join fetch profile.roomType roomType
            join fetch profile.stayPolicyVersion policy
            where roomType.id in :roomTypeIds
              and profile.active = true
              and profile.effectiveFromUtc <= :effectiveAtUtc
              and (
                  profile.effectiveToUtc is null
                  or profile.effectiveToUtc > :effectiveAtUtc
              )
              and policy.active = true
              and policy.effectiveFromUtc <= :effectiveAtUtc
              and (
                  policy.effectiveToUtc is null
                  or policy.effectiveToUtc > :effectiveAtUtc
              )
            order by roomType.id asc, profile.profileVersion desc
            """)
    List<RoomRateProfile> findEffectiveByRoomTypeIds(
            @Param("roomTypeIds") Collection<Long> roomTypeIds,
            @Param("effectiveAtUtc") Instant effectiveAtUtc);

    /**
     * Creation commands use the same effective-window rules as a public
     * quote, but lock the selected version so the persisted reservation and
     * its immutable snapshot cannot observe different financial versions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile from RoomRateProfile profile
            join fetch profile.roomType roomType
            join fetch profile.stayPolicyVersion policy
            where roomType.code = :roomTypeCode
              and profile.active = true
              and profile.effectiveFromUtc <= :effectiveAtUtc
              and (
                  profile.effectiveToUtc is null
                  or profile.effectiveToUtc > :effectiveAtUtc
              )
              and policy.active = true
              and policy.effectiveFromUtc <= :effectiveAtUtc
              and (
                  policy.effectiveToUtc is null
                  or policy.effectiveToUtc > :effectiveAtUtc
              )
            order by profile.profileVersion desc
            """)
    List<RoomRateProfile> findEffectiveByRoomTypeCodeForUpdate(
            @Param("roomTypeCode") String roomTypeCode,
            @Param("effectiveAtUtc") Instant effectiveAtUtc);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile from RoomRateProfile profile
            join fetch profile.roomType
            join fetch profile.stayPolicyVersion
            where profile.id = :id
            """)
    Optional<RoomRateProfile> findByIdForUpdate(@Param("id") Long id);

    /**
     * Locks every still-active version for one room type before an immediate
     * rate cutover. The RoomType row is locked first by the caller, keeping a
     * stable lock order for concurrent admin updates.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select profile from RoomRateProfile profile
            join fetch profile.stayPolicyVersion
            where profile.roomType.id = :roomTypeId
              and profile.active = true
            order by profile.effectiveFromUtc asc, profile.profileVersion asc
            """)
    List<RoomRateProfile> findActiveByRoomTypeIdForUpdate(
            @Param("roomTypeId") Long roomTypeId);

    @Query("""
            select coalesce(max(profile.profileVersion), 0)
            from RoomRateProfile profile
            where profile.roomType.id = :roomTypeId
            """)
    Integer findMaxProfileVersion(@Param("roomTypeId") Long roomTypeId);
}
