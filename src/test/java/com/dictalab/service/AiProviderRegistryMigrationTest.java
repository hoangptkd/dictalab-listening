package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class AiProviderRegistryMigrationTest {
    @Test void forwardMigrationPreservesAccountsAndAllOtherConstraints() throws Exception {
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:registry-upgrade-" + java.util.UUID.randomUUID(), "sa", "", true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V42__ai_provider_accounts.sql")).execute(ds);
            var jdbc = new JdbcTemplate(ds);
            jdbc.update("INSERT INTO ai_provider_pools VALUES ('gemini','fixture-model',0,0,true,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO ai_provider_accounts VALUES ('source','gemini','account','fixture-model','https://example.invalid/v1/chat/completions','unchanged-ciphertext',10,10,0,0,true,0,CURRENT_TIMESTAMP)");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_provider_pools VALUES ('fixture','fixture-model',0,0,true,CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var context = org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
            org.mockito.Mockito.when(context.getConnection()).thenReturn(ds.getConnection());
            new db.migration.V55__Extensible_ai_provider_ids().migrate(context);
            jdbc.update("INSERT INTO ai_provider_pools VALUES ('fixture','fixture-model',0,0,true,CURRENT_TIMESTAMP)");
            assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM ai_provider_accounts WHERE id='source'", String.class))
                .isEqualTo("unchanged-ciphertext");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_provider_pools VALUES ('bad-id','fixture-model',0,0,true,CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE ai_provider_pools SET global_rpm_limit=-1 WHERE platform='gemini'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE ai_provider_accounts SET platform='missing' WHERE id='source'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE ai_provider_accounts SET rpm_limit=0 WHERE id='source'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { ds.destroy(); }
    }
}
