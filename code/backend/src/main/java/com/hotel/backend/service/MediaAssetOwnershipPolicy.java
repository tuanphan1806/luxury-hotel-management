package com.hotel.backend.service;

import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.MediaAssetStatus;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.InvalidDataException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Centralizes ownership and authorization rules for uploaded media without
 * changing the media lifecycle managed by {@link MediaAssetService}.
 */
@Component
public class MediaAssetOwnershipPolicy {

    public void validateOwnerContract(
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

    public User currentUserOrThrow() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)
                || user.getId() == null) {
            throw new AccessDeniedException("Bạn cần đăng nhập để sử dụng file upload");
        }
        return user;
    }

    public void validateFinancialEvidenceRequest(
            Long assetId,
            UploadFolder purpose,
            User currentUser) {
        if (assetId == null || assetId <= 0 || purpose == null) {
            throw new InvalidDataException("Minh chứng chuyển khoản không hợp lệ");
        }
        if (currentUser == null || currentUser.getId() == null) {
            throw new AccessDeniedException("Bạn cần đăng nhập để sử dụng minh chứng chuyển khoản");
        }
    }

    public void validateFinancialEvidenceAsset(
            MediaAsset asset,
            UploadFolder purpose,
            User currentUser) {
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
    }

    public void validateManagedAssetClaim(
            MediaAsset asset,
            MediaAssetOwnerType ownerType,
            Long ownerId) {
        User currentUser = currentUserOrThrow();
        boolean admin = UserType.ADMIN.equals(currentUser.getType());
        if (!admin && !Objects.equals(currentUser.getId(), asset.getUploadedByUserId())) {
            throw new AccessDeniedException("Bạn không có quyền sử dụng file upload này");
        }
        if (!admin && MediaAssetOwnerType.USER_AVATAR.equals(ownerType)
                && !Objects.equals(currentUser.getId(), ownerId)) {
            throw new AccessDeniedException("Bạn chỉ có thể thay ảnh đại diện của chính mình");
        }
    }
}
