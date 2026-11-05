package com.dictalab.scenario;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Catalog/goal rollout. Generation additionally requires the independent engine release gate. */
@ConfigurationProperties("dictalab.scenario")
public record ScenarioProperties(boolean enabled) {}
