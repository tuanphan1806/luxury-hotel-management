package com.hotel.backend.storage;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "local", matchIfMissing = true)
@Slf4j(topic = "LOCAL-UPLOAD-STORAGE")
public class LocalUploadStorage implements UploadStorage {

    private final Path storageRoot;
    private final String publicBaseUrl;

    public LocalUploadStorage(
            @Value("${app.upload.dir}") String uploadDir,
            @Value("${app.upload.base-url}") String publicBaseUrl) {
        this.storageRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(storageRoot);
            if (!Files.isDirectory(storageRoot) || !Files.isWritable(storageRoot)) {
                throw new IllegalStateException("Upload directory is not writable: " + storageRoot);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot initialize upload directory: " + storageRoot, exception);
        }
    }

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) throws IOException {
        Path target = resolveInsideRoot(objectKey);
        Files.createDirectories(target.getParent());

        Path temporary = target.getParent().resolve("." + UUID.randomUUID() + ".uploading");
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        log.info("Stored local upload objectKey={}", objectKey);
        return new StoredObject(objectKey, publicBaseUrl + "/" + objectKey.replace('\\', '/'));
    }

    @Override
    public void delete(String objectKey) throws IOException {
        Path target = resolveInsideRoot(objectKey);
        if (Files.deleteIfExists(target)) {
            log.info("Deleted local upload objectKey={}", objectKey);
        }
    }

    private Path resolveInsideRoot(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/") || objectKey.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid upload object key");
        }
        Path target = storageRoot.resolve(objectKey).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Upload object key escapes storage root");
        }
        return target;
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("app.upload.base-url must not be blank");
        }
        return value.trim().replaceAll("/+$", "");
    }
}
