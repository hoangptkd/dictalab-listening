package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public class ColabTelemetryTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();

    public static ObjectNode report(ObjectMapper json) {
        ObjectNode r = json.createObjectNode();
        r.put("schemaVersion", 1).put("sequence", 1).put("observedAt", Instant.now().toString())
                .put("phase", "smoke").put("status", "SMOKE_PASSED").put("step", 1).put("plannedSteps", 1)
                .put("model", "Qwen/Qwen3.5-4B").put("revision", "a".repeat(40)).put("device", "cuda").put("dtype", "float16").put("gpu", "Tesla T4");
        var bindings = r.putObject("bindings");
        for (String k : new String[]{"configHash", "datasetHash", "datasetManifestHash", "reviewHash", "environmentLockHash", "inspectionHash", "trainerHash"}) bindings.put(k, "b".repeat(64));
        var config = r.putObject("config");
        config.put("base_model", "Qwen/Qwen3.5-4B").put("base_revision", "a".repeat(40)).put("device", "cuda")
                .put("seed", 17).put("max_seq_length", 4608).put("packing", false).put("assistant_only_loss", true)
                .put("enable_thinking", false).put("loss_mask_policy", "last-assistant-only");
        config.putObject("lora").put("r", 16).put("alpha", 32).put("dropout", .05);
        config.putObject("quantization").put("compute_dtype", "float16");
        config.putObject("training").put("epochs", 2).put("per_device_batch_size", 1).put("gradient_accumulation_steps", 8).put("checkpoint_steps", 10);
        r.putArray("history").addObject().put("step", 1).put("loss", .45).put("grad_norm", 2.3);
        r.putObject("validation"); r.putObject("resources").put("peakAllocatedBytes", 14819415040L);
        r.putObject("optimizerEvidence").put("actualOptimizerUpdates", 1).put("changedAdapterTensors", 32).put("finiteLoggedGradients", true);
        r.putArray("checkpoints").addObject().put("name", "checkpoint-1").put("step", 1).put("integrityReported", true).put("fileCount", 7);
        r.putObject("artifacts").put("adapterHash", "c".repeat(64)).put("adapterConfigHash", "d".repeat(64));
        var dataset = r.putObject("dataset");
        dataset.putObject("records").put("train", 800).put("validation", 96).put("test", 104);
        dataset.put("articles", 500).put("reportedApproved", true).put("humanApproved", false).put("reportedHoldout", true).put("reportedNearDuplicate", true);
        dataset.putObject("reviewers").put("AI:Codex", 244).put("AI:chatgpt-web", 256);
        return r;
    }

    Map<String, Object> register(ColabTelemetry service) {
        return service.register(Map.of("name", "Test Colab", "notebookUrl", "https://colab.research.google.com/drive/abcdefghijklm"), 1L);
    }

    @Test void scopedKeyStoredHashedAndNeverBecomesLocalResumeOrVerifiedCompletion() throws Exception {
        var s = new ColabTelemetry(json, true, directory.toString(), "https://example.com");
        var first = register(s); var second = register(s);
        String id = first.get("id").toString(), token = first.get("token").toString();
        assertThat(Files.readString(directory.resolve(id + ".json"))).doesNotContain(token);
        assertThatThrownBy(() -> s.receive(second.get("id").toString(), "Bearer " + token, json.writeValueAsBytes(report(json)), true))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(401));
        byte[] bytes = json.writeValueAsBytes(report(json));
        s.receive(id, "Bearer " + token, bytes, true);
        s.receive(id, "Bearer " + token, bytes, true); // Exact retry is idempotent.
        var result = s.detail(id);
        assertThat(result.path("status").asText()).isEqualTo("SMOKE_PASSED");
        assertThat(result.path("connection").asText()).isEqualTo("LIVE");
        assertThat(result.path("canResume").asBoolean()).isFalse();
        assertThat(result.path("completionVerified").asBoolean()).isFalse();
        assertThat(result.path("checkpoints").get(0).path("verified").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain(token, "tokenHash", directory.toString());
        s.revoke(id);
        assertThat(s.detail(id).path("connected").asBoolean()).isFalse();
        assertThatThrownBy(() -> s.receive(id, "Bearer " + token, bytes, true)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsChangedBindingReplayStaleClockAndFalseSmokeSuccess() throws Exception {
        var s = new ColabTelemetry(json, true, directory.toString(), "https://example.com");
        var connection = register(s); String id = connection.get("id").toString(), auth = "Bearer " + connection.get("token");
        var first = report(json); s.receive(id, auth, json.writeValueAsBytes(first), true);
        var invalid = first.deepCopy(); invalid.put("gpu", "Different GPU");
        ObjectNode replay = invalid;
        assertThatThrownBy(() -> s.receive(id, auth, json.writeValueAsBytes(replay), true)).isInstanceOf(ResponseStatusException.class);
        for (String fault : new String[]{"binding", "smoke", "gradient", "clock", "rawLog", "config", "phase", "nan", "counts"}) {
            var value = report(json); value.put("sequence", 2);
            switch (fault) {
                case "binding" -> ((ObjectNode)value.path("bindings")).put("datasetHash", "e".repeat(64));
                case "smoke" -> value.put("plannedSteps", 200);
                case "gradient" -> ((ObjectNode)value.path("optimizerEvidence")).put("finiteLoggedGradients", false);
                case "clock" -> value.put("observedAt", Instant.now().minusSeconds(600).toString());
                case "rawLog" -> value.put("log", "private dataset contents");
                case "config" -> ((ObjectNode)value.path("config").path("training")).put("epochs", 3);
                case "phase" -> value.put("phase", "full");
                case "nan" -> ((ObjectNode)value.path("resources")).put("cpuPercent", Double.NaN);
                case "counts" -> ((ObjectNode)value.path("dataset").path("records")).put("train", 802);
            }
            assertThatThrownBy(() -> s.receive(id, auth, json.writeValueAsBytes(value), true)).as(fault).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(s.detail(id).path("step").asInt()).isEqualTo(1);
    }

    @Test void expiredAndPlainHttpTokensFailAndStaleWorkerIsNeverCalledFailed() throws Exception {
        var s = new ColabTelemetry(json, true, directory.toString(), "https://example.com");
        var c = register(s); String id = c.get("id").toString(), auth = "Bearer " + c.get("token");
        var report = report(json); report.put("status", "RUNNING");
        assertThatThrownBy(() -> s.receive(id, auth, json.writeValueAsBytes(report), false)).isInstanceOf(ResponseStatusException.class);
        s.receive(id, auth, json.writeValueAsBytes(report), true);
        Path file = directory.resolve(id + ".json");
        ObjectNode saved = (ObjectNode)json.readTree(Files.readString(file)); saved.put("receivedAt", Instant.now().minusSeconds(120).toString());
        Files.writeString(file, json.writeValueAsString(saved));
        assertThat(s.detail(id).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(s.detail(id).path("reportedStatus").asText()).isEqualTo("RUNNING");
        assertThat(s.detail(id).path("active").asBoolean()).isFalse();
        saved.put("expiresAt", Instant.now().minusSeconds(1).toString()); Files.writeString(file, json.writeValueAsString(saved));
        assertThatThrownBy(() -> s.receive(id, auth, json.writeValueAsBytes(report), true)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void registrationCannotSetUserPathsOrUseNonColabLinksAndFeatureDefaultsClosed() {
        var s = new ColabTelemetry(json, true, directory.toString(), "https://example.com");
        assertThatThrownBy(() -> s.register(Map.of("name", "test", "notebookUrl", "javascript:alert(1)"), 1L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> s.register(Map.of("name", "test", "notebookUrl", "https://example.com", "output", "../../private"), 1L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> s.detail("../../secrets")).isInstanceOf(ResponseStatusException.class);
        assertThat(new ColabTelemetry(json, false, directory.toString(), "https://example.com").runs()).isEmpty();
    }

    @Test void localCpuPublishesToSameDashboardWithoutAcquiringRemoteControl() throws Exception {
        var s = new ColabTelemetry(json, true, directory.toString(), "https://example.com");
        var c = s.register(Map.of("name", "Paused CPU", "source", "local", "notebookUrl", ""), 1L);
        assertThat(s.detail(c.get("id").toString()).path("device").asText()).isEqualTo("cpu");
        assertThat(s.detail(c.get("id").toString()).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(s.detail(c.get("id").toString()).path("step").isNull()).isTrue();
        var report = report(json);
        report.put("device", "cpu").put("dtype", "float32").put("phase", "full").put("status", "PAUSED_BY_USER").put("plannedSteps", 200).put("gpu", "");
        ((ObjectNode)report.path("config")).put("device", "cpu");
        ((ObjectNode)report.path("config").path("quantization")).put("compute_dtype", "float32");
        s.receive(c.get("id").toString(), "Bearer " + c.get("token"), json.writeValueAsBytes(report), true);
        var row = s.detail(c.get("id").toString());
        assertThat(row.path("source").asText()).isEqualTo("local");
        assertThat(row.path("transport").asText()).isEqualTo("https");
        assertThat(row.path("canPause").asBoolean()).isFalse();
        assertThat(row.path("status").asText()).isEqualTo("PAUSED_BY_USER");
    }
}
