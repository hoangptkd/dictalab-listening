package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("dictalab.media")
public record MediaStorageProperties(Path root, DataSize maxFileSize, S3 s3) {

    public MediaStorageProperties {
        root = root == null ? Path.of("data", "media") : root;
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(25) : maxFileSize;
        if (maxFileSize.toBytes() <= 0) throw new IllegalArgumentException("Media max-file-size must be positive");
        s3 = s3 == null ? new S3(false, null, null, null, null) : s3;
    }

    public record S3(boolean enabled, String bucket, String region, String prefix, Duration signedUrlDuration) {
        public S3 {
            bucket = bucket == null ? "" : bucket.trim();
            region = region == null ? "" : region.trim();
            prefix = prefix == null ? "dictalab/audio/" : prefix.trim();
            if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";
            signedUrlDuration = signedUrlDuration == null ? Duration.ofMinutes(30) : signedUrlDuration;
            if (enabled) {
                if (bucket.isEmpty() || region.isEmpty()) {
                    throw new IllegalArgumentException("S3 bucket and region are required when media S3 is enabled");
                }
                if (!prefix.isEmpty() && !prefix.matches("[A-Za-z0-9][A-Za-z0-9/_-]*")) {
                    throw new IllegalArgumentException("Invalid S3 audio prefix");
                }
                if (signedUrlDuration.compareTo(Duration.ofSeconds(1)) < 0
                        || signedUrlDuration.compareTo(Duration.ofHours(1)) > 0) {
                    throw new IllegalArgumentException("S3 signed URL duration must be between 1 second and 1 hour");
                }
            }
        }
    }
}

