package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class ScenarioProgramTest {
    static byte[] resource(String path) throws Exception {
        try (var in = ScenarioProgramTest.class.getResourceAsStream(path)) { return in.readAllBytes(); }
    }

    static Stream<JsonNode> cases() throws Exception {
        var fixtures = ScenarioJson.MAPPER.readTree(resource("/scenario/goal-contract.json"));
        return java.util.stream.StreamSupport.stream(fixtures.path("cases").spliterator(), false);
    }

    @ParameterizedTest(name = "goal fixture {index}") @MethodSource("cases")
    void sharesExactGoalVerdictWithPython(JsonNode fixture) throws Exception {
        var registry = new ScenarioProgramRegistry();
        boolean accepted;
        try {
            var request = ScenarioJson.read(fixture.path("raw").asText().getBytes(StandardCharsets.UTF_8), ScenarioGoalRequest.class);
            registry.require(request.programCode(), request.programVersion()).validateTarget(request.target());
            accepted = true;
        } catch (RuntimeException invalid) { accepted = false; }
        assertThat(accepted).as(fixture.path("name").asText()).isEqualTo(fixture.path("valid").asBoolean());
    }

    @Test void registryDefinitionsAreDeeplyImmutableAndNotApprovedForGeneration() throws Exception {
        var registry = new ScenarioProgramRegistry();
        assertThat(registry.definitions()).hasSize(3).allMatch(p -> p.status() == ScenarioProgram.Status.DRAFT);
        var general = registry.require("general-communication", 1);
        assertThat(general.difficultyProfiles()).hasSize(3);
        assertThatThrownBy(() -> general.targetScales().get(0).labels().add("A1")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> general.difficultyProfiles().get(0).skills().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.definitions()).allMatch(p -> p.supportedPracticeModes().isEmpty() && p.promptArtifactRefs().isEmpty());
    }

    @Test void refusesHashDriftAndMissingApprovalEvidence() throws Exception {
        var definitions = ScenarioJson.MAPPER.readTree(resource("/scenario/programs-v1.json"));
        var first = (ObjectNode) definitions.get(0);
        first.put("displayNameVi", "Changed without a new version");
        assertThatThrownBy(() -> ScenarioProgramRegistry.load(ScenarioJson.MAPPER.writeValueAsBytes(definitions)))
                .hasMessageContaining("hash mismatch");
        first.put("status", "APPROVED");
        rehash(first);
        assertThatThrownBy(() -> ScenarioProgramRegistry.load(ScenarioJson.MAPPER.writeValueAsBytes(definitions)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void refusesUnknownFieldsDuplicateVersionAndUnsafeBoundsEvenWithNewHash() throws Exception {
        for (var mutation : java.util.List.of("extra", "bounds", "duplicate")) {
            var definitions = (tools.jackson.databind.node.ArrayNode) ScenarioJson.MAPPER.readTree(resource("/scenario/programs-v1.json"));
            var first = (ObjectNode) definitions.get(0);
            if (mutation.equals("extra")) first.put("ignoreQuality", true);
            if (mutation.equals("bounds")) ((ObjectNode) first.path("difficultyProfiles").get(0)).put("maxWordsPerSession", 10000);
            rehash(first);
            if (mutation.equals("duplicate")) definitions.add(first.deepCopy());
            assertThatThrownBy(() -> ScenarioProgramRegistry.load(ScenarioJson.MAPPER.writeValueAsBytes(definitions)))
                    .as(mutation).isInstanceOf(RuntimeException.class);
        }
    }

    static void rehash(ObjectNode program) {
        program.remove("definitionHash");
        program.put("definitionHash", ScenarioJson.hash(ScenarioJson.canonical(program)));
    }
}
