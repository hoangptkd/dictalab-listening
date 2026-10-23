package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiProviderRegistryTest {
    @Test void pythonEnvelopeAndIdentityBinding() throws Exception {
        // Generated with Python AESGCM, nonce=bytes(range(12)), key=bytes(range(32)).
        var row = new HashMap<String,Object>();
        row.put("id","ds1"); row.put("platform","deepseek"); row.put("account_id","account1");
        row.put("model","deepseek-flash"); row.put("endpoint","https://api.deepseek.com/chat/completions");
        row.put("api_key_ciphertext","v1:AAECAwQFBgcICQoLIWO9fuiRp2j5bOTu0psdGf6Oj9LoxIYsut6pnWxuOgE=");
        String key = Base64.getEncoder().encodeToString(java.util.stream.IntStream.range(0,32)
            .collect(java.io.ByteArrayOutputStream::new,(b,i)->b.write(i),(a,b)->a.writeBytes(b.toByteArray())).toByteArray());
        assertThat(AiProviderRegistry.decrypt(key,row)).isEqualTo("fake-test-secret");
        row.put("endpoint","https://api.deepseek.com/other");
        assertThatThrownBy(()->AiProviderRegistry.decrypt(key,row)).hasMessageContaining("fallback is disabled")
            .hasNoCause();
    }

    @Test void databaseFailureNeverFallsBack() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:registry-fail"));
        var registry = new AiProviderRegistry(jdbc,"database",Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(()->registry.properties("gemini")).hasMessageContaining("environment fallback is disabled").hasNoCause();
    }

    @Test void loadsEncryptedSnapshotAndRejectsTamperedEndpoint() {
        var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:h2:mem:registry-load","sa","",true);
        try {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/migration/V42__ai_provider_accounts.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO ai_provider_pools(platform,default_model,global_rpm_limit,global_rpd_limit,enabled) VALUES ('deepseek','deepseek-flash',13,100,true)");
        jdbc.update("""
            INSERT INTO ai_provider_accounts(id,platform,account_id,model,endpoint,api_key_ciphertext,
                rpm_limit,operating_rpm,tpm_limit,rpd_limit,enabled,priority)
            VALUES ('ds1','deepseek','account1','deepseek-flash','https://api.deepseek.com/chat/completions',?,13,13,0,100,true,0)
            ""","v1:AAECAwQFBgcICQoLIWO9fuiRp2j5bOTu0psdGf6Oj9LoxIYsut6pnWxuOgE=");
        byte[] raw = new byte[32]; for (int i=0;i<32;i++) raw[i]=(byte)i;
        String key = Base64.getEncoder().encodeToString(raw);
        var registry = new AiProviderRegistry(jdbc,"database",key);
        assertThat(registry.properties("deepseek").enabledProviders()).hasSize(1);
        assertThat(registry.properties("deepseek").enabledProviders().get(0).apiKey()).isEqualTo("fake-test-secret");
        assertThat(registry.model("deepseek")).isEqualTo("deepseek-flash");
        jdbc.update("UPDATE ai_provider_accounts SET endpoint='https://api.deepseek.com/v1/chat/completions'");
        assertThatThrownBy(()->new AiProviderRegistry(jdbc,"database",key).properties("deepseek"))
            .hasMessageContaining("environment fallback is disabled").hasNoCause();
        } finally { ds.destroy(); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="AI_REGISTRY_MIGRATE_LOCAL",matches="true")
    void migrateAndReadLocalApplicationDatabase() {
        var env = System.getenv();
        String url = "jdbc:postgresql://127.0.0.1:"+env.get("POSTGRES_PORT")+"/"+env.get("POSTGRES_DB");
        var ds = new DriverManagerDataSource(url,env.get("POSTGRES_USER"),env.get("POSTGRES_PASSWORD"));
        org.flywaydb.core.Flyway.configure().dataSource(ds)
            .locations("classpath:db/migration","classpath:db/vendor/postgresql").load().migrate();
        if ("database".equals(env.get("AI_PROVIDER_CONFIG_SOURCE"))) {
            var registry = new AiProviderRegistry(new JdbcTemplate(ds),"database",env.get("AI_PROVIDER_ENCRYPTION_KEY"));
            assertThat(registry.properties("gemini").enabledProviders()).hasSize(3);
            assertThat(registry.properties("deepseek").enabledProviders()).hasSize(1);
            assertThat(registry.model("deepseek")).isEqualTo("deepseek-flash");
        }
    }
}
