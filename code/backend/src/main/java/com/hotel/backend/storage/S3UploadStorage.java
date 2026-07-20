package com.hotel.backend.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.net.URI;

/**
 * S3-compatible production storage. Credentials are deliberately resolved by
 * the AWS default provider chain (environment, workload identity, instance
 * role, ...); secrets are never read from application.yml defaults.
 */
@Component
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "s3")
@Slf4j(topic = "S3-UPLOAD-STORAGE")
public class S3UploadStorage implements UploadStorage {

    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final String bucket;
    private final String keyPrefix;
    private final String publicBaseUrl;
    private final boolean verifyBucketOnStartup;
    private final boolean serverSideEncryption;
    private final S3Client client;

    public S3UploadStorage(
            @Value("${app.upload.s3.bucket:}") String bucket,
            @Value("${app.upload.s3.region:ap-southeast-1}") String region,
            @Value("${app.upload.s3.endpoint:}") String endpoint,
            @Value("${app.upload.s3.path-style-access:false}") boolean pathStyleAccess,
            @Value("${app.upload.s3.key-prefix:}") String keyPrefix,
            @Value("${app.upload.s3.verify-bucket-on-startup:true}") boolean verifyBucketOnStartup,
            @Value("${app.upload.s3.server-side-encryption:false}") boolean serverSideEncryption,
            @Value("${app.upload.base-url}") String publicBaseUrl) {
        this.bucket = requireValue(bucket, "app.upload.s3.bucket");
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
        this.verifyBucketOnStartup = verifyBucketOnStartup;
        this.serverSideEncryption = serverSideEncryption;

        var builder = S3Client.builder()
                .region(Region.of(requireValue(region, "app.upload.s3.region")))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        this.client = builder.build();
    }

    @PostConstruct
    public void initialize() {
        if (!verifyBucketOnStartup) {
            return;
        }
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            throw new IllegalStateException(
                    "Cannot access configured upload bucket '" + bucket + "'. Check IAM, region and endpoint.",
                    exception);
        }
    }

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) throws IOException {
        String actualKey = keyPrefix + validateRelativeKey(objectKey);
        try {
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(actualKey)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .cacheControl(IMMUTABLE_CACHE_CONTROL);
            if (serverSideEncryption) {
                request.serverSideEncryption(ServerSideEncryption.AES256);
            }
            client.putObject(request.build(), RequestBody.fromBytes(content));
            log.info("Stored S3 upload bucket={} objectKey={}", bucket, actualKey);
            return new StoredObject(actualKey, publicBaseUrl + "/" + actualKey);
        } catch (S3Exception exception) {
            throw new IOException("Unable to store image in object storage", exception);
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
            log.info("Deleted S3 upload bucket={} objectKey={}", bucket, objectKey);
        } catch (S3Exception exception) {
            throw new IOException("Unable to delete image from object storage", exception);
        }
    }

    @PreDestroy
    public void close() {
        client.close();
    }

    private static String validateRelativeKey(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("..") || value.contains("\\")) {
            throw new IllegalArgumentException("Invalid upload object key");
        }
        return value;
    }

    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized + "/";
    }

    private static String normalizeBaseUrl(String value) {
        return requireValue(value, "app.upload.base-url").replaceAll("/+$", "");
    }

    private static String requireValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value.trim();
    }
}
