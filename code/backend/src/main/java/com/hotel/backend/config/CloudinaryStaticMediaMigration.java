package com.hotel.backend.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Công cụ migration chạy một lần để chuyển bộ ảnh seed trong
 * {@code src/main/resources/static} lên Cloudinary.
 *
 * <p>Runner có thứ tự 0 để upload xong toàn bộ ảnh trước khi
 * {@link DataSeeder} (thứ tự 1) cập nhật URL trong database. Public ID giữ
 * nguyên cấu trúc thư mục/tên file và cho phép overwrite, nên có thể chạy lại
 * an toàn nếu lần chạy trước bị ngắt.</p>
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "app.upload.static-migration-enabled", havingValue = "true")
@Slf4j(topic = "CLOUDINARY-STATIC-MEDIA-MIGRATION")
public class CloudinaryStaticMediaMigration implements CommandLineRunner {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final List<String> STATIC_DIRECTORIES = List.of(
            "avatar",
            "facilities",
            "galeries",
            "room_types");

    private final Cloudinary cloudinary;
    private final String rootFolder;
    private final String storageProvider;
    private final PathMatchingResourcePatternResolver resourceResolver =
            new PathMatchingResourcePatternResolver();

    public CloudinaryStaticMediaMigration(
            @Value("${app.upload.cloudinary.cloud-name:}") String cloudName,
            @Value("${app.upload.cloudinary.api-key:}") String apiKey,
            @Value("${app.upload.cloudinary.api-secret:}") String apiSecret,
            @Value("${app.upload.cloudinary.folder:hotel-media}") String rootFolder,
            @Value("${app.upload.storage:local}") String storageProvider) {
        this.storageProvider = requireValue(storageProvider, "app.upload.storage");
        this.rootFolder = normalizeFolder(rootFolder);
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", requireValue(cloudName, "app.upload.cloudinary.cloud-name"),
                "api_key", requireValue(apiKey, "app.upload.cloudinary.api-key"),
                "api_secret", requireValue(apiSecret, "app.upload.cloudinary.api-secret"),
                "secure", true));
    }

    @Override
    public void run(String... args) throws Exception {
        if (!"cloudinary".equalsIgnoreCase(storageProvider)) {
            throw new IllegalStateException(
                    "Static media migration requires APP_UPLOAD_STORAGE=cloudinary");
        }

        int uploaded = 0;
        for (String directory : STATIC_DIRECTORIES) {
            Resource[] resources = resourceResolver.getResources(
                    "classpath*:static/" + directory + "/*.*");
            for (Resource resource : resources) {
                if (!resource.isReadable() || !isSupported(resource.getFilename())) {
                    continue;
                }
                upload(directory, resource);
                uploaded++;
            }
        }

        if (uploaded == 0) {
            throw new IllegalStateException("No static images were found for Cloudinary migration");
        }
        log.info("STATIC_MEDIA_MIGRATION_COMPLETED uploaded={}", uploaded);
    }

    private void upload(String directory, Resource resource) throws IOException {
        String filename = requireValue(resource.getFilename(), "static resource filename");
        String publicId = rootFolder + "/static/" + directory + "/" + withoutExtension(filename);
        byte[] content;
        try (var input = resource.getInputStream()) {
            content = input.readAllBytes();
        }
        if (content.length == 0) {
            throw new IOException("Static image is empty: " + directory + "/" + filename);
        }

        try {
            Map<?, ?> result = cloudinary.uploader().upload(content, ObjectUtils.asMap(
                    "resource_type", "image",
                    "public_id", publicId,
                    "overwrite", true,
                    "invalidate", true,
                    "unique_filename", false,
                    "use_filename", false,
                    "tags", "hotel-management,seed-static"));
            Object secureUrl = result.get("secure_url");
            if (!(secureUrl instanceof String url) || url.isBlank()) {
                throw new IOException("Cloudinary returned no secure URL for " + publicId);
            }
            log.info("Uploaded static image publicId={}", publicId);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Unable to upload static image " + publicId, exception);
        }
    }

    private static boolean isSupported(String filename) {
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 && SUPPORTED_EXTENSIONS.contains(
                filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String withoutExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
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
