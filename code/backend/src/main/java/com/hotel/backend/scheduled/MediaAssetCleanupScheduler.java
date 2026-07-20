package com.hotel.backend.scheduled;

import com.hotel.backend.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j(topic = "MEDIA_ASSET_CLEANUP_SCHEDULER")
@Component
@RequiredArgsConstructor
public class MediaAssetCleanupScheduler {

    private final MediaAssetService mediaAssetService;

    @Value("${app.upload.temporary-ttl-hours:24}")
    private long temporaryTtlHours;

    @Value("${app.upload.orphan-ttl-hours:24}")
    private long orphanedTtlHours;

    @Value("${app.upload.cleanup-batch-size:100}")
    private int cleanupBatchSize;

    @Scheduled(cron = "${app.upload.cleanup-cron:0 30 3 * * *}")
    public void cleanupExpiredMedia() {
        int cleaned = mediaAssetService.cleanupExpired(
                Duration.ofHours(Math.max(1, temporaryTtlHours)),
                Duration.ofHours(Math.max(1, orphanedTtlHours)),
                cleanupBatchSize);
        if (cleaned > 0) {
            log.info("Đã dọn {} media asset hết hạn", cleaned);
        }
    }
}
