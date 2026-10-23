package com.dictalab.service;

import com.dictalab.config.GeminiProviderProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;

/** Startup snapshot shared with Python; no credential-returning web endpoint. */
@Component
public class AiProviderRegistry {
    private final JdbcTemplate jdbc;
    private final boolean database;
    private final String master;
    private Map<String, GeminiProviderProperties> pools;
    private final Map<String, String> models = new HashMap<>();

    public AiProviderRegistry(JdbcTemplate jdbc, @Value("${AI_PROVIDER_CONFIG_SOURCE:env}") String source,
                              @Value("${AI_PROVIDER_ENCRYPTION_KEY:}") String master) {
        if (!Set.of("env", "database").contains(source)) throw new IllegalArgumentException("Invalid provider config source");
        this.jdbc = jdbc; this.database = source.equals("database"); this.master = master;
    }

    public boolean databaseEnabled() { return database; }

    public synchronized GeminiProviderProperties properties(String platform) {
        if (!database) throw new IllegalStateException("Database provider registry is disabled");
        if (pools == null) load();
        var result = pools.get(platform);
        if (result == null) throw unavailable();
        return result;
    }

    public synchronized String model(String platform) { properties(platform); return models.get(platform); }

    private void load() {
        try {
            if (Base64.getDecoder().decode(master).length != 32) throw unavailable();
            var rows = jdbc.queryForList("""
                SELECT p.platform,p.default_model,p.global_rpm_limit,p.global_rpd_limit,p.enabled AS pool_enabled,
                       a.id,a.account_id,a.model,a.endpoint,a.api_key_ciphertext,a.rpm_limit,a.operating_rpm,
                       a.tpm_limit,a.rpd_limit,a.enabled,a.priority
                FROM ai_provider_pools p LEFT JOIN ai_provider_accounts a ON a.platform=p.platform
                ORDER BY p.platform,a.priority,a.id
                """);
            var accounts = new HashMap<String, List<GeminiProviderProperties.Provider>>();
            var policies = new HashMap<String, Map<String,Object>>();
            for (var row : rows) {
                String platform = (String)row.get("platform");
                if (!Set.of("gemini", "deepseek", "openai_compatible").contains(platform)) throw unavailable();
                policies.put(platform, row);
                accounts.computeIfAbsent(platform, ignored -> new ArrayList<>());
                if (row.get("id") == null) continue;
                String endpoint = (String)row.get("endpoint");
                if (!URI.create(endpoint).getPath().endsWith("/chat/completions")) throw unavailable();
                String host = URI.create(endpoint).getHost();
                String expected = Map.of("gemini", "generativelanguage.googleapis.com", "deepseek", "api.deepseek.com").get(platform);
                if (expected != null && !Set.of(expected,"localhost","127.0.0.1","[::1]").contains(host)) throw unavailable();
                accounts.get(platform).add(new GeminiProviderProperties.Provider((String)row.get("id"),
                    (String)row.get("account_id"), decrypt(master,row), (String)row.get("model"), endpoint,
                    number(row,"rpm_limit"),number(row,"tpm_limit"),number(row,"rpd_limit"),number(row,"operating_rpm"),
                    Boolean.TRUE.equals(row.get("enabled")) && Boolean.TRUE.equals(row.get("pool_enabled")),number(row,"priority")));
            }
            var loaded = new HashMap<String, GeminiProviderProperties>();
            for (var entry : policies.entrySet()) {
                var row = entry.getValue();
                var sources = accounts.get(entry.getKey());
                loaded.put(entry.getKey(), new GeminiProviderProperties(sources, number(row,"global_rpm_limit"),
                    number(row,"global_rpd_limit"), sources.stream().anyMatch(GeminiProviderProperties.Provider::enabled), "redis"));
                String model = (String)row.get("default_model");
                if (!model.matches("[a-zA-Z0-9_.-]{1,100}")) throw unavailable();
                models.put(entry.getKey(),model);
            }
            if (loaded.isEmpty()) throw unavailable();
            pools = Map.copyOf(loaded);
        } catch (Exception invalid) { throw unavailable(); }
    }

    static String decrypt(String master, Map<String,Object> row) {
        try {
            String envelope = (String)row.get("api_key_ciphertext");
            if (!envelope.startsWith("v1:")) throw unavailable();
            byte[] raw = Base64.getDecoder().decode(envelope.substring(3));
            byte[] key = Base64.getDecoder().decode(master);
            if (key.length != 32 || raw.length < 29) throw unavailable();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,raw,0,12));
            String aad = String.join("\n","dictalab-ai-provider:v1",(String)row.get("id"),(String)row.get("platform"),
                (String)row.get("account_id"),(String)row.get("model"),(String)row.get("endpoint"));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(raw,12,raw.length-12),StandardCharsets.UTF_8);
        } catch (Exception invalid) { throw unavailable(); }
    }

    private static int number(Map<String,Object> row,String name) { return ((Number)row.get(name)).intValue(); }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("AI provider database unavailable or invalid; environment fallback is disabled");
    }
}
