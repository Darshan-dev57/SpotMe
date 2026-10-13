package dev.darshan.spotme.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the S3-compatible photo storage, used when spotme.storage-type=s3.
 * endpoint is the bucket server, for example MinIO at http://localhost:9000.
 * linkTtl is how long a signed download link straight to the bucket stays valid.
 */
@ConfigurationProperties(prefix = "spotme.s3")
public record S3Properties(String endpoint, String region, String bucket, String accessKey, String secretKey,
                           Duration linkTtl) {
}
