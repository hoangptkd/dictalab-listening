package com.dictalab.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dictalab.media.s3.enabled", havingValue = "true")
public class S3MediaConfig {
    @Bean(destroyMethod = "close")
    public S3Client mediaS3Client(MediaStorageProperties properties) {
        // Use the SDK credential chain: service environment/profile or IAM role, never frontend keys.
        return S3Client.builder().region(Region.of(properties.s3().region()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(45))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        // An uncertain PUT must not be replayed; DELETE recovery has durable backoff.
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner mediaS3Presigner(MediaStorageProperties properties) {
        return S3Presigner.builder().region(Region.of(properties.s3().region())).build();
    }
}
