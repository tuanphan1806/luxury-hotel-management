package com.hotel.backend.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Production image storage backed by Cloudinary.
 *
 * <p>Uploads remain server-signed: the API secret never reaches the browser.
 * Cloudinary returns an HTTPS CDN URL while the stored object key is its
 * public_id, which is also used by the orphan cleanup job.</p>
 */
@Component
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "cloudinary")
@Slf4j(topic = "CLOUDINARY-UPLOAD-STORAGE")
public class CloudinaryUploadStorage implements UploadStorage {

    private final Cloudinary cloudinary;
    private final String rootFolder;
    private final String cloudName;

    public CloudinaryUploadStorage(
            @Value("${app.upload.cloudinary.cloud-name:}") String cloudName,
            @Value("${app.upload.cloudinary.api-key:}") String apiKey,
            @Value("${app.upload.cloudinary.api-secret:}") String apiSecret,
            @Value("${app.upload.cloudinary.folder:hotel-media}") String rootFolder) {
        this.cloudName = requireValue(cloudName, "app.upload.cloudinary.cloud-name");
        this.rootFolder = normalizeFolder(rootFolder);
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", this.cloudName,
                "api_key", requireValue(apiKey, "app.upload.cloudinary.api-key"),
                "api_secret", requireValue(apiSecret, "app.upload.cloudinary.api-secret"),
                "secure", true));
    }

    @PostConstruct
    public void validateConfiguration() {
        // Fail before accepting traffic if a production secret was omitted.
        log.info("Cloudinary upload storage configured cloudName={} folder={}", cloudName, rootFolder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public StoredObject store(String objectKey, byte[] content, String contentType) throws IOException {
        String publicId = rootFolder + "/" + withoutExtension(validateRelativeKey(objectKey));
        try {
            Map<String, Object> result = cloudinary.uploader().upload(content, ObjectUtils.asMap(
                    "resource_type", "image",
                    "public_id", publicId,
                    "overwrite", false,
                    "unique_filename", false,
                    "use_filename", false,
                    "tags", "hotel-management,managed-upload"));

            Object secureUrl = result.get("secure_url");
            Object returnedPublicId = result.get("public_id");
            if (!(secureUrl instanceof String url) || url.isBlank()
                    || !(returnedPublicId instanceof String storedPublicId) || storedPublicId.isBlank()) {
                throw new IOException("Cloudinary returned an incomplete upload response");
            }
            log.info("Stored Cloudinary image publicId={}", storedPublicId);
            return new StoredObject(storedPublicId, url);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Unable to store image in Cloudinary", exception);
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        try {
            Map<?, ?> result = cloudinary.uploader().destroy(objectKey, ObjectUtils.asMap(
                    "resource_type", "image",
                    "invalidate", true));
            Object status = result.get("result");
            if (!"ok".equals(status) && !"not found".equals(status)) {
                throw new IOException("Cloudinary refused to delete asset: " + status);
            }
            log.info("Deleted Cloudinary image publicId={} status={}", objectKey, status);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Unable to delete image from Cloudinary", exception);
        }
    }

    private static String withoutExtension(String value) {
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        return dot > slash ? value.substring(0, dot) : value;
    }

    private static String validateRelativeKey(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("..") || value.contains("\\")) {
            throw new IllegalArgumentException("Invalid upload object key");
        }
        return value;
    }

    private static String normalizeFolder(String value) {
        String normalized = requireValue(value, "app.upload.cloudinary.folder")
                .replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("app.upload.cloudinary.folder is invalid");
        }
        return normalized;
    }

    private static String requireValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value.trim();
    }
}
