package com.dictalab.scenario;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate generation rollout from the previously released goal API. */
@ConfigurationProperties("dictalab.scenario.engine")
public record ScenarioEngineProperties(boolean enabled, int programVersion, String approvedProgramHash,
                                       String approvedPromptHash, boolean localPreviewEnabled,
                                       java.util.Set<Long> localPreviewUsers, java.util.Map<String,String> approvedPrograms, String ttsRecipeJson) {
    public ScenarioEngineProperties(boolean enabled, int programVersion, String approvedProgramHash,
                                    String approvedPromptHash, boolean localPreviewEnabled, java.util.Set<Long> localPreviewUsers) {
        this(enabled,programVersion,approvedProgramHash,approvedPromptHash,localPreviewEnabled,localPreviewUsers,java.util.Map.of(),"");
    }
    public ScenarioEngineProperties(boolean enabled, int programVersion, String approvedProgramHash,
                                    String approvedPromptHash, boolean localPreviewEnabled, java.util.Set<Long> localPreviewUsers, java.util.Map<String,String> approvedPrograms) {
        this(enabled,programVersion,approvedProgramHash,approvedPromptHash,localPreviewEnabled,localPreviewUsers,approvedPrograms,"");
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ScenarioEngineProperties {
        ttsRecipeJson=ttsRecipeJson==null ? "" : ttsRecipeJson;
        parseTtsRecipe(ttsRecipeJson);
        approvedPrograms = approvedPrograms == null ? java.util.Map.of() : java.util.Map.copyOf(approvedPrograms);
        if (approvedPrograms.entrySet().stream().anyMatch(e -> !e.getKey().matches("[a-z][a-z0-9-]{0,63}:[1-9][0-9]*")
                || !e.getValue().matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("Invalid program approval");
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

    public tools.jackson.databind.JsonNode ttsRecipe() { return parseTtsRecipe(ttsRecipeJson); }

    private static tools.jackson.databind.JsonNode parseTtsRecipe(String source) {
        if (source.isBlank()) return null;
        var recipe=ScenarioJson.MAPPER.readTree(source);
        ScenarioContent.fields(recipe,"id","model","apiVersion","language","outputFormat","voices");
        ScenarioContent.check(ScenarioContent.text(recipe,"id",64).matches("[a-zA-Z0-9_.-]{1,64}")
                && ScenarioContent.text(recipe,"model",100).matches("[a-zA-Z0-9_.-]{1,100}")
                && ScenarioContent.text(recipe,"apiVersion",10).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                && recipe.path("language").asText().equals("en") && recipe.path("outputFormat").asText().equals("wav-pcm16-mono-24000"));
        ScenarioContent.list(recipe.path("voices"),2,2);
        var voices=new java.util.HashSet<String>();
        for (var voice : recipe.path("voices")) ScenarioContent.check(voice.isTextual()
                && voice.asText().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") && voices.add(voice.asText()));
        return recipe;
    }
}
