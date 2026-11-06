package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class LocalQaAccountTest {
    @Test void onlyTheConfiguredLocalAccountIsUnlimited() {
        var account = new LocalQaAccount(new MockEnvironment().withProperty("spring.profiles.active", "local"), 14);
        assertThat(account.unlimited(14L)).isTrue();
        assertThat(account.unlimited(3L)).isFalse();
        assertThat(account.unlimited(null)).isFalse();
    }

    @Test void missingConfigurationAndNonLocalOrProductionProfilesCannotEnableTheExemption() {
        for (String profiles : new String[]{"", "production", "prod", "local,production", "local,aurora"}) {
            var account = new LocalQaAccount(new MockEnvironment().withProperty("spring.profiles.active", profiles), 14);
            assertThat(account.unlimited(14L)).as(profiles).isFalse();
        }
        assertThat(new LocalQaAccount(new MockEnvironment().withProperty("spring.profiles.active", "local"), 0).unlimited(14L)).isFalse();
        assertThatThrownBy(() -> new LocalQaAccount(new MockEnvironment(), -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
