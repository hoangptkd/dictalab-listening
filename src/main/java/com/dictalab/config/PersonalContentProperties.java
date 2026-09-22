package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("dictalab.personal-content")
public record PersonalContentProperties(boolean enabled, Path root, Path manifest) {

    public PersonalContentProperties {
        root = root == null ? Path.of("data", "personal-content") : root;
        manifest = manifest == null ? root.resolve("manifest.json") : manifest;
    }
}
