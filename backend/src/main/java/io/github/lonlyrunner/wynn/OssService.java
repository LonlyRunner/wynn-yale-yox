package io.github.lonlyrunner.wynn;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class OssService {
    @Value("${app.oss.endpoint:}") String endpoint;
    @Value("${app.oss.bucket:}") String bucket;
    @Value("${app.oss.access-key-id:}") String accessKeyId;
    @Value("${app.oss.access-key-secret:}") String accessKeySecret;

    boolean configured() {
        return !endpoint.isBlank() && !bucket.isBlank() && !accessKeyId.isBlank() && !accessKeySecret.isBlank();
    }

    String presignedPutUrl(String objectKey, String contentType) {
        requireConfigured();
        OSS client = client();
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.PUT);
            if (contentType != null && !contentType.isBlank()) request.setContentType(contentType);
            request.setExpiration(new Date(System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()));
            return client.generatePresignedUrl(request).toString();
        } finally {
            client.shutdown();
        }
    }

    String presignedGetUrl(String objectKey) {
        requireConfigured();
        OSS client = client();
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + Duration.ofHours(6).toMillis()));
            return client.generatePresignedUrl(request).toString();
        } finally {
            client.shutdown();
        }
    }

    String presignedDownloadUrl(String objectKey, String filename) {
        requireConfigured();
        OSS client = client();
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()));
            ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
            headers.setContentDisposition("attachment; filename=\"" + filename.replaceAll("[^a-zA-Z0-9._-]", "-") + "\"");
            request.setResponseHeaders(headers);
            return client.generatePresignedUrl(request).toString();
        } finally {
            client.shutdown();
        }
    }

    void put(String objectKey, byte[] bytes, String contentType) {
        requireConfigured();
        OSS client = client();
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            if (contentType != null && !contentType.isBlank()) metadata.setContentType(contentType);
            client.putObject(bucket, objectKey, new ByteArrayInputStream(bytes), metadata);
        } finally {
            client.shutdown();
        }
    }

    boolean exists(String objectKey) {
        requireConfigured();
        OSS client = client();
        try { return client.doesObjectExist(bucket, objectKey); }
        finally { client.shutdown(); }
    }

    byte[] get(String objectKey) {
        requireConfigured();
        OSS client = client();
        try (OSSObject object = client.getObject(bucket, objectKey)) {
            return object.getObjectContent().readAllBytes();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read OSS object", error);
        } finally {
            client.shutdown();
        }
    }

    void delete(String objectKey) {
        requireConfigured();
        OSS client = client();
        try { client.deleteObject(bucket, objectKey); }
        finally { client.shutdown(); }
    }

    private OSS client() { return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret); }
    private void requireConfigured() { if (!configured()) throw new IllegalStateException("OSS is not configured"); }
}
