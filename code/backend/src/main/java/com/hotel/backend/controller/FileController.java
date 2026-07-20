package com.hotel.backend.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;

import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.FileStorageService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.PaymentRefundService;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.ValidationException;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Slf4j(topic = "FILE-CONTROLLER")
public class FileController {

    private final FileStorageService fileStorageService;
    private final AuthRateLimitService authRateLimitService;
    private final MediaAssetService mediaAssetService;
    private final PaymentRefundService paymentRefundService;

    @Operation(summary = "Upload media", description = "Upload validated images; REFUND_PROOFS also accepts PDF")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')"
            + " or (hasRole('STAFF') and #folder == T(com.hotel.backend.constant.UploadFolder).REFUND_PROOFS)"
            + " or (isAuthenticated() and #folder == T(com.hotel.backend.constant.UploadFolder).AVATAR)")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("folder") UploadFolder folder,
            @RequestParam(value = "refundId", required = false) String refundId,
            Authentication authentication) throws IOException {

        if (folder == UploadFolder.REFUND_PROOFS
                && (refundId == null || refundId.isBlank())) {
            throw new ValidationException(
                    "refundId không được để trống khi tải minh chứng hoàn tiền");
        }

        String principal = authentication == null ? "anonymous" : authentication.getName();
        int hourlyLimit = folder == UploadFolder.AVATAR ? 20 : 120;
        authRateLimitService.check("upload:" + folder + ":" + principal, hourlyLimit, Duration.ofHours(1));

        FileStorageService.StoredImage image = fileStorageService.store(file, folder);
        MediaAsset asset;
        try {
            asset = mediaAssetService.registerTemporary(
                    image.url(),
                    image.objectKey(),
                    folder,
                    image.contentType(),
                    image.size(),
                    image.width(),
                    image.height());
        } catch (RuntimeException exception) {
            // Metadata and bytes must not diverge. Best-effort rollback avoids
            // an untracked object when the database write fails.
            try {
                fileStorageService.delete(image.objectKey());
            } catch (IOException cleanupException) {
                log.error("Unable to roll back unregistered upload objectKey={}", image.objectKey(), cleanupException);
            }
            throw exception;
        }

        if (folder == UploadFolder.REFUND_PROOFS) {
            User currentUser = authentication != null
                    && authentication.getPrincipal() instanceof User user ? user : null;
            paymentRefundService.attachManualTransferProof(
                    refundId.trim(), asset.getId(), currentUser);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", 200);
        result.put("assetId", asset.getId());
        result.put("url", image.url());
        result.put("objectKey", image.objectKey());
        result.put("contentType", image.contentType());
        result.put("size", image.size());
        result.put("width", image.width());
        result.put("height", image.height());
        return ResponseEntity.ok(result);
    }
}
