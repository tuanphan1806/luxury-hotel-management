package com.hotel.backend.repository;

import com.hotel.backend.constant.MediaAssetStatus;
import com.hotel.backend.entity.MediaAsset;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByUrl(String url);

    Optional<MediaAsset> findByObjectKey(String objectKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaAsset m where m.url = :url")
    Optional<MediaAsset> findByUrlForUpdate(@Param("url") String url);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaAsset m where m.id = :id")
    Optional<MediaAsset> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from MediaAsset m
            where (m.status = :temporaryStatus and m.createdAt < :temporaryCutoff)
               or (m.status = :orphanedStatus and m.orphanedAt < :orphanedCutoff)
            order by m.createdAt asc
            """)
    List<MediaAsset> findExpiredForCleanup(
            @Param("temporaryStatus") MediaAssetStatus temporaryStatus,
            @Param("temporaryCutoff") LocalDateTime temporaryCutoff,
            @Param("orphanedStatus") MediaAssetStatus orphanedStatus,
            @Param("orphanedCutoff") LocalDateTime orphanedCutoff,
            Pageable pageable);
}
