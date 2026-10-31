package com.dictalab.scenario;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Enables catalog/declared goals only. Generation is not a capability of this release. */
@ConfigurationProperties("dictalab.scenario")
public record ScenarioProperties(boolean enabled) {}
