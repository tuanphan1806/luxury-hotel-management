package com.hotel.backend.service;

import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.MediaAssetStatus;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.InvalidDataException;
import com.hotel.backend.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaAssetOwnershipPolicy ownershipPolicy;
    private final MediaAssetCleanupService cleanupService;

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
        User currentUser = ownershipPolicy.currentUserOrThrow();
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

        cleanupService.registerStorageRollbackCleanup(normalizedObjectKey);
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
        ownershipPolicy.validateOwnerContract(purpose, ownerType, ownerId);
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
        ownershipPolicy.validateOwnerContract(purpose, ownerType, ownerId);
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
        ownershipPolicy.validateFinancialEvidenceRequest(assetId, purpose, currentUser);

        MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new InvalidDataException("Không tìm thấy file minh chứng đã tải lên"));
        ownershipPolicy.validateFinancialEvidenceAsset(asset, purpose, currentUser);

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
        return cleanupService.cleanupExpired(temporaryTtl, orphanedTtl, requestedBatchSize);
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

        ownershipPolicy.validateManagedAssetClaim(asset, ownerType, ownerId);

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

}
