package com.dictalab.scenario;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;

@Component
public class ScenarioProgramRegistry {
    private final List<ScenarioProgram> programs;

    public ScenarioProgramRegistry() throws IOException {
        try (var input = new ClassPathResource("scenario/programs-v1.json").getInputStream()) {
            var bytes = input.readNBytes(262145);
            if (bytes.length > 262144) throw new IllegalStateException("Program catalog too large");
            programs = load(bytes);
        }
    }

    public static List<ScenarioProgram> load(byte[] bytes) {
        var root = ScenarioJson.MAPPER.readTree(bytes);
        ScenarioProgram.require(root.isArray() && root.size() > 0 && root.size() <= 32, "Invalid catalog");
        var results = new java.util.ArrayList<ScenarioProgram>();
        var keys = new java.util.HashSet<String>();
        for (var node : root) {
            ScenarioProgram.require(node.isObject(), "Invalid program");
            ObjectNode content = ((ObjectNode) node).deepCopy();
            var supplied = content.remove("definitionHash");
            ScenarioProgram.require(supplied != null && supplied.isTextual()
                    && ScenarioJson.hash(ScenarioJson.canonical(content)).equals(supplied.asText()), "Program hash mismatch");
            var definition = ScenarioJson.MAPPER.treeToValue(node, ScenarioProgram.class);
            ScenarioProgram.require(keys.add(definition.code() + ":" + definition.version()), "Duplicate program version");
            results.add(definition);
        }
        return List.copyOf(results);
    }

    public List<ScenarioProgram> definitions() { return programs; }

    public ScenarioProgram require(String code, int version) {
        return programs.stream().filter(p -> p.code().equals(code) && p.version() == version).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown program version"));
    }
}
