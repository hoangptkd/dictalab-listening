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
        assertThat(registry.definitions()).hasSize(6).allMatch(p -> p.status() == ScenarioProgram.Status.DRAFT);
        var general = registry.require("general-communication", 1);
        assertThat(general.difficultyProfiles()).hasSize(3);
        assertThatThrownBy(() -> general.targetScales().get(0).labels().add("A1")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> general.difficultyProfiles().get(0).skills().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.definitions().stream().filter(p -> p.version()==1)).allMatch(p -> p.supportedPracticeModes().isEmpty() && p.promptArtifactRefs().isEmpty());
        assertThat(registry.definitions()).allMatch(p -> p.promptArtifactRefs().isEmpty());
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

    @Test void legacyCanonicalDefinitionsKeepTheirExactFingerprint() throws Exception {
        var registry=new ScenarioProgramRegistry();
        for (var original : ScenarioJson.MAPPER.readTree(resource("/scenario/programs-v1.json"))) {
            var stored=ScenarioJson.MAPPER.valueToTree(registry.require(original.path("code").asText(),1));
            assertThat(ScenarioJson.canonical(stored)).isEqualTo(ScenarioJson.canonical(original));
        }
    }

    @Test void practiceUsesDeclaredCurrentLevelOrTargetWithoutConvertingOverall() throws Exception {
        var registry=new ScenarioProgramRegistry();
        for (String code : java.util.List.of("general-communication","toeic-listening","ielts-listening")) {
            var program=registry.require(code,2);
            var scale=program.targetScales().stream().filter(s -> s.component().equals("LISTENING")).findFirst().orElseThrow();
            String target=code.equals("general-communication") ? "challenge" : code.equals("toeic-listening") ? "450" : "7.5";
            String current=code.equals("general-communication") ? "supported" : code.equals("toeic-listening") ? "200" : "4.5";
            var goal=new ScenarioGoalRequest.Target(scale.scaleId(),"LISTENING",target);
            var context=ScenarioPersonalization.context(program,goal,current,"reason");
            assertThat(context.path("target").path("value").asText()).isEqualTo(target);
            assertThat(context.path("profile").path("id").asText()).isEqualTo("supported");
            assertThat(context.path("currentProvenance").asText()).isEqualTo("USER_DECLARED");
            assertThat(ScenarioPersonalization.context(program,goal,"","supported-inference").path("profile").path("id").asText()).isEqualTo("challenge");
            assertThatThrownBy(() -> ScenarioPersonalization.context(program,goal,current,"supported-inference")).isInstanceOf(ScenarioFailure.class);
            assertThatThrownBy(() -> ScenarioPersonalization.context(program,new ScenarioGoalRequest.Target(scale.scaleId(),"OVERALL",target),null,"detail")).isInstanceOf(ScenarioFailure.class);
            assertThatThrownBy(() -> ScenarioPersonalization.context(program,goal,"invalid","detail")).isInstanceOf(ScenarioFailure.class);
        }
    }
}
