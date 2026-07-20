package com.hotel.backend.service;

import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.exception.ValidationException;
import com.hotel.backend.storage.UploadStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileStorageServiceTest {

    private final CapturingStorage storage = new CapturingStorage();
    private final FileStorageService service = new FileStorageService(
            storage,
            5 * 1024 * 1024,
            6000,
            6000,
            24_000_000);

    @Test
    void storesDecodedPngWithServerOwnedNameAndMetadata() throws IOException {
        byte[] png = createImage("png", 32, 18);
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../avatar.png", "image/png", png);

        FileStorageService.StoredImage result = service.store(file, UploadFolder.AVATAR);

        assertTrue(result.objectKey().matches("avatar/[0-9a-f-]{36}\\.png"));
        assertEquals("https://media.example/" + result.objectKey(), result.url());
        assertEquals("image/png", result.contentType());
        assertEquals(32, result.width());
        assertEquals(18, result.height());
        assertEquals(png.length, result.size());
        assertArrayEquals(png, storage.content);
    }

    @Test
    void storesDecodedJpeg() throws IOException {
        byte[] jpeg = createImage("jpg", 24, 16);

        FileStorageService.StoredImage result = service.store(
                new MockMultipartFile("file", "room.jpg", "image/jpeg", jpeg),
                UploadFolder.ROOM_TYPES);

        assertEquals("image/jpeg", result.contentType());
        assertEquals(24, result.width());
        assertEquals(16, result.height());
        assertTrue(result.objectKey().startsWith("room_types/"));
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> service.store(file, UploadFolder.AVATAR));

        assertTrue(error.getMessage().contains("không được để trống"));
    }

    @Test
    void rejectsFileAboveConfiguredLimitBeforeWriting() {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.png", "image/png", oversized);

        assertThrows(ValidationException.class, () -> service.store(file, UploadFolder.FACILITIES));
        assertNull(storage.content);
    }

    @Test
    void rejectsDeclaredMimeThatDoesNotMatchBytes() throws IOException {
        byte[] png = createImage("png", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", png);

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> service.store(file, UploadFolder.GALLERY));

        assertTrue(error.getMessage().contains("không khớp"));
    }

    @Test
    void rejectsForgedOrTruncatedPngEvenWhenMagicBytesMatch() {
        byte[] forged = new byte[24];
        byte[] signature = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, forged, 0, signature.length);
        forged[12] = 'I'; forged[13] = 'H'; forged[14] = 'D'; forged[15] = 'R';
        forged[19] = 1;
        forged[23] = 1;

        assertThrows(ValidationException.class, () -> service.store(
                new MockMultipartFile("file", "forged.png", "image/png", forged),
                UploadFolder.FACILITIES));
    }

    @Test
    void rejectsDecodedImageAbovePixelPolicy() throws IOException {
        byte[] png = createImage("png", 20, 20);
        FileStorageService strict = new FileStorageService(storage, 5 * 1024 * 1024, 100, 100, 300);

        ValidationException error = assertThrows(ValidationException.class, () -> strict.store(
                new MockMultipartFile("file", "large-pixels.png", "image/png", png),
                UploadFolder.ROOM_TYPES));

        assertTrue(error.getMessage().contains("điểm ảnh"));
    }

    @Test
    void storesValidatedPdfForRefundProofWithoutImageDimensions() throws IOException {
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);

        FileStorageService.StoredImage result = service.store(
                new MockMultipartFile("file", "refund-proof.pdf", "application/pdf", pdf),
                UploadFolder.REFUND_PROOFS);

        assertEquals("application/pdf", result.contentType());
        assertTrue(result.objectKey().matches("refund_proofs/[0-9a-f-]{36}\\.pdf"));
        assertNull(result.width());
        assertNull(result.height());
        assertArrayEquals(pdf, storage.content);
    }

    @Test
    void rejectsPdfOutsideRefundProofFolder() {
        byte[] pdf = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        ValidationException error = assertThrows(ValidationException.class, () -> service.store(
                new MockMultipartFile("file", "avatar.pdf", "application/pdf", pdf),
                UploadFolder.AVATAR));

        assertTrue(error.getMessage().contains("minh chứng hoàn tiền"));
        assertNull(storage.content);
    }

    private static byte[] createImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, Color.DARK_GRAY.getRGB());
        image.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IllegalStateException("Missing test image writer for " + format);
        }
        return output.toByteArray();
    }

    private static class CapturingStorage implements UploadStorage {
        private byte[] content;

        @Override
        public StoredObject store(String objectKey, byte[] content, String contentType) {
            this.content = content;
            return new StoredObject(objectKey, "https://media.example/" + objectKey);
        }

        @Override
        public void delete(String objectKey) {
            this.content = null;
        }
    }
}
