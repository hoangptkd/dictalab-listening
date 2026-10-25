package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.TrainingStudio;
import com.dictalab.service.ColabTelemetry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/training")
public class TrainingStudioController {
    private final TrainingStudio studio;
    private final ColabTelemetry colab;
    private final ObjectMapper json;
    public TrainingStudioController(TrainingStudio studio, ColabTelemetry colab, ObjectMapper json) {
        this.studio = studio; this.colab = colab; this.json = json;
    }

    @ModelAttribute
    void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public JsonNode snapshot() {
        ObjectNode result;
        try {
            result = (ObjectNode) studio.request("snapshot", null, null, null);
            for (var run : result.path("runs")) ((ObjectNode)run).put("source", "local").put("phase", "full");
            result.put("localConnected", true);
        } catch (ResponseStatusException failure) {
            if (!colab.enabled()) throw failure;
            result = json.createObjectNode();
            result.put("observedAt", java.time.Instant.now().toString()).put("externalWorkerCount", 0).put("localConnected", false);
            result.putArray("runs"); result.putArray("datasets");
            result.putArray("warnings").add("Runtime điều khiển CPU local chưa kết nối máy chủ này; vẫn có thể nhận tiến độ từ Local và Colab qua HTTPS.");
            result.putObject("resources").put("scope", "Local chưa kết nối");
            result.putObject("capabilities").put("create", false);
        }
        var runs = (tools.jackson.databind.node.ArrayNode) result.path("runs");
        var datasets = (tools.jackson.databind.node.ArrayNode) result.path("datasets");
        for (var run : colab.runs()) {
            runs.add(run);
            if (!run.path("dataset").has("records")) continue;
            boolean exists = false;
            for (var d : datasets) if (d.path("hash").asText().equals(run.path("datasetHash").asText())) exists = true;
            if (!exists) {
                var d = (ObjectNode)run.path("dataset").deepCopy();
                d.put("id", run.path("datasetHash").asText()).put("hash", run.path("datasetHash").asText())
                        .put("name", "Dataset từ máy gửi").put("source", "https").put("approved", false);
                d.putObject("articlesByLevel"); datasets.add(d);
            }
        }
        ((ObjectNode)result.path("capabilities")).put("colab", colab.enabled());
        return result;
    }

    @GetMapping("/runs/{id}")
    public JsonNode detail(@PathVariable String id) {
        if (id.startsWith("colab-") || id.startsWith("local-")) return colab.detail(id);
        var result = (ObjectNode)studio.request("detail", id, null, null);
        return result.put("source", "local").put("phase", "full");
    }

    @PostMapping("/colab")
    public Map<String, Object> connect(@RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal DictaLabPrincipal actor) {
        return colab.register(body, actor.id());
    }

    @PostMapping("/colab/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id) { return colab.revoke(id); }

    @PostMapping("/runs")
    public JsonNode create(@RequestBody Map<String, Object> body,
                           @AuthenticationPrincipal DictaLabPrincipal actor) {
        return studio.request("create", null, body, actor.id());
    }

    @PostMapping("/runs/{id}/{action:start|resume|pause|stop}")
    public JsonNode control(@PathVariable String id, @PathVariable String action,
                            @RequestBody Map<String, Object> body,
                            @AuthenticationPrincipal DictaLabPrincipal actor) {
        if (id.startsWith("colab-") || id.startsWith("local-")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên HTTPS chỉ theo dõi; điều khiển tại máy train.");
        return studio.request(action, id, body, actor.id());
    }
}

/** Machine ingestion retains the existing CSRF protection and requires a separate write-only token. */
@RestController
class ColabTelemetryController {
    private final ColabTelemetry colab;
    ColabTelemetryController(ColabTelemetry colab) { this.colab = colab; }

    @PostMapping(value = "/api/training/telemetry/{id}", consumes = "application/json")
    public Map<String, Object> receive(@PathVariable String id, HttpServletRequest request,
                                       HttpServletResponse response) throws java.io.IOException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > 32_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Snapshot tối đa 32 KB.");
        return colab.receive(id, request.getHeader("Authorization"), request.getInputStream().readNBytes(32_001), request.isSecure());
    }
}
