package com.hotel.backend.controller;

import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.ValidationException;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.FileStorageService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.PaymentRefundService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock FileStorageService fileStorageService;
    @Mock AuthRateLimitService authRateLimitService;
    @Mock MediaAssetService mediaAssetService;
    @Mock PaymentRefundService paymentRefundService;
    @Mock MultipartFile file;
    @Mock Authentication authentication;

    @InjectMocks FileController controller;

    @Test
    void refundProofUploadLinksAssetToExactRefund() throws Exception {
        User staff = User.builder().username("refund-staff").build();
        staff.setId(17L);
        var stored = new FileStorageService.StoredImage(
                "/uploads/refund_proofs/proof.webp",
                "refund_proofs/proof.webp",
                "image/webp",
                2_048L,
                800,
                600);
        MediaAsset asset = MediaAsset.builder().build();
        asset.setId(91L);
        when(authentication.getName()).thenReturn("refund-staff");
        when(authentication.getPrincipal()).thenReturn(staff);
        when(fileStorageService.store(file, UploadFolder.REFUND_PROOFS)).thenReturn(stored);
        when(mediaAssetService.registerTemporary(
                stored.url(), stored.objectKey(), UploadFolder.REFUND_PROOFS,
                stored.contentType(), stored.size(), stored.width(), stored.height()))
                .thenReturn(asset);

        var response = controller.upload(
                file, UploadFolder.REFUND_PROOFS, "refund-123", authentication);

        assertEquals(91L, response.getBody().get("assetId"));
        verify(paymentRefundService).attachManualTransferProof(
                "refund-123", 91L, staff);
    }

    @Test
    void refundProofUploadRejectsMissingRefundIdBeforeWritingFile() throws Exception {
        assertThrows(ValidationException.class, () -> controller.upload(
                file, UploadFolder.REFUND_PROOFS, null, authentication));

        verify(fileStorageService, never()).store(file, UploadFolder.REFUND_PROOFS);
    }
}
