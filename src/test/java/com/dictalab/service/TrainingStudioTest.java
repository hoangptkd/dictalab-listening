package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingStudioTest {
    @Test
    void installedLocalRuntimeCanReadRealMetadataWithoutStartingTraining() {
        var python = java.nio.file.Path.of("data/fine-tuning/.venv-cpu/Scripts/python.exe").toAbsolutePath();
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(python));
        var studio = new TrainingStudio(new ObjectMapper(), true, "", python.toString());
        var snapshot = studio.request("snapshot", null, null, null);
        org.assertj.core.api.Assertions.assertThat(snapshot.path("runs").isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(snapshot.path("resources").has("ramAvailable")).isTrue();
        org.assertj.core.api.Assertions.assertThat(snapshot.toString()).doesNotContain(System.getProperty("user.home"));
    }

    @Test
    void disabledBridgeAndUnsupportedActionsNeverExecuteLocalCommands() {
        var studio = new TrainingStudio(new ObjectMapper(), false, "", "");
        assertThatThrownBy(() -> studio.request("start", "id", Map.of(), 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(503));
        assertThatThrownBy(() -> studio.request("arbitrary-command", null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }
}
