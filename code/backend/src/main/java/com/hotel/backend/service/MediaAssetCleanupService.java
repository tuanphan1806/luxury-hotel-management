package com.hotel.backend.service;

import com.hotel.backend.constant.MediaAssetStatus;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.exception.InvalidDataException;
import com.hotel.backend.repository.MediaAssetRepository;
import com.hotel.backend.storage.UploadStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles physical object cleanup and transaction rollback compensation.
 * Transaction boundaries remain on {@link MediaAssetService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaAssetCleanupService {

    private final MediaAssetRepository mediaAssetRepository;
    private final UploadStorage uploadStorage;

    public int cleanupExpired(Duration temporaryTtl, Duration orphanedTtl, int requestedBatchSize) {
        Duration safeTemporaryTtl = requirePositive(temporaryTtl, "TTL file tạm");
        Duration safeOrphanedTtl = requirePositive(orphanedTtl, "TTL file thừa");
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 500));
        LocalDateTime now = LocalDateTime.now();
        List<MediaAsset> expired = mediaAssetRepository.findExpiredForCleanup(
                MediaAssetStatus.TEMPORARY,
                now.minus(safeTemporaryTtl),
                MediaAssetStatus.ORPHANED,
                now.minus(safeOrphanedTtl),
                PageRequest.of(0, batchSize));

        int deleted = 0;
        for (MediaAsset asset : expired) {
            try {
                uploadStorage.delete(asset.getObjectKey());
                asset.setStatus(MediaAssetStatus.DELETED);
                asset.setDeletedAt(now);
                asset.setOwnerType(null);
                asset.setOwnerId(null);
                deleted++;
            } catch (IOException | RuntimeException ex) {
                // Một object storage lỗi không được chặn việc dọn các file
                // khác. Asset giữ nguyên trạng thái để lần sau retry.
                log.warn("Không thể dọn media asset id={}, objectKey={}",
                        asset.getId(), asset.getObjectKey(), ex);
            }
        }
        if (deleted > 0) {
            mediaAssetRepository.saveAll(expired);
        }
        return deleted;
    }

    /**
     * Bytes đã được object storage lưu trước khi tạo metadata. Nếu
     * insert/commit metadata thất bại, callback này bù trừ bằng cách xóa
     * object, tránh tạo orphan không có DB row để scheduler tìm thấy.
     */
    public void registerStorageRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    uploadStorage.delete(objectKey);
                } catch (IOException | RuntimeException cleanupError) {
                    log.error("Không thể rollback object sau khi lưu metadata thất bại: {}",
                            objectKey, cleanupError);
                }
            }
        });
    }

    private Duration requirePositive(Duration duration, String label) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new InvalidDataException(label + " phải lớn hơn 0");
        }
        return duration;
    }
}
