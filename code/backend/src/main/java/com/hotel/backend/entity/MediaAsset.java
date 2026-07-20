package com.hotel.backend.entity;

import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.MediaAssetStatus;
import com.hotel.backend.constant.UploadFolder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "media_assets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAsset extends AbstractEntity<Long> {

    @Column(nullable = false, unique = true, length = 500)
    private String url;

    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UploadFolder purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MediaAssetStatus status;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    private Integer width;

    private Integer height;

    /** ID người đã upload. Nullable để FK có thể SET NULL khi hard-delete user. */
    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", length = 32)
    private MediaAssetOwnerType ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "orphaned_at")
    private LocalDateTime orphanedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private long version = 0L;
}
