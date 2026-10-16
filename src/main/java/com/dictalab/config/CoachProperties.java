package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dictalab.coach")
public record CoachProperties(boolean enabled) {}
