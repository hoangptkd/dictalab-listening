package com.dictalab.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** A single explicitly configured local test account can exercise paid features without app quotas. */
@Component
public final class LocalQaAccount {
    private final long userId;

    public LocalQaAccount(Environment environment,
                          @Value("${dictalab.local-qa-user-id:0}") long configuredUserId) {
        if (configuredUserId < 0) throw new IllegalArgumentException("Invalid local QA account");
        boolean local = environment.acceptsProfiles(Profiles.of("local"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production", "aurora", "aurora-demo"));
        this.userId = local ? configuredUserId : 0;
    }

    public boolean unlimited(Long candidate) {
        return userId > 0 && candidate != null && candidate == userId;
    }
}
