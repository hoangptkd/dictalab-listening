package com.dictalab.scenario;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate generation rollout from the previously released goal API. */
@ConfigurationProperties("dictalab.scenario.engine")
public record ScenarioEngineProperties(boolean enabled, int programVersion, String approvedProgramHash,
                                       String approvedPromptHash, boolean localPreviewEnabled,
                                       java.util.Set<Long> localPreviewUsers) {
    public ScenarioEngineProperties {
        localPreviewUsers = localPreviewUsers == null ? java.util.Set.of() : java.util.Set.copyOf(localPreviewUsers);
        if (localPreviewUsers.stream().anyMatch(id -> id <= 0)) throw new IllegalArgumentException("Invalid preview user");
        if (programVersion == 0) programVersion = 1;
        if (programVersion < 1) throw new IllegalArgumentException("Invalid scenario program version");
        if (approvedProgramHash == null) approvedProgramHash = "";
        if (approvedPromptHash == null) approvedPromptHash = "";
        if (!approvedProgramHash.isEmpty() && !approvedProgramHash.matches("[a-f0-9]{64}")
                || !approvedPromptHash.isEmpty() && !approvedPromptHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Scenario release hashes must be SHA-256");
    }
}
