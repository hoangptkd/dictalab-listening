package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

@ConfigurationProperties("dictalab.media")
public record MediaStorageProperties(Path root, DataSize maxFileSize) {

    public MediaStorageProperties {
        root = root == null ? Path.of("data", "media") : root;
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(25) : maxFileSize;
    }
}

