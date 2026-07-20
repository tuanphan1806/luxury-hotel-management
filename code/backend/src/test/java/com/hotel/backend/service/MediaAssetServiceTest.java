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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetServiceTest {

    private static final String BASE_URL = "https://cdn.hotel.test/uploads";

    @Mock
    private MediaAssetRepository mediaAssetRepository;

    @Mock
    private UploadStorage uploadStorage;

    @InjectMocks
    private MediaAssetService mediaAssetService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mediaAssetService, "managedBaseUrl", BASE_URL);
        ReflectionTestUtils.setField(mediaAssetService, "storageProvider", "local");
        ReflectionTestUtils.setField(mediaAssetService, "cloudinaryCloudName", "");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Upload thành công chỉ tạo metadata TEMPORARY, chưa được coi là ảnh đang sử dụng. */
    @Test
    void registersTemporaryAssetForAuthenticatedUploader() {
        authenticate(7L, UserType.CUSTOMER);
        when(mediaAssetRepository.findByUrl(BASE_URL + "/avatar/a.webp"))
                .thenReturn(Optional.empty());
        when(mediaAssetRepository.findByObjectKey("avatar/a.webp"))
                .thenReturn(Optional.empty());
        when(mediaAssetRepository.saveAndFlush(any(MediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MediaAsset result = mediaAssetService.registerTemporary(
                BASE_URL + "/avatar/a.webp",
                "avatar/a.webp",
                UploadFolder.AVATAR,
                "image/webp",
                1234,
                800,
                800);

        assertThat(result.getStatus()).isEqualTo(MediaAssetStatus.TEMPORARY);
        assertThat(result.getUploadedByUserId()).isEqualTo(7L);
        assertThat(result.getOwnerType()).isNull();
        assertThat(result.getFileSize()).isEqualTo(1234);
    }

    /** PDF chỉ hợp lệ cho chứng từ hoàn tiền và không giả lập kích thước ảnh. */
    @Test
    void registersPdfRefundProofWithoutImageDimensions() {
        authenticate(17L, UserType.STAFF);
        when(mediaAssetRepository.findByUrl(BASE_URL + "/refund_proofs/receipt.pdf"))
                .thenReturn(Optional.empty());
        when(mediaAssetRepository.findByObjectKey("refund_proofs/receipt.pdf"))
                .thenReturn(Optional.empty());
        when(mediaAssetRepository.saveAndFlush(any(MediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MediaAsset result = mediaAssetService.registerTemporary(
                BASE_URL + "/refund_proofs/receipt.pdf",
                "refund_proofs/receipt.pdf",
                UploadFolder.REFUND_PROOFS,
                "application/pdf",
                4096,
                null,
                null);

        assertThat(result.getContentType()).isEqualTo("application/pdf");
        assertThat(result.getWidth()).isNull();
        assertThat(result.getHeight()).isNull();
        assertThat(result.getStatus()).isEqualTo(MediaAssetStatus.TEMPORARY);
    }

    /** Biên lai hoàn tiền chỉ được giữ lâu dài sau khi staff sở hữu file claim nó. */
    @Test
    void claimsTemporaryRefundProofForItsStaffUploader() {
        MediaAsset proof = temporaryAsset(17L, UploadFolder.REFUND_PROOFS, "refund_proofs/receipt.webp");
        proof.setId(91L);
        User staff = User.builder().type(UserType.STAFF).username("staff-17").build();
        staff.setId(17L);
        when(mediaAssetRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(proof));

        MediaAsset claimed = mediaAssetService.claimFinancialEvidence(
                91L, UploadFolder.REFUND_PROOFS, staff);

        assertThat(claimed.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(claimed.getClaimedAt()).isNotNull();
        assertThat(claimed.getOwnerType()).isNull();
        assertThat(claimed.getOwnerId()).isNull();
    }

    /** Staff khác không được lấy lại file biên lai do đồng nghiệp tải lên. */
    @Test
    void rejectsRefundProofUploadedByAnotherStaffMember() {
        MediaAsset proof = temporaryAsset(17L, UploadFolder.REFUND_PROOFS, "refund_proofs/receipt.webp");
        proof.setId(91L);
        User otherStaff = User.builder().type(UserType.STAFF).username("staff-18").build();
        otherStaff.setId(18L);
        when(mediaAssetRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(proof));

        assertThatThrownBy(() -> mediaAssetService.claimFinancialEvidence(
                91L, UploadFolder.REFUND_PROOFS, otherStaff))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(proof.getStatus()).isEqualTo(MediaAssetStatus.TEMPORARY);
    }

    /** File tạm đúng owner và purpose được claim khi entity nghiệp vụ lưu thành công. */
    @Test
    void claimsTemporaryAssetForItsUploader() {
        authenticate(9L, UserType.CUSTOMER);
        MediaAsset asset = temporaryAsset(9L, UploadFolder.AVATAR, "avatar/a.webp");
        when(mediaAssetRepository.findByUrlForUpdate(asset.getUrl()))
                .thenReturn(Optional.of(asset));

        String claimedUrl = mediaAssetService.replaceReference(
                null,
                asset.getUrl(),
                UploadFolder.AVATAR,
                MediaAssetOwnerType.USER_AVATAR,
                9L);

        assertThat(claimedUrl).isEqualTo(asset.getUrl());
        assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(asset.getOwnerType()).isEqualTo(MediaAssetOwnerType.USER_AVATAR);
        assertThat(asset.getOwnerId()).isEqualTo(9L);
        assertThat(asset.getClaimedAt()).isNotNull();
    }

    /** Không thể upload vào thư mục avatar rồi tái sử dụng file cho gallery. */
    @Test
    void rejectsAssetWithWrongPurpose() {
        authenticate(1L, UserType.ADMIN);
        MediaAsset asset = temporaryAsset(1L, UploadFolder.AVATAR, "avatar/a.webp");
        when(mediaAssetRepository.findByUrlForUpdate(asset.getUrl()))
                .thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> mediaAssetService.replaceReference(
                null,
                asset.getUrl(),
                UploadFolder.GALLERY,
                MediaAssetOwnerType.GALLERY,
                11L))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("không đúng mục đích");

        assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.TEMPORARY);
    }

    /** Customer không được chiếm URL file tạm do tài khoản khác upload. */
    @Test
    void rejectsClaimByDifferentCustomer() {
        authenticate(2L, UserType.CUSTOMER);
        MediaAsset asset = temporaryAsset(1L, UploadFolder.AVATAR, "avatar/a.webp");
        when(mediaAssetRepository.findByUrlForUpdate(asset.getUrl()))
                .thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> mediaAssetService.replaceReference(
                null,
                asset.getUrl(),
                UploadFolder.AVATAR,
                MediaAssetOwnerType.USER_AVATAR,
                2L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("không có quyền");
    }

    /** Kể cả file do chính customer upload cũng không được gắn làm avatar người khác. */
    @Test
    void rejectsCustomerClaimingAvatarForAnotherUser() {
        authenticate(2L, UserType.CUSTOMER);
        MediaAsset asset = temporaryAsset(2L, UploadFolder.AVATAR, "avatar/a.webp");
        when(mediaAssetRepository.findByUrlForUpdate(asset.getUrl()))
                .thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> mediaAssetService.replaceReference(
                null,
                asset.getUrl(),
                UploadFolder.AVATAR,
                MediaAssetOwnerType.USER_AVATAR,
                3L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("chính mình");
    }

    /** Admin có thể claim file khi quản trị dữ liệu, nhưng purpose vẫn phải đúng. */
    @Test
    void letsAdminClaimAnAssetUploadedByAnotherUser() {
        authenticate(99L, UserType.ADMIN);
        MediaAsset asset = temporaryAsset(7L, UploadFolder.FACILITIES, "facilities/a.webp");
        when(mediaAssetRepository.findByUrlForUpdate(asset.getUrl()))
                .thenReturn(Optional.of(asset));

        mediaAssetService.replaceReference(
                null,
                asset.getUrl(),
                UploadFolder.FACILITIES,
                MediaAssetOwnerType.FACILITY,
                12L);

        assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(asset.getOwnerId()).isEqualTo(12L);
    }

    /** Thay ảnh chuyển file cũ sang ORPHANED và claim file mới trong cùng transaction. */
    @Test
    void replacesActiveAssetWithoutDeletingItImmediately() throws IOException {
        authenticate(1L, UserType.ADMIN);
        MediaAsset oldAsset = temporaryAsset(1L, UploadFolder.ROOM_TYPES, "room_types/old.webp");
        oldAsset.setStatus(MediaAssetStatus.ACTIVE);
        oldAsset.setOwnerType(MediaAssetOwnerType.ROOM_TYPE);
        oldAsset.setOwnerId(20L);
        MediaAsset newAsset = temporaryAsset(1L, UploadFolder.ROOM_TYPES, "room_types/new.webp");
        when(mediaAssetRepository.findByUrlForUpdate(oldAsset.getUrl()))
                .thenReturn(Optional.of(oldAsset));
        when(mediaAssetRepository.findByUrlForUpdate(newAsset.getUrl()))
                .thenReturn(Optional.of(newAsset));

        mediaAssetService.replaceReference(
                oldAsset.getUrl(),
                newAsset.getUrl(),
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                20L);

        assertThat(oldAsset.getStatus()).isEqualTo(MediaAssetStatus.ORPHANED);
        assertThat(oldAsset.getOwnerId()).isNull();
        assertThat(oldAsset.getOrphanedAt()).isNotNull();
        assertThat(newAsset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(newAsset.getOwnerId()).isEqualTo(20L);
        verify(uploadStorage, never()).delete(any());
    }

    /** URL cũ/CDN ngoài hệ thống không đổi vẫn tương thích với seed và OAuth. */
    @Test
    void preservesLegacyExternalUrlWithoutMediaMetadata() {
        authenticate(1L, UserType.ADMIN);
        String legacyUrl = "https://legacy.example/room.jpg";

        String result = mediaAssetService.replaceReference(
                legacyUrl,
                legacyUrl,
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                5L);

        assertThat(result).isEqualTo(legacyUrl);
    }

    /** URL external mới bị từ chối; mọi ảnh mới phải qua validation của /files/upload. */
    @Test
    void rejectsNewUnregisteredExternalUrl() {
        authenticate(1L, UserType.ADMIN);
        String externalUrl = "https://arbitrary.example/unvalidated.jpg";
        when(mediaAssetRepository.findByUrlForUpdate(externalUrl)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaAssetService.replaceReference(
                null,
                externalUrl,
                UploadFolder.ROOM_TYPES,
                MediaAssetOwnerType.ROOM_TYPE,
                5L))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("qua hệ thống upload");
    }

    /** URL nằm trong namespace upload mới phải có metadata, tránh gắn path tự chế. */
    @Test
    void rejectsUnregisteredUrlInsideManagedNamespace() {
        authenticate(1L, UserType.ADMIN);
        String missingUrl = BASE_URL + "/gallery/not-registered.webp";
        when(mediaAssetRepository.findByUrlForUpdate(missingUrl)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaAssetService.replaceReference(
                null,
                missingUrl,
                UploadFolder.GALLERY,
                MediaAssetOwnerType.GALLERY,
                6L))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("chưa được ghi nhận");
    }

    /** Production Cloudinary không cần base-url nhưng URL thuộc tenant vẫn là managed. */
    @Test
    void rejectsUnregisteredCloudinaryTenantUrlWhenBaseUrlIsBlank() {
        authenticate(1L, UserType.ADMIN);
        ReflectionTestUtils.setField(mediaAssetService, "managedBaseUrl", "");
        ReflectionTestUtils.setField(mediaAssetService, "storageProvider", "cloudinary");
        ReflectionTestUtils.setField(mediaAssetService, "cloudinaryCloudName", "hotel-cloud");
        String missingUrl = "https://res.cloudinary.com/hotel-cloud/image/upload/v1/hotel-media/a.webp";
        when(mediaAssetRepository.findByUrlForUpdate(missingUrl)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaAssetService.replaceReference(
                null,
                missingUrl,
                UploadFolder.GALLERY,
                MediaAssetOwnerType.GALLERY,
                6L))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("chưa được ghi nhận");
    }

    /** Cleanup xóa file hết TTL; lỗi storage được giữ lại để retry lần sau. */
    @Test
    void cleansExpiredAssetsAndRetriesStorageFailures() throws IOException {
        MediaAsset deletable = temporaryAsset(1L, UploadFolder.GALLERY, "gallery/delete.webp");
        deletable.setId(1L);
        MediaAsset failed = temporaryAsset(1L, UploadFolder.GALLERY, "gallery/retry.webp");
        failed.setId(2L);
        failed.setStatus(MediaAssetStatus.ORPHANED);
        when(mediaAssetRepository.findExpiredForCleanup(
                eq(MediaAssetStatus.TEMPORARY),
                any(),
                eq(MediaAssetStatus.ORPHANED),
                any(),
                any(Pageable.class)))
                .thenReturn(List.of(deletable, failed));
        doNothing().when(uploadStorage).delete(deletable.getObjectKey());
        doThrow(new IOException("object storage unavailable"))
                .when(uploadStorage).delete(failed.getObjectKey());

        int cleaned = mediaAssetService.cleanupExpired(
                Duration.ofHours(24), Duration.ofHours(24), 100);

        assertThat(cleaned).isEqualTo(1);
        assertThat(deletable.getStatus()).isEqualTo(MediaAssetStatus.DELETED);
        assertThat(deletable.getDeletedAt()).isNotNull();
        assertThat(failed.getStatus()).isEqualTo(MediaAssetStatus.ORPHANED);
        ArgumentCaptor<List<MediaAsset>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaAssetRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(deletable, failed);
    }

    private MediaAsset temporaryAsset(Long uploaderId, UploadFolder purpose, String objectKey) {
        return MediaAsset.builder()
                .url(BASE_URL + "/" + objectKey)
                .objectKey(objectKey)
                .purpose(purpose)
                .status(MediaAssetStatus.TEMPORARY)
                .contentType("image/webp")
                .fileSize(1000)
                .width(1200)
                .height(800)
                .uploadedByUserId(uploaderId)
                .build();
    }

    private void authenticate(Long userId, UserType type) {
        User user = User.builder()
                .fullName("Test User")
                .username("user-" + userId)
                .email("user-" + userId + "@example.com")
                .type(type)
                .build();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
