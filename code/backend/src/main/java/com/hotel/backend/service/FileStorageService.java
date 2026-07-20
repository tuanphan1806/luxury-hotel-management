package com.hotel.backend.service;

import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.exception.ValidationException;
import com.hotel.backend.storage.UploadStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j(topic = "FILE-STORAGE")
public class FileStorageService {

    private static final Set<String> JPEG_SOF_MARKERS = Set.of(
            "c0", "c1", "c2", "c3", "c5", "c6", "c7", "c9", "ca", "cb", "cd", "ce", "cf");

    private final UploadStorage uploadStorage;
    private final long maxBytes;
    private final int maxWidth;
    private final int maxHeight;
    private final long maxPixels;

    public FileStorageService(
            UploadStorage uploadStorage,
            @Value("${app.upload.max-bytes:5242880}") long maxBytes,
            @Value("${app.upload.max-width:6000}") int maxWidth,
            @Value("${app.upload.max-height:6000}") int maxHeight,
            @Value("${app.upload.max-pixels:24000000}") long maxPixels) {
        this.uploadStorage = uploadStorage;
        this.maxBytes = maxBytes;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxPixels = maxPixels;
    }

    public StoredImage store(MultipartFile file, UploadFolder subFolder) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Tệp không được để trống");
        }
        if (file.getSize() > maxBytes) {
            throw new ValidationException("Kích thước ảnh tối đa là " + formatMegabytes(maxBytes) + " MB");
        }

        byte[] content = file.getBytes();
        if (content.length == 0 || content.length > maxBytes) {
            throw new ValidationException("Kích thước ảnh không hợp lệ");
        }

        if (isPdf(content)) {
            if (subFolder != UploadFolder.REFUND_PROOFS) {
                throw new ValidationException("PDF chỉ được dùng làm minh chứng hoàn tiền");
            }
            validateDeclaredContentType(file.getContentType(), "application/pdf");
            validatePdf(content);
            String relativeKey = subFolder.getPath() + "/" + UUID.randomUUID() + ".pdf";
            UploadStorage.StoredObject stored = uploadStorage.store(
                    relativeKey, content, "application/pdf");
            log.info("Stored validated PDF objectKey={} size={}", stored.objectKey(), content.length);
            return new StoredImage(
                    stored.url(), stored.objectKey(), "application/pdf", content.length, null, null);
        }

        ImageMetadata metadata = inspectImage(content);
        validateDeclaredContentType(file.getContentType(), metadata.contentType());
        validateDimensions(metadata);
        validateDecodableRaster(content, metadata);

        String relativeKey = subFolder.getPath() + "/" + UUID.randomUUID() + "." + metadata.extension();
        UploadStorage.StoredObject stored = uploadStorage.store(relativeKey, content, metadata.contentType());

        log.info("Stored validated image objectKey={} type={} size={} dimensions={}x{}",
                stored.objectKey(), metadata.contentType(), content.length, metadata.width(), metadata.height());
        return new StoredImage(
                stored.url(),
                stored.objectKey(),
                metadata.contentType(),
                content.length,
                metadata.width(),
                metadata.height());
    }

    public void delete(String objectKey) throws IOException {
        uploadStorage.delete(objectKey);
    }

    private ImageMetadata inspectImage(byte[] content) {
        if (isJpeg(content)) {
            int[] dimensions = readJpegDimensions(content);
            return new ImageMetadata("jpg", "image/jpeg", dimensions[0], dimensions[1], true);
        }
        if (isPng(content)) {
            if (content.length < 24 || !matchesAscii(content, 12, "IHDR")) {
                throw invalidImage();
            }
            int width = readIntBigEndian(content, 16);
            int height = readIntBigEndian(content, 20);
            return new ImageMetadata("png", "image/png", width, height, true);
        }
        if (isWebp(content)) {
            int[] dimensions = readWebpDimensions(content);
            return new ImageMetadata("webp", "image/webp", dimensions[0], dimensions[1], false);
        }
        throw new ValidationException("Chỉ chấp nhận ảnh JPG, PNG hoặc WebP hợp lệ");
    }

    private void validateDeclaredContentType(String declared, String detected) {
        if (declared == null || declared.isBlank() || "application/octet-stream".equalsIgnoreCase(declared)) {
            return;
        }
        String normalized = declared.toLowerCase(Locale.ROOT).trim();
        if ("image/jpg".equals(normalized)) {
            normalized = "image/jpeg";
        }
        if (!detected.equals(normalized)) {
            throw new ValidationException("Định dạng khai báo của tệp không khớp với nội dung");
        }
    }

    private void validateDimensions(ImageMetadata metadata) {
        if (metadata.width() <= 0 || metadata.height() <= 0) {
            throw invalidImage();
        }
        long pixels = (long) metadata.width() * metadata.height();
        if (metadata.width() > maxWidth || metadata.height() > maxHeight || pixels > maxPixels) {
            throw new ValidationException(String.format(
                    "Ảnh quá lớn. Kích thước tối đa %dx%d và %,d điểm ảnh",
                    maxWidth, maxHeight, maxPixels));
        }
    }

    /**
     * Java ships decoders for JPEG and PNG. Decode these formats after the
     * cheap header/dimension checks so truncated or forged files are rejected
     * without allowing a decompression bomb to allocate arbitrary memory.
     * WebP is structurally validated below because the JDK has no WebP reader.
     */
    private void validateDecodableRaster(byte[] content, ImageMetadata metadata) {
        if (!metadata.jdkDecodable()) {
            return;
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(content));
            if (decoded == null
                    || decoded.getWidth() != metadata.width()
                    || decoded.getHeight() != metadata.height()) {
                throw invalidImage();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ValidationException validationException) {
                throw validationException;
            }
            throw new ValidationException("Không thể giải mã tệp ảnh; tệp có thể bị hỏng");
        }
    }

    private int[] readJpegDimensions(byte[] content) {
        int cursor = 2;
        while (cursor < content.length) {
            while (cursor < content.length && (content[cursor] & 0xFF) == 0xFF) {
                cursor++;
            }
            if (cursor >= content.length) {
                break;
            }
            int marker = content[cursor++] & 0xFF;
            if (marker == 0xD8 || marker == 0xD9) {
                continue;
            }
            if (marker == 0xDA) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (cursor + 1 >= content.length) {
                throw invalidImage();
            }
            int segmentLength = readUnsignedShortBigEndian(content, cursor);
            if (segmentLength < 2 || cursor + segmentLength > content.length) {
                throw invalidImage();
            }
            if (JPEG_SOF_MARKERS.contains(Integer.toHexString(marker))) {
                if (segmentLength < 7) {
                    throw invalidImage();
                }
                int height = readUnsignedShortBigEndian(content, cursor + 3);
                int width = readUnsignedShortBigEndian(content, cursor + 5);
                return new int[]{width, height};
            }
            cursor += segmentLength;
        }
        throw invalidImage();
    }

    private int[] readWebpDimensions(byte[] content) {
        if (content.length < 30) {
            throw invalidImage();
        }
        long riffLength = readUnsignedIntLittleEndian(content, 4);
        if (riffLength + 8 != content.length) {
            throw invalidImage();
        }
        long firstChunkLength = readUnsignedIntLittleEndian(content, 16);
        if (firstChunkLength <= 0 || 20L + firstChunkLength > content.length) {
            throw invalidImage();
        }

        if (matchesAscii(content, 12, "VP8X")) {
            int width = 1 + readUnsigned24LittleEndian(content, 24);
            int height = 1 + readUnsigned24LittleEndian(content, 27);
            return new int[]{width, height};
        }
        if (matchesAscii(content, 12, "VP8L")) {
            if ((content[20] & 0xFF) != 0x2F) {
                throw invalidImage();
            }
            int b1 = content[21] & 0xFF;
            int b2 = content[22] & 0xFF;
            int b3 = content[23] & 0xFF;
            int b4 = content[24] & 0xFF;
            int width = 1 + (b1 | ((b2 & 0x3F) << 8));
            int height = 1 + ((b2 >> 6) | (b3 << 2) | ((b4 & 0x0F) << 10));
            return new int[]{width, height};
        }
        if (matchesAscii(content, 12, "VP8 ")
                && (content[23] & 0xFF) == 0x9D
                && (content[24] & 0xFF) == 0x01
                && (content[25] & 0xFF) == 0x2A) {
            int width = readUnsignedShortLittleEndian(content, 26) & 0x3FFF;
            int height = readUnsignedShortLittleEndian(content, 28) & 0x3FFF;
            return new int[]{width, height};
        }
        throw invalidImage();
    }

    private static boolean isJpeg(byte[] content) {
        return content.length >= 4
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] content) {
        return content.length >= 8
                && (content[0] & 0xFF) == 0x89
                && content[1] == 0x50 && content[2] == 0x4E && content[3] == 0x47
                && content[4] == 0x0D && content[5] == 0x0A && content[6] == 0x1A && content[7] == 0x0A;
    }

    private static boolean isWebp(byte[] content) {
        return content.length >= 12
                && matchesAscii(content, 0, "RIFF")
                && matchesAscii(content, 8, "WEBP");
    }

    private static boolean isPdf(byte[] content) {
        return content.length >= 8
                && matchesAscii(content, 0, "%PDF-");
    }

    private static void validatePdf(byte[] content) {
        int tailStart = Math.max(0, content.length - 2048);
        boolean eofFound = false;
        for (int offset = tailStart; offset <= content.length - 5; offset++) {
            if (matchesAscii(content, offset, "%%EOF")) {
                eofFound = true;
                break;
            }
        }
        if (!eofFound) {
            throw new ValidationException("Tệp PDF bị hỏng hoặc thiếu marker kết thúc");
        }
    }

    private static boolean matchesAscii(byte[] content, int offset, String value) {
        if (offset < 0 || offset + value.length() > content.length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((byte) value.charAt(index) != content[offset + index]) {
                return false;
            }
        }
        return true;
    }

    private static int readIntBigEndian(byte[] content, int offset) {
        return ((content[offset] & 0xFF) << 24)
                | ((content[offset + 1] & 0xFF) << 16)
                | ((content[offset + 2] & 0xFF) << 8)
                | (content[offset + 3] & 0xFF);
    }

    private static int readUnsignedShortBigEndian(byte[] content, int offset) {
        return ((content[offset] & 0xFF) << 8) | (content[offset + 1] & 0xFF);
    }

    private static int readUnsignedShortLittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xFF) | ((content[offset + 1] & 0xFF) << 8);
    }

    private static int readUnsigned24LittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xFF)
                | ((content[offset + 1] & 0xFF) << 8)
                | ((content[offset + 2] & 0xFF) << 16);
    }

    private static long readUnsignedIntLittleEndian(byte[] content, int offset) {
        return Integer.toUnsignedLong(
                (content[offset] & 0xFF)
                        | ((content[offset + 1] & 0xFF) << 8)
                        | ((content[offset + 2] & 0xFF) << 16)
                        | ((content[offset + 3] & 0xFF) << 24));
    }

    private static ValidationException invalidImage() {
        return new ValidationException("Tệp ảnh bị hỏng hoặc không đúng định dạng");
    }

    private static long formatMegabytes(long bytes) {
        return Math.max(1, bytes / (1024 * 1024));
    }

    private record ImageMetadata(
            String extension,
            String contentType,
            int width,
            int height,
            boolean jdkDecodable) {
    }

    public record StoredImage(
            String url,
            String objectKey,
            String contentType,
            long size,
            Integer width,
            Integer height) {
    }
}
