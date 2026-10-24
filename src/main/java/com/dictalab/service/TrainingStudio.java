package com.dictalab.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Administrator-only local bridge. Fixed executable/module; user input travels through JSON stdin. */
@Service
public class TrainingStudio {
    private final ObjectMapper json;
    private final Path root;
    private final Path python;
    private final boolean enabled;
    private final Semaphore permit = new Semaphore(1);

    public TrainingStudio(ObjectMapper json,
                          @Value("${dictalab.training.enabled:false}") boolean enabled,
                          @Value("${dictalab.training.root:}") String root,
                          @Value("${dictalab.training.python:}") String python) {
        this.json = json;
        this.enabled = enabled;
        this.root = Path.of(root.isBlank() ? System.getProperty("user.dir") : root).toAbsolutePath().normalize();
        this.python = python.isBlank() ? this.root.resolve("data/fine-tuning/.venv-cpu/Scripts/python.exe")
                : Path.of(python).toAbsolutePath().normalize();
    }

    public JsonNode request(String action, String id, Map<String, Object> body, Long actor) {
        if (!Set.of("snapshot", "detail", "create", "start", "resume", "pause", "stop").contains(action))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thao tác train không hợp lệ.");
        if (!enabled || !Files.isRegularFile(python) || !Files.isRegularFile(root.resolve("training/studio.py")))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Máy train chưa kết nối. Cần bật Training Studio và cấu hình runtime trên máy chủ.");
        Process process = null;
        boolean acquired = false;
        try {
            acquired = permit.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Đang kiểm tra train; thử lại sau.");
            var request = new LinkedHashMap<String, Object>();
            request.put("action", action);
            request.put("id", id);
            request.put("body", body == null ? Map.of() : body);
            request.put("actor", actor);
            byte[] input = json.writeValueAsBytes(request);
            if (input.length > 32_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yêu cầu quá lớn.");
            var builder = new ProcessBuilder(python.toString(), "-X", "utf8", "-m", "training.studio")
                    .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("PYTHONUTF8", "1");
            process = builder.start();
            Process started = process;
            var output = CompletableFuture.supplyAsync(() -> {
                try { return started.getInputStream().readNBytes(1_500_001); }
                catch (IOException failed) { throw new IllegalStateException("Training bridge output unavailable"); }
            });
            try (var stdin = process.getOutputStream()) { stdin.write(input); }
            if (!process.waitFor(45, TimeUnit.SECONDS))
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Kiểm tra train quá thời gian. Trạng thái lệnh chưa xác minh; tải lại trước khi thử lại.");
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 1_500_000)
                throw new IOException("Training bridge failed");
            JsonNode result = json.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (!result.path("ok").asBoolean())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        result.path("error").asText("Runtime train từ chối yêu cầu."));
            return result.path("data");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Yêu cầu train bị gián đoạn; kiểm tra trạng thái trước khi thử lại.");
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (Exception failed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không đọc được runtime train. Kiểm tra cấu hình máy chủ.");
        } finally {
            // Only the short-lived bridge is closed; detached training workers have their own lifecycle.
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (acquired) permit.release();
        }
    }
}
