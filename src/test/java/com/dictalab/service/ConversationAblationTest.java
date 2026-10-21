package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exports only. Inference is sequential, resumable, and separate from the unit-test timeout. */
@EnabledIfSystemProperty(named="conversation.ablation", matches="true")
class ConversationAblationTest {
    @Test @EnabledIfSystemProperty(named="conversation.ablationAudit", matches="true")
    void savedOutputsUseTheSameJavaNormalization() throws Exception {
        var json = new ObjectMapper();
        for (String stage : java.util.List.of("s4-slots", "s5-temperature")) {
            var lines = Files.readAllLines(Path.of("data/conversation-ablation-20261019/" + stage + "-outputs.jsonl"), StandardCharsets.UTF_8);
            assertEquals(45, lines.size());
            for (String line : lines) {
                var row = json.readTree(line);
                if (row.has("output")) assertEquals(row.path("output"), ConversationLocalClient.normalizeOutput(row.path("rawOutput")));
                else if (row.has("rawOutput")) assertThrows(IllegalStateException.class,
                        () -> ConversationLocalClient.normalizeOutput(row.path("rawOutput")));
            }
        }
    }

    @Test void exportActualRequests() throws Exception {
        var json = new ObjectMapper();
        String stage = System.getProperty("conversation.ablationStage");
        assertTrue(stage != null && stage.matches("[a-z0-9-]{1,60}"));
        var root = Path.of("data/conversation-ablation-20261019");
        var manifest = json.readTree(Files.readString(root.resolve("manifest.json"), StandardCharsets.UTF_8));
        var client = new ConversationLocalClient(json,
                new ConversationGenerationProperties("ollama", null, "qwen3.5:9b", 600),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        var requests = json.createArrayNode();
        for (var item : manifest.path("cases")) {
            var row = requests.addObject().put("id", item.path("id").asText());
            row.set("input", item.path("input"));
            row.set("request", json.readTree(client.requestBody(item.path("level").asText(),
                    json.writeValueAsString(item.path("input")))));
        }
        assertEquals(15, requests.size());
        var document = json.createObjectNode().put("stage", stage)
                .put("promptVersion", ConversationGenerationPrompt.VERSION);
        document.set("requests", requests);
        var path = root.resolve(stage + "-requests.json");
        if (Files.exists(path)) {
            var frozen = json.readTree(Files.readString(path));
            assertTrue(frozen.equals(document), "Frozen stage changed; choose a new stage, never overwrite results");
            for (int i = 0; i < requests.size(); i++) assertEquals(
                    frozen.path("requests").path(i).path("request").path("format").toString(),
                    requests.path(i).path("request").path("format").toString(),
                    "Schema property order changed; do not reuse this stage");
        }
        else Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
    }
}
