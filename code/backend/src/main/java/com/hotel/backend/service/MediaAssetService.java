package com.hotel.backend.service;

import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.MediaAssetStatus;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.InvalidDataException;
import com.hotel.backend.repository.MediaAssetRepository;
import com.hotel.backend.storage.UploadStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Quản lý metadata và vòng đời của file upload.
 *
 * <p>Upload và gắn file là hai bước tách biệt: controller lưu bytes,
 * sau đó gọi {@link #registerTemporary} để tạo metadata TEMPORARY. Khi
 * entity nghiệp vụ được lưu, service của entity gọi
 * {@link #replaceReference} để claim file. File bị thay thế không xóa ngay
 * mà chuyển ORPHANED, giúp transaction rollback an toàn và scheduler dọn sau.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private final MediaAssetRepository mediaAssetRepository;
    private final UploadStorage uploadStorage;

    @Value("${app.upload.base-url:}")
    private String managedBaseUrl;

    @Value("${app.upload.storage:local}")
    private String storageProvider;

    @Value("${app.upload.cloudinary.cloud-name:}")
    private String cloudinaryCloudName;

    /**
     * Ghi nhận file vừa upload. File chỉ được coi là hợp lệ để gắn vào
     * entity sau khi metadata này được tạo thành công.
     */
    @Transactional
    public MediaAsset registerTemporary(
            String url,
            String objectKey,
            UploadFolder purpose,
            String contentType,
            long fileSize,
            Integer width,
            Integer height) {
        User currentUser = currentUserOrThrow();
        String normalizedUrl = requireText(url, "URL file không hợp lệ");
        String normalizedObjectKey = requireText(objectKey, "Object key không hợp lệ");
        String normalizedContentType = requireText(contentType, "Content-Type không hợp lệ");
        if (purpose == null) {
            throw new InvalidDataException("Mục đích upload không hợp lệ");
        }
        boolean pdfRefundProof = purpose == UploadFolder.REFUND_PROOFS
                && "application/pdf".equalsIgnoreCase(normalizedContentType);
        if (fileSize <= 0 || (!pdfRefundProof
                && (width == null || width <= 0 || height == null || height <= 0))) {
            throw new InvalidDataException("Metadata kích thước ảnh không hợp lệ");
        }

        var existingByUrl = mediaAssetRepository.findByUrl(normalizedUrl);
        if (existingByUrl.isPresent()) {
            MediaAsset existing = existingByUrl.get();
            if (Objects.equals(existing.getObjectKey(), normalizedObjectKey)
                    && Objects.equals(existing.getUploadedByUserId(), currentUser.getId())) {
                return existing;
            }
            throw new InvalidDataException("URL file đã được ghi nhận");
        }
        if (mediaAssetRepository.findByObjectKey(normalizedObjectKey).isPresent()) {
            throw new InvalidDataException("Object key đã được ghi nhận");
        }

        registerStorageRollbackCleanup(normalizedObjectKey);
        return mediaAssetRepository.saveAndFlush(MediaAsset.builder()
                .url(normalizedUrl)
                .objectKey(normalizedObjectKey)
                .purpose(purpose)
                .status(MediaAssetStatus.TEMPORARY)
                .contentType(normalizedContentType)
                .fileSize(fileSize)
                .width(width)
                .height(height)
                .uploadedByUserId(currentUser.getId())
                .build());
    }

    /**
     * Thay tham chiếu ảnh của entity. URL legacy/ngoài hệ thống được giữ
     * tương thích; URL thuộc namespace upload hiện tại bắt buộc phải có
     * metadata và đúng purpose/owner.
     *
     * @return URL đã trim, hoặc {@code null} nếu người dùng xóa ảnh.
     */
    @Transactional
    public String replaceReference(
            String previousUrl,
            String requestedUrl,
            UploadFolder purpose,
            MediaAssetOwnerType ownerType,
            Long ownerId) {
        validateOwnerContract(purpose, ownerType, ownerId);
        String previous = normalizeNullable(previousUrl);
        String requested = normalizeNullable(requestedUrl);
        if (Objects.equals(previous, requested)) {
            return requested;
        }

        // Cùng transaction: nếu claim file mới thất bại, thay đổi ORPHANED
        // của file cũ cũng rollback.
        orphanOwnedReference(previous, ownerType, ownerId);
        // Flush trạng thái ORPHANED trước khi claim asset thay thế.
        mediaAssetRepository.flush();
        claimManagedReference(requested, purpose, ownerType, ownerId);
        return requested;
    }

    /**
     * Atomically replaces an ordered set of media references for one owner.
     * Existing legacy/static URLs may remain unchanged, while every newly
     * introduced URL must have been validated by /files/upload.
     */
    @Transactional
    public List<String> replaceReferences(
            Collection<String> previousUrls,
            Collection<String> requestedUrls,
            UploadFolder purpose,
            MediaAssetOwnerType ownerType,
            Long ownerId,
            int maxImages) {
        validateOwnerContract(purpose, ownerType, ownerId);
        if (maxImages < 1) {
            throw new InvalidDataException("Giới hạn số ảnh không hợp lệ");
        }

        List<String> previous = normalizeReferences(previousUrls);
        List<String> requested = normalizeReferences(requestedUrls);
        if (requested.size() > maxImages) {
            throw new InvalidDataException("Số lượng ảnh vượt quá giới hạn " + maxImages);
        }
        if (previous.equals(requested)) {
            return List.copyOf(requested);
        }

        previous.stream()
                .filter(url -> !requested.contains(url))
                .forEach(url -> orphanOwnedReference(url, ownerType, ownerId));
        mediaAssetRepository.flush();
        requested.stream()
                .filter(url -> !previous.contains(url))
                .forEach(url -> claimManagedReference(url, purpose, ownerType, ownerId));
        return List.copyOf(requested);
    }

    @Transactional
    public void releaseReference(String url, MediaAssetOwnerType ownerType, Long ownerId) {
        if (ownerType == null || ownerId == null) {
            throw new InvalidDataException("Chủ sở hữu file không hợp lệ");
        }
        orphanOwnedReference(normalizeNullable(url), ownerType, ownerId);
    }

    @Transactional
    public void releaseReferences(
            Collection<String> urls,
            MediaAssetOwnerType ownerType,
            Long ownerId) {
        if (ownerType == null || ownerId == null) {
            throw new InvalidDataException("Chủ sở hữu file không hợp lệ");
        }
        normalizeReferences(urls)
                .forEach(url -> orphanOwnedReference(url, ownerType, ownerId));
    }

    /**
     * Claim một ảnh chứng từ tài chính. Asset được giữ ACTIVE bằng khóa ngoại
     * từ bản ghi nghiệp vụ thay vì owner_type/owner_id, vì mã refund là UUID.
     * File TEMPORARY không được claim sẽ tiếp tục do scheduler dọn bình thường.
     */
    @Transactional
    public MediaAsset claimFinancialEvidence(Long assetId, UploadFolder purpose, User currentUser) {
        if (assetId == null || assetId <= 0 || purpose == null) {
            throw new InvalidDataException("Minh chứng chuyển khoản không hợp lệ");
        }
        if (currentUser == null || currentUser.getId() == null) {
            throw new AccessDeniedException("Bạn cần đăng nhập để sử dụng minh chứng chuyển khoản");
        }

        MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new InvalidDataException("Không tìm thấy file minh chứng đã tải lên"));
        if (!purpose.equals(asset.getPurpose())) {
            throw new InvalidDataException("File upload không đúng mục đích làm minh chứng hoàn tiền");
        }
        if (!MediaAssetStatus.TEMPORARY.equals(asset.getStatus())) {
            throw new InvalidDataException("File minh chứng đã được sử dụng hoặc không còn hiệu lực");
        }

        boolean admin = UserType.ADMIN.equals(currentUser.getType());
        if (!admin && !Objects.equals(currentUser.getId(), asset.getUploadedByUserId())) {
            throw new AccessDeniedException("Bạn chỉ có thể sử dụng file minh chứng do chính mình tải lên");
        }

        asset.setStatus(MediaAssetStatus.ACTIVE);
        asset.setClaimedAt(LocalDateTime.now());
        asset.setOrphanedAt(null);
        asset.setDeletedAt(null);
        return asset;
    }

    /**
     * Xóa vật lý theo lô. Metadata được giữ lại ở trạng thái DELETED
     * để URL đã xóa không bị coi nhầm là URL legacy hợp lệ.
     */
    @Transactional
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

    private void claimManagedReference(
            String url,
            UploadFolder purpose,
            MediaAssetOwnerType ownerType,
            Long ownerId) {
        if (url == null) {
            return;
        }
        var managedAsset = mediaAssetRepository.findByUrlForUpdate(url);
        if (managedAsset.isEmpty()) {
            if (isManagedNamespace(url)) {
                throw new InvalidDataException("File upload không tồn tại hoặc chưa được ghi nhận");
            }
            // Chỉ URL cũ không thay đổi mới được giữ tương thích
            // (replaceReference đã return sớm). URL mới, kể cả external,
            // phải đi qua /files/upload để có validation và metadata.
            throw new InvalidDataException("Ảnh mới phải được tải lên qua hệ thống upload");
        }

        MediaAsset asset = managedAsset.get();
        if (!purpose.equals(asset.getPurpose())) {
            throw new InvalidDataException("File upload không đúng mục đích sử dụng");
        }
        if (MediaAssetStatus.DELETED.equals(asset.getStatus())) {
            throw new InvalidDataException("File upload đã bị xóa");
        }
        if (MediaAssetStatus.ACTIVE.equals(asset.getStatus())) {
            if (ownerType.equals(asset.getOwnerType()) && ownerId.equals(asset.getOwnerId())) {
                return;
            }
            throw new InvalidDataException("File upload đã được sử dụng bởi bản ghi khác");
        }

        User currentUser = currentUserOrThrow();
        boolean admin = UserType.ADMIN.equals(currentUser.getType());
        if (!admin && !Objects.equals(currentUser.getId(), asset.getUploadedByUserId())) {
            throw new AccessDeniedException("Bạn không có quyền sử dụng file upload này");
        }
        if (!admin && MediaAssetOwnerType.USER_AVATAR.equals(ownerType)
                && !Objects.equals(currentUser.getId(), ownerId)) {
            throw new AccessDeniedException("Bạn chỉ có thể thay ảnh đại diện của chính mình");
        }

        asset.setStatus(MediaAssetStatus.ACTIVE);
        asset.setOwnerType(ownerType);
        asset.setOwnerId(ownerId);
        asset.setClaimedAt(LocalDateTime.now());
        asset.setOrphanedAt(null);
        asset.setDeletedAt(null);
    }

    private void orphanOwnedReference(String url, MediaAssetOwnerType ownerType, Long ownerId) {
        if (url == null) {
            return;
        }
        mediaAssetRepository.findByUrlForUpdate(url).ifPresent(asset -> {
            if (MediaAssetStatus.ACTIVE.equals(asset.getStatus())
                    && ownerType.equals(asset.getOwnerType())
                    && ownerId.equals(asset.getOwnerId())) {
                asset.setStatus(MediaAssetStatus.ORPHANED);
                asset.setOwnerType(null);
                asset.setOwnerId(null);
                asset.setOrphanedAt(LocalDateTime.now());
            }
        });
    }

    private void validateOwnerContract(
            UploadFolder purpose,
            MediaAssetOwnerType ownerType,
            Long ownerId) {
        if (purpose == null || ownerType == null || ownerId == null || ownerId <= 0) {
            throw new InvalidDataException("Thông tin gắn file không hợp lệ");
        }
        if (!purpose.equals(ownerType.getRequiredPurpose())) {
            throw new InvalidDataException("Mục đích upload không khớp với loại dữ liệu");
        }
    }

    private User currentUserOrThrow() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)
                || user.getId() == null) {
            throw new AccessDeniedException("Bạn cần đăng nhập để sử dụng file upload");
        }
        return user;
    }

    private boolean isManagedNamespace(String url) {
        String baseUrl = normalizeNullable(managedBaseUrl);
        if (baseUrl != null) {
            String normalizedBase = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
            if (url.equals(normalizedBase) || url.startsWith(normalizedBase + "/")) {
                return true;
            }
        }

        // Cloudinary sinh URL CDN nên production có thể không cấu hình
        // app.upload.base-url. Vẫn phải nhận diện URL thuộc đúng tenant;
        // nếu metadata không tồn tại thì không được coi là legacy.
        String cloudName = normalizeNullable(cloudinaryCloudName);
        if ("cloudinary".equalsIgnoreCase(normalizeNullable(storageProvider)) && cloudName != null) {
            String httpsPrefix = "https://res.cloudinary.com/" + cloudName + "/";
            String httpPrefix = "http://res.cloudinary.com/" + cloudName + "/";
            return url.startsWith(httpsPrefix) || url.startsWith(httpPrefix);
        }
        return false;
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> normalizeReferences(Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String url : urls) {
            String value = normalizeNullable(url);
            if (value != null) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String requireText(String value, String message) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new InvalidDataException(message);
        }
        return normalized;
    }

    private Duration requirePositive(Duration duration, String label) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new InvalidDataException(label + " phải lớn hơn 0");
        }
        return duration;
    }

    /**
     * Bytes đã được object storage lưu trước khi tạo metadata. Nếu
     * insert/commit metadata thất bại, callback này bù trừ bằng cách xóa
     * object, tránh tạo orphan không có DB row để scheduler tìm thấy.
     */
    private void registerStorageRollbackCleanup(String objectKey) {
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
}
