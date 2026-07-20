package com.hotel.backend.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalUploadStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesAtomicallyAndNormalizesPublicBaseUrl() throws Exception {
        LocalUploadStorage storage = new LocalUploadStorage(
                tempDirectory.toString(),
                "https://example.test/uploads///");
        storage.initialize();

        UploadStorage.StoredObject result = storage.store(
                "avatar/test-image.png",
                new byte[]{1, 2, 3},
                "image/png");

        assertEquals("avatar/test-image.png", result.objectKey());
        assertEquals("https://example.test/uploads/avatar/test-image.png", result.url());
        assertArrayEquals(
                new byte[]{1, 2, 3},
                Files.readAllBytes(tempDirectory.resolve("avatar/test-image.png")));
    }

    @Test
    void rejectsTraversalAndDeletesOnlyManagedObject() throws Exception {
        LocalUploadStorage storage = new LocalUploadStorage(tempDirectory.toString(), "https://example.test/uploads");
        storage.initialize();

        assertThrows(IllegalArgumentException.class, () -> storage.store(
                "../outside.png", new byte[]{1}, "image/png"));

        storage.store("gallery/remove.png", new byte[]{4}, "image/png");
        Path stored = tempDirectory.resolve("gallery/remove.png");
        storage.delete("gallery/remove.png");
        assertFalse(Files.exists(stored));
    }
}
