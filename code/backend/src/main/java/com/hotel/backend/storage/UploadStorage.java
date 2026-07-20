package com.hotel.backend.storage;

import java.io.IOException;

/**
 * Storage boundary for uploaded media.
 *
 * <p>The application keeps validation and business ownership outside the
 * storage adapter, so local disk, Amazon S3, MinIO and Cloudflare R2 share the
 * same upload contract.</p>
 */
public interface UploadStorage {

    StoredObject store(String objectKey, byte[] content, String contentType) throws IOException;

    void delete(String objectKey) throws IOException;

    record StoredObject(String objectKey, String url) {
    }
}
